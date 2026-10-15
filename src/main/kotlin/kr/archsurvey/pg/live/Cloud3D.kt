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
 * 깊이 복셀(기본 2 cm): 칸마다 가중 평균 위치·색, 관측 수, 최고 신뢰도.
 * 상한을 넘으면 링 위치 근처 64칸 중 관측 수가 가장 적은 칸을 비워 재사용.
 */
class DepthVoxels(val voxel: Double = 0.02, val cap: Int = 400_000) {
    val gpu = GpuSlots(cap)
    private val keys = LongArray(cap); private val n = IntArray(cap); private val conf = FloatArray(cap)
    private val sx = FloatArray(cap); private val sy = FloatArray(cap); private val sz = FloatArray(cap)
    private val cr = FloatArray(cap); private val cg = FloatArray(cap); private val cb = FloatArray(cap)
    private val index = HashMap<Long, Int>(1 shl 16)
    var size = 0; private set
    private var ring = 0
    var evicted = 0L; private set

    private fun k(v: Float) = floor(v / voxel).toLong() and 0x1FFFFF
    fun key(x: Float, y: Float, z: Float): Long = (k(x) shl 42) or (k(y) shl 21) or k(z)

    fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, c: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val key = key(x, y, z)
        var i = index[key]
        if (i == null) {
            i = if (size < cap) size++ else evict()
            index[key] = i; keys[i] = key; n[i] = 0; conf[i] = 0f
            sx[i] = 0f; sy[i] = 0f; sz[i] = 0f; cr[i] = 0f; cg[i] = 0f; cb[i] = 0f
        }
        // 신뢰도 가중 평균(최근 20회 수준 기억)
        val w = c.coerceAtLeast(0.05f); val keep = minOf(n[i], 20).toFloat()
        val tot = keep + w
        sx[i] = (sx[i] * keep + x * w) / tot; sy[i] = (sy[i] * keep + y * w) / tot; sz[i] = (sz[i] * keep + z * w) / tot
        cr[i] = (cr[i] * keep + r * w) / tot; cg[i] = (cg[i] * keep + g * w) / tot; cb[i] = (cb[i] * keep + b * w) / tot
        n[i]++; if (c > conf[i]) conf[i] = c
        gpu.put(i, sx[i], sy[i], sz[i], cr[i], cg[i], cb[i])
    }

    private fun evict(): Int {
        var best = ring; var bn = Int.MAX_VALUE
        for (t in 0 until 64) { val j = (ring + t) % cap; if (n[j] < bn) { bn = n[j]; best = j } }
        ring = (best + 1) % cap
        index.remove(keys[best]); evicted++
        return best
    }

    fun count(i: Int) = n[i]
    fun conf(i: Int) = conf[i]
    fun pos(i: Int) = floatArrayOf(sx[i], sy[i], sz[i])
    /** 내보내기용: 최고 신뢰도 ≥ minConf 인 칸 (x,y,z,r,g,b) */
    fun export(minConf: Float = 0.66f): FloatArray {
        var m = 0; for (i in 0 until size) if (conf[i] >= minConf) m++
        val out = FloatArray(m * 6); var o = 0
        for (i in 0 until size) if (conf[i] >= minConf) { out[o++] = sx[i]; out[o++] = sy[i]; out[o++] = sz[i]; out[o++] = cr[i]; out[o++] = cg[i]; out[o++] = cb[i] }
        return out
    }
    /** 다시 색칠(충분 색 보기 전환) */
    fun recolor(f: (Int, FloatArray) -> FloatArray) {
        val tmp = FloatArray(3)
        for (i in 0 until size) { tmp[0] = cr[i]; tmp[1] = cg[i]; tmp[2] = cb[i]; val c = f(i, tmp); gpu.put(i, sx[i], sy[i], sz[i], c[0], c[1], c[2]) }
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
