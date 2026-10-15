package kr.archsurvey.t0146

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.dig.DigKind
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
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

/** 0.1.53 [미니멀 | 일반]: 버튼 수(실제 렌더에서 누를 수 있는 것 세기) · 숨긴 것은 ⋯ 에 · 완화책 · 기본 = 일반 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Minimal0153Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "/workspace/survey-app/build-reports/0.1.53/screens").apply { mkdirs() }
    private val before = System.getenv("SHOT_BEFORE") != null
    private val app get() = RuntimeEnvironment.getApplication()
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "survey_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** 화면에 보이는 누를 수 있는 것(지도 바탕·가림막 제외) */
    private fun buttons(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        .map { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } ?: "?" }
        .filter { it !in setOf("map_area", "drawer_scrim", "more_scrim") }
    private fun mode(min: Boolean) { if (!before) rule.runOnIdle { Minimal.set(app, min); Minimal.setZoomButtons(app, false) } }
    @After fun reset() { if (!before) { Minimal.set(app, false); Minimal.setZoomButtons(app, false); Minimal.moreOpen = false } }
    private fun start(home: Boolean, connected: Boolean = true): Pair<kr.archsurvey.t0130.StubSrc, SurveyController> {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID, Mem())
        c.projectName = "정밀발굴 1지구"; c.zone = KoreanTmZone.EAST; c.ui.toastMs = 1L
        if (connected) s.push(FixQuality.RTK_FIXED, 0.014)
        c.homeOpen = home
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle(); return s to c
    }
    private fun record(name: String, b: List<String>) = File(out, "survey_count_$name.txt").writeText("${b.size}\n" + b.joinToString("\n") + "\n")

    @Test fun normal_isDefault_andCounts() {
        if (!before) assertFalse("기본 = 일반", app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_mode", false))
        val (_, c) = start(true)
        val tag = if (before) "0152" else "0153_normal"
        shot("${tag}_home"); record("${tag}_home", buttons())
        rule.runOnIdle { c.homeOpen = false }; rule.waitForIdle()
        shot("${tag}_map"); record("${tag}_map", buttons())
        if (!before) for (t in listOf("tool_layers", "tool_fit", "tool_locate", "tool_zoom_in", "tool_zoom_out", "tool_memo", "tool_photo", "tool_points", "measure_button", "pole_chip", "now_row", "status_pill"))
            rule.onNodeWithTag(t).assertExists()
    }

    @Test fun minimal_home_hidesOnlyDisplay_andShowsSafety() {
        if (before) return
        val (_, c) = start(true)
        rule.onNodeWithTag("mode_min").performClick(); rule.waitForIdle()
        assertTrue(Minimal.on); assertTrue("폰에 저장", app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_mode", false))
        shot("0153_min_home"); record("0153_min_home", buttons())
        for (t in listOf("home_areas", "home_goto", "home_settings2", "home_card_open", "home_features", "home_points", "home_export", "home_receiver", "home_settings")) rule.onNodeWithTag(t).assertDoesNotExist()
        for (t in listOf("status_pill", "home_new_job", "home_survey", "home_more", "home_job", "mode_chip", "home_sun")) rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithTag("home_backup_off").assertExists() // 자동 백업 없음 → 「백업 안 됨」
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        // ⋯ 에 숨긴 것 전부
        rule.onNodeWithTag("home_more").performClick(); rule.waitForIdle()
        shot("0153_min_more")
        for (t in listOf("more_points", "more_photo", "more_memo", "more_layers", "more_fit", "more_zoom_buttons", "more_jobs", "more_areas", "more_goto", "more_features", "more_export", "more_settings", "more_receiver", "more_drawer")) rule.onNodeWithTag(t).assertExists()
        rule.onNode(hasTestTag("mode_min") and hasAnyAncestor(hasTestTag("more_menu"))).assertExists()
        rule.onNodeWithTag("more_drawer").performClick(); rule.waitForIdle(); assertTrue(c.drawerOpen); assertFalse(Minimal.moreOpen)
        // 일반으로 되돌리면 0.1.52 그대로
        rule.runOnIdle { c.drawerOpen = false }; rule.onAllNodesWithTag("mode_normal")[0].performClick(); rule.waitForIdle()
        assertFalse(Minimal.on); rule.onNodeWithTag("home_points").assertExists(); rule.onNodeWithTag("home_settings").assertExists()
    }

    @Test fun minimal_home_receiverRowReappearsOnFailure() {
        if (before) return
        mode(true)
        start(true, connected = false)
        shot("0153_min_home_noreceiver")
        val rows = rule.onAllNodes(SemanticsMatcher("prep row") { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.let { it.startsWith("prep_") && it != "prep_count" && !it.endsWith("_next") } == true }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("수신기 실패 → 준비 줄이 다시 보임: ${rows.size}", rows.isNotEmpty())
        rule.onNodeWithTag("home_prep_done").assertDoesNotExist()
    }

    @Test fun minimal_map_counts_andMitigations() {
        if (before) return
        mode(true)
        val (_, c) = start(false)
        shot("0153_min_map"); record("0153_min_map", buttons())
        for (t in listOf("tool_layers", "tool_fit", "tool_locate", "tool_zoom_in", "tool_photo", "tool_points", "tool_memo")) rule.onNodeWithTag(t).assertDoesNotExist()
        for (t in listOf("measure_button", "pole_chip", "now_row", "status_pill", "map_more", "survey_back", "map_notice")) rule.onNodeWithTag(t).assertExists()
        // 위험 1: 손으로 옮기면 내 위치가 나타남
        rule.runOnIdle { c.panBy(5.0, 5.0, c.mapState().center) }; rule.waitForIdle()
        rule.onNodeWithTag("tool_locate").assertExists()
        shot("0153_min_map_panned")
        // 위험 5: ⋯ 「확대 단추 보이기」
        rule.onNodeWithTag("map_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("more_zoom_buttons").performClick(); rule.waitForIdle()
        assertTrue(app.getSharedPreferences("ui_prefs", 0).getBoolean("minimal_zoom_buttons", false))
        rule.runOnIdle { Minimal.moreOpen = false }; rule.waitForIdle()
        rule.onNodeWithTag("tool_zoom_in").assertExists(); rule.onNodeWithTag("tool_zoom_out").assertExists()
        // 위험 3: 면 측량 중이면 면 닫기가 자동으로
        rule.runOnIdle { c.dig.select(DigKind.FEATURE, null) }; rule.waitForIdle()
        rule.onNodeWithTag("tool_close_area").assertDoesNotExist()
        rule.runOnIdle { c.dig.closeArmed = true }; rule.waitForIdle()
        rule.onNodeWithTag("tool_close_area").assertExists()
        shot("0153_min_map_polygon")
        // 측량 단추는 오른쪽 끝 그대로(0.1.50)
        val b = rule.onNodeWithTag("measure_button").fetchSemanticsNode().boundsInRoot
        assertTrue(b.right / rule.density.density > 380f)
    }
}
