package kr.archsurvey.pg.live

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/**
 * PIX4Dcatch 처럼 쌓이는 색 점구름. 복셀(기본 1.5 cm)마다 점 하나(평균 위치·색), 상한을 넘으면 가장 오래된 점부터 바꿔 씀.
 * 작업 스레드 하나에서만 add/snapshot 한다.
 */
class PointCloud(val voxel: Double = 0.015, val cap: Int = 300_000) {
    private val xyz = FloatArray(cap * 3); private val rgb = FloatArray(cap * 3); private val n = IntArray(cap); private val keys = LongArray(cap)
    private val index = HashMap<Long, Int>(cap / 2)
    var size = 0; private set
    private var next = 0
    var added = 0L; private set
    var rejected = 0L; private set

    private fun k(v: Double) = floor(v / voxel).toLong() and 0x1FFFFF
    fun key(x: Double, y: Double, z: Double): Long = (k(x) shl 42) or (k(y) shl 21) or k(z)

    /** r,g,b 는 0..1 */
    fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || kotlin.math.abs(x) > 10_000f || kotlin.math.abs(y) > 10_000f || kotlin.math.abs(z) > 10_000f) { rejected++; return }
        val key = key(x.toDouble(), y.toDouble(), z.toDouble())
        val i = index[key]
        if (i != null) {
            val m = minOf(n[i], 15).toFloat(); val w = 1f / (m + 1f)
            xyz[3 * i] += (x - xyz[3 * i]) * w; xyz[3 * i + 1] += (y - xyz[3 * i + 1]) * w; xyz[3 * i + 2] += (z - xyz[3 * i + 2]) * w
            rgb[3 * i] += (r - rgb[3 * i]) * w; rgb[3 * i + 1] += (g - rgb[3 * i + 1]) * w; rgb[3 * i + 2] += (b - rgb[3 * i + 2]) * w
            n[i]++; return
        }
        val j: Int
        if (size < cap) { j = size; size++ } else { j = next; next = (next + 1) % cap; index.remove(keys[j]) }
        keys[j] = key; index[key] = j; n[j] = 1
        xyz[3 * j] = x; xyz[3 * j + 1] = y; xyz[3 * j + 2] = z; rgb[3 * j] = r; rgb[3 * j + 1] = g; rgb[3 * j + 2] = b
        added++
    }

    fun clear() { index.clear(); size = 0; next = 0 }
    fun count(i: Int) = n[i]
    fun pos(i: Int) = floatArrayOf(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])

    /** GL 버퍼(x,y,z,r,g,b,a). tint != null 이면 점 위치별 덧칠 색(충분 색) */
    fun snapshot(tint: ((Float, Float, Float) -> FloatArray?)? = null): Pair<FloatBuffer, Int> {
        val b = ByteBuffer.allocateDirect(maxOf(28, size * 28)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val tmp = FloatArray(7)
        for (i in 0 until size) {
            val x = xyz[3 * i]; val y = xyz[3 * i + 1]; val z = xyz[3 * i + 2]
            tmp[0] = x; tmp[1] = y; tmp[2] = z
            val t = tint?.invoke(x, y, z)
            if (t != null) { tmp[3] = rgb[3 * i] * 0.35f + t[0] * 0.65f; tmp[4] = rgb[3 * i + 1] * 0.35f + t[1] * 0.65f; tmp[5] = rgb[3 * i + 2] * 0.35f + t[2] * 0.65f }
            else { tmp[3] = rgb[3 * i]; tmp[4] = rgb[3 * i + 1]; tmp[5] = rgb[3 * i + 2] }
            tmp[6] = 1f
            b.put(tmp)
        }
        b.position(0); return b to size
    }
}

/**
 * 「천천히」 판단. 카메라 프레임 시각(ns)으로 재고, 같은 프레임(LATEST_CAMERA_IMAGE 에서 반복)은 무시,
 * 0.25초 창으로 평균 속도를 내어 손떨림(프레임마다 1° 안팎)을 걸러낸다. 두 번 연속 넘어야 「천천히」.
 */
class SpeedGate(val maxMove: Double = 1.0, val maxRotDeg: Double = 90.0, val windowNs: Long = 250_000_000L, val holdNs: Long = 600_000_000L) {
    private class S(val t: Long, val p: FloatArray, val q: FloatArray)
    private val buf = ArrayDeque<S>()
    private var over = 0; private var slowUntil = Long.MIN_VALUE; private var lastT = Long.MIN_VALUE
    var move = 0.0; private set
    var rot = 0.0; private set
    /** true = 방금 새로 「천천히」 가 됨 */
    fun feed(tNs: Long, p: FloatArray, q: FloatArray): Boolean {
        if (tNs <= lastT) return false
        lastT = tNs
        buf.addLast(S(tNs, p.copyOf(), q.copyOf()))
        while (buf.size > 2 && buf.first().t < tNs - windowNs) buf.removeFirst()
        val a = buf.first(); val dt = (tNs - a.t) / 1e9
        if (dt < windowNs / 1e9 * 0.8) return false
        move = ArRendererMath.dist(a.p, p) / dt; rot = ArRendererMath.angDeg(a.q, q) / dt
        over = if (move > maxMove || rot > maxRotDeg) over + 1 else 0
        if (over >= 2) { val fresh = tNs >= slowUntil; slowUntil = tNs + holdNs; return fresh }
        return false
    }
    fun slow(tNs: Long) = tNs < slowUntil
    fun reset() { buf.clear(); over = 0; slowUntil = Long.MIN_VALUE; lastT = Long.MIN_VALUE }
}

object ArRendererMath {
    fun dist(a: FloatArray, b: FloatArray): Double { val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]; return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()) }
    fun angDeg(a: FloatArray, b: FloatArray): Double { val d = kotlin.math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f); return Math.toDegrees(2 * kotlin.math.acos(d.toDouble())) }
}
