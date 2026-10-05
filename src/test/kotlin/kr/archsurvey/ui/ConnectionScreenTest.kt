package kr.archsurvey.ui

import android.graphics.Bitmap
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
import java.io.FileOutputStream

/** 수신기 연결 화면을 Robolectric(JVM)으로 그려 본다. 실기기/에뮬레이터 실행이 아니며 실제 블루투스·VRS 접속은 하지 않는다(가짜 플랫폼). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ConnectionScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("../build-reports/conn").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var started = 0
        var saved: SavedConnection? = null
        override fun bondedDevices() = listOf("Trimble R10-5512" to "00:11:22:33:44:55", "내 이어폰" to "AA:AA:AA:AA:AA:AA")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection(null, null, NtripForm())
        override fun save(s: SavedConnection) { saved = s }
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, name)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setup(): Triple<SurveyController, ConnectionController, P> {
        val p = P()
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 5)
        val cc = ConnectionController(p)
        c.connection = cc
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
        return Triple(c, cc, p)
    }

    @Test fun drawerHasConnectionItemAndOpensScreen() {
        val (c, cc, _) = setup()
        rule.runOnUiThread { c.drawerOpen = true }
        rule.waitForIdle()
        shot("c0_drawer")
        rule.onNodeWithText("수신기 연결 · VRS").assertExists().performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(cc.open)
        rule.onNodeWithTag("connection_screen").assertExists()
        shot("c1_connection_top")
        rule.onNodeWithTag("conn_model_trimble_r10").assertExists()
    }

    @Test fun realModeFlowToConnect() {
        val (c, cc, p) = setup()
        rule.runOnUiThread { cc.openScreen() }
        rule.waitForIdle()
        rule.onNodeWithTag("conn_model_spectra_sp60").performClick()
        rule.onNodeWithTag("conn_model_trimble_r10").performClick()
        rule.waitForIdle()
        shot("c2_real_top")
        rule.onNodeWithTag("conn_bonded_00:11:22:33:44:55").performScrollTo().performClick()
        for (i in 0 until cc.checks.size) rule.onNodeWithTag("conn_check_$i").performScrollTo().performClick()
        rule.waitForIdle()
        // 입력 누락 → 오류 카드
        rule.onNodeWithTag("conn_connect").performClick()
        rule.waitForIdle()
        assertEquals(0, p.started)
        rule.onNodeWithTag("conn_notice").assertExists()
        shot("c3_vrs_missing")
        rule.onNodeWithTag("conn_user").performScrollTo().performTextInput("tester")
        rule.onNodeWithTag("conn_pw").performScrollTo().performTextInput("secret")
        rule.onNodeWithTag("conn_connect").performClick()
        rule.waitForIdle()
        assertEquals(1, p.started)
        assertTrue(cc.connectRequested)
        shot("c4_connecting")
    }

    @Test fun errorCardsAndAlertStrip() {
        val (c, cc, p) = setup()
        p.link.value = LinkStatus(state = LinkState.NEEDS_USER, error = BtError.BT_OFF)
        p.ntrip.value = NtripStatus(state = NtripState.AUTH_FAILED)
        rule.waitForIdle()
        // 화면을 닫은 상태에서는 지도 위에 알림 줄
        rule.onNodeWithTag("alert_strip").assertExists()
        shot("c5_alert_strip")
        rule.onNodeWithTag("alert_strip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("conn_card_BT-101").performScrollTo().assertExists()
        rule.onNodeWithTag("conn_card_NT-401").performScrollTo().assertExists()
        shot("c6_error_cards")
        // 오류가 풀리면 카드/알림 줄이 사라진다
        p.link.value = LinkStatus(state = LinkState.STREAMING); p.ntrip.value = NtripStatus(state = NtripState.RECEIVING)
        rule.runOnUiThread { cc.close() }
        rule.waitForIdle()
        rule.onNodeWithTag("alert_strip").assertDoesNotExist()
    }
}
