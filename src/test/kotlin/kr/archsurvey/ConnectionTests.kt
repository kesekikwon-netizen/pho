package kr.archsurvey

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kr.archsurvey.bt.BtError
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

private class StubSource(state: LinkState) : GnssSource {
    override val link = MutableStateFlow(LinkStatus(state = state))
    override val gnss = MutableStateFlow(GnssSnapshot())
    override fun clockMs() = 0L
    override fun stop() {}
}

private class FakePlatform : ConnectionPlatform {
    override val link = MutableStateFlow(LinkStatus())
    override val ntrip = MutableStateFlow(NtripStatus())
    var devices = listOf("Trimble R10-1234" to "AA:BB")
    var perm = true
    var permGrant = true
    var btOn = true
    var started: Triple<String, String, NtripConfig>? = null
    var ntripEnabledSeen: Boolean? = null
    var stopped = 0
    var saved: SavedConnection? = null
    var initial = SavedConnection(null, null, NtripForm(user = "", password = ""))
    override fun bondedDevices() = devices
    override fun hasBluetoothPermission() = perm
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) { if (permGrant) perm = true; onResult(permGrant) }
    override fun bluetoothEnabled() = btOn
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {
        started = Triple(profile.id, address, ntrip); ntripEnabledSeen = ntripEnabled
    }
    override fun stopReal() { stopped++ }
    override fun loadSaved() = initial
    override fun save(s: SavedConnection) { saved = s }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTests {
    @Test fun switchableFollowsDelegate() = runTest(UnconfinedTestDispatcher()) {
        val a = StubSource(LinkState.STREAMING); val b = StubSource(LinkState.IDLE)
        val s = SwitchableGnssSource(backgroundScope, a)
        assertEquals(LinkState.STREAMING, s.link.value.state)
        s.switchTo(b)
        assertEquals(LinkState.IDLE, s.link.value.state)
        assertSame(b, s.delegate)
        b.link.value = LinkStatus(state = LinkState.STALE)
        assertEquals(LinkState.STALE, s.link.value.state)
        s.switchTo(a)
        assertEquals(LinkState.STREAMING, s.link.value.state)
    }

    @Test fun formValidation() {
        val f = NtripForm(user = "", password = "")
        assertEquals(2, f.problems().size)
        assertTrue(NtripForm(port = "70000", user = "u", password = "p").problems().any { "포트" in it })
        assertTrue(NtripForm(host = " ", user = "u", password = "p").problems().any { "서버" in it })
        assertTrue(NtripForm(mount = "", user = "u", password = "p").problems().any { "마운트" in it })
        assertTrue(NtripForm(user = "u", password = "p").problems().isEmpty())
        assertTrue(NtripForm(enabled = false).problems().isEmpty())
        val c = NtripForm(host = " x.y ", port = "2101", mount = "M", user = " u ", password = " p ").toConfig()
        assertEquals("x.y", c.host); assertEquals(2101, c.port); assertEquals("u", c.username); assertEquals(" p ", c.password)
    }

    @Test fun defaultsAreFakeAndVrs() {
        val f = NtripForm()
        assertEquals("RTS1.ngii.go.kr", f.host); assertEquals("2101", f.port)
    }

    @Test fun errorCardsMapping() {
        assertTrue(ErrorCards.from(LinkStatus(), NtripStatus(), true).isEmpty())
        val bt = ErrorCards.from(LinkStatus(state = LinkState.NEEDS_USER, error = BtError.BT_OFF), null, false)
        assertEquals("BT-101", bt.single().code); assertFalse(bt.single().autoRetry)
        val retry = ErrorCards.from(LinkStatus(state = LinkState.RECONNECTING, error = BtError.ALL_FAILED), null, false)
        assertTrue(retry.isNotEmpty())
        val auth = ErrorCards.from(LinkStatus(), NtripStatus(state = NtripState.AUTH_FAILED), true)
        assertEquals("NT-401", auth.single().code); assertFalse(auth.single().autoRetry)
        assertEquals("NT-500", ErrorCards.from(LinkStatus(), NtripStatus(state = NtripState.ERROR), true).single().code)
        assertEquals("NT-300", ErrorCards.from(LinkStatus(), NtripStatus(state = NtripState.RECONNECTING), true).single().code)
        // VRS 를 끈 경우 NTRIP 오류는 보이지 않는다
        assertTrue(ErrorCards.from(LinkStatus(), NtripStatus(state = NtripState.AUTH_FAILED), false).isEmpty())
    }

    private fun real(p: FakePlatform = FakePlatform()): Pair<ConnectionController, FakePlatform> {
        val c = ConnectionController(p)
        return c to p
    }

    @Test fun preChecksAreRememberedAfterFirstSuccessfulConnect() {
        val (c, p) = real()
        c.selectAddress("AA:BB")
        for (i in c.checks.indices) c.setCheck(i, true)
        c.updateForm(c.form.copy(user = "u", password = "pw"))
        c.connect()
        assertTrue(p.saved!!.preChecked)
        // 다음 실행: 저장된 값으로 체크가 이미 켜져 있어 다시 누를 필요가 없다
        p.initial = p.saved!!
        val c2 = ConnectionController(p)
        assertTrue(c2.checks.isNotEmpty() && c2.checks.all { it })
        // 처음 쓰는 기기는 그대로 미체크
        val c3 = ConnectionController(FakePlatform())
        assertTrue(c3.checks.none { it })
    }

    @Test fun connectPreflightOrder() {
        val (c, p) = real()
        // 권한 거부
        p.perm = false; p.permGrant = false
        c.connect(); assertEquals("BT-103", c.notice?.code); assertNull(p.started)
        // 권한 허용 → 블루투스 꺼짐
        p.permGrant = true; p.btOn = false
        c.connect(); assertEquals("BT-101", c.notice?.code)
        p.btOn = true
        // 기기 미선택
        c.connect(); assertEquals("BT-110", c.notice?.code)
        // 등록 안 된 기기
        c.selectAddress("ZZ:ZZ"); c.connect(); assertEquals("BT-111", c.notice?.code)
        c.selectAddress("AA:BB")
        // 사전 확인 미체크
        c.connect(); assertTrue(c.notice!!.title.contains("체크")); assertNull(p.started)
        for (i in c.checks.indices) c.setCheck(i, true)
        // VRS 입력 없음
        c.connect(); assertTrue(c.notice!!.title.contains("VRS")); assertNull(p.started)
        c.updateForm(c.form.copy(user = "u", password = "pw"))
        c.connect()
        assertNull(c.notice)
        assertEquals("AA:BB", p.started!!.second)
        assertEquals("u", p.started!!.third.username)
        assertTrue(c.connectRequested)
    }

    @Test fun vrsDisabledSkipsVrsValidation() {
        val (c, p) = real()
        for (i in c.checks.indices) c.setCheck(i, true)
        c.selectAddress("AA:BB"); c.updateForm(NtripForm(enabled = false))
        c.connect()
        assertNotNull(p.started); assertEquals(false, p.ntripEnabledSeen)
    }

    @Test fun selectingModelResetsChecksAndAddress() {
        val (c, _) = real()
        c.selectAddress("AA:BB")
        c.selectProfile(DeviceProfiles.SP60)
        assertNull(c.address); assertEquals(DeviceProfiles.SP60.preConnectChecks.size, c.checks.size)
        assertTrue(c.checks.none { it })
    }
}
