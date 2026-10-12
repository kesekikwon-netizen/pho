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
 * 0.1.14 회귀: 작업 정보 「적용」 → JobConfig 저장 후 **반드시 측량(지도) 화면**.
 * 0.1.13 버그 = 홈 › 환경설정 › 작업 정보 확인 경로에서 적용하면 hub 만 닫혀 그 밑의 환경설정 목록이 그대로 보였다.
 * 「나가기」 = 저장 없이 뒤로(온 화면으로).
 * Robolectric(JVM) 화면 시험 — 실기기 아님. 캡처: build-reports/v0114/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0114ApplyToSurveyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/v0114").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Jobs : JobPlatform {
        override var current = "te1"; val names = linkedSetOf("te1")
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
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private lateinit var c: SurveyController
    private lateinit var f: FakeGnssSource
    private lateinit var jobs: Jobs
    private val cfgs = MemoryJobConfigPlatform()

    private fun setup() {
        TileImages.networkEnabled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed())
        c = SurveyController(f, db, scope, epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        jobs = Jobs()
        c.projectName = jobs.current
        c.settingsState = SettingsState(c, Sett())
        c.jobs = JobController(c, jobs, scope, cfgs)
        c.vworld = VWorldSettingsState(MemoryMapKeyPlatform(), scope, Dispatchers.Unconfined)
        c.onJobConfigChanged = { cfgs.save(jobs.current, it) }
        c.homeOpen = true // 앱 시작 = 홈
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 76) } }
        repeat(2) { f.emitNext() }
        rule.waitForIdle()
    }

    /** 측량 화면이 맨 위: 지도 머리글(survey_back) 있음, 홈·환경설정 목록·작업 화면 없음 */
    private fun assertOnSurvey() {
        assertEquals("survey", c.visibleRoute())
        rule.onNodeWithTag("survey_back").assertExists()
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        rule.onNodeWithTag("settings_menu").assertDoesNotExist()
        rule.onNodeWithTag("job_screen").assertDoesNotExist()
        rule.onNodeWithTag("job_apply").assertDoesNotExist()
    }

    private fun pickEast() {
        rule.onNodeWithTag("job_zone").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobzone_" + KoreanTmZone.entries.indexOf(KoreanTmZone.EAST)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_geoid").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobgeoid_" + GeoidModel.entries.indexOf(GeoidModel.EGM96)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_map").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobmap_" + MapBase.entries.indexOf(MapBase.CADASTRAL)).performClick(); rule.waitForIdle()
    }

    /** 사용자 스크린샷과 같은 경로: 홈 › 환경설정 › 작업 정보 확인 › 적용 */
    @Test fun applyFromSettingsMenuNavigatesToSurvey() {
        setup()
        // 0.1.35: 환경설정 목록 없앰 → 홈 「오늘 준비」의 「작업」 단추
        rule.onNodeWithTag("home_job_btn").performClick(); rule.waitForIdle()
        assertEquals("tool:JOB_INFO", c.visibleRoute())
        pickEast()
        shot("01_job_info_before_apply")
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        // 저장됨
        assertEquals(5187, c.zone.epsg); assertEquals(GeoidModel.EGM96, c.geoid); assertEquals(MapBase.CADASTRAL, c.mapBase)
        val saved = cfgs.load("te1")!!
        assertEquals(5187, saved.epsg); assertEquals(GeoidModel.EGM96, saved.geoid); assertEquals(MapBase.CADASTRAL, saved.mapBase)
        // 측량 화면으로 이동(0.1.13 에서는 여기서 settings_menu 가 남아 있었다)
        assertFalse(c.settingsMenuOpen); assertFalse(c.homeOpen); assertNull(c.hub.screen)
        assertOnSurvey()
        // 완료 멘트
        assertTrue(c.ui.toast.orEmpty().contains("적용"))
        shot("02_after_apply_survey")
    }

    /** 서랍(≡) › 작업 정보 › 적용 */
    @Test fun applyFromDrawerNavigatesToSurvey() {
        setup()
        // 0.1.38: 서랍의 「작업 정보」는 홈 「작업」 단추와 겹쳐 뺐다 → 홈 「작업」에서
        rule.onNodeWithTag("home_job_btn").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("tool:JOB_INFO", c.visibleRoute())
        pickEast()
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals(5187, cfgs.load("te1")!!.epsg)
        assertOnSurvey()
    }

    /** 환경설정 › 좌표계·측량 설정 › 지역 줄 › 작업 정보 › 적용 — 겹친 창 두 겹 */
    @Test fun applyFromSettingsScreenNavigatesToSurvey() {
        setup()
        rule.onNodeWithTag("home_settings2").performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        rule.onNodeWithTag("zone_toggle").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("tool:JOB_INFO", c.visibleRoute())
        pickEast()
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals(5187, cfgs.load("te1")!!.epsg)
        assertOnSurvey()
    }

    /** 0.1.39: 새 작업 › 「만들고 측량 시작」 → 바로 측량(예전: 작업 정보 → 적용 한 번 더) */
    @Test fun newJobThenApplyNavigatesToSurvey() {
        setup()
        rule.onNodeWithTag("home_job_btn").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_to_new").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_stage_TRIAL").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertTrue(c.projectName, c.projectName.startsWith("시굴 1지구 · "))
        assertOnSurvey()
    }

    /** 나가기 = 저장 없이 뒤로(환경설정 목록으로), 머리글 ← 도 같음 */
    @Test fun exitDoesNotSaveAndGoesBack() {
        setup()
        val before = c.jobConfig()
        cfgs.save("te1", before)
        rule.onNodeWithTag("home_settings2").performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        rule.onNodeWithTag("zone_toggle").performScrollTo().performClick(); rule.waitForIdle()
        pickEast()
        rule.onNodeWithTag("job_exit").performClick(); rule.waitForIdle()
        assertEquals("tool:SETTINGS", c.visibleRoute())
        rule.onNodeWithTag("job_screen").assertDoesNotExist()
        assertEquals(before.epsg, c.zone.epsg); assertEquals(before.geoid, c.geoid); assertEquals(before.mapBase, c.mapBase)
        assertEquals(before, cfgs.load("te1"))
        // 다시 열면 폼은 저장된 값(나가기로 바꾼 값은 버려짐)
        rule.onNodeWithTag("zone_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_zone_value", useUnmergedTree = true).assertTextContains(before.epsg.toString(), substring = true)
        rule.onNodeWithTag("tool_back").performClick(); rule.waitForIdle()
        assertEquals("tool:SETTINGS", c.visibleRoute())
        assertEquals(before, cfgs.load("te1"))
    }

    /** 적용이 막히면(점이 있는 작업의 좌표계 변경) 이동하지 않고 오류 멘트 — 저장도 안 됨 */
    @Test fun blockedApplyStaysWithError() {
        setup()
        rule.runOnUiThread { c.homeOpen = false; c.onMeasure() }
        repeat(6) { f.emitNext() }
        rule.waitForIdle()
        assertEquals(1, c.points.size)
        val before = c.zone
        rule.runOnUiThread { c.homeOpen = true; c.jobs!!.openInfo() }; rule.waitForIdle()
        // 0.1.35 X11: 점이 있으면 좌표계 잠김 — 먼저 풀어야 고를 수 있다(풀어도 다른 좌표계 적용은 아래에서 막힘)
        rule.onNodeWithTag("crs_locked").performScrollTo().assertExists()
        rule.onNodeWithTag("job_zone").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("jobzone_0").assertDoesNotExist()
        rule.onNodeWithTag("crs_unlock").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_zone").performScrollTo().performClick(); rule.waitForIdle()
        val other = KoreanTmZone.entries.first { it != before }
        rule.onNodeWithTag("jobzone_" + KoreanTmZone.entries.indexOf(other)).performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals(before, c.zone)
        assertEquals("tool:JOB_INFO", c.visibleRoute())
        rule.onNodeWithTag("job_error").assertExists()
    }

    /** 교차검증: 홈 › 점 목록(빈) › 「지도로 돌아가기」 → 측량(0.1.13 은 홈에 남음) */
    @Test fun pointsBackToMapFromHomeGoesToSurvey() {
        setup()
        // 0.1.35: 홈 「측량한 것」 → 뒤로 = 측량 지도
        rule.onNodeWithTag("home_points").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("features_summary").assertExists()
        rule.onNodeWithTag("tool_back").performClick(); rule.waitForIdle()
        assertOnSurvey()
    }

    /** 교차검증: 홈에서 서랍(≡) › 「측량」 → 측량(0.1.13 은 홈에 남음) */
    @Test fun drawerGoSurveyFromHomeGoesToSurvey() {
        setup()
        // 0.1.38: 「측량」 칸은 홈 「측량 시작」과 겹쳐 뺐다. 서랍 「지도에 보이기」는 홈에서도 지도로 가서 시트를 연다
        rule.runOnUiThread { c.drawerOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("menu_layers").performScrollTo().performClick(); rule.waitForIdle()
        assertFalse(c.drawerOpen); assertFalse(c.homeOpen); assertTrue(c.dig.layersOpen)
    }

    /** 시스템 뒤로: 작업 정보 → (적용 안 함) 환경설정 목록 → 홈. 저장 없음 */
    @Test fun systemBackFromJobInfoDoesNotSave() {
        setup()
        val before = c.jobConfig(); cfgs.save("te1", before)
        rule.onNodeWithTag("home_settings2").performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        rule.onNodeWithTag("zone_toggle").performScrollTo().performClick(); rule.waitForIdle()
        pickEast()
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertEquals("tool:SETTINGS", c.visibleRoute())
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertEquals("home", c.visibleRoute())
        assertEquals(before, cfgs.load("te1"))
    }
}
