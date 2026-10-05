package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** 전자 기포 시험용 폰 센서·저장소 */
class FakeTiltPlatform(override val available: Boolean = true) : TiltPlatform {
    override var enabled = true; override var limitDeg = 2.0; override var policyId = "warn"; override var reference: Vec3? = null
    var listener: ((Vec3) -> Unit)? = null; var starts = 0; var stops = 0
    override fun start(onSample: (Vec3) -> Unit) { listener = onSample; starts++ }
    override fun stop() { listener = null; stops++ }
}

/** 폴 위쪽이 오른쪽(+X)으로 [rightDeg], 사용자 반대쪽으로 [awayDeg] 기울었을 때 가속도 센서 값(위쪽 반력) */
fun tiltReading(rightDeg: Double, awayDeg: Double = 0.0, g: Double = TiltMath.G): Vec3 {
    val a = Math.toRadians(rightDeg); val b = Math.toRadians(awayDeg)
    return Vec3(-g * sin(a) * cos(b), g * cos(a) * cos(b), g * sin(b))
}

class TiltTests {
    private fun ctl(p: FakeTiltPlatform = FakeTiltPlatform(), t: () -> Long = { 0L }) = TiltController(p, t)
    private fun feed(c: TiltController, v: Vec3, n: Int = 60) = repeat(n) { c.onSample(v) }

    @Test fun uprightIsZeroAndTiltAngleMatches() {
        assertEquals(0.0, TiltMath.angleDeg(tiltReading(0.0))!!, 1e-9)
        assertEquals(3.0, TiltMath.angleDeg(tiltReading(3.0))!!, 1e-6)
        assertEquals(2.0, TiltMath.angleDeg(tiltReading(0.0, 2.0))!!, 1e-6)
    }
    @Test fun bubbleGoesToHighSide() {
        // 위쪽이 오른쪽으로 기울면 높은 쪽은 왼쪽 → 기포 x < 0
        assertTrue(TiltMath.bubble(tiltReading(3.0))!!.x < 0)
        // 위쪽이 사용자 반대쪽으로 기울면 높은 쪽은 사용자 쪽(화면 아래) → 기포 y > 0
        assertTrue(TiltMath.bubble(tiltReading(0.0, 3.0))!!.y > 0)
        assertEquals(0.0, TiltMath.bubble(tiltReading(0.0))!!.x, 1e-9)
    }
    @Test fun lowPassSmoothsAndConverges() {
        val lp = TiltLowPass(0.15)
        lp.add(tiltReading(0.0)); val one = lp.add(tiltReading(4.0))
        assertTrue(TiltMath.angleDeg(one)!! < 1.0)   // 한 번 튄 값은 조금만 반영
        repeat(80) { lp.add(tiltReading(4.0)) }
        assertEquals(4.0, TiltMath.angleDeg(lp.value!!)!!, 0.01)
    }
    @Test fun limitsClampToHalfDegreeSteps() {
        assertEquals(0.5, TiltLimits.clamp(0.1), 0.0); assertEquals(5.0, TiltLimits.clamp(9.0), 0.0); assertEquals(2.5, TiltLimits.clamp(2.3), 0.0)
        val c = ctl(); repeat(20) { c.stepLimit(true) }; assertEquals(5.0, c.limitDeg, 0.0); repeat(20) { c.stepLimit(false) }; assertEquals(0.5, c.limitDeg, 0.0)
        assertEquals(TiltPolicy.WARN, TiltPolicy.byId(null))
    }
    @Test fun stateFollowsLimitStaleAndSwitch() {
        var now = 0L
        val p = FakeTiltPlatform(); val c = ctl(p) { now }
        assertEquals(TiltState.NO_DATA, c.state)
        feed(c, tiltReading(1.0)); assertEquals(TiltState.OK, c.state)
        feed(c, tiltReading(3.0)); assertEquals(TiltState.OVER, c.state); assertTrue(c.isOver)
        now = 5_000L; assertEquals(TiltState.NO_DATA, c.state); assertNull(c.currentAngle())
        c.changeEnabled(false); assertEquals(TiltState.OFF, c.state); assertFalse(p.enabled)
    }
    @Test fun noSensorHidesEverything() {
        val p = FakeTiltPlatform(available = false); val c = ctl(p)
        c.resume(); assertEquals(0, p.starts)
        assertEquals(TiltState.NO_SENSOR, c.state)
        assertNotNull(c.calibrate())
    }
    @Test fun resumePauseOnlyWhenEnabled() {
        val p = FakeTiltPlatform(); val c = ctl(p)
        c.resume(); c.resume(); assertEquals(1, p.starts)
        c.pause(); assertEquals(1, p.stops)
        p.enabled = false; val c2 = ctl(p); c2.resume(); assertEquals(1, p.starts)
    }
    @Test fun calibrationRemovesMountingOffset() {
        val p = FakeTiltPlatform(); val c = ctl(p)
        feed(c, tiltReading(4.0, 1.0))           // 폰이 폴에 약간 비뚤게 붙음
        assertTrue(c.angleDeg!! > 3.9)
        assertNull(c.calibrate()); assertTrue(c.calibrated); assertNotNull(p.reference)
        assertEquals(0.0, c.angleDeg!!, 0.05)
        feed(c, tiltReading(6.0, 1.0))           // 폴을 2° 더 기울임
        assertEquals(2.0, c.angleDeg!!, 0.1)
        c.resetCalibration(); assertNull(p.reference); assertFalse(c.calibrated)
    }
    @Test fun calibrationRefusesBadPose() {
        val c = ctl()
        assertNotNull(c.calibrate())                               // 값 없음
        feed(c, tiltReading(40.0)); assertNotNull(c.calibrate())   // 세로가 아님
        val c2 = ctl(); feed(c2, tiltReading(1.0, g = 14.0)); assertNotNull(c2.calibrate())  // 흔들림
        assertFalse(c.calibrated)
    }

    // ---- 측점과의 연결 ----
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun survey(policy: TiltPolicy, deg: Double): Triple<SurveyController, FakeGnssSource, TiltController> {
        val f = FakeGnssSource(FakeScenarios.allFixed())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(policy)
        feed(tc, tiltReading(deg))
        c.tilt = tc
        repeat(2) { f.emitNext() }
        return Triple(c, f, tc)
    }
    @Test fun warnPolicyStillMeasuresAndRecordsTilt() {
        val (c, f) = survey(TiltPolicy.WARN, 3.0)
        c.onMeasure(); repeat(7) { f.emitNext() }
        val p = c.points.single()
        assertEquals(3.0, p.tiltDeg!!, 0.05); assertEquals(3.0, p.tiltMaxDeg!!, 0.05)
    }
    @Test fun blockPolicyRefusesWithReason() {
        val (c, f) = survey(TiltPolicy.BLOCK, 3.0)
        c.onMeasure(); repeat(7) { f.emitNext() }
        assertTrue(c.points.isEmpty()); assertTrue(c.message!!.contains("기울"))
    }
    @Test fun confirmPolicyAsksThenMeasuresOnYes() {
        val (c, f) = survey(TiltPolicy.CONFIRM, 3.0)
        c.onMeasure(); assertNotNull(c.tiltPrompt); assertEquals(Phase.IDLE, c.phase)
        c.answerTilt(false); assertNull(c.tiltPrompt); repeat(7) { f.emitNext() }; assertTrue(c.points.isEmpty())
        c.onMeasure(); c.answerTilt(true); repeat(7) { f.emitNext() }
        assertEquals(1, c.points.size)
    }
    @Test fun withinLimitNoPromptAndNoSensorMeansNullTilt() {
        val (c, f) = survey(TiltPolicy.BLOCK, 1.0)
        c.onMeasure(); assertNull(c.tiltPrompt); repeat(7) { f.emitNext() }
        assertEquals(1.0, c.points.single().tiltDeg!!, 0.05)
        val f2 = FakeGnssSource(FakeScenarios.allFixed())
        val c2 = SurveyController(f2, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c2.tilt = TiltController(FakeTiltPlatform(available = false), { 0L })
        repeat(2) { f2.emitNext() }; c2.onMeasure(); repeat(7) { f2.emitNext() }
        assertNull(c2.points.single().tiltDeg)
    }
}
