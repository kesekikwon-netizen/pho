package kr.archsurvey.t0133

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.Attempt
import kr.archsurvey.bt.BtError
import kr.archsurvey.bt.Strategy
import kr.archsurvey.bt.planAttempts
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.comm.MockSocket
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.ui.ErrorCards
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0.1.33-r10 현장(2026-10-11): R10 은 GGA 를 받다가 1~2분마다 세션이 다시 시작됐다. R10 에 쓰는 것은 RTCM 뿐이라,
 * 수신기가 RTCM 을 안 받으면 쓰기가 막혀(3 s) 세션을 끊고 → 다시 연결해도 같은 자리에서 또 막혔다.
 * 위치가 계속 오면 세션은 유지하고 보정 전달만 멈춘 뒤 안내한다.
 */
class R10RtcmStall0133Tests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun prof() = DeviceProfiles.R10.copy(
        staleAfterMs = 600, reconnectAfterMs = 1_500, noDataHoldMs = 0, nmeaHealAttempts = 0, rtcmWriteGapMs = 0,
        reconnect = ReconnectPolicy(initialDelayMs = 100, maxDelayMs = 400, settleMs = 0, repairHintAfterFailures = 3),
    )

    private class Rig(p: DeviceProfile, scope: CoroutineScope, writeStallMs: Long, factory: (Int) -> MockSocket) {
        val link = MutableStateFlow(LinkStatus())
        val rtcm = Channel<ByteArray>(64)
        val sockets = CopyOnWriteArrayList<MockSocket>()
        val ends = CopyOnWriteArrayList<LinkSupervisor.SessionEnd>()
        val gga = CopyOnWriteArrayList<String>()
        val job = scope.launch {
            LinkSupervisor(p, { factory(sockets.size).also { sockets.add(it) } }, { System.nanoTime() / 1_000_000 },
                link, MutableStateFlow(GnssSnapshot()), rtcm, Channel(Channel.CONFLATED), {}, { gga.add(it) },
                watchdogPeriodMs = 50, writeStallMs = writeStallMs, onSessionEnd = { ends.add(it) }).run()
        }
        fun waitFor(ms: Long = 5000, what: String, c: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (c()) return; Thread.sleep(10) }
            fail("timeout $what link=${link.value} sockets=${sockets.size} ends=$ends")
        }
    }

    @Test fun rtcmStallWhilePositionFlows_keepsSession_thenResumesWithFreshCorrections() {
        val blocked = AtomicBoolean(true)
        val r = Rig(prof(), scope, writeStallMs = 300) {
            MockSocket(writeBehavior = { s, b, o, n ->
                while (blocked.get() && !s.closed.get()) Thread.sleep(10)
                if (s.closed.get()) throw IOException("closed")
                s.written.write(b, o, n)
            })
        }
        r.waitFor(what = "socket") { r.sockets.size == 1 }
        val feeder = scope.launch(Dispatchers.IO) {
            var i = 0
            while (isActive) { r.sockets[0].push(gga("0927%02d.00".format(i++ % 60))); delay(100) }
        }
        r.waitFor(what = "streaming") { r.link.value.state == LinkState.STREAMING }
        val f1 = rtcmFrame(1077, 100)
        r.rtcm.trySend(f1)
        r.waitFor(what = "rtcm blocked notice") { r.link.value.error == BtError.WRITE_STALLED }
        assertEquals(LinkState.STREAMING, r.link.value.state)
        assertEquals(LinkSupervisor.RTCM_BLOCKED_MSG, r.link.value.message)

        val ggaBefore = r.gga.size
        Thread.sleep(1_200) // 막힘 판정(300 ms)·stale(600 ms)보다 충분히 김
        assertEquals("위치가 오는 동안 새 연결을 만들면 안 됨", 1, r.sockets.size)
        assertTrue("세션이 끝나면 안 됨: ${r.ends}", r.ends.isEmpty())
        assertTrue("막힌 동안에도 위치는 계속 와야 함", r.gga.size > ggaBefore + 5)

        // 막힌 동안 쌓인 보정은 풀리면 버리고, 새 보정부터 보낸다
        r.rtcm.trySend(rtcmFrame(1077, 50, fill = 0x11)); r.rtcm.trySend(rtcmFrame(1077, 50, fill = 0x22))
        blocked.set(false)
        r.waitFor(what = "notice cleared") { r.link.value.state == LinkState.STREAMING && r.link.value.error == null }
        val f4 = rtcmFrame(1005, 19)
        r.rtcm.trySend(f4)
        r.waitFor(what = "fresh written") { r.sockets[0].written.size() >= f1.size + f4.size }
        assertArrayEquals(f1 + f4, r.sockets[0].written.toByteArray())
        assertEquals(1, r.sockets.size)
        feeder.cancel()
    }

    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val r10Advertised = UUID.fromString("00001103-0000-1000-8000-00805f9b34fb") // 현장 폰의 R10 페어링 기록

    @Test fun planAttempts_r10AdvertisingOnly1103_triesItFirst() {
        assertEquals(
            listOf(Attempt(Strategy.SDP_UUID, r10Advertised), Attempt(Strategy.SECURE_SPP), Attempt(Strategy.INSECURE_SPP), Attempt(Strategy.REFLECT_CH1)),
            planAttempts(listOf(r10Advertised), null, emptySet()),
        )
    }

    @Test fun planAttempts_sppAdvertisedOrUnknown_keepsOldOrder() {
        assertEquals(Strategy.SECURE_SPP, planAttempts(emptyList(), null, emptySet()).first().strategy)
        assertEquals(
            listOf(Attempt(Strategy.SECURE_SPP), Attempt(Strategy.INSECURE_SPP), Attempt(Strategy.SDP_UUID, r10Advertised), Attempt(Strategy.REFLECT_CH1)),
            planAttempts(listOf(spp, r10Advertised), null, emptySet()),
        )
    }

    @Test fun planAttempts_preferredAndSilentExclusionStillApply() {
        assertEquals(Strategy.INSECURE_SPP, planAttempts(listOf(r10Advertised), Strategy.INSECURE_SPP, emptySet()).first().strategy)
        val p = planAttempts(listOf(r10Advertised), null, setOf(Strategy.SDP_UUID))
        assertTrue(p.none { it.strategy == Strategy.SDP_UUID })
        assertEquals(Strategy.SECURE_SPP, p.first().strategy)
    }

    @Test fun streamingWithRtcmBlocked_showsReceiverInputCard() {
        val cards = ErrorCards.from(LinkStatus(LinkState.STREAMING, error = BtError.WRITE_STALLED, message = LinkSupervisor.RTCM_BLOCKED_MSG), null, false)
        assertEquals(BtError.WRITE_STALLED.code, cards.first().code)
        assertTrue("Input" in cards.first().action && "R10" in cards.first().action)
        assertTrue(ErrorCards.from(LinkStatus(LinkState.STREAMING), null, false).isEmpty())
    }
}
