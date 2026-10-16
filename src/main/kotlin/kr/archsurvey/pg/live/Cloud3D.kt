package kr.archsurvey.pg.live

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/**
 * GPU 로 올릴 고정 크기 점 배열(x,y,z,r,g,b,a). 작업 스레드가 칸을 고치면 바뀐 범위만 표시해 두고,
 * GL 스레드가 [takeDirty] 로 그 범위만 glBufferSubData 한다.
 */
class GpuSlots(val cap: Int) {
    val data = FloatArray(cap * STRIDE)
    @Volatile var size = 0; private set
    private var lo = Int.MAX_VALUE; private var hi = -1
    private var upload: FloatBuffer? = null
    /** 전체를 다시 올려야 함(GL 문맥 새로 생김 등) */
    @Synchronized fun markAll() { if (size > 0) { lo = 0; hi = size - 1 } }
    @Synchronized fun put(i: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        val o = i * STRIDE
        data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = r; data[o + 4] = g; data[o + 5] = b; data[o + 6] = 1f
        if (i >= size) size = i + 1
        if (i < lo) lo = i; if (i > hi) hi = i
    }
    @Synchronized fun clear() { size = 0; lo = Int.MAX_VALUE; hi = -1 }
    class Dirty(val first: Int, val count: Int, val buf: FloatBuffer, val total: Int)
    /** 바뀐 범위 복사본(GL 스레드). 없으면 null */
    @Synchronized fun takeDirty(): Dirty? {
        if (hi < lo) return null
        val n = hi - lo + 1
        var b = upload
        if (b == null || b.capacity() < n * STRIDE) { b = ByteBuffer.allocateDirect(maxOf(n, 4096) * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); upload = b }
        b!!.clear(); b.put(data, lo * STRIDE, n * STRIDE); b.flip()
        val d = Dirty(lo, n, b, size)
        lo = Int.MAX_VALUE; hi = -1
        return d
    }
    companion object { const val STRIDE = 7 }
}

/** ARCore 특징점: ID 로 중복 제거, 최신 위치·색. 상한 넘으면 가장 오래된 ID 의 칸을 재사용 */
class FeatureCloud(val cap: Int = 30_000) {
    val gpu = GpuSlots(cap)
    private val slot = object : LinkedHashMap<Int, Int>(cap, 0.75f, true) {}
    private var nextFree = 0
    val size get() = slot.size
    fun put(id: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val s = slot[id] ?: run {
            val ns = if (nextFree < cap) nextFree++ else { val e = slot.entries.iterator(); val old = e.next(); e.remove(); old.value }
            slot[id] = ns; ns
        }
        gpu.put(s, x, y, z, r, g, b)
    }
}

/**
 * 0.0.20 깊이 점(클린룸 설계, 24-pointcloud-gap.md):
 *  - 화면용: 평균 없이 원래 점 그대로 쌓는 고정 버퍼(기본 100만 점). 가득 차면 쓰기 위치를 무작위 10~99칸씩
 *    건너뛰며 덮어써 전체가 고르게 솎아진다(칸 크기를 키우지 않음, 성장 멈춤 없음).
 *  - 내보내기용: 2 cm 칸마다 최근 3개 관측의 중앙값(x,y,z 각각) — 드리프트·튐에 강함. 칸 상한을 넘으면 새 칸만 안 받음(화면은 계속).
 * 이름(DepthVoxels)·API 는 기존 호출부 호환용으로 유지.
 */
class DepthVoxels(voxel0: Double = 0.02, val cap: Int = defaultCap(), val exportCap: Int = 400_000, seed: Long = 20L) {
    /** 내보내기 칸 크기(고정) */
    val voxel = voxel0
    /** 0.0.20: 칸 키우기 없음(호환용, 항상 0) */
    val coarsenings = 0
    val gpu = GpuSlots(cap)
    private val px = FloatArray(cap); private val py = FloatArray(cap); private val pz = FloatArray(cap)
    private val pr = FloatArray(cap); private val pg = FloatArray(cap); private val pb = FloatArray(cap); private val pc = FloatArray(cap)
    /** 화면 버퍼에 들어 있는 점 수 */
    var size = 0; private set
    /** 지금까지 받은 점(덮어쓴 것 포함) */
    var total = 0L; private set
    /** 덮어써서 솎은 점 수 */
    var evicted = 0L; private set
    private var w = 0
    private val rnd = java.util.Random(seed)

    // 내보내기 중앙값 칸
    private val index = HashMap<Long, Int>(1 shl 16)
    private val ex = FloatArray(exportCap * 3); private val ey = FloatArray(exportCap * 3); private val ez = FloatArray(exportCap * 3)
    private val er = FloatArray(exportCap); private val eg = FloatArray(exportCap); private val eb = FloatArray(exportCap)
    private val en = IntArray(exportCap); private val econf = FloatArray(exportCap)
    var exportSize = 0; private set
    var exportDropped = 0L; private set

    private fun k(v: Float) = floor(v / voxel).toLong() and 0x1FFFFF
    fun key(x: Float, y: Float, z: Float): Long = (k(x) shl 42) or (k(y) shl 21) or k(z)
    companion object {
        /** 힙이 넉넉하면 100만 점(약 60 MB), 작은 옛 폰(minSdk 21)은 30만 점 */
        fun defaultCap(): Int = if (Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024) 1_000_000 else 300_000
    }

    fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, c: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        total++
        val i: Int
        if (size < cap) { i = size++; w = size % cap }
        else { i = w; evicted++; w = (w + 10 + rnd.nextInt(90)) % cap }
        px[i] = x; py[i] = y; pz[i] = z; pr[i] = r; pg[i] = g; pb[i] = b; pc[i] = c
        gpu.put(i, x, y, z, r, g, b)
        addExport(x, y, z, r, g, b, c)
    }

    private fun addExport(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, c: Float) {
        val key = key(x, y, z)
        var j = index[key]
        if (j == null) {
            if (exportSize >= exportCap) { exportDropped++; return }
            j = exportSize++; index[key] = j; en[j] = 0; econf[j] = 0f; er[j] = 0f; eg[j] = 0f; eb[j] = 0f
        }
        val s = en[j] % 3; val o = 3 * j + s
        ex[o] = x; ey[o] = y; ez[o] = z
        val m = minOf(en[j], 20).toFloat(); val t = m + 1f
        er[j] = (er[j] * m + r) / t; eg[j] = (eg[j] * m + g) / t; eb[j] = (eb[j] * m + b) / t
        en[j]++; if (c > econf[j]) econf[j] = c
    }

    private fun med(a: FloatArray, j: Int, n: Int): Float {
        val o = 3 * j
        return when (minOf(n, 3)) {
            1 -> a[o]
            2 -> (a[o] + a[o + 1]) / 2f
            else -> { val p = a[o]; val q = a[o + 1]; val r = a[o + 2]; maxOf(minOf(p, q), minOf(maxOf(p, q), r)) }
        }
    }

    fun count(i: Int) = en[i]
    fun conf(i: Int) = pc[i]
    fun pos(i: Int) = floatArrayOf(px[i], py[i], pz[i])
    /** 내보내기용: 2 cm 칸 중앙값, 최고 신뢰도 ≥ minConf (x,y,z,r,g,b) */
    fun export(minConf: Float = 0.66f): FloatArray {
        var m = 0; for (j in 0 until exportSize) if (econf[j] >= minConf) m++
        val out = FloatArray(m * 6); var o = 0
        for (j in 0 until exportSize) if (econf[j] >= minConf) {
            val n = en[j]; out[o++] = med(ex, j, n); out[o++] = med(ey, j, n); out[o++] = med(ez, j, n); out[o++] = er[j]; out[o++] = eg[j]; out[o++] = eb[j]
        }
        return out
    }
    /** 다시 색칠(충분 색 보기 전환) — 화면 버퍼만 */
    fun recolor(f: (Int, FloatArray) -> FloatArray) {
        val tmp = FloatArray(3)
        for (i in 0 until size) { tmp[0] = pr[i]; tmp[1] = pg[i]; tmp[2] = pb[i]; val c = f(i, tmp); gpu.put(i, px[i], py[i], pz[i], c[0], c[1], c[2]) }
    }
}

/**
 * 깊이 → 세계 점(순수 계산, 시험 가능). 깊이 화상을 grid×grid 화소 칸으로 나눠 칸마다 신뢰도 최고 화소 하나.
 * 신뢰도 ≥ minConf, 거리 [minM, maxM]. 화소 축: x 오른쪽, y 아래 / 카메라 축: OpenGL(−z 앞).
 */
object DepthSampler {
    class Out(val xyz: FloatArray, val conf: FloatArray, val u: IntArray, val v: IntArray, val n: Int, val rejectedConf: Int, val rejectedRange: Int)
    fun sample(w: Int, h: Int, depthMm: (Int, Int) -> Int, conf255: ((Int, Int) -> Int)?, fx: Float, fy: Float, cx: Float, cy: Float,
               pose: FloatArray, grid: Int = 3, minConf: Float = 0.4f, minM: Float = 0.3f, maxM: Float = 4.0f): Out {
        val cells = ((w + grid - 1) / grid) * ((h + grid - 1) / grid)
        val xyz = FloatArray(cells * 3); val cf = FloatArray(cells); val us = IntArray(cells); val vs = IntArray(cells)
        var k = 0; var rc = 0; var rr = 0
        var y0 = 0
        while (y0 < h) {
            var x0 = 0
            while (x0 < w) {
                var bc = -1; var bx = 0; var by = 0; var bd = 0
                for (y in y0 until minOf(y0 + grid, h)) for (x in x0 until minOf(x0 + grid, w)) {
                    val d = depthMm(x, y); if (d <= 0) continue
                    val c = conf255?.invoke(x, y) ?: 255
                    if (c > bc) { bc = c; bx = x; by = y; bd = d }
                }
                if (bc >= 0) {
                    val c = bc / 255f; val dm = bd / 1000f
                    when {
                        c < minConf -> rc++
                        dm < minM || dm > maxM -> rr++
                        else -> {
                            val px = (bx - cx) / fx * dm; val py = -(by - cy) / fy * dm; val pz = -dm
                            xyz[3 * k] = pose[0] * px + pose[4] * py + pose[8] * pz + pose[12]
                            xyz[3 * k + 1] = pose[1] * px + pose[5] * py + pose[9] * pz + pose[13]
                            xyz[3 * k + 2] = pose[2] * px + pose[6] * py + pose[10] * pz + pose[14]
                            cf[k] = c; us[k] = bx; vs[k] = by; k++
                        }
                    }
                }
                x0 += grid
            }
            y0 += grid
        }
        return Out(xyz, cf, us, vs, k, rc, rr)
    }
}

/**
 * 0.0.20: 사진 한 장당 목표 점 수(기본 7000, 3000~14000). 칸을 목표의 약 2배로 잘게 나눠 칸마다 신뢰도 최고 화소를 고르고,
 * 신뢰도 ≥ hi(0.66) 점을 먼저 담고 모자라면 ≥ lo(0.40) 점으로 채운다. 넘치면 고른 간격으로 솎는다.
 */
object DepthTarget {
    const val DEFAULT = 7000; const val MIN = 3000; const val MAX = 14000
    fun clamp(n: Int) = n.coerceIn(MIN, MAX)
    fun grid(w: Int, h: Int, target: Int): Int = maxOf(1, kotlin.math.sqrt(w.toDouble() * h / (2.0 * target)).toInt())
    fun sample(w: Int, h: Int, depthMm: (Int, Int) -> Int, conf255: ((Int, Int) -> Int)?, fx: Float, fy: Float, cx: Float, cy: Float,
               pose: FloatArray, target: Int = DEFAULT, hi: Float = 0.66f, lo: Float = 0.40f, minM: Float = 0.3f, maxM: Float = 4.0f): DepthSampler.Out {
        val t = clamp(target)
        val all = DepthSampler.sample(w, h, depthMm, conf255, fx, fy, cx, cy, pose, grid(w, h, t), lo, minM, maxM)
        val hiIdx = ArrayList<Int>(); val loIdx = ArrayList<Int>()
        for (i in 0 until all.n) if (all.conf[i] >= hi) hiIdx += i else loIdx += i
        val pick = ArrayList<Int>(t)
        thin(hiIdx, t, pick); if (pick.size < t) thin(loIdx, t - pick.size, pick)
        val n = pick.size
        val xyz = FloatArray(n * 3); val cf = FloatArray(n); val us = IntArray(n); val vs = IntArray(n)
        for ((k, i) in pick.withIndex()) { xyz[3 * k] = all.xyz[3 * i]; xyz[3 * k + 1] = all.xyz[3 * i + 1]; xyz[3 * k + 2] = all.xyz[3 * i + 2]; cf[k] = all.conf[i]; us[k] = all.u[i]; vs[k] = all.v[i] }
        return DepthSampler.Out(xyz, cf, us, vs, n, all.rejectedConf, all.rejectedRange)
    }
    private fun thin(src: List<Int>, want: Int, out: MutableList<Int>) {
        if (want <= 0 || src.isEmpty()) return
        if (src.size <= want) { out.addAll(src); return }
        val step = src.size.toDouble() / want
        for (k in 0 until want) out += src[(k * step).toInt()]
    }
}
