package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 수신 세션을 세는 감싸개: stop() 호출과 연결 상태 변화를 기록한다 */
private class Spy(val f: FakeGnssSource) : GnssSource by f {
    var stops = 0
    override fun stop() { stops++; f.stop() }
}

/**
 * 사용자 인수 기준: "한 점을 찍고, 다음 점을 누르면, 다음 점이 바로 측량이 되게."
 * 점이 저장된 뒤 수신 세션은 그대로이고, 측점 버튼 한 번 탭이 곧바로 새 취득이다(중단·재시작·확인 탭 0).
 * JVM(Robolectric) 시험이며 실기기 시험이 아니다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ConsecutivePointsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private fun setup(): Triple<FakeGnssSource, Spy, SurveyController> {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val spy = Spy(f)
        val c = SurveyController(spy, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5)
        c.resultStripMs = Long.MAX_VALUE   // 시험 시계가 띠를 건너뛰지 않게(저절로 사라지는 동작은 따로 시험)
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
        return Triple(f, spy, c)
    }
    private fun adv(f: FakeGnssSource, n: Int) { rule.runOnIdle { repeat(n) { f.emitNext() } }; rule.waitForIdle() }

    @Test fun threeConsecutivePointsNeedOnlyTheMeasureButtonAndKeepTheSession() {
        val (f, spy, c) = setup(); adv(f, 3)
        var taps = 0
        val names = mutableListOf<String>()
        repeat(3) { i ->
            // 이전 점의 결과 띠가 떠 있어도 측점 버튼은 보이고 눌린다
            rule.onNodeWithTag("measure_button").assertIsDisplayed().assertHasClickAction()
            rule.onNodeWithTag("measure_button").performClick(); taps++; rule.waitForIdle()
            assertEquals("탭 ${i + 1}번 직후 곧바로 취득 시작", Phase.ACQUIRING, c.phase)
            adv(f, 5)
            assertEquals(Phase.RESULT, c.phase)
            assertEquals(i + 1, c.points.size)
            names += c.points.last().name
            assertEquals("점 저장 뒤에도 연결 유지", LinkState.STREAMING, spy.link.value.state)
            assertEquals("세션 종료 호출 없음", 0, spy.stops)
        }
        assertEquals("점 3개에 필요한 탭 = 측점 버튼 3번, 그 밖의 확인·다음·재시작 탭 0", 3, taps)
        assertEquals(listOf("P-0001", "P-0002", "P-0003"), names)
        assertEquals("P-0004", c.nextName)
        rule.onNodeWithTag("next_point").assertExists() // 0.1.43 점 확인 화면의 「다음 점 재기」 = 측정 단추와 같은 한 번
    }

    @Test fun nextPointButtonOnExpandedPanelStartsAcquisitionWithOneTap() {
        val (f, spy, c) = setup(); adv(f, 3)
        rule.onNodeWithTag("measure_button").performClick(); adv(f, 5)
        // 0.1.35 X3: 장부 카드를 닫지 않아도 측점 단추(「다음 점」) 한 번 = 바로 다음 측정
        rule.onNodeWithTag("point_confirm").assertExists()
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("측정") // 0.1.43 시안: 늘 「측정」
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertEquals(Phase.ACQUIRING, c.phase)
        adv(f, 5)
        assertEquals(2, c.points.size)
        assertEquals(LinkState.STREAMING, spy.link.value.state); assertEquals(0, spy.stops)
    }

    @Test fun resultStripHidesByItselfAndNeverBlocksMeasure() {
        val (f, _, c) = setup(); adv(f, 3)
        rule.onNodeWithTag("measure_button").performClick(); adv(f, 5)
        rule.onNodeWithTag("point_confirm").assertExists()
        rule.runOnIdle { c.resultStripMs = 4000L; c.hideResultStrip() }; rule.waitForIdle()
        rule.onNodeWithTag("point_confirm").assertDoesNotExist()
        assertEquals(Phase.RESULT, c.phase)
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertEquals(Phase.ACQUIRING, c.phase)
    }

    @Test fun unavailableStillBlocksAndUndoIsInQuickMenu() {
        val (f, _, c) = setup(); adv(f, 3)
        rule.onNodeWithTag("measure_button").performClick(); adv(f, 5)
        assertEquals(1, c.points.size)
        // 불가(단독) 구간에서는 저장 차단 정책 유지
        rule.runOnIdle { f.skipToNextSegment(); f.skipToNextSegment() }; adv(f, 2)
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertNotEquals(Phase.ACQUIRING, c.phase)
        assertEquals(1, c.points.size)
        assertTrue(c.message!!.contains("측점할 수 없어요"))
        // 되돌리기는 퀵 메뉴에 있다
        rule.runOnIdle { c.quickSheetOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("quick_undo").performClick(); rule.waitForIdle()
        // 0.1.9: 되돌리기는 확인 창을 거친다
        assertEquals(1, c.points.size)
        rule.onNodeWithTag("confirm_dialog").assertExists()
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(0, c.points.size)
    }
}
