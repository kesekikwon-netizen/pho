package kr.archsurvey.t0143

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.core.FixQuality
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.dig.DigKind
import kr.archsurvey.export.*
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.StubSrc
import kr.archsurvey.t0130.rig
import kr.archsurvey.t0139.SiteJobs
import kr.archsurvey.t0140.FakeConn
import kr.archsurvey.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

internal class ShotExp : ExportPlatform {
    override suspend fun summary() = ExportSummary("정밀발굴 1지구 · 10-12", 15, 0, 0, 0)
    override suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput = throw IllegalStateException()
    override fun share(out: ExportOutput) {}
    override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
}

/** 0.1.43 시안 10 화면 — Robolectric 그림(실기기 아님). 시안과 같은 412×892 dp */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xhdpi")
class Shots0143Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/workspace/survey-app/build-reports/0.1.43/screens").apply { mkdirs() }
    private val prefix = System.getProperty("shotPrefix") ?: "app"
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "${prefix}_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun setup(): Pair<StubSrc, SurveyController> {
        TileImages.networkEnabled = false
        val store = Mem()
        val (s, c) = rig(false, DeviceProfiles.SP60_ID, store)
        val name = "정밀발굴 1지구 · 10-12"
        c.projectName = name
        val sj = SiteJobs(name, "광령리1").apply { reg.assign("시굴 3지구 · 10-08", "광령리1") }
        val day = 86_400_000L; val now = System.currentTimeMillis()
        val jp = object : JobPlatform by sj {
            override suspend fun list() = listOf(
                JobInfo(name, 8, 0, 0, now - 3600_000, now, folder = sj.folderOf(name)),
                JobInfo("시굴 3지구 · 10-08", 6, 1, 1, null, now - 4 * day, folder = sj.folderOf("시굴 3지구 · 10-08")),
                JobInfo("제주광령리", 17, 0, 0, null, now - 9 * day),
                JobInfo("제주", 1, 0, 0, null, now - 12 * day),
                JobInfo("작업1", 0, 0, 0, null, now - 14 * day))
        }
        c.jobs = JobController(c, jp, scope)
        c.exporter = ExportController(ShotExp(), scope)
        val fc = FakeConn(); fc.devices = listOf("R10, 5411457757" to "60:21:C0:9F:83:95", "SP60 2210-0417" to "00:80:25:4B:1A:07")
        c.connection = ConnectionController(fc, scope)
        c.ui.toastMs = 1L
        c.arch = kr.archsurvey.arch.ArchController(c, kr.archsurvey.ui.ArchUiTest.MemArch(), scope)
        s.push(FixQuality.RTK_FIXED, 0.014)
        // 유구 가-1(3점) · 가-2(2점) · 레벨 1점
        runBlocking {
            c.dig.select(DigKind.FEATURE); c.dig.newFeature()
        }
        val base = 36.5
        fun m(a: Double, b: Double) { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = base + a, lon = 127.0 + b); c.onMeasure(); c.dismissResult() }
        m(0.0, 0.0); m(0.00009, 0.00002); m(0.00006, 0.00011); m(-0.00002, 0.00008)
        println("FEATURES " + c.dig.featureOutlines().map { it.label + ":" + it.pointRowIds.size })
        c.dig.armClose(); c.dig.confirmClose()
        runBlocking { c.dig.newFeature() }
        m(0.00012, 0.00025); m(0.00022, 0.00030); m(0.00018, 0.00040)
        c.homeOpen = true
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        return s to c
    }

    @Test fun allTen() {
        val (s, c) = setup()
        shot("01_home")
        rule.runOnIdle { c.homeOpen = false }; rule.waitForIdle(); rule.runOnIdle { c.follow = false; c.fitAll(); val ms = c.mapState(); println("DBG pts=" + c.points.map { c.projection.toLocal(it.lat, it.lon) } + " center=" + ms.center + " dp=" + c.dpPerM + " follow=" + c.follow) }; shot("02_map")
        rule.runOnIdle { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = 36.50016, lon = 127.00046); c.onMeasure() }; shot("03_point")
        rule.runOnIdle { c.dismissResult(); c.dig.pickerOpen = true }; shot("04_types")
        rule.runOnIdle { c.dig.pickerOpen = false; c.hub.open(ToolScreen.FEATURES) }; shot("05_points")
        rule.runOnIdle { c.hub.closeAll(); c.connection?.openScreen() }; shot("06_connection")
        rule.runOnIdle { c.connection?.leaveScreen(); c.jobs?.openList() }; shot("07_jobs")
        rule.runOnIdle { c.hub.closeAll(); c.jobs?.openNew() }; shot("08_new_job")
        rule.runOnIdle { c.hub.closeAll(); c.exporter?.openScreen() }; shot("09_export")
        rule.runOnIdle { c.exporter?.close(); c.hub.open(ToolScreen.SETTINGS) }; shot("10_settings_screen")
    }

    /** 시안 ⑩ 은 「내려 본 전체」라 긴 화면으로 한 장 더 */
    @Config(qualifiers = "w412dp-h1700dp-notnight-xhdpi")
    @Test fun settingsFull() {
        val (_, c) = setup()
        rule.runOnIdle { c.homeOpen = false; c.hub.open(ToolScreen.SETTINGS) }; shot("10_settings")
    }
}
