package kr.archsurvey.gcp

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.TmProjection
import kr.archsurvey.device.LinkState
import kr.archsurvey.gcp.measure.*
import org.junit.Assert.*
import org.junit.Test

class MeasureTest {
    private fun run(g: TestGnss, epochs: Int = 3, allowNoSigma: Boolean = false, feed: suspend () -> Unit): MeasureResult = runBlocking {
        val m = TagMeasurer(g)
        val r = async { m.measure(epochs, KoreanTmZone.EAST, 1.68, allowNoSigma) }
        delay(20); feed(); r.await()
    }

    @Test fun strictFix_averagesAndSubtractsAntennaFromReceiverMsl() {
        val g = TestGnss(); g.emit()
        val r = run(g) { repeat(3) { g.emit(msl = 5.80 + it * 0.01); delay(5) } }
        assertTrue(r.toString(), r is MeasureResult.Ok)
        val m = (r as MeasureResult.Ok).m
        // 누른 순간의 최신 에폭 + 다음 두 에폭(5.80, 5.80, 5.81) 평균 − 안테나 높이(지오이드 다시 계산 없음)
        assertEquals((5.80 + 5.80 + 5.81) / 3 - 1.68, m.h, 1e-9)
        val tm = TmProjection.forward(37.7712, 128.8790, KoreanTmZone.EAST)
        assertEquals(tm.easting, m.e, 1e-6); assertEquals(tm.northing, m.n, 1e-6)
        assertEquals(3, m.epochs)
    }

    @Test fun floatDuringAveraging_abortsWithoutSaving() {
        val g = TestGnss(); g.emit()
        val r = run(g) { g.emit(); delay(5); g.emit(q = FixQuality.RTK_FLOAT); delay(5) }
        assertTrue(r is MeasureResult.Aborted)
        assertTrue((r as MeasureResult.Aborted).reasonKo.contains("FLOAT"))
        assertTrue(r.reasonKo.contains("저장하지 않았어요"))
    }

    @Test fun noSigmaNeedsExplicitAllow() {
        val g = TestGnss(); g.emit(sigma = null)
        assertEquals(Gate.AskNoSigma, MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)))
        val blocked = run(g) { g.emit(sigma = null); delay(5) }
        assertTrue(blocked is MeasureResult.Aborted)
        val g2 = TestGnss(); g2.emit(sigma = null)
        val ok = run(g2, allowNoSigma = true) { repeat(3) { g2.emit(sigma = null); delay(5) } }
        assertTrue(ok is MeasureResult.Ok); assertNull((ok as MeasureResult.Ok).m.sigmaH)
    }

    @Test fun gate_blocksFloatOldCorrectionLowPrecisionAndDisconnected() {
        val g = TestGnss()
        g.emit(q = FixQuality.RTK_FLOAT); assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)) is Gate.Blocked)
        g.emit(age = 30.0); assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)) is Gate.Blocked)
        g.emit(age = null); assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)) is Gate.Blocked)
        g.emit(sigma = 0.05); assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)) is Gate.Blocked)
        g.emit(); assertEquals(Gate.Ok, MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now)))
        assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.IDLE, g.now)) is Gate.Blocked)
        assertTrue(MeasureGate.check(StatusJudge.judge(g.g.value, LinkState.STREAMING, g.now + 10_000)) is Gate.Blocked)   // 끊김
    }

    @Test fun missingMsl_aborts() {
        val g = TestGnss(); g.emit(msl = null)
        val r = run(g) { repeat(3) { g.emit(msl = null); delay(5) } }
        assertTrue(r is MeasureResult.Aborted)
    }
}
