package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** 0.1.14 UX: 버튼 햅틱 · 대기열/진행 게이지 · 완료/오류 멘트(Robolectric, 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0114UxTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Jobs : JobPlatform {
        override var current = "te1"
        override suspend fun list() = listOf(JobInfo(current, 0, 0, 0, null, null))
        override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null)
        override suspend fun create(name: String) { current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String) = "b.zip"
    }
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }
    private class Exp : ExportPlatform {
        var gate = CompletableDeferred<ExportOutput>()
        override suspend fun summary() = ExportSummary("te1", 3, 0, 0, 0)
        override suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput = gate.await()
        override fun share(out: ExportOutput) {}
        override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
    }

    private lateinit var c: SurveyController
    private val exp = Exp()

    private fun setup() {
        TileImages.networkEnabled = false
        TapHaptics.reset()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = FakeGnssSource(FakeScenarios.allFixed())
        c = SurveyController(f, db, scope, epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        val jobs = Jobs(); c.projectName = jobs.current
        c.settingsState = SettingsState(c, Sett())
        c.jobs = JobController(c, jobs, scope, MemoryJobConfigPlatform())
        c.vworld = VWorldSettingsState(MemoryMapKeyPlatform(), scope, Dispatchers.Unconfined)
        c.exporter = ExportController(exp, scope)
        c.homeOpen = true
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        repeat(2) { f.emitNext() }
        rule.waitForIdle()
    }

    @Test fun buttonTapsGiveHapticAndRespectVibrationSetting() {
        setup()
        rule.onNodeWithTag("home_settings").performClick(); rule.waitForIdle()
        assertTrue("탭 햅틱", TapHaptics.taps >= 1)
        rule.onNodeWithTag("zone_toggle").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_exit").performClick(); rule.waitForIdle()
        assertTrue(TapHaptics.taps >= 3)
        // 진동 끔 → 탭 햅틱도 끔
        rule.runOnUiThread { c.settingsState!!.changeVibration(false) }; rule.waitForIdle()
        val before = TapHaptics.taps
        rule.onNodeWithTag("zone_toggle").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("job_exit").performClick(); rule.waitForIdle()
        assertEquals(before, TapHaptics.taps)
    }

    @Test fun applyShowsOkToastWithConfirmHaptic() {
        setup()
        rule.runOnUiThread { c.jobs!!.openInfo() }; rule.waitForIdle()
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals(ToastKind.OK, c.ui.toastKind)
        rule.onNodeWithTag("toast_text", useUnmergedTree = true).assertTextContains("적용", substring = true)
        assertTrue(TapHaptics.oks >= 1)
        assertEquals("survey", c.visibleRoute())
    }

    @Test fun exportShowsQueueGaugeThenCompletionMessage() {
        setup()
        rule.runOnUiThread { c.homeOpen = false; c.exporter!!.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("export_run").performClick(); rule.waitForIdle()
        assertTrue(c.exporter!!.running)
        rule.onNodeWithTag("busy_queue").assertExists()
        rule.onNodeWithTag("busy_op_export", useUnmergedTree = true).assertTextContains("내보내기", substring = true)
        rule.onNodeWithTag("busy_gauge_export").assertExists()
        rule.runOnUiThread { exp.gate.complete(ExportOutput("te1.zip", 2048, "/x/te1.zip", 3, 0, 0)) }; rule.waitForIdle()
        assertFalse(c.exporter!!.running)
        rule.onNodeWithTag("busy_queue").assertDoesNotExist()
        assertEquals(ToastKind.OK, c.ui.toastKind)
        assertTrue(c.ui.toast!!.contains("te1.zip"))
    }

    @Test fun exportFailureShowsErrorMessage() {
        setup()
        rule.runOnUiThread { c.homeOpen = false; c.exporter!!.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("export_run").performClick(); rule.waitForIdle()
        rule.runOnUiThread { exp.gate.completeExceptionally(java.io.IOException("disk")) }; rule.waitForIdle()
        assertEquals(ToastKind.ERROR, c.ui.toastKind)
        rule.onNodeWithTag("toast_error_mark", useUnmergedTree = true).assertExists()
        assertTrue(TapHaptics.errors >= 1)
    }

    @Test fun vworldTestResultMessage() {
        setup()
        rule.runOnUiThread { c.vworld.test() }; rule.waitForIdle()
        assertEquals(ToastKind.ERROR, c.ui.toastKind) // 메모리 플랫폼은 시험 불가 문구 → 오류 멘트
        assertTrue(c.ui.toast!!.startsWith("V월드 연결 시험"))
    }

    @Test fun gaugeNeverReachesFullBeforeDone() {
        val op = BusyOp("x", "x", 10)
        assertEquals(0.04f, BusyOps.gauge(op, 0), 1e-6f)
        assertEquals(0.5f, BusyOps.gauge(op, 5), 1e-6f)
        assertEquals(0.95f, BusyOps.gauge(op, 60), 1e-6f)
        assertEquals(0.3f, BusyOps.gauge(op.copy(progress = 0.3f), 60), 1e-6f)
    }

    @Test fun goToSurveyClosesEveryOverlay() {
        setup()
        rule.runOnUiThread {
            c.settingsMenuOpen = true; c.drawerOpen = true; c.gnssInfoOpen = true; c.skyPlotOpen = true
            c.antennaSheetOpen = true; c.setupOpen = true; c.quickSheetOpen = true; c.exporter!!.openScreen(); c.hub.open(ToolScreen.SETTINGS)
        }; rule.waitForIdle()
        assertNotEquals("survey", c.visibleRoute())
        rule.runOnUiThread { c.goToSurvey() }; rule.waitForIdle()
        assertEquals("survey", c.visibleRoute())
        rule.onNodeWithTag("survey_back").assertExists()
    }
}
