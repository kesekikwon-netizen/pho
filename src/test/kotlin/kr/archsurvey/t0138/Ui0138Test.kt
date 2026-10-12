package kr.archsurvey.t0138

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

/** 작업 관리 가짜: 폴더 기록은 진짜 JobFolderRegistry */
internal class FolderJobs(start: String = "광령리 3지구") : JobPlatform {
    val reg = JobFolderRegistry(MemoryJobFolderStore())
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

/** 0.1.38 화면(Robolectric — 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0138Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/workspace/survey-app/build-reports/0.1.38/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "u38_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun setup(home: Boolean, drive: DriveSyncController? = null): Pair<SurveyController, FolderJobs> {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = "광령리 3지구"
        val jobs = FolderJobs()
        c.jobs = JobController(c, jobs, scope)
        c.drive = drive
        c.homeOpen = home
        c.ui.toastMs = 600_000L
        s.push(FixQuality.RTK_FIXED, 0.005)
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        return c to jobs
    }

    @Test fun home_showsJobFolder_menuOpensSingleDrawer() {
        val (c, _) = setup(home = true)
        rule.onNodeWithTag("home_job_folder", useUnmergedTree = true).assertTextContains("Documents/ExcavationSurvey/광령리 3지구", substring = true)
        shot("01_home")
        rule.onNodeWithTag("home_settings").performClick(); rule.waitForIdle()
        assertTrue(c.drawerOpen)
        rule.onNodeWithTag("drawer_folder", useUnmergedTree = true).assertTextContains("ExcavationSurvey/광령리 3지구", substring = true)
        rule.onAllNodesWithTag("drawer_status").assertCountEquals(0)
        shot("02_menu")
    }

    @Test fun mapHeaderMenu_opensDrawerDirectly_noSecondMenuSheet() {
        val (c, _) = setup(home = false)
        rule.onNodeWithContentDescription("메뉴").performClick(); rule.waitForIdle()
        assertTrue(c.drawerOpen)
        rule.onAllNodesWithTag("dig_menu").assertCountEquals(0)
        rule.onAllNodesWithTag("menu_all").assertCountEquals(0)
    }

    @Test fun newJob_createsFolder_withGroup_preview() {
        val (c, jobs) = setup(home = false)
        rule.runOnIdle { c.jobs!!.openNew() }; rule.waitForIdle()
        rule.onNodeWithTag("job_name").performTextInput("A트렌치")
        rule.onNodeWithTag("job_group").performTextInput("광령리 유적")
        rule.waitForIdle()
        rule.onNodeWithTag("job_folder_preview", useUnmergedTree = true).assertTextContains("Documents/ExcavationSurvey/광령리 유적/A트렌치", substring = true)
        shot("03_new_job")
        rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertEquals("광령리 유적", jobs.lastGroup)
        assertEquals("A트렌치", c.projectName)
        assertEquals("Documents/ExcavationSurvey/광령리 유적/A트렌치", jobs.folderOf("A트렌치"))
        rule.onNodeWithTag("job_info_folder", useUnmergedTree = true).assertTextContains("광령리 유적/A트렌치", substring = true)
    }

    @Test fun newJob_duplicateName_refused() {
        val (c, _) = setup(home = false)
        rule.runOnIdle { c.jobs!!.refresh(); c.jobs!!.openNew() }; rule.waitForIdle()
        rule.onNodeWithTag("job_name").performTextInput("광령리 3지구")
        rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertEquals("같은 이름의 작업이 이미 있어요", c.jobs!!.error)
        rule.runOnIdle { c.jobs!!.newName = "a/b" }
        rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertTrue(c.jobs!!.error!!.contains("쓸 수 없어요"))
    }

    @Test fun settings_driveConnect_oneButton_thenEmailToggleUploadNow() {
        val p = Drive0138Test.FakeDrive().apply { email = null }
        val s = Drive0138Test.MemSettings()
        val drive = DriveSyncController(p, s, scope, { "광령리 3지구" }, { error("no") }, { ByteArray(1) }, debounceMs = 0L,
            jobFolder = { JobFolderSnapshot("ExcavationSurvey/광령리 3지구", listOf(MirrorFile("측점.csv", 3, 1) { ByteArray(3) })) })
        val (c, _) = setup(home = false, drive = drive)
        rule.runOnIdle { c.hub.open(ToolScreen.SETTINGS) }; rule.waitForIdle()
        rule.onNodeWithTag("drive_sign_in").performScrollTo().assertTextContains("구글 드라이브 연결", substring = true)
        rule.onAllNodesWithTag("drive_upload_now").assertCountEquals(0)
        shot("04_settings_drive_off")
        rule.onNodeWithTag("drive_sign_in").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("drive_status", useUnmergedTree = true).assertTextContains("field@example.com", substring = true)
        rule.onNodeWithTag("drive_folder", useUnmergedTree = true).assertTextContains("ExcavationSurvey/광령리 3지구", substring = true)
        rule.onNodeWithTag("drive_auto").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(s.autoJobUpload)
        rule.onNodeWithTag("drive_upload_now").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(p.uploads.contains("ExcavationSurvey/광령리 3지구/측점.csv"))
        shot("05_settings_drive_on")
    }
}
