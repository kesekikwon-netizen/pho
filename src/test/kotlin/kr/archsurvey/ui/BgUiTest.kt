package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.arch.*
import kr.archsurvey.core.*
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
import kr.archsurvey.georef.*
import kr.archsurvey.ui.bg.*
import org.junit.rules.TemporaryFolder

/** 배경 레이어 시트(투명도·관리·파일 올리기·결과·경고·좌표계 고르기)를 Robolectric 으로 조작해 본다. 실기기 아님. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class BgUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val out = File("../build-reports/bg").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    class MemPoints : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = saved.toList()
        override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }

    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private lateinit var bg: BackgroundController
    private val workDir by lazy { tmp.newFolder() }

    private fun start() {
        f = FakeGnssSource(FakeScenarios.allFixed())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        c = SurveyController(f, db, scope, store = MemPoints())
        bg = BackgroundController({ c.projection }, JsonFileBgLayerStore(tmp.newFolder()))
        bg.pickFiles = { cb -> cb(listOf(FakeUploads.create(FakeSample.GOOD_TIF, workDir, bg.work.center))) }
        c.bg = bg
        repeat(2) { f.emitNext() }
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun ui(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }

    @Test fun layerButtonOpensSheetsAndUploadFlowWorksFromScreens() {
        start()
        ui { c.quickSheetOpen = true }
        rule.onNodeWithTag("quick_bg").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_opacity").assertExists()
        rule.onNodeWithTag("no_layers").assertExists()
        shot("b1_opacity_empty")
        rule.onNodeWithTag("btn_manage").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_manage").assertExists(); rule.onNodeWithTag("no_uploads").assertExists()
        rule.onNodeWithTag("manage_note").assertExists()
        shot("b2_manage_empty")
        rule.onNodeWithTag("btn_upload").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_pick").assertExists()
        shot("b3_pick")
        rule.onNodeWithTag("btn_pick_files").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_result").assertExists()
        rule.onNodeWithTag("placed_pill").assertExists()
        shot("b4_result")
        rule.onNodeWithTag("btn_import").performClick(); rule.waitForIdle()
        assertEquals(1, bg.layers.size)
        rule.onNodeWithTag("bg_sheet_opacity").assertExists()
        rule.onNodeWithTag("map_caption").assertExists()
        shot("b5_opacity_one")
        rule.onNodeWithTag("btn_manage").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("delete_${bg.layers[0].id}").performClick(); rule.waitForIdle()
        assertEquals("두 번 눌러야 지움", 1, bg.layers.size)
        rule.onNodeWithTag("delete_${bg.layers[0].id}").performClick(); rule.waitForIdle()
        assertEquals(0, bg.layers.size)
    }

    @Test fun warningSheetForFileWithoutCoordinatesOffersNoManualButExplains() {
        start()
        ui { bg.onPicked(listOf(FakeUploads.create(FakeSample.NO_COORD_TIF, workDir, bg.work.center))) }
        rule.onNodeWithTag("bg_sheet_warning").assertExists()
        rule.onNodeWithTag("warning_head").assertExists()
        rule.onNodeWithTag("manual_unavailable").assertExists()
        shot("b6_warning")
        rule.onNodeWithTag("btn_cancel").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_manage").assertExists()
    }

    @Test fun dxfCrsPickFlowFromScreens() {
        start()
        ui { bg.onPicked(listOf(FakeUploads.create(FakeSample.MAP_DXF, workDir, bg.work.center))) }
        rule.onNodeWithTag("btn_pick_crs").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_crs").assertExists()
        shot("b7_crs")
        rule.onNodeWithTag("crs_5186").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_result").assertExists()
    }

    @Test fun quickSheetHasBackgroundEntry() {
        start(); ui { c.quickSheetOpen = true }
        rule.onNodeWithTag("quick_bg").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("btn_manage").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("bg_sheet_manage").assertExists()
    }
}
