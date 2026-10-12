package kr.archsurvey.comm

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.archsurvey.core.FixQuality
import kr.archsurvey.engine.FixSoundGate
import kr.archsurvey.engine.SurveyEngine
import kr.archsurvey.feedback.FeedbackEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.1.19 게이트 SFX-0119-1: RTK 고정↔유동 플랩 때 FIX_ACQUIRED/FIX_LOST 소리·진동 연타 금지.
 * 새 상태가 FixSoundGate.HOLD_MS(3 s) 유지될 때만, 마지막으로 알린 상태와 다르면 한 번 울림.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixFlap0119Tests {
    private val fixSounds = setOf(FeedbackEvent.FIX_ACQUIRED, FeedbackEvent.FIX_LOST)

    // ---------- 게이트(가상 시간) ----------

    @Test fun sfx0119_1_flap10x1s_playsAtMostTwice() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        var fixed = false
        repeat(10) { fixed = !fixed; g.onFixedChanged(fixed); advanceTimeBy(1_000) }
        advanceTimeBy(10_000) // 마지막 상태(10번째 = 비고정) 안착
        assertTrue("1초 간격 10회 전환 → 소리 ≤2: $got", got.size <= 2)
        assertTrue("시작·끝이 같은 비고정이면 무음: $got", got.isEmpty())
    }

    @Test fun sfx0119_1_flapThenSettleFixed_onlyOneAcquire() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        var fixed = false
        repeat(9) { fixed = !fixed; g.onFixedChanged(fixed); advanceTimeBy(1_000) } // 9번째 = 고정
        advanceTimeBy(10_000)
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED), got)
    }

    @Test fun singleFixAcquire_stillPlays_afterHold() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        g.onFixedChanged(true)
        advanceTimeBy(FixSoundGate.HOLD_MS - 1); runCurrent()
        assertTrue("유지 시간 전에는 아직 무음", got.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED), got)
    }

    @Test fun sustainedLoss_afterFix_playsFixLostOnce() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        g.onFixedChanged(true); advanceTimeBy(5_000)
        g.onFixedChanged(false); advanceTimeBy(30_000)
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED, FeedbackEvent.FIX_LOST), got)
    }

    @Test fun briefDropWhileFixed_noSound() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        g.onFixedChanged(true); advanceTimeBy(5_000)
        got.clear()
        repeat(5) { g.onFixedChanged(false); advanceTimeBy(1_000); g.onFixedChanged(true); advanceTimeBy(1_000) }
        advanceTimeBy(10_000)
        assertTrue("고정 중 1초 끊김 반복은 무음: $got", got.isEmpty())
    }

    @Test fun reset_isSilent_andNextSessionFixPlays() = runTest {
        val got = ArrayList<FeedbackEvent>()
        val g = FixSoundGate(backgroundScope) { got.add(it) }
        g.onFixedChanged(true); advanceTimeBy(5_000)
        g.onFixedChanged(false); g.reset(); advanceTimeBy(10_000)
        assertEquals("세션 종료 시 FIX_LOST 무음", listOf(FeedbackEvent.FIX_ACQUIRED), got)
        g.onFixedChanged(true); advanceTimeBy(5_000)
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED, FeedbackEvent.FIX_ACQUIRED), got)
    }

    // ---------- 엔진 배선(실시간, 수정 제거 시 실패 대조) ----------

    private fun engineWithCollector(): Pair<SurveyEngine, CopyOnWriteArrayList<FeedbackEvent>> {
        val e = SurveyEngine(RuntimeEnvironment.getApplication(), { null }, { _, _ -> })
        val got = CopyOnWriteArrayList<FeedbackEvent>()
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { e.events.collect { got.add(it) } }
        return e to got
    }

    @Test fun engine_flap_emitsNoImmediateFixSounds() {
        val (e, got) = engineWithCollector()
        repeat(10) { i -> e.onQualityMaybeChanged(if (i % 2 == 0) FixQuality.RTK_FIXED else FixQuality.RTK_FLOAT) }
        Thread.sleep(300)
        assertTrue("플랩 즉시 FIX_* 연타 금지(수정 전 10회): $got", got.none { it in fixSounds })
        e.stop()
    }

    @Test fun engine_singleFix_stillEmitsAcquire() {
        val (e, got) = engineWithCollector()
        e.onQualityMaybeChanged(FixQuality.RTK_FLOAT)
        e.onQualityMaybeChanged(FixQuality.RTK_FIXED)
        val end = System.currentTimeMillis() + FixSoundGate.HOLD_MS + 3_000
        while (System.currentTimeMillis() < end && got.isEmpty()) Thread.sleep(20)
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED), got.filter { it in fixSounds })
        e.stop()
    }
}
