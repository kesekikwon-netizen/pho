package kr.archsurvey.comm

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.engine.*
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.ntrip.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NtripSupervisorTests {
    private val cfg = NtripConfig(host = "h", port = 1, mountPoint = "VRS", username = "u", password = "p")

    /** 가상 시간 위에서 도는 가짜 접속. 호출 시각을 기록하고, script 가 동작을 정한다. */
    private class Fake(val now: () -> Long, val script: suspend Fake.(call: Int, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit) -> Nothing) : NtripRunner {
        val callTimes = ArrayList<Long>()
        override suspend fun run(cfg: NtripConfig, ggaIntervalMs: Long, noDataTimeoutMs: Long, ggaProvider: () -> String?, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit): Nothing {
            callTimes.add(now())
            this.script(callTimes.size, onState, onRtcm)
        }
    }

    private class Rig(val ts: TestScope, link: Boolean = true, scriptBuilder: (Rig) -> suspend Fake.(Int, (NtripState) -> Unit, (ByteArray, Int) -> Unit) -> Nothing) {
        val clock: () -> Long = { ts.testScheduler.currentTime + 1_000 }
        val status = MutableStateFlow(NtripStatus())
        val linkOk = MutableStateFlow(link)
        val frames = ArrayList<ByteArray>()
        val events = ArrayList<FeedbackEvent>()
        val fake = Fake(clock, scriptBuilder(this))
        val sup = NtripSupervisor(ts.backgroundScope, status, clock, linkOk, { "\$GPGGA,x" }, { frames.add(it) }, { events.add(it) }, fake,
            pauseAfterLinkLossMs = 60_000, stableAfterMs = 15_000, noDataTimeoutMs = 10_000, notRtcmAfterBytes = 3_000)
        fun feed(onRtcm: (ByteArray, Int) -> Unit, f: ByteArray) = onRtcm(f, f.size)
    }

    @Test fun disabledOrUnusableConfigStaysIdleAndNeverConnects() = runTest {
        val r = Rig(this) { { _, _, _ -> awaitCancellation() } }
        r.sup.start(cfg, false, 5000); advanceTimeBy(5000)
        assertEquals(NtripState.IDLE, r.status.value.state); assertEquals("보정 사용 안 함", r.status.value.message)
        r.sup.start(cfg.copy(username = ""), true, 5000); advanceTimeBy(5000)
        assertTrue("VRS 정보" in r.status.value.message); assertEquals(0, r.fake.callTimes.size)
    }

    @Test fun waitsForReceiverPositionBeforeConnecting_savesData() = runTest {
        val r = Rig(this, link = false) { { _, st, _ -> st(NtripState.CONNECTED); awaitCancellation() } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(30_000)
        assertEquals(0, r.fake.callTimes.size)
        assertTrue(r.status.value.paused)
        r.linkOk.value = true; advanceTimeBy(100)
        assertEquals(1, r.fake.callTimes.size)
        assertFalse(r.status.value.paused)
    }

    @Test fun authFailureStopsRetrying_networkSignalDoesNotUnlock_manualRetryDoes() = runTest {
        val r = Rig(this) { { call, _, _ -> throw NtripAuthException(401, "아이디/비밀번호가 맞지 않아요 (401)") } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        assertEquals(1, r.fake.callTimes.size)
        assertEquals(NtripState.AUTH_FAILED, r.status.value.state)
        // 네트워크가 바뀌어도(Wi-Fi<->LTE) 틀린 비밀번호로 반복 접속하지 않는다(계정 잠금 방지)
        repeat(5) { r.sup.networkAvailable(); advanceTimeBy(60_000) }
        assertEquals(1, r.fake.callTimes.size)
        r.sup.retryNow(); advanceTimeBy(1000)
        assertEquals(2, r.fake.callTimes.size)
    }

    @Test fun mountErrorAlsoWaitsForUser() = runTest {
        val r = Rig(this) { { _, _, _ -> throw NtripMountException("마운트포인트 'VRS' 를 찾을 수 없어요") } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(120_000)
        assertEquals(1, r.fake.callTimes.size); assertEquals(NtripState.ERROR, r.status.value.state)
        assertTrue("마운트" in r.status.value.message)
    }

    @Test fun staleNetworkSignalAtStartDoesNotSkipFirstBackoffOrAuthHold() = runTest {
        val r = Rig(this) { { _, _, _ -> throw NtripAuthException(401, "x") } }
        r.sup.networkAvailable() // registerNetworkCallback 는 등록 직후 onAvailable 을 즉시 부른다
        r.sup.start(cfg, true, 5000); advanceTimeBy(10_000)
        assertEquals("낡은 신호로 인증 실패 보류가 풀려선 안 됨", 1, r.fake.callTimes.size)
    }

    @Test fun networkFailureBacksOffExponentiallyAndNetworkSignalShortensWait() = runTest {
        val r = Rig(this) { { _, _, _ -> throw NtripNetworkException("서버 주소를 찾지 못했어요", java.net.UnknownHostException()) } }
        r.sup.start(cfg, true, 5000)
        advanceTimeBy(60_000)
        val t = r.fake.callTimes
        val gaps = t.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L), gaps.take(4)) // 2,4,8,16초
        assertTrue(r.status.value.message, "UnknownHost" in r.status.value.message) // 기존 화면 문구 선택 단서
        // 네트워크 복구 신호 -> 대기 즉시 종료
        val before = r.fake.callTimes.size
        r.sup.networkAvailable(); runCurrent()
        assertEquals(before + 1, r.fake.callTimes.size)
    }

    @Test fun backoffCappedAt30s() = runTest {
        val r = Rig(this) { { _, _, _ -> throw NtripTimeoutException(NtripTimeoutException.Phase.CONNECT, "t") } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(600_000)
        val gaps = r.fake.callTimes.zipWithNext { a, b -> b - a }
        assertEquals(30_000L, gaps.last()); assertTrue(gaps.all { it <= 30_000 })
    }

    @Test fun validFramesRelayedAndStateReceiving_junkIgnored() = runTest {
        lateinit var rig: Rig
        rig = Rig(this) { { _, st, rtcm ->
            st(NtripState.CONNECTED)
            rtcm("junk".toByteArray(), 4)
            assertNotEquals("쓰레기만으로는 수신 중이 아니다", NtripState.RECEIVING, rig.status.value.state)
            val f = rtcmFrame(1077, 50); rtcm(f, f.size)
            awaitCancellation()
        } }
        rig.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        assertEquals(1, rig.frames.size)
        assertEquals(NtripState.RECEIVING, rig.status.value.state)
        assertEquals(1L, rig.status.value.framesOk)
        assertTrue(rig.status.value.lastRtcmAtMs > 0)
    }

    @Test fun nonRtcmStreamIsRejectedAndNotForwarded() = runTest {
        val r = Rig(this) { { _, st, rtcm ->
            st(NtripState.CONNECTED)
            val html = ByteArray(1000) { 'x'.code.toByte() }
            repeat(10) { rtcm(html, html.size) }
            awaitCancellation()
        } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        assertTrue(r.frames.isEmpty())
        assertEquals(NtripFailureKind.NOT_RTCM, r.status.value.failure)
        assertEquals(NtripState.RECONNECTING, r.status.value.state)
        val gap = 30_000L // 오류 페이지를 주는 서버에 1초마다 두들기지 않는다
        advanceTimeBy(gap - 2000); assertEquals(1, r.fake.callTimes.size)
    }

    @Test fun silentSocketDetectedAsNoData_afterTenSecondsWithoutValidFrame() = runTest {
        val r = Rig(this) { { _, st, rtcm ->
            st(NtripState.CONNECTED)
            val f = rtcmFrame(1005, 19); rtcm(f, f.size)
            awaitCancellation() // 이후 소켓은 살아있지만 데이터 없음
        } }
        r.sup.start(cfg, true, 5000)
        advanceTimeBy(9_000); assertEquals(NtripState.RECEIVING, r.status.value.state)
        advanceTimeBy(3_000)
        assertEquals(NtripState.RECONNECTING, r.status.value.state)
        assertEquals(NtripFailureKind.NO_DATA, r.status.value.failure)
        assertTrue("동안 오지 않아요" in r.status.value.message)
        assertTrue(FeedbackEvent.NTRIP_LOST in r.events)
        // 연령은 마지막 유효 프레임 기준
        assertNotNull(r.status.value.rtcmAgeMs(r.clock()))
    }

    @Test fun backoffResetsOnlyAfterStableReception() = runTest {
        // 1~3회: 2초 받고 끊김(불안정), 4회: 20초 받고 끊김(안정), 5회: 곧바로 실패
        val r = Rig(this) { { call, st, rtcm ->
            st(NtripState.CONNECTED)
            val f = rtcmFrame(1005, 19); rtcm(f, f.size)
            when (call) {
                in 1..3 -> { delay(2_000); throw java.net.SocketException("Connection reset") }
                4 -> { repeat(20) { delay(1_000); rtcm(f, f.size) }; throw java.net.SocketException("Connection reset") }
                else -> throw java.net.SocketException("Connection reset")
            }
        } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(200_000)
        val gaps = r.fake.callTimes.zipWithNext { a, b -> b - a }
        // 호출 간격 = 접속 유지 시간 + 대기. 대기 = 1,2,4(불안정이라 누적) -> 4회차 안정 -> 대기 1(초기화)
        assertEquals(2_000 + 1_000L, gaps[0]); assertEquals(2_000 + 2_000L, gaps[1]); assertEquals(2_000 + 4_000L, gaps[2])
        assertEquals("안정적으로 받은 뒤에는 백오프가 처음부터", 20_000 + 1_000L, gaps[3])
    }

    @Test fun pausesCasterWhenReceiverLinkLostForOver60s_andResumes() = runTest {
        var cancelled = 0
        val r = Rig(this) { { _, st, rtcm ->
            st(NtripState.CONNECTED); val f = rtcmFrame(1005, 19)
            try { while (true) { rtcm(f, f.size); delay(1_000) } } finally { cancelled++ }
            @Suppress("UNREACHABLE_CODE") awaitCancellation()
        } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(5_000)
        assertEquals(1, r.fake.callTimes.size)
        r.linkOk.value = false; advanceTimeBy(30_000)
        assertEquals("30초는 접속 유지", 0, cancelled)
        advanceTimeBy(31_000)
        assertEquals("60초 넘으면 캐스터 접속을 닫아 데이터 사용을 멈춘다", 1, cancelled)
        assertTrue(r.status.value.paused)
        val calls = r.fake.callTimes.size
        advanceTimeBy(120_000); assertEquals(calls, r.fake.callTimes.size)
        r.linkOk.value = true; advanceTimeBy(100)
        assertEquals(calls + 1, r.fake.callTimes.size)
    }

    @Test fun briefReceiverDropoutDoesNotDropCaster() = runTest {
        var cancelled = 0
        val r = Rig(this) { { _, st, rtcm ->
            st(NtripState.CONNECTED); val f = rtcmFrame(1005, 19)
            try { while (true) { rtcm(f, f.size); delay(1_000) } } finally { cancelled++ }
            @Suppress("UNREACHABLE_CODE") awaitCancellation()
        } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        r.linkOk.value = false; advanceTimeBy(20_000); r.linkOk.value = true; advanceTimeBy(1000)
        assertEquals(0, cancelled)
    }

    @Test fun restartingCancelsPreviousConnection() = runTest {
        var cancelled = 0
        val r = Rig(this) { { _, st, _ -> st(NtripState.CONNECTED); try { awaitCancellation() } finally { cancelled++ } } }
        r.sup.start(cfg, true, 5000); advanceTimeBy(1000)
        r.sup.start(cfg.copy(mountPoint = "X"), true, 5000); advanceTimeBy(1000)
        assertEquals(1, cancelled); assertEquals(2, r.fake.callTimes.size)
        r.sup.stop(); advanceTimeBy(1000)
        assertEquals(2, cancelled); assertFalse(r.sup.active)
    }
}
