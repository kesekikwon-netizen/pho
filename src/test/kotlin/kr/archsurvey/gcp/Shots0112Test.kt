package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.12 오른손 비교 그림(Fold7 커버 412×892, 글자 100%). SHOT_DIR 환경변수로 저장 위치 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0112Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "build-reports/0.1.12/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun rigTargets(): ControllerTest.Rig {
        TileImages.networkEnabled = false
        val r = ControllerTest.rig().siteWithObj()
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194880.0, 573989.0)
        repeat(3) { r.gnss.emitAt(194882.0, 573987.0) }
        return r
    }
    @Test fun g_targets() {
        val r = rigTargets(); r.c.openTargets()
        rule.mainClock.autoAdvance = false
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }
        rule.mainClock.advanceTimeBy(300)
        rule.runOnIdle { r.c.ui.toast("#2 저장 · XML · 다음 #3") }
        rule.mainClock.advanceTimeBy(300)
        shot("gcp_targets")
    }
    @Test fun g_obj() {
        val r = rigTargets()
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }
        shot("gcp_obj")
    }

    private fun b(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = with(rule.density) { px.toDp().value }

    @Test fun rightHand_targets() {
        val r = rigTargets(); r.c.openTargets()
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }; rule.waitForIdle()
        val m = b("measure_button"); assertTrue("측량 104dp", dp(m.height) >= 103.5f)
        val ep = b("epochs_chip"); val pole = b("pole_chip")
        assertTrue("평균 횟수는 폴 옆 위 줄", ep.bottom < m.top && kotlin.math.abs(ep.center.y - pole.center.y) < 2f)
        val q1 = b("qtag_1"); assertTrue("칩 줄 40dp 오른쪽", dp(q1.left) >= 40f + 12f - 0.5f)
        val cells = (1..4).map { b("qtag_$it") }
        assertTrue("측량 위 16dp 비움", dp(m.top - cells.maxOf { it.bottom }) >= 15.5f)
        val map = b("map_area")
        for (t in listOf("tool_locate", "tool_zoom_in", "tool_zoom_out")) { val x = b(t); assertTrue("$t 아래", x.top > map.center.y); assertTrue("$t 오른쪽", x.left > map.center.x) }
        // 「+」 짧게 = 안내만, 길게 = 표적 더하기
        rule.onNodeWithTag("add_slot").performClick(); rule.waitForIdle()
        assertNull(r.c.numPick); assertEquals(ADD_SLOT_HINT, r.c.ui.toast)
        rule.onNodeWithTag("add_slot").performTouchInput { longClick() }; rule.waitForIdle()
        assertNotNull("길게 = 표적 더하기 창", r.c.numPick ?: r.c.ui.choice ?: r.c.ui.confirm)
    }

    @Test fun rightHand_obj_mapButton_cardsLow() {
        val r = rigTargets()
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }; rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val back = b("obj_to_map"); val step4 = b("step_send")
        assertTrue("아래 뒤로 단추", back.top > root.height * 0.8f); assertTrue("76dp 폭", dp(back.width) >= 75.5f)
        assertTrue("단계 카드 아래쪽", step4.bottom > root.height * 0.6f)
        val before = r.c.screen
        rule.onNodeWithTag("obj_to_map").performClick(); rule.waitForIdle()
        assertNotEquals(before, r.c.screen)
    }
}
