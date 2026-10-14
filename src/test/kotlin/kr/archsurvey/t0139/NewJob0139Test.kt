package kr.archsurvey.t0139

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.FixQuality
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.job.JobFolderRegistry
import kr.archsurvey.job.JobFolders
import kr.archsurvey.job.MemoryJobFolderStore
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

internal class SiteJobs(start: String, group: String?) : JobPlatform {
    val reg = JobFolderRegistry(MemoryJobFolderStore()).apply { assign(start, group) }
    private val names = linkedSetOf(start)
    override var current: String = start; private set
    var lastGroup: String? = null
    override suspend fun list() = names.map { info(it) }
    override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null, folder = folderOf(name))
    override suspend fun create(name: String) { names.add(name); current = name }
    override suspend fun create(name: String, group: String?) { lastGroup = group; reg.assign(name, group); create(name) }
    override suspend fun open(name: String) { current = name }
    override suspend fun delete(name: String): String { names.remove(name); reg.forget(name); return "b.zip" }
    override fun groups() = reg.groups()
    override fun folderOf(name: String) = JobFolders.display(reg.folderOf(name))
}

/** 0.1.39 새 작업 = 홈에서 한 번, 고르기만(Robolectric — 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class NewJob0139Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/workspace/survey-app/build-reports/0.1.39/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "u39_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var taps = 0
    private fun tap(tag: String) { val n = rule.onNodeWithTag(tag); runCatching { n.performScrollTo() }; n.performClick(); rule.waitForIdle(); taps++ }

    private fun setup(start: String = "강릉 교동 · 정밀발굴 1지구 · 10-01", group: String? = "강릉 교동"): Pair<SurveyController, SiteJobs> {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = start
        val jobs = SiteJobs(start, group)
        c.jobs = JobController(c, jobs, scope).apply { today = { java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-10-12")!! } }
        c.homeOpen = true
        c.ui.toastMs = 600_000L
        s.push(FixQuality.RTK_FIXED, 0.005)
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        return c to jobs
    }

    @Test fun home_bigNewJob_twoTapsToMap_noKeyboard() {
        val (c, jobs) = setup()
        shot("01_home_new_job")
        rule.onNodeWithTag("home_new_job").assertIsDisplayed()
        tap("home_new_job")
        assertEquals(ToolScreen.JOB_NEW, c.hub.screen)
        // 유적·단계는 지난 작업 것, 지구 번호는 다음 빈 번호, 날짜 자동
        rule.onNodeWithTag("job_built_name", useUnmergedTree = true).assertTextEquals("강릉 교동 · 정밀발굴 2지구 · 10-12")
        rule.onNodeWithTag("job_name").assertDoesNotExist()   // 키보드 칸 없음(직접 입력 접힘)
        rule.onNodeWithTag("job_group").assertDoesNotExist()
        shot("02_new_job")
        tap("job_create")
        assertEquals("강릉 교동 · 정밀발굴 2지구 · 10-12", jobs.current)
        assertEquals("강릉 교동", jobs.lastGroup)
        assertFalse(c.homeOpen); assertNull(c.hub.screen)  // 바로 측량 지도
        rule.onNodeWithTag("measure_button").assertExists()
        assertEquals(2, taps)
        shot("03_map_after_create")
    }

    @Test fun pickStageAndNumber_buildsName_folderUnderSite() {
        val (c, jobs) = setup()
        tap("home_new_job")
        tap("job_stage_TRIAL")
        rule.onNodeWithTag("job_built_name", useUnmergedTree = true).assertTextEquals("강릉 교동 · 시굴 1지구 · 10-12")
        repeat(11) { tap("job_no_p1") } // 0.1.43 시안: − / + 만
        rule.onNodeWithTag("job_built_name", useUnmergedTree = true).assertTextEquals("강릉 교동 · 시굴 12지구 · 10-12")
        tap("job_no_m1")
        rule.onNodeWithTag("job_folder_preview", useUnmergedTree = true).assertTextContains("Documents/ExcavationSurvey/강릉 교동/", substring = true)
        tap("job_site_none")
        rule.onNodeWithTag("job_built_name", useUnmergedTree = true).assertTextEquals("시굴 1지구 · 10-12")
        tap("job_site_강릉 교동")
        shot("04_new_job_trial_11")
        tap("job_create")
        assertEquals("강릉 교동 · 시굴 1지구 · 10-12", jobs.current)
        assertNull(c.hub.screen)
    }

    @Test fun zoneTiles_recommendedPreselected_detailsHidden() {
        val (c, _) = setup()
        tap("home_new_job")
        val rec = c.jobs!!.recommendedZone
        if (rec != null) rule.onNodeWithTag("job_zone_${rec.epsg}").assertExists()
        rule.onNodeWithTag("job_zone_5186").assertExists(); rule.onNodeWithTag("job_zone_5187").assertExists()
        rule.onNodeWithTag("job_manual").assertDoesNotExist()
        tap("job_details")
        // 0.1.46: 새 작업은 선택만 — 작업명 직접 입력창 없음
        rule.onNodeWithTag("job_manual").assertDoesNotExist()
        rule.onNodeWithTag("job_name").assertDoesNotExist()
        shot("05_new_job_details")
    }

    @Test fun newSite_isOptionalKeyboard_fallback() {
        val (c, jobs) = setup()
        tap("home_new_job")
        tap("job_site_new")
        rule.onNodeWithTag("job_group").performScrollTo().performTextInput("광령리")
        rule.onNodeWithTag("job_built_name", useUnmergedTree = true).assertTextEquals("광령리 · 정밀발굴 2지구 · 10-12")
        tap("job_create")
        assertEquals("광령리", jobs.lastGroup)
        assertNull(c.hub.screen)
    }

    @Test fun drawer_firstItem_isNewJob_cardOpensList() {
        val (c, _) = setup()
        assertEquals("job_new", DrawerMenu.sections.first().items.first().id)
        assertEquals("+ 새 작업", DrawerMenu.sections.first().items.first().label)
        rule.onNodeWithTag("home_card_new").assertDoesNotExist() // 같은 단추 두 번 없음
        rule.onNodeWithTag("home_card_open").performClick(); rule.waitForIdle()
        assertEquals(ToolScreen.JOB_OPEN, c.hub.screen)
    }
}
