package kr.archsurvey

import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.device.*
import kr.archsurvey.diag.CrashLog
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.engine.diagnosticsFrom
import kr.archsurvey.ntrip.MountInfo
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.18 연결 상호작용(HTML 「수신기 · VRS 연결」) + 호환 필터 + 비밀번호 보안. JVM 단위 시험 — 실기기 아님 */
class ConnectUx0118Tests {
    private class P(saved: SavedConnection = SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "u", password = "secretPW1"), true)) : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var stopped = 0; var started = 0
        private val s = saved
        override fun bondedDevices() = listOf("R10" to "AA:BB", "R10-2" to "CC:DD")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
        override fun stopReal() { stopped++ }
        override fun loadSaved() = s
        override fun save(s: SavedConnection) {}
    }

    private fun ctl(p: P = P()) = ConnectionController(p).also { c -> for (i in c.checks.indices) c.setCheck(i, true) }

    // ---------- 단계 ----------
    @Test fun phase_followsHtmlStates() {
        assertEquals(ConnPhase.IDLE, ConnPhases.of(false, LinkStatus(LinkState.STREAMING)))
        assertEquals(ConnPhase.CONNECTING, ConnPhases.of(true, LinkStatus(LinkState.CONNECTING)))
        assertEquals(ConnPhase.CONNECTING, ConnPhases.of(true, LinkStatus(LinkState.RECONNECTING)))
        // BT-130 치유 중(소켓만 붙고 위치 없음)은 아직 연결 중
        assertEquals(ConnPhase.CONNECTING, ConnPhases.of(true, LinkStatus(LinkState.CONNECTED_NO_DATA)))
        assertEquals(ConnPhase.CONNECTED, ConnPhases.of(true, LinkStatus(LinkState.STREAMING)))
        assertEquals(ConnPhase.CONNECTED, ConnPhases.of(true, LinkStatus(LinkState.STALE)))
        assertEquals(ConnPhase.FAILED, ConnPhases.of(true, LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT)))
        assertEquals("진행 중", ConnPhases.stateKo(StepState.ACTIVE)); assertEquals("실패", ConnPhases.stateKo(StepState.FAIL))
    }

    @Test fun connectLabel_retryAfterFailure_andRtcmConfirm() {
        val p = P(); val c = ctl(p)
        assertEquals("연결", c.connectLabel())
        c.connect(); p.link.value = LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT)
        assertEquals("다시 시도", c.connectLabel())
        // 다시 시도 = 다시 연결 시작(재연결)
        c.connect(); assertEquals(2, p.started)
        // SP60 + RTCM 3.4: 첫 누름은 경고만 → 「그래도 연결」
        val p2 = P(SavedConnection(DeviceProfiles.SP60_ID, "AA:BB", NtripForm(user = "u", password = "p", mount = "VRS-RTCM34"), true)); val c2 = ctl(p2)
        c2.connect(); assertEquals(0, p2.started); assertEquals("그래도 연결", c2.connectLabel())
        c2.connect(); assertEquals(1, p2.started)
    }

    // ---------- 취소 / 해제 구분 ----------
    @Test fun cancel_vs_disconnect_messages() {
        val p = P(); val c = ctl(p)
        c.connect(); p.link.value = LinkStatus(LinkState.CONNECTING)
        c.cancelConnect()
        assertFalse(c.connectRequested); assertFalse(c.showProgress); assertEquals(1, p.stopped)
        assertEquals(ConnectionController.CANCELLED_MSG, c.ui.toast)
        c.connect(); p.link.value = LinkStatus(LinkState.STREAMING)
        c.disconnect()
        assertEquals(2, p.stopped); assertEquals(ConnectionController.DISCONNECTED_MSG, c.ui.toast)
        assertEquals(ConnPhase.IDLE, c.phase())
    }

    // ---------- 모델·수신기 잠금 ----------
    @Test fun modelAndDevice_lockedWhileConnectingOrConnected() {
        val p = P(); val c = ctl(p)
        c.connect(); p.link.value = LinkStatus(LinkState.CONNECTING)
        c.selectProfile(DeviceProfiles.SP60); assertEquals(DeviceProfiles.R10_ID, c.profile.id)
        assertEquals(ConnectionController.LOCKED_MSG, c.ui.toast)
        c.selectAddress("CC:DD"); assertEquals("AA:BB", c.address)
        p.link.value = LinkStatus(LinkState.STREAMING)
        c.selectProfile(DeviceProfiles.SP60); assertEquals(DeviceProfiles.R10_ID, c.profile.id)
        // 실패하면 바꿀 수 있음(HTML: none / fail 만 허용)
        p.link.value = LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT)
        c.selectAddress("CC:DD"); assertEquals("CC:DD", c.address)
        c.disconnect(); c.selectProfile(DeviceProfiles.SP60); assertEquals(DeviceProfiles.SP60_ID, c.profile.id)
    }

    // ---------- 진행 창 닫기 ----------
    @Test fun dismissProgress_hintOnce_andDoneClosesScreen() {
        val p = P(); val c = ctl(p)
        c.openScreen(); c.connect(); p.link.value = LinkStatus(LinkState.CONNECTING)
        assertTrue(c.showProgress)
        c.dismissProgress(done = false)
        assertFalse(c.showProgress); assertTrue("연결 계속", c.connectRequested); assertEquals(0, p.stopped); assertTrue("화면 유지", c.open)
        assertEquals(ConnectionController.KEEP_HINT, c.ui.toast)
        val seq = c.ui.toastSeq
        c.showProgress = true; c.dismissProgress(done = false)
        assertEquals("안내는 처음 1회만", seq, c.ui.toastSeq)
        // 완료 = 「확인」: 진행 창 + 연결 화면 함께 닫힘
        c.showProgress = true; c.dismissProgress(done = c.progressDone(4))
        assertFalse(c.showProgress); assertFalse(c.open); assertTrue(c.connectRequested)
        assertFalse(c.progressDone(5)); assertTrue(c.progressDone(4))
    }

    @Test fun leaveScreen_whileConnecting_keepsConnection() {
        val p = P(); val c = ctl(p)
        c.openScreen(); c.connect(); p.link.value = LinkStatus(LinkState.CONNECTING); c.showProgress = false
        c.leaveScreen()
        assertFalse(c.open); assertTrue(c.connectRequested); assertEquals(0, p.stopped)
        assertEquals(ConnectionController.KEEP_HINT, c.ui.toast)
    }

    // ---------- 실패 문구(BT-130 회귀 금지) ----------
    @Test fun failHeadline_onlyForBluetoothFailure_notBt130() {
        val to = ConnectFailCopy.headline(DeviceProfiles.R10, LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT))!!
        assertEquals(ConnectFailCopy.TITLE, to.title); assertEquals("BT-120", to.code)
        assertTrue(to.action.contains("다른 폰에 연결"))
        val sp = ConnectFailCopy.headline(DeviceProfiles.SP60, LinkStatus(LinkState.NEEDS_USER, error = BtError.SOCKET_CLOSED))!!
        assertTrue("SP60 위성 추적 후 연결(field-learning §1.2)", sp.action.contains("위성을 잡은 뒤"))
        assertNull("BT-130은 기존 카드", ConnectFailCopy.headline(DeviceProfiles.SP60, LinkStatus(LinkState.NEEDS_USER, error = BtError.NO_DATA)))
        assertNull(ConnectFailCopy.headline(DeviceProfiles.R10, LinkStatus(LinkState.STREAMING)))
        // BT-130 단계 분리 유지: 블루투스 완료 · 위치 데이터 실패
        val st = ConnectSteps.compute(LinkStatus(LinkState.NEEDS_USER, error = BtError.NO_DATA), NtripStatus(), true, 0)
        assertEquals(StepState.DONE, st[0]); assertEquals(StepState.FAIL, st[1])
    }

    // ---------- 마운트 호환 필터 · R10 3.4 ----------
    private val list = listOf(
        MountInfo("VRS-RTCM31", "", "RTCM 3.1", "GPS", true), MountInfo("VRS-RTCM32", "", "RTCM 3.2", "GPS", true),
        MountInfo("VRS-RTCM34", "", "RTCM 3.4", "GPS", true), MountInfo("VRS-CMRx", "", "CMRx", "GPS", true),
    )

    @Test fun mountFilter_sp60HidesRtcm34_r10KeepsAndRecommends() {
        val sp = ctl(P(SavedConnection(DeviceProfiles.SP60_ID, "AA:BB", NtripForm(host = "RTS9.invalid", user = "u", password = "p"), true)))
        setMounts(sp, list)
        assertTrue(sp.mountCompatOnly)
        assertFalse(sp.filteredMounts().any { it.mountPoint == "VRS-RTCM34" })
        assertTrue("버전 모름(CMRx)은 남김", sp.filteredMounts().any { it.mountPoint == "VRS-CMRx" })
        assertEquals(1, sp.hiddenIncompatibleCount())
        assertEquals("권장", sp.mountGoodNote(list[1]))
        sp.mountCompatOnly = false
        assertTrue(sp.filteredMounts().any { it.mountPoint == "VRS-RTCM34" }); assertEquals(0, sp.hiddenIncompatibleCount())

        val r10 = ctl(); setMounts(r10, list)
        assertTrue(r10.filteredMounts().any { it.mountPoint == "VRS-RTCM34" })
        assertEquals(0, r10.hiddenIncompatibleCount())
        assertEquals("권장 · R10 3.4 사용 가능", r10.mountGoodNote(list[2]))
        assertNull(r10.mountCompatNote(list[2]))
    }

    private fun setMounts(c: ConnectionController, l: List<MountInfo>) {
        val f = ConnectionController::class.java.getDeclaredField("mounts\$delegate"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") (f.get(c) as androidx.compose.runtime.MutableState<List<MountInfo>>).value = l
    }

    // ---------- 보안 ----------
    @Test fun password_neverInToString_orLogs() {
        val form = NtripForm(user = "kim", password = "secretPW1")
        assertFalse(form.toString().contains("secretPW1")); assertTrue(form.toString().contains("***"))
        assertFalse(form.toConfig().toString().contains("secretPW1"))
        assertEquals("secretPW1", form.toConfig().password) // 실제 값은 그대로(접속용)
        val leaky = "x password=secretPW1, pw: abc123 \"password\":\"q1w2\" 비밀번호=zz9 Authorization: Basic dXNlcjpwYXNz\n"
        val r = CrashLog.redact(leaky)
        for (bad in listOf("secretPW1", "abc123", "q1w2", "zz9", "dXNlcjpwYXNz")) assertFalse(bad, r.contains(bad))
        val diag = diagnosticsFrom("=== 2026 · 연결 진단 · ${NtripConfig(username = "u", password = "secretPW1")} password=secretPW1\n")
        assertFalse(diag.contains("secretPW1"))
    }

    @Test fun successToast_hasNoPassword() {
        val p = P(); val c = ctl(p)
        c.connect(); c.onStatus(LinkStatus(LinkState.STREAMING), NtripStatus(NtripState.RECEIVING))
        assertTrue(c.ui.toast.orEmpty().contains("연결 설정 저장됨"))
        assertFalse(c.ui.toast.orEmpty().contains("secretPW1"))
    }

    /** HTML 알림 표: 수신기 연결 끊김 = 길게 3회 */
    @Test fun linkLost_vibratesLongThreeTimes() {
        val pulses = kr.archsurvey.feedback.FeedbackEvent.LINK_LOST.pattern.toList().drop(1).filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(3, pulses.size); assertTrue(pulses.all { it >= 500 })
    }
}
