package kr.archsurvey.t0146

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import androidx.compose.ui.test.*
import org.junit.Assert.*
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.50 오른손 비교 그림(Fold7 커버 412×892, 글자 100%). SHOT_DIR 환경변수로 저장 위치 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0150Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "/workspace/survey-app/build-reports/0.1.50/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun setup(home: Boolean) = run {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.SP60_ID, Mem())
        c.projectName = "정밀발굴 1지구"; c.zone = KoreanTmZone.EAST; c.ui.toastMs = 1L
        s.push(FixQuality.RTK_FIXED, 0.014); c.homeOpen = home
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle(); s to c
    }
    @Test fun s_measure_map_saved() {
        val (s, c) = setup(false)
        rule.runOnIdle { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = 36.5003, lon = 127.0006); c.onMeasure() }
        shot("survey_measure_map")
    }
    @Test fun s_home() { setup(true); shot("survey_home") }

    private fun b(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = with(rule.density) { px.toDp().value }

    @Test fun rightHand_measureArea() {
        setup(false)
        val m = b("measure_button"); val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("측량 104dp", dp(m.height) >= 103.5f)
        assertTrue("오른쪽 정렬", m.right >= root.right - with(rule.density) { 17.dp.toPx() })
        val now = b("now_row"); assertTrue("측량 위 16dp 비움", dp(m.top - now.bottom) >= 15.5f)
        val memo = b("tool_memo"); assertTrue("보조 줄 70dp 이상 위", dp(m.center.y - memo.bottom) >= 70f)
        // 다음 점 줄 = 종류 시트
        rule.onNodeWithTag("now_row").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("map_now_banner").assertDoesNotExist()
        // 내 위치·확대·축소 = 지도 오른쪽 아래
        val map = b("map_area")
        for (t in listOf("tool_locate", "tool_zoom_in", "tool_zoom_out")) { val r = b(t); assertTrue("$t 아래 반", r.top > map.center.y); assertTrue("$t 오른쪽 반", r.left > map.center.x) }
        assertTrue("내 위치는 확대 왼쪽", b("tool_locate").right <= b("tool_zoom_in").left)
    }

    @Test fun rightHand_savedNoticeBottomLeft_withUndo() {
        val (s, c) = setup(false)
        rule.runOnIdle { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = 36.5003, lon = 127.0006); c.onMeasure() }
        rule.waitForIdle()
        val map = b("map_area"); val t = b("saved_toast"); val u = b("saved_toast_undo"); val m = b("measure_button")
        assertTrue("지도 아래 반", t.top > map.center.y); assertTrue("왼쪽에서 시작", t.left < map.center.x)
        assertTrue("되돌리기는 알림 안", u.right <= t.right + 1f)
        val dist = kotlin.math.hypot((u.center.x - m.center.x).toDouble(), (u.center.y - m.center.y).toDouble())
        assertTrue("되돌리기-측량 거리 ${dp(dist.toFloat())}dp", dp(dist.toFloat()) >= 120f)
    }

    @Test fun rightHand_measureRepeatGuard_0_8s() {
        val (s, c) = setup(false)
        rule.runOnIdle { s.push(FixQuality.RTK_FIXED, 0.014) }
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        val p1 = c.phase
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertEquals("0.8초 안 두 번째 누름은 무시(중지되지 않음)", p1, c.phase)
    }

    @Test fun rightHand_home_continuePinnedBottom() {
        setup(true)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val go = b("home_survey"); assertTrue("측량 계속하기 104dp", dp(go.height) >= 103.5f)
        assertTrue("맨 아래", dp(root.bottom - go.bottom) <= 40f)
        assertTrue("자주 쓰는 것이 드문 것보다 아래(0.1.56 S6: 새 작업·다른 작업·PC로 보내기 = 맨 아래 엄지 줄)", b("home_points").top > b("home_areas").top && b("home_export").top > b("home_points").top && b("home_new_job").top > b("home_points").top)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
