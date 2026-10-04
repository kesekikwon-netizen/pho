package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.height
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.ui.bg.*
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
 * 배경 레이어 시트(s35/s36)와 업로드 정합 화면(s34c/d/e)을 Robolectric(JVM)으로 그려 본다. 실기기/에뮬레이터 실행이 아니다.
 * 360x800dp, xxhdpi 가정. 스크린샷: /workspace/survey-app/build-reports/bg/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class BackgroundScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("../build-reports/bg").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private fun setup(): BackgroundController {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5)
        val dir = File("build/tmp/fake_uploads").apply { mkdirs() }
        val bg = BackgroundController(c.projection, dir)
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82, bg = bg) } }
        rule.runOnIdle { repeat(6) { f.emitNext() } }
        rule.waitForIdle()
        return bg
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screenH() = rule.onRoot().getBoundsInRoot().height.value
    private fun sheetFraction(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().height.value / screenH()
    private fun clickTag(tag: String) { runCatching { rule.onNodeWithTag(tag).performScrollTo() }; rule.onNodeWithTag(tag).performClick(); rule.waitForIdle() }
    private fun assertTouch(tag: String) {
        val b = rule.onNodeWithTag(tag).getBoundsInRoot()
        assertTrue("$tag 높이 ${b.height} < 48dp", b.height.value >= 47.9f)
    }
    private fun mapVisibleAbove(sheetTag: String) {
        val sheetTop = rule.onNodeWithTag(sheetTag).getBoundsInRoot().top.value
        val mapTop = rule.onNodeWithTag("map_area").getBoundsInRoot().top.value
        assertTrue("시트 위로 지도가 ≥ 30% 보여야 함 (시트 상단 $sheetTop)", (sheetTop - mapTop) / screenH() >= 0.3f)
    }

    @Test fun opacitySheet_s36_sliderChangesLayerAndCaption() {
        val bg = setup()
        rule.onNodeWithTag("layer_btn").assertIsDisplayed(); assertTouch("layer_btn")
        clickTag("layer_btn")
        rule.onNodeWithTag("bg_sheet_opacity").assertIsDisplayed()
        rule.onNodeWithTag("opacity_title").assertTextEquals("레이어 투명도")
        rule.onNodeWithTag("pct_sat").assertTextEquals("100%"); rule.onNodeWithTag("pct_cad").assertTextEquals("60%")
        assertTrue(sheetFraction("bg_sheet_opacity") <= 0.5f + 0.001f); mapVisibleAbove("bg_sheet_opacity")
        assertTouch("btn_manage"); assertTouch("btn_close_sheet")
        shot("s36_opacity_quick_initial")
        // 슬라이더 조작 (접근성 동작으로 값 지정)
        rule.runOnUiThread { bg.setOpacity("cad", 0.35) }
        rule.waitForIdle(); rule.onNodeWithTag("pct_cad").assertTextEquals("35%")
        rule.onNodeWithTag("opacity_cad").performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        rule.waitForIdle(); rule.onNodeWithTag("pct_cad").assertTextEquals("80%")
        assertEquals(0.8, bg.layers.first { it.id == "cad" }.opacity, 0.02)
        // 스위치
        rule.onNodeWithTag("toggle_cad").performClick(); rule.waitForIdle()
        assertFalse(bg.layers.first { it.id == "cad" }.enabled)
        clickTag("btn_close_sheet"); rule.onNodeWithTag("bg_sheet_opacity").assertDoesNotExist()
    }

    @Test fun fullFlow_goodFile_s35_s34c_thenSliderChangesMapCaption() {
        val bg = setup()
        clickTag("layer_btn"); clickTag("btn_manage")
        rule.onNodeWithTag("bg_sheet_manage").assertIsDisplayed()
        rule.onNodeWithTag("manage_row_sat").assertIsDisplayed(); rule.onNodeWithTag("manage_row_cad").assertIsDisplayed()
        rule.onNodeWithTag("no_uploads").assertIsDisplayed()
        assertTrue(sheetFraction("bg_sheet_manage") <= 0.5f + 0.001f); assertTouch("btn_upload"); assertTouch("manage_toggle_sat")
        shot("s35_manage_before_upload")
        clickTag("btn_upload")
        rule.onNodeWithTag("bg_sheet_pick").assertIsDisplayed()
        for (s in FakeSample.entries) assertTouch("pick_${s.name}")
        shot("s34_pick_file_fake")
        clickTag("pick_GOOD_TIF")
        rule.onNodeWithTag("bg_sheet_result").assertIsDisplayed()
        rule.onNodeWithTag("info_crs").assertTextContains("EPSG:5186", substring = true)
        rule.onNodeWithTag("placed_pill").assertIsDisplayed()
        assertTouch("btn_import"); assertTouch("btn_cancel"); mapVisibleAbove("bg_sheet_result")
        assertTrue(sheetFraction("bg_sheet_result") <= 0.5f + 0.001f)
        shot("s34c_result_aligned")
        clickTag("btn_import")
        rule.onNodeWithTag("bg_sheet_opacity").assertIsDisplayed()
        rule.onNodeWithTag("badge_up1").assertTextEquals("정합됨(자동)")
        rule.onNodeWithTag("pct_up1").assertTextEquals("50%")
        rule.onNodeWithTag("map_caption").assertTextEquals("업로드 50%")
        shot("s36_opacity_with_upload_50")
        rule.onNodeWithTag("opacity_up1").performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
        rule.waitForIdle(); rule.onNodeWithTag("map_caption").assertTextEquals("업로드 90%")
        clickTag("btn_manage")
        // 삭제는 두 번
        clickTag("delete_up1"); rule.onNodeWithTag("delete_up1").assertTextEquals("정말 지울까요?")
        assertEquals(3, bg.layers.size)
        clickTag("delete_up1"); rule.onNodeWithTag("delete_up1").assertDoesNotExist()
        assertEquals(2, bg.layers.size); rule.onNodeWithTag("map_caption").assertDoesNotExist()
        shot("s35_manage_after_delete")
    }

    @Test fun noCoordinates_s34d_thenManualAlign_s34e() {
        val bg = setup()
        clickTag("layer_btn"); clickTag("btn_manage"); clickTag("btn_upload"); clickTag("pick_NO_COORD_TIF")
        rule.onNodeWithTag("bg_sheet_warning").assertIsDisplayed()
        rule.onNodeWithTag("warning_head").assertTextContains("좌표 정보가 없습니다", substring = true)
        assertTouch("btn_pick_crs"); assertTouch("btn_manual"); assertTouch("btn_cancel")
        rule.onNodeWithTag("btn_pick_crs").assertIsNotEnabled()   // 좌표가 없는 파일에는 의미 없음
        rule.onNodeWithTag("btn_manual").assertIsEnabled()
        mapVisibleAbove("bg_sheet_warning"); assertTrue(sheetFraction("bg_sheet_warning") <= 0.5f + 0.001f)
        shot("s34d_warning_no_coordinates")
        clickTag("btn_manual")
        rule.onNodeWithTag("bg_sheet_manual").assertIsDisplayed()
        rule.onNodeWithTag("btn_apply_manual").assertIsNotEnabled()
        rule.onNodeWithTag("pair_counter").assertTextEquals("쌍 0 / 4")
        clickTag("btn_fake_pair")
        rule.onNodeWithTag("pair_residuals").assertTextContains("쌍 2를 지정", substring = true)
        shot("s34e_manual_1pair")
        clickTag("btn_fake_pair")
        rule.onNodeWithTag("btn_apply_manual").assertIsEnabled()
        rule.onNodeWithTag("pair_residuals").assertTextContains("평균", substring = true)
        shot("s34e_manual_2pairs_preview")
        clickTag("btn_fake_pair"); clickTag("btn_fake_pair")
        rule.onNodeWithTag("pair_counter").assertTextEquals("쌍 4 / 4")
        rule.onNodeWithTag("btn_fake_pair").assertIsNotEnabled()
        assertTouch("btn_apply_manual"); assertTouch("btn_undo_pair"); assertTouch("btn_fake_pair")
        mapVisibleAbove("bg_sheet_manual"); assertTrue(sheetFraction("bg_sheet_manual") <= 0.5f + 0.001f)
        shot("s34e_manual_4pairs")
        clickTag("btn_apply_manual")
        rule.onNodeWithTag("bg_sheet_opacity").assertIsDisplayed()
        assertTrue(bg.layers.last().badge!!.startsWith("정합됨(수동, RMS"))
        rule.onNodeWithTag("badge_up1").assertTextContains("정합됨(수동", substring = true)
        shot("s36_after_manual_align")
    }

    @Test fun mismatchAndDxf_crsPick() {
        val bg = setup()
        clickTag("layer_btn"); clickTag("btn_manage"); clickTag("btn_upload"); clickTag("pick_WRONG_PLACE_TIF")
        rule.onNodeWithTag("warning_head").assertTextContains("맞지 않아요", substring = true)
        rule.onNodeWithTag("btn_pick_crs").assertIsEnabled()
        shot("s34d_warning_mismatch_100km")
        clickTag("btn_cancel")
        rule.onNodeWithTag("bg_sheet_manage").assertIsDisplayed()
        clickTag("btn_upload"); clickTag("pick_MAP_DXF")
        rule.onNodeWithTag("warning_head").assertTextContains("좌표계를 알 수 없어요", substring = true)
        rule.onNodeWithTag("btn_manual").assertIsNotEnabled()
        clickTag("btn_pick_crs")
        rule.onNodeWithTag("bg_sheet_crs").assertIsDisplayed()
        assertTrue(sheetFraction("bg_sheet_crs") <= 0.5f + 0.001f)
        rule.onNodeWithTag("crs_5174").assertIsNotEnabled()
        shot("s34d_crs_pick")
        clickTag("crs_5186")
        rule.onNodeWithTag("bg_sheet_result").assertIsDisplayed()
        rule.onNodeWithTag("info_crs").assertTextContains("직접 지정", substring = true)
        clickTag("btn_import")
        rule.onNodeWithTag("badge_up1").assertTextEquals("정합됨(좌표계 지정)")
        shot("s36_dxf_user_crs")
        assertEquals(1, bg.uploads.size)
    }
}
