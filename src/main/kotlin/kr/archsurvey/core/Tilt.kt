package kr.archsurvey.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 폰 가속도(중력) 센서로 폴 기울기를 잰다 — 전자 기포.
 * 전제: 폰이 폴에 **세로(초상화)로** 고정되어 있다. 폴이 똑바로 서 있으면 가속도 센서는 폰 +Y 방향으로 약 9.8 m/s² 를 읽는다.
 * 좌표 기울기 보정(기울어진 만큼 위치를 옮기는 계산)은 하지 않는다. 각도는 표시·경고·기록용이다.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val norm: Double get() = sqrt(x * x + y * y + z * z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun unit(): Vec3? { val n = norm; return if (n < 1e-9) null else Vec3(x / n, y / n, z / n) }
    companion object { val UP_PORTRAIT = Vec3(0.0, 1.0, 0.0) }
}

/** 저역통과 필터(지수 평균). alpha 가 작을수록 부드럽고 느리다. 첫 값은 그대로 받는다 */
class TiltLowPass(private val alpha: Double = 0.15) {
    var value: Vec3? = null; private set
    fun add(v: Vec3): Vec3 {
        val p = value
        val n = if (p == null) v else Vec3(p.x + alpha * (v.x - p.x), p.y + alpha * (v.y - p.y), p.z + alpha * (v.z - p.z))
        value = n; return n
    }
    fun reset() { value = null }
}

/** 기포 위치: 영점 축에 수직인 면 위의 성분(−1..1). x 오른쪽+, y 아래쪽+(폰 화면 쪽=사용자 쪽). 기포는 높은 쪽으로 간다 */
data class Bubble(val x: Double, val y: Double)

object TiltMath {
    const val G = 9.80665
    /** 측정값 크기가 중력과 이만큼(m/s²) 넘게 다르면 폴이 흔들리는 중으로 본다 */
    const val MOVING_TOLERANCE = 1.5
    /** 영점 잡기 때 허용하는 기본 자세(세로)에서의 최대 벗어남(°). 넘으면 폰이 세로로 붙어 있지 않은 것 */
    const val MAX_CALIBRATION_OFFSET_DEG = 25.0

    /** 측정 벡터와 영점 축 사이 각도(°) */
    fun angleDeg(reading: Vec3, ref: Vec3 = Vec3.UP_PORTRAIT): Double? {
        val a = reading.unit() ?: return null; val r = ref.unit() ?: return null
        return Math.toDegrees(acos((a dot r).coerceIn(-1.0, 1.0)))
    }

    /** 기포 표시용 평면 성분. 기본 영점(+Y)에서 x = 폰 X, y = 폰 Z */
    fun bubble(reading: Vec3, ref: Vec3 = Vec3.UP_PORTRAIT): Bubble? {
        val a = reading.unit() ?: return null; val r = ref.unit() ?: return null
        val xAxis = Vec3(1.0, 0.0, 0.0)
        val u = (xAxis - r * (xAxis dot r)).unit() ?: return null
        val v = (u cross r).unit() ?: return null
        return Bubble(a dot u, a dot v)
    }

    fun isMoving(reading: Vec3) = abs(reading.norm - G) > MOVING_TOLERANCE
}

/** 기울기가 허용값을 넘었을 때 측점 동작 */
enum class TiltPolicy(val id: String, val labelKo: String) {
    WARN("warn", "경고만"), CONFIRM("confirm", "측점 때 확인"), BLOCK("block", "측점 막기");
    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: WARN }
}

object TiltLimits {
    const val MIN = 0.5; const val MAX = 5.0; const val DEFAULT = 2.0; const val STEP = 0.5
    fun clamp(v: Double) = (Math.round(v / STEP) * STEP).coerceIn(MIN, MAX)
}
