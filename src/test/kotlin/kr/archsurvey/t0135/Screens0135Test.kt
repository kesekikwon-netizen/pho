package kr.archsurvey.t0135

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.arch.ArchController
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.dig.DigKind
import kr.archsurvey.dig.LevelKind
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.35 화면 스냅샷(Robolectric · JVM). 실기기 아님: 글꼴 렌더·햇빛 대비·터치감은 확인 못 함. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
open class Screens0135Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SurveyDb
    protected lateinit var f: FakeGnssSource
    protected lateinit var c: SurveyController
    protected open val prefix = "p"
    private val out = File("/workspace/survey-app/build-reports/0.1.38/screens").apply { mkdirs() } // 0.1.38: 지금 코드 화면(0.1.35 폴더는 이전 기록으로 둠)

    private class Sett : SettingsPlatform {
        override var zone = kr.archsurvey.core.KoreanTmZone.EAST; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed())
        val store = RoomPointStore(db, { "광령리 3지구" }, { "R10" })
        c = SurveyController(f, AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()), scope, store = store, epochsPerPoint = 3)
        c.projectName = "광령리 3지구"
        c.arch = ArchController(c, RoomArchStore(db, { "광령리 3지구" }), scope)
        c.settingsState = SettingsState(c, Sett())
        c.homeOpen = false
        repeat(3) { f.emitNext() }
    }
    @After fun tearDown() { db.close() }

    private fun show() { rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }; rule.waitForIdle() }
    private fun adv(n: Int) { rule.runOnIdle { repeat(n) { f.emitNext() } }; rule.waitForIdle() }
    private fun measure() { rule.runOnIdle { c.onMeasure() }; adv(8) }
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "${prefix}_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun dig(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }

    @Test fun screens() {
        show()
        // 홈 「오늘 준비」
        dig { c.homeOpen = true }
        rule.onNodeWithTag("home_screen").assertExists()
        shot("01_home_today")
        dig { c.homeOpen = false }
        rule.onNodeWithTag("measure_button").assertExists()
        val mb = rule.onNodeWithTag("measure_button").getBoundsInRoot()
        assertTrue("측점 버튼 ≥72dp (${mb.height})", mb.height.value >= 72f)
        shot("02_map_idle")
        // 유구 4점 → 면 닫기
        dig { c.dig.select(DigKind.FEATURE, null) }
        repeat(4) { measure(); adv(4) }
        shot("03_map_feature_saved_ledger")
        dig { c.dig.armClose() }
        shot("04_feature_close_preview")
        dig { c.dig.confirmClose() }
        // 유물 2점, 레벨 1점
        dig { c.dig.select(DigKind.ARTIFACT, null) }
        repeat(2) { measure(); adv(3) }
        dig { c.dig.select(DigKind.LEVEL, null, LevelKind.FLOOR) }
        measure()
        shot("05_map_artifacts_level")
        dig { c.dig.pickerOpen = true }
        shot("06_picker_now_measuring")
        dig { c.dig.pickerOpen = false; c.dig.layersOpen = true }
        shot("07_layers")
        dig { c.dig.layersOpen = false; c.dig.statusOpen = true }
        shot("08_status")
        dig { c.dig.statusOpen = false; c.dig.menuOpen = true }
        shot("09_menu")
        dig { c.dig.menuOpen = false; c.hub.open(ToolScreen.FEATURES) }
        rule.onNodeWithTag("features_summary").assertExists()
        shot("10_features")
        dig { c.hub.back(); c.hub.open(ToolScreen.SETTINGS) }
        rule.onNodeWithTag("set_receiver").assertExists()
        rule.onNodeWithTag("set_pole").assertExists()
        rule.onNodeWithTag("zone_toggle").assertExists()
        shot("11_settings")
        rule.onNodeWithTag("mapbase_toggle").assertExists()
        shot("12_settings_advanced")
        dig { c.hub.closeAll(); c.ui.hideToast() }
        assertTrue("점이 있으면 좌표계 잠김", c.dig.crsLocked)
        // 기준점: 첫 값 = 기준, 두 번째 = 확인 결과 카드
        dig { c.dig.select(DigKind.BM, null) }
        measure(); adv(3); measure()
        shot("14_bm_check")
        dig { c.dig.select(DigKind.STAKE, null) }
        shot("15_grid_stake")
        // 모든 클릭 가능 요소 ≥48dp (측량 지도)
        dig { c.hub.closeAll() }
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { val b = it.boundsInRoot; b.width > 0 && minOf(b.width, b.height) / rule.density.density < 47.5f }
        File(out, "${prefix}_touch_targets.txt").writeText("small clickables on map: ${small.size}\n" + small.joinToString("\n") { "${it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }} ${it.boundsInRoot}" })
    }
}

/** 폴드7 안쪽 화면(펼침) 크기 근사: 2단(지도 + 오늘 점·조작부) */
@Config(sdk = [34], qualifiers = "w840dp-h900dp-notnight-xhdpi")
class Screens0135FoldTest : Screens0135Test() { override val prefix = "fold" }
