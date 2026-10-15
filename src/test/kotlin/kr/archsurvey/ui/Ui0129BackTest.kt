package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.FakeTiltPlatform
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.tiltReading
import kr.archsurvey.voicecmd.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.1.29 T22 시스템 뒤로 = 맨 위 창 하나만 닫기(취소). 모두 rule.activity.onBackPressedDispatcher 로 누른다.
 * T22-1 선택 창 · T22-2 확인 창(겹침 포함) · T22-3 기울기 확인 · T22-4 중복 점 · T22-5 측정(취득) 중 · 음성 「예」가 뒤로 닫은 뒤 오면 무시.
 * 이 파일은 0.1.28(65d147a) 코드에도 그대로 컴파일된다 — 수정 전 실패 증거용. Robolectric(JVM) — 실기기 아님.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0129BackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        override fun bondedDevices() = listOf("R10_1234" to "00:11:22:33:44:55")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection(DeviceProfiles.R10_ID, "00:11:22:33:44:55", NtripForm(user = "u", password = "p"), true)
        override fun save(s: SavedConnection) {}
    }

    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL
        override var poleRight = true
        override var sound = true
        override var vibration = true
        override var keepScreenOn = false
    }

    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private lateinit var cc: ConnectionController

    private fun setup(scenario: FakeScenario = FakeScenarios.allFixed(), epochs: Int = 3, home: Boolean = false, before: (SurveyController) -> Unit = {}) {
        TileImages.networkEnabled = false
        f = FakeGnssSource(scenario)
        c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = epochs)
        c.ui.toastMs = 600_000L
        c.settingsState = SettingsState(c, Sett())
        cc = ConnectionController(P(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui; c.connection = cc
        c.homeOpen = home
        before(c)
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
    }

    private fun ui(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }
    private fun back() = ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
    private fun cautionState() { repeat(2) { f.emitNext() }; f.skipToNextSegment(); f.emitNext() }

    // ---------------- T22-1 선택 창 ----------------

    /** 사용자 신고(0.1.28): 홈 → 장비 연결 → 「보정 서비스」 창 → 뒤로 = 창은 그대로, 연결 화면만 닫혔다 */
    @Test fun t22_1_serviceSheet_back_closesOnlySheet_thenScreen() {
        setup(home = true)
        ui { cc.openScreen() }
        ui { cc.askService() }
        rule.onNodeWithTag("choice_sheet").assertExists()
        val form0 = cc.form
        back()
        assertNull("뒤로 1번 = 보정 서비스 창만 닫힘", c.ui.choice)
        rule.onNodeWithTag("choice_sheet").assertDoesNotExist()
        assertTrue("뒤 연결 화면은 그대로", cc.open)
        assertTrue("홈 그대로", c.homeOpen)
        assertEquals("고르지 않음(취소)", form0, cc.form)
        back()
        assertFalse("뒤로 2번 = 평소대로 연결 화면 닫힘", cc.open)
        assertTrue(c.homeOpen)
    }

    /** 측량 화면 위 선택 창(배경지도·측정 횟수 등 공용 ChoiceSheet): 뒤로 = 창만 닫고 고르지 않음, 측량 화면은 그대로 */
    @Test fun t22_1_choiceOnSurvey_back_isCancel_noHome() {
        setup()
        var picked = -1
        ui { c.ui.choose("측정 횟수", listOf("1회", "3회", "5회"), 1, "epochs") { picked = it } }
        rule.onNodeWithTag("choice_sheet").assertExists()
        back()
        assertNull(c.ui.choice); assertEquals("고르지 않음", -1, picked)
        assertFalse("측량 → 홈으로 가지 않음", c.homeOpen)
        back()
        assertTrue("다음 뒤로는 평소대로 홈", c.homeOpen)
    }

    // ---------------- T22-2 확인 창 ----------------

    /** 주의(CAUTION) 확인 창 + 뒤로 = 「취소」 — 주의 승인·저장 없음, 측량 화면 그대로 */
    @org.junit.Ignore("0.1.58 B3(사용자 결정): 주의 확인 창 제거 — 플로트·10cm 초과는 창 없이 차단. 새 흐름은 t0158·Council0156 m1_replacedBy_b3 가 시험") @Test fun t22_2_cautionConfirm_back_isCancel_neverSaves() {
        setup(FakeScenarios.fieldWalk(), epochs = 3)
        ui { cautionState(); c.onMeasure() }
        assertNotNull("주의 확인 창", c.ui.confirm)
        rule.onNodeWithTag("confirm_dialog").assertExists()
        back()
        assertNull("확인 창 닫힘", c.ui.confirm)
        rule.onNodeWithTag("confirm_dialog").assertDoesNotExist()
        assertEquals("측정 시작 안 함", Phase.IDLE, c.phase)
        ui { repeat(6) { f.emitNext() } }
        assertTrue("저장 없음", c.points.isEmpty())
        assertFalse("측량 → 홈으로 가지 않음", c.homeOpen)
    }

    /** 겹침: 선택 창 위 확인 창 → 뒤로 1번 = 확인 창만(취소), 2번 = 선택 창만, 3번 = 화면(홈) */
    @Test fun t22_2_confirmOverChoice_oneBackOneLayer() {
        setup()
        var picked = -1; var okCalls = 0
        ui { c.ui.choose("배경지도", listOf("위성", "지적"), 0, "bgmap") { picked = it } }
        ui { c.ui.ask(ConfirmRequest("지울까요?", "되돌릴 수 없어요", "지우기") { okCalls++ }) }
        back()
        assertNull("맨 위 확인 창 닫힘", c.ui.confirm); assertEquals("승인 아님", 0, okCalls)
        assertNotNull("아래 선택 창은 그대로", c.ui.choice); assertFalse(c.homeOpen)
        back()
        assertNull(c.ui.choice); assertEquals(-1, picked); assertFalse("화면은 아직 그대로", c.homeOpen)
        back()
        assertTrue(c.homeOpen)
    }

    /** 되돌리기 확인 + 뒤로 = 되돌리지 않음 */
    @Test fun t22_2_undoConfirm_back_keepsPoint() {
        setup(epochs = 1)
        ui { repeat(2) { f.emitNext() }; c.onMeasure() }
        assertEquals(1, c.points.size)
        ui { c.askUndo() }
        assertNotNull(c.ui.confirm)
        back()
        assertNull(c.ui.confirm); assertEquals("점 그대로", 1, c.points.size); assertFalse(c.homeOpen)
    }

    // ---------------- T22-3 기울기 확인 ----------------

    @Test fun t22_3_tiltPrompt_back_isNo_neverMeasures() {
        setup(epochs = 3) { s ->
            val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(TiltPolicy.CONFIRM)
            repeat(60) { tc.onSample(tiltReading(3.0)) }
            s.tilt = tc
        }
        ui { repeat(2) { f.emitNext() }; c.onMeasure() }
        assertNotNull("기울기 확인 창", c.tiltPrompt)
        back()
        assertNull("기울기 확인 닫힘", c.tiltPrompt)
        assertEquals("측정 시작 안 함", Phase.IDLE, c.phase)
        ui { repeat(6) { f.emitNext() } }
        assertTrue("저장 없음", c.points.isEmpty())
        assertFalse("측량 → 홈으로 가지 않음", c.homeOpen)
    }

    // ---------------- T22-4 중복 점 ----------------

    /** 중복 점 창은 반드시 골라야 함: 뒤로를 먹고 창·방금 측정값은 그대로(저장도 버림도 안 함) */
    @Test fun t22_4_dupPrompt_back_keepsChoiceOpen_noSaveNoLoss() {
        setup(epochs = 1)
        ui { repeat(2) { f.emitNext() }; c.onMeasure() }
        val p = c.points.single()
        val d = DupPrompt(p.copy(name = "새"), p, 0.012, SurveyStatus.FIXED)
        ui { c.dupPrompt = d }
        rule.onNodeWithTag("dup_sheet").assertExists()
        back()
        assertSame("창 그대로 · 방금 측정값 보존", d, c.dupPrompt)
        rule.onNodeWithTag("dup_sheet").assertExists()
        assertEquals("저장 안 함(새 점·덮기 없음)", 1, c.points.size)
        assertFalse("측량 → 홈으로 가지 않음", c.homeOpen)
        assertFalse(rule.activity.isFinishing)
        back()
        assertSame(d, c.dupPrompt); assertEquals(1, c.points.size); assertFalse(c.homeOpen)
    }

    // ---------------- T22-5 측정(취득) 중 ----------------

    /** 예전: backTarget = null → 앱이 나갔다. 지금: 기존 「중지」(cancelAcquire) — 저장 없음, 앱·화면 그대로 */
    @Test fun t22_5_acquiring_back_stopsSafely_neverExits() {
        setup(epochs = 5)
        ui { repeat(2) { f.emitNext() }; c.onMeasure() }
        assertEquals(Phase.ACQUIRING, c.phase)
        back()
        assertFalse("앱이 나가지 않음", rule.activity.isFinishing)
        assertEquals("측정 중지", Phase.IDLE, c.phase)
        assertFalse(c.homeOpen)
        ui { repeat(8) { f.emitNext() } }
        assertTrue("저장 없음", c.points.isEmpty())
    }

    // ---------------- 음성 확인과 함께 ----------------

    private var now = 1_000_000L
    /** TTS 끝 + 잔향 쉼(이 시간이 지나야 「예」를 듣는다) */
    private fun speechDone(v: VoiceCommandController, spk: FakeSpeaker) { spk.finish(); now += VoiceCommandController.TTS_GUARD_MS + 50; v.tick() }

    private fun voiceRig(): Triple<VoiceCommandController, FakeEngine, FakeSpeaker> {
        val eng = FakeEngine(); val spk = FakeSpeaker()
        val plat = FakeVoicePlatform(eng, spk).also { it.perm = true }
        val v = VoiceCommandController(c.voiceHost, plat, c.mic) { now }
        c.voice = v
        v.toggle(true); v.setForeground(true); v.tick()
        return Triple(v, eng, spk)
    }

    /** 음성으로 연 주의 확인을 뒤로 닫은 뒤 「예」가 와도 승인·저장하지 않는다 */
    @org.junit.Ignore("0.1.58 B3(사용자 결정): 주의 확인 창 제거 — 플로트·10cm 초과는 창 없이 차단. 새 흐름은 t0158·Council0156 m1_replacedBy_b3 가 시험") @Test fun voiceYesAfterBackClosedCautionConfirm_isIgnored() {
        setup(FakeScenarios.fieldWalk(), epochs = 3)
        lateinit var rig: Triple<VoiceCommandController, FakeEngine, FakeSpeaker>
        ui { rig = voiceRig(); cautionState(); rig.first.tick() }
        val (v, eng, spk) = rig
        ui { eng.emit("저장") }
        assertNotNull("음성으로 연 주의 확인", c.ui.confirm)
        ui { speechDone(v, spk) }
        assertNotNull("음성 확인 대기", v.asking)
        assertEquals("「예」를 들을 수 있는 상태", VoiceState.CONFIRM, v.state)
        // 뒤로로 닫고, 같은 UI 차례에 바로 「예」(tick 이 끼어들 틈 없음)
        ui { rule.activity.onBackPressedDispatcher.onBackPressed(); eng.emit("예") }
        assertNull(c.ui.confirm)
        assertEquals("측정 시작 안 함", Phase.IDLE, c.phase)
        ui { repeat(6) { f.emitNext() }; now += 300; v.tick() }
        assertTrue("저장 없음", c.points.isEmpty())
        assertNull("음성 확인 대기도 끝", v.asking)
    }

    /** 음성으로 연 기울기 확인을 뒤로 닫은 뒤 「예」가 와도 측정하지 않는다 */
    @Test fun voiceYesAfterBackClosedTiltPrompt_isIgnored() {
        setup(epochs = 3) { s ->
            val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(TiltPolicy.CONFIRM)
            repeat(60) { tc.onSample(tiltReading(3.0)) }
            s.tilt = tc
        }
        lateinit var rig: Triple<VoiceCommandController, FakeEngine, FakeSpeaker>
        ui { rig = voiceRig(); repeat(2) { f.emitNext() }; rig.first.tick() }
        val (v, eng, spk) = rig
        ui { eng.emit("저장") }
        assertNotNull("음성으로 연 기울기 확인", c.tiltPrompt)
        ui { speechDone(v, spk) }
        assertEquals("「예」를 들을 수 있는 상태", VoiceState.CONFIRM, v.state)
        ui { rule.activity.onBackPressedDispatcher.onBackPressed(); eng.emit("예") }
        assertNull(c.tiltPrompt)
        assertEquals(Phase.IDLE, c.phase)
        ui { repeat(6) { f.emitNext() }; now += 300; v.tick() }
        assertTrue("저장 없음", c.points.isEmpty())
    }
}
