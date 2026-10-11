package kr.archsurvey.t0131

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.device.LinkState
import kr.archsurvey.t0130.StubSrc
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.1.31 화면 시험(Robolectric — 실기기 아님): 초록 띠 문구 · 점 H/V · 홈 뒤로 종료 창 · 3초 시작 모션 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0131Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(strict: Boolean, home: Boolean = false, before: (StubSrc, SurveyController) -> Unit = { _, _ -> }): Pair<StubSrc, SurveyController> {
        TileImages.networkEnabled = false
        val (s, c) = rig(strict)
        c.ui.toastMs = 600_000L
        c.homeOpen = home
        before(s, c)
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        return s to c
    }

    @Test fun band_fixNoSigma_normal_readyGreenTitle_withSmallNote() {
        show(strict = false) { s, _ -> s.push(FixQuality.RTK_FIXED, null) }
        // 0.1.35: 상태 띠 → 머리줄 상태 알약(초록 「측량 가능」 + 작은 σ 없음)
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측량 가능")
        rule.onNodeWithTag("pill_sigma", useUnmergedTree = true).assertTextContains("σ 없음", substring = true)
    }

    @Test fun band_fixWithSigma_readyTitle_sigmaShown() {
        show(strict = false) { s, _ -> s.push(FixQuality.RTK_FIXED, 0.012) }
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측량 가능")
        rule.onNodeWithTag("pill_sigma", useUnmergedTree = true).assertTextContains("±1.2", substring = true)
    }

    @Test fun band_fixNoSigma_strict_blockedTitle() {
        show(strict = true) { s, _ -> s.push(FixQuality.RTK_FIXED, null) }
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측점 불가 · σ 없음")
    }

    @Test fun pointList_and_detail_showHv() {
        val (_, c) = show(strict = false) { s, c ->
            s.push(FixQuality.RTK_FIXED, 0.01)
            c.points.add(pt(0.012, 0.020, caution = false, name = "P-1"))
            c.points.add(pt(null, null, name = "P-2"))
        }
        rule.runOnUiThread { c.hub.open(ToolScreen.POINTS) }; rule.waitForIdle()
        rule.onNodeWithTag("point_hv_P-1", useUnmergedTree = true).assertTextEquals("H 0.012 · V 0.020 m")
        rule.onNodeWithTag("point_hv_P-2", useUnmergedTree = true).assertTextEquals("H — · V —")
        rule.onNodeWithTag("point_row_P-1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("detail_hv", useUnmergedTree = true).assertTextEquals("오차 H 0.012 · V 0.020 m")
    }

    @Test fun homeBack_showsExitDialog_yesCallsExit() {
        val conn = CountingConn(LinkState.STREAMING)
        var exits = 0
        show(strict = false, home = true) { s, c ->
            s.push(FixQuality.RTK_FIXED, 0.01)
            val cc = ConnectionController(conn, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)); c.connection = cc
            c.onExitApp = { exits++ }
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        // 0.1.32(의도된 변경): 「기기 종료」 → 「프로그램 종료」 두 단계
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals(AppExit.TITLE_DEVICE)
        // 확인 창에서 뒤로 = 취소(앱 그대로)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("confirm_dialog").assertDoesNotExist(); assertEquals(0, exits); assertEquals(0, conn.stops)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals(0, exits)
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals(AppExit.TITLE_APP)
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals(1, exits)
    }

    @Test fun drawer_exitRow_opensSameDialog() {
        val (_, c) = show(strict = false) { s, _ -> s.push(FixQuality.RTK_FIXED, 0.01) }
        rule.runOnUiThread { c.drawerOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("drawer_exit").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("confirm_dialog").assertExists()
    }

    @Test fun splash_threeSeconds_thenDone() {
        var done = false
        rule.mainClock.autoAdvance = false
        rule.setContent { SurveyTheme { if (!done) SplashScreen("Ver.0.1.31 · 빌드 32") { done = true } } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("splash").assertExists()
        rule.onNodeWithTag("splash_title").assertTextEquals("발굴 측량")
        rule.mainClock.advanceTimeBy(2_700)
        assertFalse("3초 전에는 끝나지 않음", done)
        rule.mainClock.advanceTimeBy(500)
        assertTrue("3초 뒤 앱으로", done)
    }
}
