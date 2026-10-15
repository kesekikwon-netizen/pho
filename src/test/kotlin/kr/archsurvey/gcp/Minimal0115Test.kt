package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.Minimal
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.15 [미니멀 | 일반]: 실제 렌더에서 누를 수 있는 것 세기 · 숨긴 것은 ⋯ 에 · 완화책 · 기본 = 일반 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Minimal0115Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "build-reports/0.1.15/screens").apply { mkdirs() }
    private val before = System.getenv("SHOT_BEFORE") != null
    private val app get() = RuntimeEnvironment.getApplication()
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "gcp_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun buttons(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        .map { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } ?: "?" }.filter { it != "map_area" }
    private fun record(name: String, b: List<String>) = File(out, "gcp_count_$name.txt").writeText("${b.size}\n" + b.joinToString("\n") + "\n")
    private fun mode(min: Boolean) { if (!before) { Minimal.set(app, min); Minimal.setZoomButtons(app, false) } }
    @After fun reset() { if (!before) { Minimal.set(app, false); Minimal.setZoomButtons(app, false) } }
    private fun rig(): ControllerTest.Rig {
        TileImages.networkEnabled = false
        val r = ControllerTest.rig().siteWithObj()
        r.measure(1, 194884.0, 573986.0)
        repeat(3) { r.gnss.emitAt(194882.0, 573987.0) }
        while (r.c.screen != Screen.OBJECTS && r.c.stack.size > 1) r.c.back()
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }; rule.waitForIdle()
        return r
    }
    private fun toTargets(r: ControllerTest.Rig) { rule.runOnIdle { r.c.openObj(r.c.book!!.objs.first().id); r.c.openTargets() }; rule.waitForIdle() }

    @Test fun normal_isDefault_andCounts() {
        if (!before) assertFalse(app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_mode", false))
        val r = rig(); val tag = if (before) "0114" else "0115_normal"
        shot("${tag}_home"); record("${tag}_home", buttons())
        toTargets(r); shot("${tag}_targets"); record("${tag}_targets", buttons())
        if (!before) for (t in listOf("tool_fit", "tool_locate", "tool_zoom_in", "tool_zoom_out", "add_slot", "targets_obj", "qtag_1", "qtag_2", "measure_button", "pole_chip", "epochs_chip")) rule.onNodeWithTag(t).assertExists()
    }

    @Test fun minimal_home_and_targets() {
        if (before) return
        mode(true)
        val r = rig(); val c = r.c
        shot("0115_min_home"); record("0115_min_home", buttons())
        for (t in listOf("stage_row", "to_handoff", "menu")) rule.onNodeWithTag(t).assertDoesNotExist()
        for (t in listOf("obj_new", "more", "status_pill")) rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithTag("more").performClick(); rule.waitForIdle()
        shot("0115_min_home_more")
        rule.onNodeWithTag("menu_mode", useUnmergedTree = true).assertExists()
        for (t in listOf("menu_handoff", "menu_stage_all", "menu_stage_handoff", "menu_zoom_buttons", "menu_settings", "menu_map", "menu_sites")) rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithTag("menu_stage_photo").performClick(); rule.waitForIdle(); assertEquals(Stage.PHOTO, c.filter)
        rule.runOnIdle { c.filter = null }
        toTargets(r)
        shot("0115_min_targets"); record("0115_min_targets", buttons())
        for (t in listOf("tool_fit", "tool_zoom_in", "add_slot", "targets_obj", "qtag_1")) rule.onNodeWithTag(t).assertDoesNotExist()
        // 내 위치: 따라가기 켜짐이면 숨김, 꺼짐(대상에 맞춰 봄·손으로 옮김)이면 보임
        if (c.follow) rule.onNodeWithTag("tool_locate").assertDoesNotExist() else rule.onNodeWithTag("tool_locate").assertExists()
        rule.runOnIdle { c.recenter() }; rule.waitForIdle(); if (c.follow) rule.onNodeWithTag("tool_locate").assertDoesNotExist()
        for (t in listOf("measure_button", "pole_chip", "epochs_chip", "qtag_now", "status_pill", "more", "header_back")) rule.onNodeWithTag(t).assertExists()
        // 위험 6: 지금 표적 번호는 측량 단추 글자에
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextContains("#2", substring = true)
        rule.onNodeWithTag("qtag_count", useUnmergedTree = true).assertTextContains("1/4", substring = true)
        // 길게 = 전체 칸, 접기
        rule.onNodeWithTag("qtag_now").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("qtag_1").assertExists(); shot("0115_min_targets_all")
        rule.onNodeWithTag("qtag_fold").performClick(); rule.waitForIdle(); rule.onNodeWithTag("qtag_1").assertDoesNotExist()
        // 위험 1: 지도를 옮기면 내 위치
        rule.runOnIdle { c.panBy(3.0, 3.0) }; rule.waitForIdle(); rule.onNodeWithTag("tool_locate").assertExists()
        // ⋯ 에서 대상·표적 더하기·맞춤·확대 단추
        rule.onNodeWithTag("more").performClick(); rule.waitForIdle()
        for (t in listOf("menu_obj", "menu_add_slot", "menu_fit", "menu_zoom_buttons")) rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithTag("menu_zoom_buttons").performClick(); rule.waitForIdle()
        assertTrue(app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_zoom_buttons", false))
        rule.runOnIdle { c.menuOpen = false }; rule.waitForIdle(); rule.onNodeWithTag("tool_zoom_in").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val b = rule.onNodeWithTag("measure_button").fetchSemanticsNode().boundsInRoot
        assertTrue("측량 단추 오른쪽 끝 그대로", b.right / rule.density.density > 380f)
    }

    @Test fun modeChip_onSites_switchesAndSaves() {
        if (before) return
        val r = rig(); rule.runOnIdle { while (r.c.screen != Screen.SITES && r.c.stack.size > 1) r.c.back(); if (r.c.screen != Screen.SITES) r.c.openSitePicker() }; rule.waitForIdle()
        rule.onNodeWithTag("mode_min").performClick(); rule.waitForIdle()
        assertTrue(Minimal.on && app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_mode", false))
        shot("0115_min_sites")
        rule.onNodeWithTag("mode_normal").performClick(); rule.waitForIdle(); assertFalse(Minimal.on)
    }
}
