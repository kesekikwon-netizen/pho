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

    /** 0.1.55: 홈 「+ 새 작업」 → 이름 칸에 바로 커서 → 쓰고 「만들기」 → 측량 지도. 폴더 칸에는 지난 폴더가 미리 적혀 있음 */
    @Test fun home_newJob_typeName_create() {
        val (c, jobs) = setup()
        tap("home_new_job")
        assertEquals(ToolScreen.JOB_NEW, c.hub.screen)
        rule.onNodeWithTag("job_name").assertIsFocused()
        rule.onNodeWithTag("job_site_new").assertDoesNotExist()
        rule.onNodeWithTag("job_no_p1").assertDoesNotExist()
        // 0.1.56 S3: 폴더 칸은 빈 채 시작 — 전에 쓴 폴더는 칩(누르면 채움)
        rule.onNodeWithTag("job_recent_0").assertTextContains("강릉 교동"); tap("job_recent_0")
        rule.onNodeWithTag("job_group").assertTextContains("강릉 교동")
        rule.onNodeWithTag("job_name").performTextInput("2호 주거지")
        shot("01_new_job_typed")
        tap("job_create")
        assertEquals("2호 주거지", c.projectName)
        assertEquals("강릉 교동", jobs.lastGroup)
        assertNull(c.hub.screen)
    }

    @Test fun emptyName_refused_noAutoName() {
        val (c, _) = setup()
        tap("home_new_job")
        // 0.1.56 M4: 빈 이름 = 「만들기」 꺼짐(단추 안 「이름을 쓰세요」), 오류 창·토스트 없음
        rule.onNodeWithTag("job_create").assertIsNotEnabled()
        tap("job_create")
        assertNull(c.jobs!!.error); assertNull(c.ui.toast)
        assertEquals(ToolScreen.JOB_NEW, c.hub.screen)
    }

    @Test fun clearFolder_putsJobAtTop() {
        val (c, jobs) = setup()
        tap("home_new_job")
        rule.onNodeWithTag("job_group").performTextClearance()
        rule.onNodeWithTag("job_name").performTextInput("시험")
        tap("job_create")
        assertNull(jobs.lastGroup)
        assertEquals("시험", c.projectName)
    }
}
