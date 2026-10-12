package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.1.18 연결 화면 상호작용(HTML 버튼 59~66): 단계 목록 · 연결/다시 시도 · 연결 취소 · 연결 해제+지도로 가기 · 실패 카드 · 뒤로.
 * Robolectric(JVM) — 실기기·실제 BT 아님.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0118ConnectUxTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var stopped = 0
        override fun bondedDevices() = listOf("R10_5512" to "00:11:22:33:44:55")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() { stopped++ }
        override fun loadSaved() = SavedConnection(DeviceProfiles.R10_ID, "00:11:22:33:44:55", NtripForm(user = "u", password = "secretPW1"), true)
        override fun save(s: SavedConnection) {}
    }

    private fun setup(): Triple<SurveyController, ConnectionController, P> {
        TileImages.networkEnabled = false
        val p = P()
        val c = SurveyController(FakeGnssSource(FakeScenarios.fieldWalk()), db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.ui.toastMs = 600_000L
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui; c.connection = cc
        c.homeOpen = false
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.runOnUiThread { cc.openScreen() }
        rule.waitForIdle()
        return Triple(c, cc, p)
    }

    private fun set(p: P, l: LinkStatus) { rule.runOnUiThread { p.link.value = l }; rule.waitForIdle() }

    @Test fun bottomBar_followsPhases_andStepsInline() {
        val (c, cc, p) = setup()
        rule.onNodeWithTag("conn_steps").assertExists()
        rule.onNodeWithTag("conn_step_state_0", useUnmergedTree = true).assertTextEquals("대기")
        rule.onNodeWithTag("conn_connect_label", useUnmergedTree = true).assertTextEquals("연결")
        rule.onNodeWithTag("conn_cancel_connect").assertDoesNotExist()
        rule.onNodeWithTag("conn_go_map").assertDoesNotExist()
        rule.onAllNodesWithText("secretPW1", substring = true, useUnmergedTree = true).assertCountEquals(0)

        // 연결 → 진행 창 → 닫기(계속 연결) → 화면은 「연결 취소」만
        rule.onNodeWithTag("conn_connect").performClick(); rule.waitForIdle()
        set(p, LinkStatus(LinkState.CONNECTING, attempt = 1))
        rule.onNodeWithTag("conn_progress_close").performClick(); rule.waitForIdle()
        assertTrue(cc.connectRequested); assertEquals(ConnectionController.KEEP_HINT, c.ui.toast)
        rule.onNodeWithTag("conn_step_state_0", useUnmergedTree = true).assertTextEquals("진행 중")
        rule.onNodeWithTag("conn_cancel_connect").assertExists()
        rule.onNodeWithTag("conn_connect").assertDoesNotExist()
        // 모델 바꾸기 막힘
        rule.onNodeWithTag("conn_model_spectra_sp60").performClick(); rule.waitForIdle()
        assertEquals(DeviceProfiles.R10_ID, cc.profile.id); assertEquals(ConnectionController.LOCKED_MSG, c.ui.toast)

        // 실패 → 실패 카드 + 「다시 시도」
        set(p, LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT))
        rule.onNodeWithTag("conn_fail_card").assertExists()
        rule.onNodeWithTag("conn_step_state_0", useUnmergedTree = true).assertTextEquals("실패")
        rule.onNodeWithTag("conn_connect_label", useUnmergedTree = true).assertTextEquals("다시 시도")

        // BT-130: 실패 머리 카드 없음, 기존 BT-130 카드 유지
        set(p, LinkStatus(LinkState.NEEDS_USER, error = BtError.NO_DATA))
        rule.onNodeWithTag("conn_fail_card").assertDoesNotExist()
        rule.onNodeWithTag("conn_card_BT-130").assertExists()

        // 연결됨 → 「연결 해제」 + 「지도로 가기」
        rule.runOnUiThread { cc.showProgress = false }
        set(p, LinkStatus(LinkState.STREAMING))
        rule.onNodeWithTag("conn_disconnect").assertExists()
        rule.onNodeWithTag("conn_go_map").assertExists()
        rule.onNodeWithTag("conn_cancel_connect").assertDoesNotExist()
        rule.onNodeWithTag("conn_go_map").performClick(); rule.waitForIdle()
        assertFalse(cc.open); assertTrue("연결 유지", cc.connectRequested)

        rule.runOnUiThread { cc.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_disconnect").performClick(); rule.waitForIdle()
        assertFalse(cc.connectRequested); assertEquals(ConnectionController.DISCONNECTED_MSG, c.ui.toast)
        rule.onNodeWithTag("conn_connect").assertExists()
    }

    /** 0.1.17 비차단 QA-0117-1/AND-0117-1: 완료(고정) 상태 진행 창에서 backTarget 뒤로 = 「확인」(진행 창 + 연결 화면 닫기) */
    @Test fun backTarget_doneState_closesProgressAndScreen() {
        val (c, cc, p) = setup()
        rule.runOnUiThread { cc.connect() }; set(p, LinkStatus(LinkState.STREAMING))
        assertTrue(cc.showProgress)
        // 완료 판정은 진행 창과 같은 기준(GGA 품질 4)
        val done = cc.progressDone(c.gv.quality.code)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertFalse(cc.showProgress)
        assertEquals("완료면 연결 화면도 닫힘, 진행 중이면 유지", !done, cc.open)
        assertTrue(cc.connectRequested); assertEquals(0, p.stopped)
    }

    @Test fun headerBack_whileConnecting_keepsConnection_hintOnce() {
        val (c, cc, p) = setup()
        rule.runOnUiThread { cc.connect(); cc.showProgress = false }; set(p, LinkStatus(LinkState.CONNECTING))
        rule.onNodeWithTag("conn_back").performClick(); rule.waitForIdle()
        assertFalse(cc.open); assertTrue(cc.connectRequested); assertEquals(0, p.stopped)
        assertEquals(ConnectionController.KEEP_HINT, c.ui.toast)
        rule.onNodeWithTag("busy_queue").assertExists()
    }
}
