package kr.archsurvey.comm

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtConnectException
import kr.archsurvey.bt.BtError
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.gst
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.engine.LinkTransport
import kr.archsurvey.feedback.FeedbackEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 목 소켓: 시험이 넣어준 바이트를 read 로 내주고, close() 되면 막힌 read 를 깨운다(블루투스 소켓과 같은 동작). */
class MockSocket(
    private val connectBehavior: (MockSocket) -> Unit = {},
    private val writeBehavior: (MockSocket, ByteArray, Int, Int) -> Unit = { s, b, o, n -> s.written.write(b, o, n) },
) : LinkTransport {
    private val q = LinkedBlockingQueue<Any>() // ByteArray | EOF | CLOSED
    val written = ByteArrayOutputStream()
    val closed = AtomicBoolean(false)
    val connectEntered = AtomicBoolean(false)
    val readerExited = AtomicBoolean(false)
    override val strategyName: String? = "SECURE_SPP"
    fun push(s: String) = q.put(s.toByteArray())
    fun push(b: ByteArray) = q.put(b)
    fun eof() = q.put(EOF)

    override val input = object : InputStream() {
        private var cur: ByteArray? = null; private var pos = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            try {
                if (cur == null || pos >= cur!!.size) {
                    when (val x = q.take()) {
                        EOF -> return -1
                        CLOSED -> throw IOException("bt socket closed, read return: -1")
                        else -> { cur = x as ByteArray; pos = 0 }
                    }
                }
                val n = minOf(len, cur!!.size - pos); System.arraycopy(cur!!, pos, b, off, n); pos += n; return n
            } finally { if (closed.get()) readerExited.set(true) }
        }
    }
    override val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { if (closed.get()) throw IOException("Broken pipe"); writeBehavior(this@MockSocket, b, off, len) }
    }
    override fun connect() { connectEntered.set(true); connectBehavior(this) }
    override fun close() { if (closed.compareAndSet(false, true)) q.put(CLOSED) }
    companion object { private val EOF = Any(); private val CLOSED = Any() }
}

class LinkSupervisorTests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun profile(stale: Long = 300, dead: Long = 700, initial: Long = 100, max: Long = 400) = DeviceProfiles.R10.copy(
        staleAfterMs = stale, reconnectAfterMs = dead, rtcmWriteChunk = 64,
        reconnect = ReconnectPolicy(initialDelayMs = initial, maxDelayMs = max, settleMs = 0, repairHintAfterFailures = 3),
    )

    private class Rig(val profile: DeviceProfile, val scope: CoroutineScope, stableAfterMs: Long = 20_000, writeStallMs: Long = 3_000, factory: (Int) -> MockSocket) {
        val link = MutableStateFlow(LinkStatus())
        val gnss = MutableStateFlow(GnssSnapshot())
        val rtcm = Channel<ByteArray>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val events = CopyOnWriteArrayList<FeedbackEvent>()
        val gga = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<MockSocket>()
        val createdAt = CopyOnWriteArrayList<Long>()
        val errors = CopyOnWriteArrayList<BtError>()
        /** 새 소켓을 만드는 순간의 연속 실패 횟수(소켓이 곧바로 끊기는 시험에서 경쟁 없이 읽으려고 기록) */
        val failuresAtCreate = CopyOnWriteArrayList<Int>()
        val sup: LinkSupervisor = LinkSupervisor(profile, {
            failuresAtCreate.add(this.sup.failures)
            val s = factory(sockets.size); sockets.add(s); createdAt.add(System.currentTimeMillis()); s
        }, { System.nanoTime() / 1_000_000 }, link, gnss, rtcm, wake, { events.add(it) }, { gga.add(it) },
            watchdogPeriodMs = 50, writeStallMs = writeStallMs, stableAfterMs = stableAfterMs)
        val job = scope.launch { sup.run() }
        init { scope.launch { link.collect { st -> st.error?.let { if (errors.lastOrNull() != it) errors.add(it) } } } }
        fun waitFor(ms: Long = 4000, what: String = "", cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
            fail("timeout waiting: $what link=${link.value} sockets=${sockets.size} errors=$errors")
        }
    }

    @Test fun streamsNmeaAndRelaysRtcmToReceiverInChunks() {
        val r = Rig(profile(), scope) { MockSocket() }
        r.waitFor(what = "socket") { r.sockets.size == 1 }
        r.sockets[0].push(gga() + gst())
        r.waitFor(what = "streaming") { r.link.value.state == LinkState.STREAMING }
        assertEquals(1, r.gga.size); assertNotNull(r.gnss.value.gga)
        val f = rtcmFrame(1077, 200)
        r.rtcm.trySend(f)
        r.waitFor(what = "written") { r.sockets[0].written.size() >= f.size }
        assertArrayEquals(f, r.sockets[0].written.toByteArray())
    }

    @Test fun eofTriggersReconnect_andEventsAreEmitted() {
        val r = Rig(profile(), scope) { MockSocket() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.sockets[0].eof()
        r.waitFor(what = "second socket") { r.sockets.size == 2 }
        assertTrue(r.sockets[0].closed.get())
        r.sockets[1].push(gga("092752.00"))
        r.waitFor(what = "restored") { r.link.value.state == LinkState.STREAMING }
        assertTrue(FeedbackEvent.LINK_LOST in r.events); assertTrue(FeedbackEvent.LINK_RESTORED in r.events)
    }

    @Test fun flappingReceiverDoesNotResetBackoff() {
        // 매번 GGA 한 개만 보내고 곧바로 끊는 수신기. (이전 구현은 데이터가 오면 백오프를 0 으로 되돌려 대기 없이 계속 재연결했다)
        val r = Rig(profile(initial = 150, max = 2000), scope) { MockSocket().also { s -> s.push(gga()); s.eof() } }
        r.waitFor(ms = 6000, what = "5 reconnects") { r.sockets.size >= 5 }
        val gaps = r.createdAt.zipWithNext { a, b -> b - a }
        assertTrue("간격이 늘어나야 함: $gaps", gaps[3] > gaps[1] && gaps[1] >= 100)
        assertTrue("마지막 간격이 첫 간격의 2배 이상: $gaps", gaps[3] >= gaps[0] * 2 - 50)
    }

    @Test fun failureCounterResetsOnlyAfterStableSession() {
        // 0번: 300ms 이상 안정적으로 받다 끊김(안정 기준 200ms) / 1번: GGA 한 개 보내고 곧바로 끊김(불안정)
        val r = Rig(profile(initial = 100, max = 400), scope, stableAfterMs = 200) { i ->
            MockSocket().also { s -> if (i == 1) { s.push(gga()); s.eof() } }
        }
        r.waitFor { r.sockets.size >= 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        Thread.sleep(350)
        r.sockets[0].eof()
        r.waitFor(what = "socket 1") { r.sockets.size == 2 }
        r.waitFor(what = "socket 2") { r.sockets.size == 3 }
        assertEquals("첫 연결 때 0", 0, r.failuresAtCreate[0])
        assertEquals("안정적으로 받은 뒤 끊기면 실패 횟수는 1(초기화 후 +1)", 1, r.failuresAtCreate[1])
        assertEquals("불안정(바로 끊김)하면 누적", 2, r.failuresAtCreate[2])
    }

    @Test fun silentSocketIsDetectedAsPowerOrRange_andSocketClosedToUnblockRead() {
        val r = Rig(profile(stale = 200, dead = 500), scope) { MockSocket() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        // 이후 아무것도 오지 않음: 소켓은 안 닫혔는데 read 가 영원히 블로킹되는 상황
        r.waitFor(what = "stale") { r.link.value.state == LinkState.STALE }
        r.waitFor(what = "new socket after dead") { r.sockets.size == 2 }
        assertTrue("블로킹된 read 를 깨우려고 소켓을 닫아야 함", r.sockets[0].closed.get())
        r.waitFor(what = "reader exit") { r.sockets[0].readerExited.get() }
        assertTrue(BtError.POWER_OR_RANGE in r.errors)
        assertTrue(r.link.value.state != LinkState.STREAMING)
    }

    @Test fun noSentencesAtAllMeansNmeaNotEnabled_needsUser_noRetryStorm() {
        val r = Rig(profile(dead = 400), scope) { MockSocket() }
        r.waitFor(what = "needs user") { r.link.value.state == LinkState.NEEDS_USER }
        assertEquals(BtError.NO_DATA, r.link.value.error)
        Thread.sleep(600)
        assertEquals("사용자 조치 전까지 재연결 폭주 금지", 1, r.sockets.size)
        r.wake.trySend(Unit)
        r.waitFor { r.sockets.size == 2 }
    }

    @Test fun stalledRtcmWriteIsDetected() {
        val blockForever = Object()
        val r = Rig(profile(dead = 5000, stale = 4000), scope, writeStallMs = 300) { i ->
            if (i == 0) MockSocket(writeBehavior = { s, _, _, _ -> synchronized(blockForever) { while (!s.closed.get()) blockForever.wait(50) }; throw IOException("closed") })
            else MockSocket()
        }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.rtcm.trySend(rtcmFrame(1077, 100))
        r.waitFor(what = "second socket after stall") { r.sockets.size == 2 }
        assertTrue(BtError.WRITE_STALLED in r.errors)
        assertTrue(r.sockets[0].closed.get())
    }

    @Test fun staleRtcmQueuedWhileDisconnectedIsNotReplayedAfterReconnect() {
        val r = Rig(profile(), scope) { MockSocket() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.sockets[0].eof()
        r.waitFor(what = "second") { r.sockets.size == 2 }
        // 끊긴 동안 쌓인 낡은 보정은 새 연결 때 버려진다 (연결 시점에 비움). 새 데이터만 전송.
        val fresh = rtcmFrame(1005, 19)
        r.sockets[1].push(gga("092753.00")); r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.rtcm.trySend(fresh)
        r.waitFor(what = "fresh written") { r.sockets[1].written.size() >= fresh.size }
        assertArrayEquals(fresh, r.sockets[1].written.toByteArray())
    }

    @Test fun cancelDuringBlockedConnectReturnsPromptlyByClosingSocket() {
        val gate = AtomicBoolean(false)
        val r = Rig(profile(), scope) { MockSocket(connectBehavior = { s -> while (!s.closed.get()) Thread.sleep(10); throw BtConnectException(BtError.SOCKET_CLOSED) }) }
        r.waitFor(what = "connect entered") { r.sockets.firstOrNull()?.connectEntered?.get() == true }
        val t0 = System.currentTimeMillis()
        r.job.cancel()
        runBlocking { withTimeout(2000) { r.job.join() } }
        assertTrue("취소가 12초 connect 를 기다리면 안 됨", System.currentTimeMillis() - t0 < 1500)
        assertTrue(r.sockets[0].closed.get())
        assertEquals(LinkState.IDLE, r.link.value.state)
        assertEquals(1, r.sockets.size)
    }

    @Test fun cancelDuringStreamingClosesSocketAndEndsAllChildren() {
        val r = Rig(profile(), scope) { MockSocket() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga()); r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.job.cancel(); runBlocking { withTimeout(2000) { r.job.join() } }
        assertTrue(r.sockets[0].closed.get())
        r.waitFor(what = "reader exit") { r.sockets[0].readerExited.get() }
        assertTrue("취소 뒤에는 새 소켓을 만들지 않는다", r.sockets.size == 1)
    }

    @Test fun btOffIsUserActionNotRetryLoop() {
        val r = Rig(profile(), scope) { MockSocket(connectBehavior = { throw BtConnectException(BtError.BT_OFF) }) }
        r.waitFor(what = "needs user") { r.link.value.state == LinkState.NEEDS_USER }
        Thread.sleep(500)
        assertEquals(1, r.sockets.size)
        assertEquals(BtError.BT_OFF, r.link.value.error)
        r.wake.trySend(Unit); r.waitFor { r.sockets.size == 2 }
    }

    @Test fun repeatedTimeoutsAfterHavingConnectedBecomePowerOrRange() {
        val r = Rig(profile(), scope) { i ->
            if (i == 0) MockSocket().also { it.push(gga()) }
            else MockSocket(connectBehavior = { throw BtConnectException(BtError.CONNECT_TIMEOUT) })
        }
        r.waitFor { r.sockets.size == 1 }
        r.waitFor { r.link.value.state == LinkState.STREAMING }
        r.sockets[0].eof()
        r.waitFor(ms = 6000, what = "power or range") { BtError.POWER_OR_RANGE in r.errors }
        assertTrue(r.link.value.repairHint || r.link.value.state == LinkState.RECONNECTING)
    }
}
