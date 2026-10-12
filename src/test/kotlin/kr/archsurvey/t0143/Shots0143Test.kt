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
import androidx.compose.ui.test.*
import kr.archsurvey.ui.Phase
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
        c.connection!!.updateForm(c.connection!!.form.copy(enabled = true, host = "RTS1.ngii.go.kr", port = "2101", mount = "VRS-RTCM34", user = "example_user", password = "pw"))
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
        rule.runOnIdle { c.homeOpen = false }; rule.waitForIdle(); rule.runOnIdle { c.follow = false; c.fitAll() }; shot("02_map")
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

    /** 0.1.43: 측정 직후 점 확인 → 「다음 점 재기」 한 번 = 바로 다음 측정, 글 메모는 고르는 문구(키보드 없음) */
    @Test fun pointConfirmNextAndMemoChips() {
        val (s, c) = setup()
        rule.runOnIdle { c.homeOpen = false }
        rule.runOnIdle { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = 36.50030, lon = 127.00060); c.onMeasure() }
        rule.waitForIdle()
        rule.onNodeWithTag("point_confirm").assertExists()
        rule.onNodeWithTag("result_memo").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("memo_chips").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
        val first = QuickNotes.ITEMS.first()
        rule.onNodeWithTag("memo_$first").performScrollTo().performClick(); rule.waitForIdle()
        org.junit.Assert.assertTrue(c.points.last().memo.contains(first))
        val n = c.points.size
        rule.onNodeWithTag("next_point").performClick(); rule.waitForIdle()
        org.junit.Assert.assertTrue("다음 점 재기 = 바로 다음 측정 시작", c.phase == Phase.ACQUIRING || c.points.size == n + 1 || c.dupPrompt != null)
    }

    /** 0.1.43: 무엇을 잴까요에 AprilTag · 사진 기준점 없음, 기타 점만 */
    @Test fun pickerHasNoAprilTag() {
        val (_, c) = setup()
        rule.runOnIdle { c.homeOpen = false; c.dig.pickerOpen = true }; rule.waitForIdle()
        rule.onNodeWithTag("dig_picker_close").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("AprilTag", substring = true), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasText("사진 기준점", substring = true), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasText("기타 점", substring = true), useUnmergedTree = true).assertCountEquals(1)
    }

    /** 0.1.43: 설정 값은 줄 → 고르기 창, 내보내기는 파일 개수 · iTwin 없음, 진단 기록은 장비 연결 안 */
    @Test fun settingsChoiceExportAndDiagnostics() {
        val (_, c) = setup()
        rule.runOnIdle { c.homeOpen = false; c.hub.open(ToolScreen.SETTINGS) }; rule.waitForIdle()
        rule.onNodeWithTag("epochs_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("choice_sheet").assertExists()
        rule.runOnIdle { c.ui.dismissChoice(); c.hub.closeAll(); c.exporter?.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("export_run").assertTextContains("파일 5개", substring = true)
        rule.onAllNodes(androidx.compose.ui.test.hasText("iTwin", substring = true), useUnmergedTree = true).assertCountEquals(0)
        rule.runOnIdle { c.exporter?.close(); c.connection?.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_diag_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("conn_diag_text").assertExists()
        rule.onNodeWithTag("pair_find").assertDoesNotExist() // 시험 기기에는 찾기 기능 없음(가짜) — 실제 앱은 PIN 1234 자동
    }
}
