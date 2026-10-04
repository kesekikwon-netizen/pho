package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import java.io.FileOutputStream

/**
 * 에뮬레이터 없이 JVM(Robolectric) 에서 실제 Compose 화면을 그려 확인한다.
 * 이것은 실기기/에뮬레이터 실행이 아니다: 터치 지연·폰 해상도 차이·글꼴·성능은 확인하지 못한다.
 * 360x800dp, xxhdpi(3.0x) 가정. 스크린샷은 /workspace/survey-app/build-reports/ 에 저장.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class SurveyScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("../build-reports").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private fun make(scn: FakeScenario = FakeScenarios.fieldWalk(), poleRight: Boolean = true): Pair<FakeGnssSource, SurveyController> {
        val f = FakeGnssSource(scn)
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5)
        c.poleOnRight = poleRight
        return f to c
    }

    private fun show(c: SurveyController, dark: Boolean = false) {
        rule.setContent { SurveyTheme(dark = dark) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        // captureToImage(PixelCopy) 는 Robolectric 에서 시간 초과 → 뷰 계층을 소프트웨어 캔버스에 직접 그린다
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun adv(f: FakeGnssSource, n: Int) { rule.runOnIdle { repeat(n) { f.emitNext() } }; rule.waitForIdle() }
    private fun skip(f: FakeGnssSource) { rule.runOnIdle { f.skipToNextSegment() }; rule.waitForIdle() }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()
    private fun mapFraction(): Double = bounds("map_area").height.value.toDouble() / rule.onRoot().getBoundsInRoot().height.value

    @Test fun a_startup_map_always_visible_and_marker_moves() {
        val (f, c) = make(); show(c)
        rule.onNodeWithTag("screen").assertIsDisplayed()
        rule.onNodeWithTag("map_canvas").assertIsDisplayed()
        adv(f, 3); shot("01_idle_fixed_t3")
        val a = c.mapState().current!!.at
        adv(f, 12); shot("02_idle_fixed_t15_moved")
        val b = c.mapState().current!!.at
        val moved = Math.hypot(b.x - a.x, b.y - a.y)
        assertTrue("현재 위치가 지도에서 이동 $moved m", moved > 2.5)
        assertTrue("지도 영역 ≥60% (${mapFraction()})", mapFraction() >= 0.6)
        File(out, "metrics.txt").appendText("idle map_fraction=${"%.3f".format(mapFraction())} marker_moved_m=${"%.2f".format(moved)}\n")
    }

    @Test fun b_status_band_three_states() {
        val (f, c) = make(); show(c)
        adv(f, 5)
        rule.onNodeWithTag("status_main").assertTextContains("고정", substring = true)
        rule.onNodeWithTag("pole_label").assertTextContains("고정")
        shot("03_status_fixed")
        skip(f); adv(f, 2)
        rule.onNodeWithTag("status_main").assertTextContains("주의", substring = true)
        rule.onNodeWithTag("pole_label").assertTextContains("주의")
        shot("04_status_caution_float")
        skip(f); adv(f, 2)
        rule.onNodeWithTag("status_main").assertTextContains("불가", substring = true)
        rule.onNodeWithTag("pole_label").assertTextContains("불가")
        shot("05_status_unavailable_single")
        skip(f); adv(f, 2)
        rule.onNodeWithTag("status_sub").assertTextContains("연결이 끊겼어요", substring = true)
        shot("06_status_unavailable_link_down")
    }

    @Test fun c_measure_flow_map_never_covered() {
        val (f, c) = make(); show(c); adv(f, 3)
        assertEquals(72f, bounds("measure_button").height.value, 0.6f)
        val idleMap = bounds("map_area")
        val metrics = StringBuilder()
        fun check(label: String, expectMin: Double = 0.6) {
            val m = bounds("map_area"); val info = bounds("info_zone"); val ctl = bounds("control_area")
            assertTrue("$label: 지도와 정보 영역이 겹치지 않음", m.bottom.value <= info.top.value + 0.6f)
            assertTrue("$label: 정보와 컨트롤이 겹치지 않음", info.bottom.value <= ctl.top.value + 0.6f)
            assertTrue("$label: 측점 버튼은 컨트롤 영역 안", bounds("measure_button").top.value >= ctl.top.value - 0.6f)
            val fr = mapFraction()
            assertTrue("$label: 지도 비율 $fr", fr >= expectMin)
            metrics.append("$label map_fraction=${"%.3f".format(fr)} map_h_dp=${"%.1f".format(m.height.value)}\n")
        }
        check("idle")
        rule.runOnIdle { c.onMeasure() }; adv(f, 2)
        assertEquals(Phase.ACQUIRING, c.phase)
        rule.onNodeWithTag("acquire_text").assertIsDisplayed()
        check("acquiring"); shot("07_acquiring")
        adv(f, 5)
        assertEquals(Phase.RESULT, c.phase)
        rule.onNodeWithTag("result_chip").assertIsDisplayed()
        check("result_strip"); shot("08_result_strip_min")
        rule.onNodeWithTag("expand_btn").performClick(); rule.waitForIdle()
        assertEquals(Phase.RESULT_EXPANDED, c.phase)
        check("result_expanded", 0.4); shot("09_result_expanded_photos_voice")
        rule.onNodeWithTag("expand_btn").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("voice_btn").performClick(); rule.waitForIdle()
        assertEquals(Phase.RECORDING, c.phase)
        check("recording"); shot("10_recording_mock")
        rule.onNodeWithTag("record_stop").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("measure_button").performClick(); adv(f, 6)
        assertEquals(2, c.points.size)
        shot("11_second_point_next")
        assertEquals(idleMap.width.value, bounds("map_area").width.value, 0.6f)
        File(out, "metrics.txt").appendText(metrics.toString())
    }

    @Test fun d_touch_targets_min_48dp_and_pole_strip_has_no_clickables() {
        val (f, c) = make(); show(c); adv(f, 3)
        rule.runOnIdle { c.onMeasure() }; adv(f, 7)
        val clickables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty())
        val tooSmall = clickables.filter { n -> n.size.width / 3f < 47.5f || n.size.height / 3f < 47.5f }
        assertTrue("48dp 미만 터치 요소: " + tooSmall.map { "${it.size.width / 3f}x${it.size.height / 3f}" }, tooSmall.isEmpty())
        assertEquals(56f, bounds("pole_strip").width.value, 0.6f)
        rule.onNode(hasAnyAncestor(hasTestTag("pole_strip")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertDoesNotExist()
        rule.onNode(hasTestTag("pole_strip") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertDoesNotExist()
        File(out, "metrics.txt").appendText("clickable_nodes=${clickables.size} smaller_than_48dp=0 pole_strip_clickables=0\n")
    }

    @Test fun e_gear_opens_left_drawer_and_map_still_visible() {
        val (f, c) = make(); show(c); adv(f, 3)
        rule.onNodeWithTag("drawer").assertDoesNotExist()
        rule.onNodeWithTag("gear").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        val d = bounds("drawer")
        assertEquals("서랍은 왼쪽에서 시작", 0f, d.left.value, 0.6f)
        val root = rule.onRoot().getBoundsInRoot()
        assertTrue("서랍 폭 ≈ 80% (${d.width.value})", Math.abs(d.width.value - root.width.value * 0.8f) < 1.5f)
        assertTrue("오른쪽 지도 노출 폭 ${root.width.value - d.width.value}", root.width.value - d.width.value >= 60f)
        rule.onNodeWithTag("menu_job_new").assertIsDisplayed()
        rule.onNodeWithTag("menu_ai").assertIsNotEnabled()
        shot("12_drawer_open")
        rule.onNodeWithTag("drawer_scrim").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("drawer").assertDoesNotExist()
        rule.onNodeWithTag("gear").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("menu_job_new").performClick(); rule.waitForIdle()
        assertTrue(c.message!!.contains("다음 라운드"))
        File(out, "metrics.txt").appendText("drawer_width_dp=${"%.1f".format(d.width.value)} map_exposed_dp=${"%.1f".format(root.width.value - d.width.value)}\n")
    }

    @Test fun f_antenna_sheet_values() {
        val (f, c) = make(); show(c); adv(f, 3)
        rule.onNodeWithTag("antenna_input", useUnmergedTree = true).assertTextContains("2.000", substring = true)
        rule.onNodeWithTag("apc_label").assertTextContains("2.199", substring = true)
        rule.onNodeWithTag("antenna_chip").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("antenna_sheet").assertIsDisplayed()
        rule.onNodeWithTag("sheet_summary").assertTextContains("안테나 위상중심 2.199 m", substring = true)
        shot("13_antenna_sheet_r10_quick_release_2_1991")
        rule.onNodeWithTag("ref_bottom_of_antenna_mount").performClick()
        repeat(5) { rule.onNodeWithTag("step_plus").performClick() }
        rule.waitForIdle()
        assertEquals(2.05, c.antenna.inputValueM, 1e-9)
        assertEquals(2.1991, c.antennaResult!!.totalToApcM, 1e-9)
        rule.onNodeWithTag("sheet_summary").assertTextContains("2.199 m", substring = true)
        shot("14_antenna_sheet_r10_mount_2050_2_1991")
        rule.onNodeWithTag("model_SPECTRA_SP60").performClick(); rule.waitForIdle()
        repeat(5) { rule.onNodeWithTag("step_minus").performClick() }
        rule.waitForIdle()
        assertEquals(2.000, c.antenna.inputValueM, 1e-9)
        assertEquals(2.0687, c.antennaResult!!.totalToApcM, 1e-9)
        rule.onNodeWithTag("sheet_summary").assertTextContains("2.069 m", substring = true)
        shot("15_antenna_sheet_sp60_2_0687")
    }

    @Test fun g_pole_left() {
        val (f, c) = make(poleRight = false); show(c); adv(f, 3)
        assertEquals(0f, bounds("pole_strip").left.value, 0.6f)
        shot("16_pole_left")
    }

    @Test fun h_dark_theme() {
        val (f, c) = make(); show(c, dark = true); adv(f, 8)
        shot("17_dark_idle")
    }
}
