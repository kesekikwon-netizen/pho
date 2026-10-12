package kr.archsurvey.comm

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.engine.LinkTransport
import kr.archsurvey.engine.NtripRunner
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.engine.NtripSupervisor
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.ntrip.NtripAuthException
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.ConnectionController
import kr.archsurvey.ui.ConnectionPlatform
import kr.archsurvey.ui.NtripForm
import kr.archsurvey.ui.SavedConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.1.19 게이트 QA-0119-1/2: 성공 전 실패는 CONNECT_FAIL 한 번만, 성공 뒤 끊김은 LINK_LOST/NTRIP_LOST 만.
 * 엔진 감독자(emit) + ConnectionController(onFeedback) 를 함께 돌려 실패계열 소리 개수를 센다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoubleBeep0119Tests {
    private val failSounds = setOf(FeedbackEvent.CONNECT_FAIL, FeedbackEvent.LINK_LOST, FeedbackEvent.NTRIP_LOST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private class P(ntripEnabled: Boolean) : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        private val s = SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "u", password = "p", enabled = ntripEnabled), true)
        override fun bondedDevices() = listOf("R10" to "AA:BB")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() {}
        override fun loadSaved() = s
        override fun save(s: SavedConnection) {}
    }

    private fun controller(ntripEnabled: Boolean, sink: MutableList<FeedbackEvent>) =
        ConnectionController(P(ntripEnabled)).also { c ->
            for (i in c.checks.indices) c.setCheck(i, true)
            c.onFeedback = { sink.add(it) }
            c.connect()
            assertTrue(c.connectRequested)
        }

    // ---------------- QA-0119-2: 수신기 소켓만 붙고(GGA 전) 사용자 조치 실패 ----------------

    /** connect 는 되지만 첫 read 에서 권한 오류(SecurityException → PERMISSION_NEARBY, 사용자 조치) */
    private class SocketOnlyThenPermission : LinkTransport {
        override val strategyName: String? = "SECURE_SPP"
        override val input = object : InputStream() {
            override fun read(): Int = throw SecurityException("need BLUETOOTH_CONNECT")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("need BLUETOOTH_CONNECT")
        }
        override val output = object : OutputStream() { override fun write(b: Int) {} }
        override fun connect() {}
        override fun close() {}
    }

    private class LinkRig(scope: CoroutineScope, newT: (Int) -> LinkTransport) {
        val link = MutableStateFlow(LinkStatus())
        val events = CopyOnWriteArrayList<FeedbackEvent>()
        val sockets = CopyOnWriteArrayList<LinkTransport>()
        val sup = LinkSupervisor(
            DeviceProfiles.R10.copy(
                staleAfterMs = 300, reconnectAfterMs = 700, rtcmWriteChunk = 64, noDataHoldMs = 0, nmeaHealAttempts = 0,
                reconnect = ReconnectPolicy(initialDelayMs = 100, maxDelayMs = 400, settleMs = 0, repairHintAfterFailures = 3),
            ),
            { newT(sockets.size).also { sockets.add(it) } }, { System.nanoTime() / 1_000_000 }, link, MutableStateFlow(GnssSnapshot()),
            Channel(64), Channel(Channel.CONFLATED), { events.add(it) }, {}, watchdogPeriodMs = 50,
        )
        val job = scope.launch { sup.run() }
        fun waitFor(ms: Long = 4000, what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
            fail("timeout: $what link=${link.value} events=$events")
        }
    }

    @Test fun qa0119_2_socketOnlyThenNeedsUser_onlyConnectFail() {
        val r = LinkRig(scope) { SocketOnlyThenPermission() }
        r.waitFor(what = "needs user") { r.link.value.state == LinkState.NEEDS_USER }
        Thread.sleep(150) // 늦게 오는 emit 이 있으면 잡히도록
        assertFalse("성공(STREAMING) 전 소켓만 붙었던 실패에 LINK_LOST 금지: ${r.events}", FeedbackEvent.LINK_LOST in r.events)

        val ui = ArrayList<FeedbackEvent>()
        val c = controller(ntripEnabled = false, sink = ui)
        c.onStatus(r.link.value, NtripStatus())
        val all = r.events + ui
        assertEquals("실패음은 CONNECT_FAIL 한 번: $all", listOf(FeedbackEvent.CONNECT_FAIL), all.filter { it in failSounds })
        r.job.cancel()
    }

    @Test fun qa0119_2_socketOnlyDropThenBtOff_noLinkLostBeforeSuccess() {
        // 0번: 소켓만 붙고 바로 EOF(자동 재연결) → 1번: 블루투스 꺼짐(사용자 조치). 성공 전이므로 LINK_LOST 없음
        val r = LinkRig(scope) { i ->
            if (i == 0) MockSocket().also { it.eof() }
            else MockSocket(connectBehavior = { throw kr.archsurvey.bt.BtConnectException(kr.archsurvey.bt.BtError.BT_OFF) })
        }
        r.waitFor(what = "needs user") { r.link.value.state == LinkState.NEEDS_USER }
        assertTrue(r.sockets.size >= 2)
        assertFalse("소켓만 붙었다 끊긴 것은 LINK_LOST 아님: ${r.events}", FeedbackEvent.LINK_LOST in r.events)
        assertFalse(FeedbackEvent.LINK_RESTORED in r.events)
        r.job.cancel()
    }

    @Test fun regression_postSuccessDrop_linkLostOnly_noConnectFail() {
        val r = LinkRig(scope) { i ->
            if (i == 0) MockSocket()
            else MockSocket(connectBehavior = { throw kr.archsurvey.bt.BtConnectException(kr.archsurvey.bt.BtError.BT_OFF) })
        }
        r.waitFor(what = "socket") { r.sockets.size == 1 }
        val ui = ArrayList<FeedbackEvent>()
        val c = controller(ntripEnabled = false, sink = ui)
        (r.sockets[0] as MockSocket).push(gga())
        r.waitFor(what = "streaming") { r.link.value.state == LinkState.STREAMING }
        c.onStatus(r.link.value, NtripStatus())
        assertEquals(listOf(FeedbackEvent.CONNECT_OK), ui)
        (r.sockets[0] as MockSocket).eof()
        r.waitFor(what = "needs user after drop") { r.link.value.state == LinkState.NEEDS_USER }
        c.onStatus(r.link.value, NtripStatus())
        val all = r.events + ui
        assertEquals("성공 뒤 끊김은 LINK_LOST 만: $all", listOf(FeedbackEvent.LINK_LOST), all.filter { it in failSounds })
        r.job.cancel()
    }

    // ---------------- QA-0119-1: VRS 인증 실패(비번 틀림) ----------------

    private val cfg = NtripConfig(host = "h", port = 1, mountPoint = "VRS", username = "u", password = "p")

    private class NtripRig(ts: TestScope, val script: suspend (call: Int, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit) -> Nothing) {
        val clock: () -> Long = { ts.testScheduler.currentTime + 1_000 }
        val status = MutableStateFlow(NtripStatus())
        val events = ArrayList<FeedbackEvent>()
        var calls = 0
        val sup = NtripSupervisor(ts.backgroundScope, status, clock, MutableStateFlow(true), { "\$GPGGA,x" }, {}, { events.add(it) },
            NtripRunner { _, _, _, _, st, rtcm -> calls++; script(calls, st, rtcm) },
            pauseAfterLinkLossMs = 60_000, stableAfterMs = 15_000, noDataTimeoutMs = 10_000, notRtcmAfterBytes = 3_000)
    }

    @Test fun qa0119_1_wrongPasswordBeforeAnyData_onlyConnectFail() = runTest {
        val r = NtripRig(this) { _, _, _ -> throw NtripAuthException(401, "아이디/비밀번호가 맞지 않아요 (401)") }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        assertEquals(NtripState.AUTH_FAILED, r.status.value.state)
        assertFalse("첫 수신 전 인증 실패에 NTRIP_LOST 금지: ${r.events}", FeedbackEvent.NTRIP_LOST in r.events)

        val ui = ArrayList<FeedbackEvent>()
        val c = controller(ntripEnabled = true, sink = ui)
        c.onStatus(LinkStatus(LinkState.STREAMING), r.status.value)
        val all = r.events + ui
        assertEquals("실패음은 CONNECT_FAIL 한 번: $all", listOf(FeedbackEvent.CONNECT_FAIL), all.filter { it in failSounds })
    }

    @Test fun qa0119_1_afterAuthFixRetry_success_noExtraNtripOk() = runTest {
        // 비번 틀림 → 사용자가 다시 시도 → 성공: 성공음은 CONNECT_OK 하나(NTRIP_OK 가 겹치지 않음)
        val f = rtcmFrame(1005, 19)
        val r = NtripRig(this) { call, st, rtcm ->
            if (call == 1) throw NtripAuthException(401, "x")
            st(NtripState.CONNECTED); rtcm(f, f.size); awaitCancellation()
        }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        r.sup.retryNow(); advanceTimeBy(1000)
        assertEquals(NtripState.RECEIVING, r.status.value.state)
        assertFalse("잃은 적 없는데 복구음 금지: ${r.events}", FeedbackEvent.NTRIP_OK in r.events)
    }

    @Test fun regression_postSuccessAuthFailure_ntripLostOnce_noConnectFail() = runTest {
        // 받다가(성공) 소켓 끊김 → 재접속에서 인증 실패(계정 만료 등): NTRIP_LOST 한 번, CONNECT_FAIL 없음
        val f = rtcmFrame(1005, 19)
        val r = NtripRig(this) { call, st, rtcm ->
            if (call == 1) { st(NtripState.CONNECTED); rtcm(f, f.size); delay(2_000); throw java.net.SocketException("Connection reset") }
            throw NtripAuthException(401, "x")
        }
        val ui = ArrayList<FeedbackEvent>()
        val c = controller(ntripEnabled = true, sink = ui)
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        c.onStatus(LinkStatus(LinkState.STREAMING), r.status.value)
        assertEquals(listOf(FeedbackEvent.CONNECT_OK), ui)
        advanceTimeBy(10_000)
        assertEquals(NtripState.AUTH_FAILED, r.status.value.state)
        c.onStatus(LinkStatus(LinkState.STREAMING), r.status.value)
        val all = r.events + ui
        assertEquals("성공 뒤에는 NTRIP_LOST 만: $all", listOf(FeedbackEvent.NTRIP_LOST), all.filter { it in failSounds })
    }

    @Test fun regression_postSuccessAuthFailureDirect_stillNtripLost() = runTest {
        // 받다가 서버가 401 로 끊음(첫 끊김이 AUTH) → 이전처럼 NTRIP_LOST 한 번
        val f = rtcmFrame(1005, 19)
        val r = NtripRig(this) { _, st, rtcm -> st(NtripState.CONNECTED); rtcm(f, f.size); delay(2_000); throw NtripAuthException(401, "x") }
        r.sup.start(cfg, true, 5000); advanceTimeBy(5_000)
        assertEquals(NtripState.AUTH_FAILED, r.status.value.state)
        assertEquals(listOf(FeedbackEvent.NTRIP_LOST), r.events)
    }

    @Test fun regression_vrsRestore_ntripLostThenNtripOk() = runTest {
        val f = rtcmFrame(1005, 19)
        val r = NtripRig(this) { call, st, rtcm ->
            st(NtripState.CONNECTED); rtcm(f, f.size)
            if (call == 1) { delay(2_000); throw java.net.SocketException("Connection reset") }
            awaitCancellation()
        }
        r.sup.start(cfg, true, 5000); advanceTimeBy(10_000)
        assertEquals(NtripState.RECEIVING, r.status.value.state)
        assertEquals(listOf(FeedbackEvent.NTRIP_LOST, FeedbackEvent.NTRIP_OK), r.events)
    }
}
