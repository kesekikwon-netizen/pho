package kr.archsurvey.pg.live

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 0.0.15 점군 보기 손가락 조작(클린룸: 동작 아이디어만 참고).
 * - 따라가기(기본): ARCore 카메라 그대로
 * - 자유 보기: 기준점(pivot) 둘레 공전(yaw/pitch) · 거리(확대) · 이동. 「따라가기」/두 번 탭까지 유지
 * 행렬은 OpenGL 열 우선(column-major) 4×4. 순수 JVM(단위시험).
 */
class OrbitView {
    var free = false; private set
    var pivot = floatArrayOf(0f, 0f, 0f); private set
    var yaw = 0.0; private set      // 세계 y 축 둘레(라디안)
    var pitch = 0.0; private set    // 위(+)·아래(−), ±85°
    var dist = 2.0; private set
    var fovY = Math.toRadians(60.0); private set

    companion object {
        const val MIN_DIST = 0.15; const val MAX_DIST = 60.0
        val MAX_PITCH = Math.toRadians(85.0)
        const val ORBIT_RAD_PER_PX = 0.006
        const val NEAR_MIN = 0.01f; const val FAR_MAX = 500f
    }

    /** 자유 보기 시작: 지금 눈 위치(eye)에서 pivot 을 보는 상태로(화면이 튀지 않게) */
    fun begin(eye: FloatArray, pivotAt: FloatArray, fovYRad: Double) {
        pivot = pivotAt.copyOf(); fovY = fovYRad.coerceIn(0.2, 2.5)
        val dx = (eye[0] - pivot[0]).toDouble(); val dy = (eye[1] - pivot[1]).toDouble(); val dz = (eye[2] - pivot[2]).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        dist = if (d.isFinite() && d > 1e-6) d.coerceIn(MIN_DIST, MAX_DIST) else 2.0
        pitch = if (d > 1e-6) asin((dy / d).coerceIn(-1.0, 1.0)).coerceIn(-MAX_PITCH, MAX_PITCH) else 0.0
        yaw = if (abs(dx) + abs(dz) > 1e-9) atan2(dx, dz) else 0.0
        free = true
    }

    fun reset() { free = false }

    fun orbit(dxPx: Float, dyPx: Float) {
        yaw = wrap(yaw - dxPx * ORBIT_RAD_PER_PX)
        pitch = (pitch + dyPx * ORBIT_RAD_PER_PX).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** 두 손가락 벌림/모음: scale > 1 = 확대(가까이) */
    fun zoom(scale: Float) {
        if (!scale.isFinite() || scale <= 0f) return
        dist = (dist / scale).coerceIn(MIN_DIST, MAX_DIST)
    }

    /** 두 손가락 끌기: 손가락 따라 장면이 움직임(viewH = 화면 높이 px) */
    fun pan(dxPx: Float, dyPx: Float, viewH: Int) {
        if (viewH <= 0) return
        val worldPerPx = 2 * dist * tan(fovY / 2) / viewH
        val (r, u) = rightUp()
        for (i in 0..2) pivot[i] = (pivot[i] - (r[i] * dxPx - u[i] * dyPx) * worldPerPx).toFloat()
    }

    fun eye(): FloatArray {
        val cp = cos(pitch)
        return floatArrayOf((pivot[0] + dist * cp * sin(yaw)).toFloat(), (pivot[1] + dist * sin(pitch)).toFloat(), (pivot[2] + dist * cp * cos(yaw)).toFloat())
    }

    /** 화면 오른쪽·위 (세계) */
    fun rightUp(): Pair<DoubleArray, DoubleArray> {
        val f = forward()
        val r = norm(cross(f, doubleArrayOf(0.0, 1.0, 0.0)))
        val u = cross(r, f)
        return r to u
    }

    private fun forward(): DoubleArray { val e = eye(); return norm(doubleArrayOf((pivot[0] - e[0]).toDouble(), (pivot[1] - e[1]).toDouble(), (pivot[2] - e[2]).toDouble())) }

    /** 가까운/먼 면: 확대해도 뒤집히지 않게 near ≥ 0.01, far > near × 100 */
    fun nearFar(cloudRadius: Double): Pair<Float, Float> {
        val near = (dist * 0.02).toFloat().coerceIn(NEAR_MIN, 1f)
        val far = (dist + 2 * cloudRadius.coerceAtLeast(1.0) + 5).toFloat().coerceIn(near * 100, FAR_MAX)
        return near to far
    }

    fun viewMatrix(out: FloatArray) = lookAt(out, eye(), pivot, floatArrayOf(0f, 1f, 0f))
    fun projMatrix(out: FloatArray, aspect: Float, cloudRadius: Double) { val (n, f) = nearFar(cloudRadius); perspective(out, fovY.toFloat(), aspect, n, f) }

    private fun wrap(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }
}

object GlMath {
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        val r = FloatArray(16)
        for (c in 0..3) for (rw in 0..3) { var s = 0f; for (k in 0..3) s += a[k * 4 + rw] * b[c * 4 + k]; r[c * 4 + rw] = s }
        r.copyInto(out)
    }
    /** 세계 점 → (ndcX, ndcY, w) */
    fun project(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]; val cy = m[1] * x + m[5] * y + m[9] * z + m[13]; val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        return floatArrayOf(cx / cw, cy / cw, cw)
    }
}

internal fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun norm(a: DoubleArray): DoubleArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return if (l < 1e-12) doubleArrayOf(0.0, 0.0, -1.0) else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }

fun lookAt(out: FloatArray, eye: FloatArray, at: FloatArray, up: FloatArray) {
    val f = norm(doubleArrayOf((at[0] - eye[0]).toDouble(), (at[1] - eye[1]).toDouble(), (at[2] - eye[2]).toDouble()))
    val s = norm(cross(f, doubleArrayOf(up[0].toDouble(), up[1].toDouble(), up[2].toDouble())))
    val u = cross(s, f)
    val m = floatArrayOf(
        s[0].toFloat(), u[0].toFloat(), (-f[0]).toFloat(), 0f,
        s[1].toFloat(), u[1].toFloat(), (-f[1]).toFloat(), 0f,
        s[2].toFloat(), u[2].toFloat(), (-f[2]).toFloat(), 0f,
        0f, 0f, 0f, 1f)
    m[12] = -(m[0] * eye[0] + m[4] * eye[1] + m[8] * eye[2])
    m[13] = -(m[1] * eye[0] + m[5] * eye[1] + m[9] * eye[2])
    m[14] = -(m[2] * eye[0] + m[6] * eye[1] + m[10] * eye[2])
    m.copyInto(out)
}

fun perspective(out: FloatArray, fovY: Float, aspect: Float, near: Float, far: Float) {
    val f = (1.0 / tan(fovY / 2.0)).toFloat()
    java.util.Arrays.fill(out, 0f)
    out[0] = f / aspect; out[5] = f
    out[10] = (far + near) / (near - far); out[11] = -1f
    out[14] = 2 * far * near / (near - far)
}

/**
 * 손가락 상태(촬영과 무관 — 셔터·자동은 이 상태를 보지 않음).
 * 한 손가락 끌기 = 공전, 두 손가락 = 확대(벌림) + 이동(가운데 끌기), 두 번 탭 = 따라가기.
 * 8 px 넘게 움직여야 조작으로 침(살짝 닿는 것은 무시).
 */
class TouchOrbit(private val view: OrbitView, private val slopPx: Float = 8f) {
    enum class Mode { IDLE, PENDING, ORBIT, PINCH }
    var mode = Mode.IDLE; private set
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f
    private var lastSpan = 0f; private var lastCx = 0f; private var lastCy = 0f
    /** 자유 보기를 시작해야 할 때(눈 위치·pivot 을 렌더러가 줌). 터치 위치 px */
    var onBegin: ((Float, Float) -> Unit)? = null

    fun down(x: Float, y: Float) { mode = Mode.PENDING; lastX = x; lastY = y; downX = x; downY = y }

    /** pts = 지금 닿아 있는 손가락들(x,y) */
    fun move(pts: List<Pair<Float, Float>>, viewH: Int) {
        if (pts.isEmpty()) return
        if (pts.size >= 2) {
            val (a, b) = pts[0] to pts[1]
            val span = kotlin.math.hypot(a.first - b.first, a.second - b.second)
            val cx = (a.first + b.first) / 2; val cy = (a.second + b.second) / 2
            if (mode != Mode.PINCH) { ensureFree(cx, cy); mode = Mode.PINCH; lastSpan = span; lastCx = cx; lastCy = cy; return }
            if (lastSpan > 1f && span > 1f) view.zoom(span / lastSpan)
            view.pan(cx - lastCx, cy - lastCy, viewH)
            lastSpan = span; lastCx = cx; lastCy = cy
            return
        }
        val (x, y) = pts[0]
        when (mode) {
            Mode.IDLE -> down(x, y)
            Mode.PENDING -> if (kotlin.math.hypot(x - downX, y - downY) > slopPx) { ensureFree(downX, downY); mode = Mode.ORBIT; view.orbit(x - lastX, y - lastY); lastX = x; lastY = y }
            Mode.ORBIT -> { view.orbit(x - lastX, y - lastY); lastX = x; lastY = y }
            Mode.PINCH -> { lastX = x; lastY = y; mode = Mode.ORBIT } // 두 손가락 → 한 손가락: 튀지 않게 이어서
        }
    }

    fun up() { mode = Mode.IDLE }
    fun doubleTap() { mode = Mode.IDLE; view.reset() }

    private fun ensureFree(x: Float, y: Float) { if (!view.free) onBegin?.invoke(x, y) }
}

/** 기준점 고르기: 닿은 곳 40 px 안에서 가장 앞의 점, 없으면 점군 가운데(최대 6만 점 표본) */
object CloudPick {
    class Pivot(val point: FloatArray, val touched: Boolean, val radius: Double)
    fun pivot(a: FloatArray, na: Int, b: FloatArray, nb: Int, vp: FloatArray, w: Int, h: Int, x: Float, y: Float, radiusPx: Float = 40f, stride: Int = 7): Pivot {
        val total = na + nb
        val step = maxOf(1, total / 60_000)
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        var best: FloatArray? = null; var bestW = Float.MAX_VALUE
        fun visit(d: FloatArray, cnt: Int) {
            var i = 0
            while (i < cnt) {
                val o = i * stride; val px = d[o]; val py = d[o + 1]; val pz = d[o + 2]
                if (px.isFinite() && py.isFinite() && pz.isFinite()) {
                    sx += px; sy += py; sz += pz; n++
                    val s = GlMath.project(vp, px, py, pz)
                    if (s[2] > 0) {
                        val qx = (s[0] + 1) / 2 * w; val qy = (1 - s[1]) / 2 * h
                        if (kotlin.math.hypot(qx - x, qy - y) <= radiusPx && s[2] < bestW) { bestW = s[2]; best = floatArrayOf(px, py, pz) }
                    }
                }
                i += step
            }
        }
        visit(a, minOf(na, a.size / stride)); visit(b, minOf(nb, b.size / stride))
        if (n == 0) return Pivot(floatArrayOf(0f, 0f, -1.5f), false, 3.0)
        val c = floatArrayOf((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())
        // 반경: 가운데에서의 평균 거리 × 2
        var rs = 0.0; var m = 0
        fun rad(d: FloatArray, cnt: Int) { var i = 0; while (i < cnt) { val o = i * stride; val dx = d[o] - c[0]; val dy = d[o + 1] - c[1]; val dz = d[o + 2] - c[2]; val l = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()); if (l.isFinite()) { rs += l; m++ }; i += step } }
        rad(a, minOf(na, a.size / stride)); rad(b, minOf(nb, b.size / stride))
        val radius = if (m > 0) (rs / m * 2).coerceIn(0.5, 200.0) else 3.0
        return best?.let { Pivot(it, true, radius) } ?: Pivot(c, false, radius)
    }
}
