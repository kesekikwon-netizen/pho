package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.engine.SurveyConnectionService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 회귀 시험: 사용자 보고 ‘마운트 포인트 변경 시 앱 종료’.
 * 0.1.8 코드에서 같은 순서(실제 AndroidConnectionPlatform·설정 저장·연결 서비스 시작/재시작, 다크 모드)를 돌렸을 때 JVM(Robolectric)에서는 재현되지 않았다.
 * 실기기의 IME·블루투스·Keystore 경로는 여기서 확인할 수 없다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-night-xxhdpi")
class MountCrashRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    @Test fun changingMountWithRealPlatformAndReconnectDoesNotCrash() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val p = AndroidConnectionPlatform(app)
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui
        c.connection = cc
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 82) } }
        rule.runOnUiThread { cc.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_user").performScrollTo().performTextInput("testuser")
        rule.onNodeWithTag("conn_pw").performScrollTo().performTextInput("pw")
        // 보정 서비스 선택 창: FKP → RTS1
        rule.onNodeWithTag("conn_service").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("service_2").performClick(); rule.waitForIdle()
        assertEquals("FKP-RTCM31", cc.form.mount)
        rule.onNodeWithTag("conn_service").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("service_0").performClick(); rule.waitForIdle()
        assertEquals("RTS1.ngii.go.kr", cc.form.host)
        // 고급: 마운트 직접 입력(지우기·입력·한글·치환), 포트에 문자
        rule.onNodeWithTag("conn_server_toggle").performScrollTo().performClick(); rule.waitForIdle()
        val m = rule.onNodeWithTag("conn_mount").performScrollTo()
        m.performTextClearance(); rule.waitForIdle()
        m.performTextInput("VRS-RTCM32"); rule.waitForIdle()
        m.performTextReplacement(""); rule.waitForIdle()
        m.performTextInput("가나다 VRS"); rule.waitForIdle()
        m.performTextReplacement("VRS-RTCM32"); rule.waitForIdle()
        rule.onNodeWithTag("conn_port").performScrollTo().performTextReplacement("2101x9"); rule.waitForIdle()
        assertEquals("21019", cc.form.port)
        rule.onNodeWithTag("conn_port").performTextReplacement("2101"); rule.waitForIdle()
        // 마운트 선택 창(이 환경에서는 소스테이블 실패 → 알려진 목록) → 직접 입력 적용
        rule.onNodeWithTag("conn_mount_pick").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mount_picker").assertExists()
        rule.onNodeWithTag("mount_manual").performTextReplacement("VRS-RTCM31"); rule.waitForIdle()
        rule.onNodeWithTag("mount_apply").performClick(); rule.waitForIdle()
        assertEquals("VRS-RTCM31", cc.form.mount)
        assertEquals("VRS-RTCM31", p.loadSaved().ntrip.mount)
        // 연결 버튼(권한 없으면 오류 카드), 저장·서비스 시작 경로는 직접 부른다
        rule.onNodeWithTag("conn_connect").performClick(); rule.waitForIdle()
        p.startReal(DeviceProfiles.R10, "00:11:22:33:44:55", cc.form.toConfig(), true)
        assertEquals("VRS-RTCM31", p.loadSaved().ntrip.mount)
        val intent = Shadows.shadowOf(app).nextStartedService
        assertNotNull(intent)
        val svc = Robolectric.buildService(SurveyConnectionService::class.java, intent).create()
        svc.startCommand(0, 1)
        // 마운트를 바꿔 다시 시작
        p.startReal(DeviceProfiles.R10, "00:11:22:33:44:55", cc.form.copy(mount = "FKP-RTCM31").toConfig(), true)
        svc.withIntent(Shadows.shadowOf(app).nextStartedService).startCommand(0, 2)
        Thread.sleep(1000)
        rule.waitForIdle()
        assertEquals("FKP-RTCM31", p.loadSaved().ntrip.mount)
        p.stopReal()
    }
}
