package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
 * 0.1.17: 「연결 중」 진행 창이 떠 있을 때 시스템 뒤로 → 그 창만 닫힘(연결은 계속).
 * 예전(0.1.16): 진행 창은 그대로, 뒤 화면(측량 → 홈 / 연결 화면 닫기)만 뒤로 갔음.
 * Robolectric(JVM) — 실기기 아님.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0117BackProgressTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus(NtripState.CONNECTING))
        var stopped = 0
        override fun bondedDevices() = listOf("SP60_221060" to "00:11:22:33:44:55")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() { stopped++ }
        override fun loadSaved() = SavedConnection(DeviceProfiles.SP60_ID, "00:11:22:33:44:55", NtripForm(user = "u", password = "p"), true)
        override fun save(s: SavedConnection) {}
    }

    /** 측량 지도(homeOpen=false) 위에서 연결 시도 → 진행 창 */
    private fun setup(connOpen: Boolean): Triple<SurveyController, ConnectionController, P> {
        TileImages.networkEnabled = false
        val p = P()
        val c = SurveyController(FakeGnssSource(FakeScenarios.fieldWalk()), db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.ui.toastMs = 600_000L
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui; c.connection = cc
        c.homeOpen = false
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.runOnUiThread {
            cc.openScreen()
            cc.connect()
            if (!connOpen) cc.close()
            p.link.value = LinkStatus(LinkState.CONNECTING, attempt = 1)
        }
        rule.waitForIdle()
        assertTrue("연결 시도 중", cc.connectRequested)
        assertTrue("진행 창 표시", cc.showProgress)
        assertEquals(connOpen, cc.open)
        rule.onNodeWithTag("conn_progress").assertExists()
        return Triple(c, cc, p)
    }

    @Test fun backTarget_onSurvey_dismissesProgressOnly() {
        val (c, cc, p) = setup(connOpen = false)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertFalse("진행 창 닫힘", cc.showProgress)
        assertFalse("측량 → 홈으로 가지 않음", c.homeOpen)
        assertTrue("연결은 계속", cc.connectRequested)
        assertEquals("연결 끊지 않음", 0, p.stopped)
        rule.onNodeWithTag("conn_progress").assertDoesNotExist()
        // 다음 뒤로는 원래대로 측량 → 홈
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertTrue(c.homeOpen)
    }

    @Test fun backTarget_withConnectionScreen_dismissesProgressKeepsScreen() {
        val (c, cc, _) = setup(connOpen = true)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertFalse(cc.showProgress)
        assertTrue("연결 화면은 그대로", cc.open)
        assertFalse(c.homeOpen)
        assertTrue(cc.connectRequested)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle()
        assertFalse("두 번째 뒤로 = 연결 화면 닫기", cc.open)
        assertFalse(c.homeOpen)
    }

    /** 실제 시스템 뒤로 경로(OnBackPressedDispatcher → Compose BackHandler) */
    @Test fun systemBack_dispatcher_dismissesProgressOnly() {
        val (c, cc, p) = setup(connOpen = true)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertFalse("진행 창 닫힘", cc.showProgress)
        rule.onNodeWithTag("conn_progress").assertDoesNotExist()
        assertTrue("연결 화면은 그대로(뒤 화면이 뒤로 가지 않음)", cc.open)
        assertFalse(c.homeOpen)
        assertTrue(cc.connectRequested)
        assertEquals(0, p.stopped)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertFalse(cc.open)
        assertFalse(c.homeOpen)
    }
}
