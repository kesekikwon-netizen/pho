package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.map.MapBase
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
 * 0.1.12 화면 시험(Robolectric, JVM — 실기기·실제 V월드 아님, 타일 네트워크 끔).
 * 새 작업(작업명·좌표계·지역·배경맵·Geoid 한 화면, 나가기|만들기) → 작업 정보 확인(적용) → V월드 키 → 안테나 10 cm.
 * 캡처: build-reports/v0112/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0112Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/v0112").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Jobs : JobPlatform {
        override var current = "20261005-1040"; val names = linkedSetOf("20261005-1040")
        override suspend fun list() = names.map { JobInfo(it, 0, 0, 0, null, null) }
        override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null)
        override suspend fun create(name: String) { names += name; current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String): String { names -= name; return "b.zip" }
    }
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, "v0112_$name")
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private lateinit var c: SurveyController
    private val cfgs = MemoryJobConfigPlatform()
    private val keys = MemoryMapKeyPlatform()

    private fun setup() {
        TileImages.networkEnabled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = FakeGnssSource(FakeScenarios.allFixed())
        c = SurveyController(f, db, scope, epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        val jobs = Jobs()
        c.projectName = jobs.current
        c.settingsState = SettingsState(c, Sett())
        c.jobs = JobController(c, jobs, scope, cfgs)
        c.vworld = VWorldSettingsState(keys, scope, Dispatchers.Unconfined)
        c.mapKeyProvider = { c.vworld.access }
        c.onJobConfigChanged = { cfgs.save(jobs.current, it) }
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 82) } }
        repeat(2) { f.emitNext() }
        rule.waitForIdle()
    }

    @Test fun newJobAllSettingsOnOneScreen() {
        setup()
        rule.runOnUiThread { c.jobs!!.openNew() }; rule.waitForIdle()
        // 0.1.39: 좌표계 타일 + 큰 단추가 기본, 높이·배경지도는 「자세히」 안
        for (tag in listOf("job_zone_5186", "job_create", "job_details")) rule.onNodeWithTag(tag).assertExists()
        rule.onNodeWithTag("job_geoid").assertDoesNotExist()
        rule.onNodeWithTag("job_details").performScrollTo().performClick(); rule.waitForIdle()
        for (tag in listOf("job_crs", "job_zone", "job_map", "job_geoid")) rule.onNodeWithTag(tag).assertExists()
        rule.onNodeWithTag("job_crs_value", useUnmergedTree = true).assertTextContains("GRS80 (TM)")
        shot("01_job_new")
        rule.onNodeWithTag("job_zone").performScrollTo().performClick(); rule.waitForIdle()
        shot("02_job_zone_choice")
        rule.onNodeWithTag("jobzone_" + KoreanTmZone.entries.indexOf(KoreanTmZone.EAST)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_zone_value", useUnmergedTree = true).assertTextContains("5187", substring = true)
        rule.onNodeWithTag("job_geoid").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobgeoid_" + GeoidModel.entries.indexOf(GeoidModel.EGM96)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_map").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobmap_" + MapBase.entries.indexOf(MapBase.CADASTRAL)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_manual").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_name").performScrollTo().performTextReplacement("동부-트렌치1")
        shot("03_job_new_filled")
        rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertEquals("동부-트렌치1", c.projectName)
        assertEquals(5187, c.zone.epsg); assertEquals(GeoidModel.EGM96, c.geoid); assertEquals(MapBase.CADASTRAL, c.mapBase)
        assertEquals(5187, cfgs.load("동부-트렌치1")!!.epsg)
        assertNull(c.hub.screen) // 0.1.39: 바로 측량
        rule.runOnUiThread { c.jobs!!.openInfo() }; rule.waitForIdle()
        rule.onNodeWithTag("job_apply").assertExists()
        shot("04_job_info")
        // 작업 정보에서 Geoid 바꾸고 적용
        rule.onNodeWithTag("job_geoid").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobgeoid_" + GeoidModel.entries.indexOf(GeoidModel.NONE)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals(GeoidModel.NONE, c.geoid); assertEquals(GeoidModel.NONE, cfgs.load("동부-트렌치1")!!.geoid)
        // 0.1.13: 적용 → 측량 화면(도구 닫힘 · 홈 아님)
        assertNull(c.hub.screen); assertFalse(c.homeOpen)
    }

    @Test fun vworldKeyEntryAndMapNotice() {
        setup()
        rule.runOnUiThread { c.homeOpen = false; c.mapBase = MapBase.CADASTRAL }; rule.waitForIdle()
        shot("05_map_no_key")
        rule.runOnUiThread { c.vworld.load(); c.hub.open(ToolScreen.VWORLD) }; rule.waitForIdle()
        rule.onNodeWithTag("vworld_key_state").assertTextContains("아직 키가 없어요", substring = true) // MemoryMapKeyPlatform: 시드 없음
        rule.onNodeWithTag("vworld_key").performTextInput("TESTKEY0-1234-5678-ABCD")
        rule.onNodeWithTag("vworld_domain").performTextInput("http://localhost")
        shot("06_vworld_settings")
        rule.onNodeWithTag("vworld_save").performClick(); rule.waitForIdle()
        assertEquals("TESTKEY0-1234-5678-ABCD", keys.key); assertEquals("http://localhost", keys.domain)
        rule.onNodeWithTag("vworld_key_state").assertTextContains("저장 인증키 사용 중", substring = true)
        rule.onNodeWithTag("vworld_key_state").assertTextContains("…ABCD", substring = true)
        // 화면 어디에도 키 값이 그대로 보이지 않는다
        assertTrue(rule.onAllNodesWithText("TESTKEY0-1234-5678-ABCD", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(2, c.mapTiles().size)
    }

    @Test fun antennaSheetSteps10cmAndChips() {
        setup()
        rule.runOnUiThread { c.homeOpen = false; c.antennaSheetOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("step_plus").assertTextContains("+10 cm", substring = true)
        rule.onNodeWithTag("step_plus").performClick(); rule.waitForIdle()
        assertEquals(2.1, c.antenna.inputValueM, 1e-9)
        rule.onNodeWithTag("pole_chip_1.8").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(1.8, c.antenna.inputValueM, 1e-9)
        rule.onNodeWithTag("step_minus").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(1.7, c.antenna.inputValueM, 1e-9)
        assertEquals(1.7, cfgs.load("20261005-1040")!!.poleM, 1e-9)
        shot("07_antenna_10cm")
    }

    @Test fun settingsMenuHasJobInfoAndVworldRows() {
        setup()
        rule.runOnUiThread { c.settingsMenuOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("set_row_job").assertExists()
        rule.onNodeWithTag("set_row_vworld").performScrollTo().assertExists()
        shot("08_settings_menu")
    }
}
