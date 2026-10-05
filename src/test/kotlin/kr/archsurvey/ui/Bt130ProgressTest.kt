package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** BT-130 진행 창: 블루투스 성공 · 위치 데이터 실패로 분리 표시. 실기기 아님 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Bt130ProgressTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/v011").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus(NtripState.CONNECTED))
        override fun bondedDevices() = listOf("SP60_221060" to "00:11:22:33:44:55")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection(DeviceProfiles.SP60_ID, "00:11:22:33:44:55", NtripForm(user = "u", password = "p"), true)
        override fun save(s: SavedConnection) {}
    }

    @Test fun progressShowsBluetoothDoneAndPositionFailedOnBt130() {
        val p = P()
        val c = SurveyController(FakeGnssSource(FakeScenarios.fieldWalk()), db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui; c.connection = cc
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.runOnUiThread {
            cc.openScreen()
            // 연결 시도 상태로 진행 창 노출
            cc.connect()
            // 현장과 같이: 보정은 접속됨 · 링크는 NEEDS_USER(BT-130)
            p.link.value = LinkStatus(
                LinkState.NEEDS_USER, attempt = 1, failures = 1, error = BtError.NO_DATA,
                message = "연결은 됐는데 위치가 오지 않아요 (오류 BT-130) · ① 위성 LED(추적) 확인 ② …",
                strategy = "SECURE_SPP",
            )
            cc.showProgress = true
        }
        rule.waitForIdle()
        val steps = ConnectSteps.compute(p.link.value, p.ntrip.value, true, 0)
        assertEquals(StepState.DONE, steps[0])
        assertEquals(StepState.FAIL, steps[1])
        rule.onNodeWithTag("conn_progress").assertExists()
        rule.onNodeWithTag("conn_progress_title").assertTextContains("위치 없음", substring = true)
        rule.onNodeWithTag("conn_progress_step_0").assertTextContains("블루투스", substring = true)
        // 병합된 행에 '실패'는 위치 데이터 쪽에만
        rule.onNodeWithTag("conn_progress_step_1").assertTextContains("실패", substring = true)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "01_bt130_progress.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
