package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.archsurvey.core.Bubble
import kr.archsurvey.core.TiltLimits
import kr.archsurvey.core.TiltLowPass
import kr.archsurvey.core.TiltMath
import kr.archsurvey.core.TiltPolicy
import kr.archsurvey.core.Vec3

/** 폰 센서·저장소. 시험에서는 가짜로 바꾼다 */
interface TiltPlatform {
    /** 가속도 센서가 있는지. 없으면 기포를 숨기고 설정에 안내를 보인다 */
    val available: Boolean
    var enabled: Boolean
    var limitDeg: Double
    var policyId: String
    /** 영점(폴을 세웠을 때의 측정 방향). null = 기본(폰 세로 +Y) */
    var reference: Vec3?
    /** 센서 받기 시작/멈춤 (화면이 보일 때만 받는다) */
    fun start(onSample: (Vec3) -> Unit)
    fun stop()
}

enum class TiltState { OFF, NO_SENSOR, NO_DATA, MOVING, OK, OVER }

/**
 * 전자 기포 상태. 센서 값 → 저역통과 → 영점 축 대비 각도.
 * 좌표 기울기 보정은 하지 않는다(각도만 표시·경고·기록).
 */
class TiltController(private val p: TiltPlatform, private val clock: () -> Long = { System.currentTimeMillis() }) {
    val available: Boolean get() = p.available
    var enabled by mutableStateOf(p.enabled); private set
    var limitDeg by mutableStateOf(TiltLimits.clamp(p.limitDeg)); private set
    var policy by mutableStateOf(TiltPolicy.byId(p.policyId)); private set
    var calibrated by mutableStateOf(p.reference != null); private set
    var angleDeg by mutableStateOf<Double?>(null); private set
    var bubble by mutableStateOf<Bubble?>(null); private set
    var moving by mutableStateOf(false); private set
    /** 설정 화면에 보일 영점 결과 한 줄 */
    var calibrationNote by mutableStateOf<String?>(null); private set

    private val lp = TiltLowPass()
    private var lastSampleMs = 0L
    private var running = false

    /** 이 시간(ms) 동안 센서 값이 없으면 ‘값 없음’ */
    var staleMs = 1500L

    val state: TiltState get() = when {
        !available -> TiltState.NO_SENSOR
        !enabled -> TiltState.OFF
        angleDeg == null || clock() - lastSampleMs > staleMs -> TiltState.NO_DATA
        moving -> TiltState.MOVING
        (angleDeg ?: 0.0) > limitDeg -> TiltState.OVER
        else -> TiltState.OK
    }

    /** 지금 각도(값이 오래됐거나 꺼져 있으면 null) */
    fun currentAngle(): Double? = if (state == TiltState.OK || state == TiltState.OVER || state == TiltState.MOVING) angleDeg else null
    val isOver: Boolean get() = state == TiltState.OVER

    fun resume() { if (running || !available || !enabled) return; running = true; p.start { onSample(it) } }
    fun pause() { if (!running) return; running = false; p.stop(); lp.reset() }

    fun onSample(raw: Vec3) {
        val f = lp.add(raw)
        val ref = p.reference ?: Vec3.UP_PORTRAIT
        angleDeg = TiltMath.angleDeg(f, ref)
        bubble = TiltMath.bubble(f, ref)
        moving = TiltMath.isMoving(raw)
        lastSampleMs = clock()
    }

    fun changeEnabled(v: Boolean) { enabled = v; p.enabled = v; if (v) resume() else { pause(); angleDeg = null; bubble = null } }
    fun changeLimit(v: Double) { val c = TiltLimits.clamp(v); limitDeg = c; p.limitDeg = c }
    fun stepLimit(up: Boolean) = changeLimit(limitDeg + if (up) TiltLimits.STEP else -TiltLimits.STEP)
    fun changePolicy(v: TiltPolicy) { policy = v; p.policyId = v.id }

    /** ‘폴 세우고 영점 잡기’: 지금 방향을 수직으로 삼는다. 실패 사유를 돌려준다(성공 null) */
    fun calibrate(): String? {
        val f = lp.value
        val err = when {
            !available -> "이 폰에는 기울기 센서가 없어요"
            f == null || clock() - lastSampleMs > staleMs -> "센서 값이 아직 없어요. 잠시 뒤 다시 누르세요"
            TiltMath.isMoving(f) -> "폴이 흔들리고 있어요. 멈춘 뒤 다시 누르세요"
            (TiltMath.angleDeg(f, Vec3.UP_PORTRAIT) ?: 99.0) > TiltMath.MAX_CALIBRATION_OFFSET_DEG -> "폰이 세로로 붙어 있지 않은 것 같아요. 폰을 폴에 세로로 고정하세요"
            else -> null
        }
        if (err != null) { calibrationNote = err; return err }
        val off = TiltMath.angleDeg(f!!, Vec3.UP_PORTRAIT) ?: 0.0
        p.reference = f.unit(); calibrated = true
        calibrationNote = "영점을 잡았어요 (장착 오차 ${String.format(java.util.Locale.US, "%.1f", off)}°)"
        onSample(f)
        return null
    }

    fun resetCalibration() { p.reference = null; calibrated = false; calibrationNote = "영점을 기본값(폰 세로)으로 되돌렸어요"; lp.value?.let { onSample(it) } }

    fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}

/** 0.1.11 전자 기포 문구(전문가 GNSS P3): 목업 23 「IMU 측량」과 다름을 분명히 */
object TiltCopy {
    const val SHORT = "폰 센서 · 좌표 미보정"
    const val NOTE = "폰 가속도 센서로 보는 전자 기포예요. 좌표를 보정하지 않고, 수신기 IMU(R10 SurePoint) 기울기 보정도 아니에요. 각도는 경고·기록용이에요."
    /** 폴 2 m 기준 수평 어긋남(cm) = 2 m × sin(각도) */
    fun offsetCm(deg: Double, poleM: Double = 2.0): Double = poleM * kotlin.math.sin(Math.toRadians(deg)) * 100.0
    fun offsetLine(deg: Double): String = String.format(java.util.Locale.US, "%.1f°면 폴 2 m에서 수평 약 %.1f cm 어긋나요", deg, offsetCm(deg))
}
