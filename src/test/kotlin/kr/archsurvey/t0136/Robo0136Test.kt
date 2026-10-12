package kr.archsurvey.t0136

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kr.archsurvey.arch.ArchController
import kr.archsurvey.core.FixQuality
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.*
import kr.archsurvey.dig.DigKind
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.export.ExportFormat
import kr.archsurvey.export.ExportService
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.photo.PhotoStore
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.unit.dp
import java.util.zip.ZipFile

/** 연결 플랫폼: 끊기·다시 잇기 횟수를 센다 */
internal class TrackConn(state: LinkState = LinkState.STREAMING) : ConnectionPlatform {
    var stops = 0; var starts = 0
    override val link = MutableStateFlow(LinkStatus(state))
    override val ntrip = MutableStateFlow(NtripStatus())
    override fun bondedDevices() = listOf("R10_5411457757" to "00:11:22:33:44:66")
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { starts++; link.value = LinkStatus(LinkState.STREAMING) }
    override fun stopReal() { stops++; link.value = LinkStatus() }
    override fun loadSaved() = SavedConnection(DeviceProfiles.R10_ID, "00:11:22:33:44:66", NtripForm(user = "u", password = "p"), true)
    override fun save(s: SavedConnection) {}
}

/** 0.1.36 화면 시험(Robolectric — 실기기 아님): 종료 창(현장 보고 재현) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ExitUi0136Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 재현: R10 연결 중 홈에서 뒤로 → 1단계 창의 확인 단추.
     * 0.1.35: 제목 「기기 종료」·단추 「예」 → 누르면 블루투스만 끊기고 R10 전원은 그대로(앱엔 전원 끄기 명령이 없음) = 「안 꺼진다」.
     * 0.1.36: 제목·단추가 「수신기 연결 끊기」(본문: 앱은 전원을 끌 수 없음), 누르면 연결이 정말 끊기고(다시 잇지 않음) 이어서 프로그램 종료.
     */
    @Test fun homeBack_disconnectReallyStops_noReconnect_thenExit() {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        val conn = TrackConn()
        var exits = 0
        c.ui.toastMs = 600_000L
        c.homeOpen = true
        s.push(FixQuality.RTK_FIXED, 0.005)
        c.connection = ConnectionController(conn, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.onExitApp = { exits++ }
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals("수신기 연결 끊기")
        rule.onNodeWithTag("confirm_ok").assertTextContains("연결 끊기", substring = true)
        rule.onNodeWithTag("confirm_dialog").assertTextContains("전원을 끌 수 없어요", substring = true)
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals("다시 잇지 않음", 0, conn.starts)
        assertFalse(c.connection!!.linkActive())
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals(AppExit.TITLE_APP)
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals(1, exits); assertEquals(0, conn.starts)
    }

    @Test fun longJobName_headerShowsTwoLines_pillStillVisible() {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = "제주 애월읍 광령리 1234번지 유적 시굴조사"
        c.homeOpen = false
        s.push(FixQuality.RTK_FIXED, 0.005)
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        rule.onNodeWithTag("header_title", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("status_pill").assertIsDisplayed()
        val lines = rule.onNodeWithTag("header_title", useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()
        val oneLine = with(rule.density) { 22.dp.toPx() }
        assertTrue("두 줄로 보임(높이 $lines > 한 줄 $oneLine)", lines > oneLine)
    }
}
