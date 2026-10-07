package kr.archsurvey.t0132

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.App
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ui.ConnectionController
import kr.archsurvey.ui.ConnectionPlatform
import kr.archsurvey.ui.NtripForm
import kr.archsurvey.ui.SavedConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.1.32 현장 보고 재현(「재접속하면 폰 위치로 남고 매번 다시 설정해야 한다」). 0.1.31 API 만 써서 0.1.31 트리에서도 돌릴 수 있게 썼다
 * (0.1.31 = 실패, 0.1.32 = 통과 — 보고서 release-gate-0.1.32.md).
 * 실제 SettingsStore·AndroidConnectionPlatform(Robolectric) 위에서 블루투스 목록·시작만 가짜.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreReceiver0132Test {
    private val r10 = "Trimble R10-5432" to "AA:AA:AA:AA:AA:01"
    private val sp60 = "SP60_1234" to "BB:BB:BB:BB:BB:02"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 실제 플랫폼(설정 저장)에 블루투스 목록·연결 시작만 가짜로 */
    private class P(real: AndroidConnectionPlatform, val devices: List<Pair<String, String>>) : ConnectionPlatform by real {
        val started = mutableListOf<Pair<String, String>>()
        val ntripOn = mutableListOf<Boolean>()
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        override fun bondedDevices() = devices
        override fun hasBluetoothPermission() = true
        override fun bluetoothEnabled() = true
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started += profile.id to address; ntripOn += ntripEnabled }
        override fun stopReal() { link.value = LinkStatus() }
    }

    private fun freshApp(): App {
        val a = RuntimeEnvironment.getApplication() as App
        a.getSharedPreferences("settings", 0).edit().clear().commit()
        return a
    }

    /** 현장: R10·SP60 둘 다 등록. SP60 을 쓰다가 R10 으로 돌아와 연결 → 앱을 끄고 다시 켬 */
    @Test fun switchBetweenReceivers_thenRestart_restoresLastReceiverAndAutoConnects() {
        val a = freshApp()
        a.settings.lastConnectOkAt = 1L // 전에 현장에서 성공한 적 있음
        val p1 = P(AndroidConnectionPlatform(a), listOf(r10, sp60))
        val c1 = ConnectionController(p1, scope)
        c1.selectAddress(r10.second)
        c1.updateForm(c1.form.copy(user = "u1", password = "pw"))
        c1.saveOnly()
        c1.selectProfile(DeviceProfiles.SP60); c1.selectAddress(sp60.second)
        c1.selectProfile(DeviceProfiles.R10) // 오늘은 다시 R10
        assertEquals("R10 으로 돌아오면 R10 주소가 그대로", r10.second, c1.address)

        // 다시 실행(새 화면 모델 · 같은 저장소)
        val p2 = P(AndroidConnectionPlatform(a), listOf(r10, sp60))
        val c2 = ConnectionController(p2, scope)
        assertEquals(DeviceProfiles.R10_ID, c2.profile.id)
        assertEquals("마지막 수신기 주소 복원", r10.second, c2.address)
        assertTrue("자동 연결", c2.autoConnectOnLaunch())
        assertEquals(listOf(DeviceProfiles.R10_ID to r10.second), p2.started)
    }

    /** 고른 수신기로 위치까지 받았지만 VRS 가 끝내 안 됨(401 등) → 다음 실행에도 수신기는 자동 연결돼야 함(폰 위치로 남지 않게) */
    @Test fun receiverChosen_vrsNeverReceived_stillAutoConnectsReceiver() {
        val a = freshApp() // lastConnectOkAt = 0 (보정까지 성공한 적 없음)
        val p1 = P(AndroidConnectionPlatform(a), listOf(sp60))
        val c1 = ConnectionController(p1, scope)
        c1.selectProfile(DeviceProfiles.SP60); c1.selectAddress(sp60.second)
        c1.updateForm(c1.form.copy(user = "u1", password = "pw")); c1.saveOnly()
        val p2 = P(AndroidConnectionPlatform(a), listOf(sp60))
        val c2 = ConnectionController(p2, scope)
        assertTrue(c2.autoConnectOnLaunch())
        assertEquals(listOf(DeviceProfiles.SP60_ID to sp60.second), p2.started)
    }

    /** VRS 비밀번호가 비어 있음(암호화 저장소 실패 등) → 예전엔 조용히 자동 연결 자체를 건너뜀. 수신기는 연결, 이번만 보정 끔, 저장값은 켜짐 그대로 */
    @Test fun vrsInputMissing_receiverStillAutoConnects_ntripOffThisRunOnly() {
        val a = freshApp()
        a.settings.lastConnectOkAt = 1L
        val p1 = P(AndroidConnectionPlatform(a), listOf(r10))
        val c1 = ConnectionController(p1, scope)
        c1.selectAddress(r10.second) // 아이디·비밀번호 없음
        val p2 = P(AndroidConnectionPlatform(a), listOf(r10))
        val c2 = ConnectionController(p2, scope)
        assertTrue(c2.autoConnectOnLaunch())
        assertEquals(listOf(false), p2.ntripOn)
        assertTrue("저장된 VRS 사용 설정은 그대로", a.settings.ntripEnabled)
        assertTrue(c2.ui.toast.orEmpty().contains("VRS"))
    }

    /** 저장된 수신기 없음(새 설치·초기화) + 폰에 SP60 하나만 등록 → 이름으로 SP60 인식해 바로 연결 */
    @Test fun noSaved_singlePairedSp60_autoDetectedAndConnected() {
        val a = freshApp()
        val p = P(AndroidConnectionPlatform(a), listOf("Galaxy Buds" to "CC:CC:CC:CC:CC:03", sp60))
        val c = ConnectionController(p, scope)
        assertTrue(c.autoConnectOnLaunch())
        assertEquals(listOf(DeviceProfiles.SP60_ID to sp60.second), p.started)
        assertEquals(DeviceProfiles.SP60_ID, c.profile.id)
        assertEquals("SP60 기본 보정(RTS2 · VRS-RTCM32)", "VRS-RTCM32", c.form.mount)
    }
}
