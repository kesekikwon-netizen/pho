package kr.archsurvey.ui

import kr.archsurvey.settingsTab

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.voicecmd.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.1.28 T14: 듣는 중 표시(기기 내/온라인 구분)·확인 대기 띠·설정 카드가 그려지는지 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0128VoiceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    private var lastEngine = FakeEngine()
    private fun setup(mode: RecognizerMode, scenario: kr.archsurvey.device.FakeScenario = FakeScenarios.allFixed()): Triple<SurveyController, VoiceCommandController, FakeGnssSource> {
        TileImages.networkEnabled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = FakeGnssSource(scenario)
        val c = SurveyController(f, db, scope, epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        c.settingsState = SettingsState(c, Sett())
        lastEngine = FakeEngine()
        val plat = FakeVoicePlatform(lastEngine, FakeSpeaker(), mode).also { it.perm = true; it.onlineConsent = true; it.savedOn = true }
        val v = VoiceCommandController(c.voiceHost, plat, c.mic)
        c.voice = v
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        repeat(2) { f.emitNext() }
        v.restore(); v.setForeground(true); v.tick()
        rule.waitForIdle()
        return Triple(c, v, f)
    }

    @Test fun onDevicePillShowsListening() {
        val (_, v) = setup(RecognizerMode.ON_DEVICE)
        assertEquals(VoiceState.LISTENING, v.state)
        rule.onNodeWithTag("voice_pill").assertExists()
        rule.onNodeWithTag("voice_state").assertTextContains("듣는 중", substring = true)
        rule.onNodeWithTag("voice_mode").assertTextContains("기기 내 인식", substring = true)
    }

    @Test fun onlinePillSaysOnlineRecognition() {
        setup(RecognizerMode.ONLINE)
        rule.onNodeWithTag("voice_mode").assertTextContains("온라인 인식", substring = true)
    }

    @org.junit.Ignore("0.1.58 B3(사용자 결정): 주의 확인 창 제거 — 플로트·10cm 초과는 창 없이 차단. 새 흐름은 t0158·Council0156 m1_replacedBy_b3 가 시험") @Test fun confirmBannerShowsWhileWaitingForYes() {
        val (c, v, f) = setup(RecognizerMode.ON_DEVICE, FakeScenarios.fieldWalk())
        f.skipToNextSegment(); f.emitNext(); v.tick()
        assertSame(v, c.voice)
        lastEngine.emit("저장")
        rule.waitForIdle()
        rule.onNodeWithTag("voice_confirm_banner").assertExists()
        rule.onNodeWithTag("confirm_dialog").assertExists()
    }

    @Test fun settingsCardHasToggleAndNote() {
        val (c) = setup(RecognizerMode.ON_DEVICE)
        c.hub.open(ToolScreen.SETTINGS)
        rule.waitForIdle()
        rule.settingsTab(2).onNodeWithTag("set_voice_save").performScrollTo()
        rule.settingsTab(2).onNodeWithTag("set_voice_save").assertExists()
        rule.onNodeWithTag("set_voice_mode").assertTextContains("오프라인", substring = true)
    }
}
