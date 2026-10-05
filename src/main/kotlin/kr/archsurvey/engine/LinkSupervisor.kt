package kr.archsurvey.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtConnectException
import kr.archsurvey.bt.BtError
import kr.archsurvey.bt.NmeaActivation
import kr.archsurvey.bt.NmeaActOutcome
import kr.archsurvey.diag.CrashLog
import kr.archsurvey.core.DataHealth
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.NmeaDriver
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.feedback.FeedbackEvent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 수신기 쪽 바이트 통로 하나(= 소켓 하나). 블루투스는 [kr.archsurvey.bt.BtTransport], 시험에서는 가짜 스트림. close() 는 멱등이어야 한다. */
interface LinkTransport {
    val input: InputStream
    val output: OutputStream
    val strategyName: String?
    /** 블로킹. IO 스레드에서 호출. 취소 시 [close] 가 불려 블로킹을 깨운다 */
    fun connect()
    fun close()
}

/**
 * 수신기 연결 감독: 연결 → NMEA 읽기 / 워치독 / RTCM 쓰기 → 끊김 분류 → 재연결(지수 백오프). Android 의존 없음.
 *
 * 0.1.13 BT-130 치유:
 * - NMEA 활성화가 끝날 때까지 DEAD로 소켓을 끊지 않는다(후보 명령이 reconnectAfterMs 보다 길 수 있음).
 * - 활성화 실패 뒤 [DeviceProfile.noDataHoldMs] 동안 소켓을 유지해 진단(TX/RX·바이트)을 설정에서 볼 수 있게 한다.
 * - 소켓만 되고 GGA가 없으면 [onSilentStrategy]로 알려 다음 치유에서 그 연결 방식을 뺀다.
 * - [onStrategyUsed]는 **GGA 수신(STREAMING)** 때만 호출 — 무음 SDP_UUID 를 선호값으로 저장하지 않는다.
 */
class LinkSupervisor(
    private val profile: DeviceProfile,
    private val newTransport: () -> LinkTransport,
    private val clock: () -> Long,
    private val link: MutableStateFlow<LinkStatus>,
    private val gnss: MutableStateFlow<GnssSnapshot>,
    private val rtcmQueue: ReceiveChannel<ByteArray>,
    private val wake: ReceiveChannel<Unit>,
    private val emit: (FeedbackEvent) -> Unit,
    private val onGga: (String) -> Unit,
    private val onQuality: (kr.archsurvey.core.FixQuality) -> Unit = {},
    private val onStrategyUsed: (String) -> Unit = {},
    /** BT-130(소켓 OK·GGA 없음)일 때 이번 연결 방식 이름. Engine 이 다음 시도에서 exclude 한다 */
    private val onSilentStrategy: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val watchdogPeriodMs: Long = 1_000,
    private val writeStallMs: Long = 3_000,
    private val stableAfterMs: Long = 20_000,
) {
    class SessionLost(val error: BtError, msg: String) : IOException(msg)

    @Volatile private var wasConnectedOnce = false
    @Volatile private var linkLostAnnounced = false
    @Volatile private var streamingSince = 0L
    @Volatile private var writeStartedAt = 0L
    /** 직전 세션 진단 요약(TX/RX·바이트·명령). BT-130 CrashLog 에 붙인다 */
    private val lastDiag = AtomicReference("")

    /** 지금까지 연속으로 실패한 횟수(안정적으로 받은 뒤 끊기면 0 부터 다시) */
    @Volatile var failures = 0; private set
    /** BT-130(소켓 OK·GGA 없음) 치유 재시도 횟수. STREAMING 되면 0 */
    @Volatile var nmeaHeals = 0; private set

    private fun setLink(state: LinkState, attempt: Int = link.value.attempt, failures: Int = link.value.failures,
                        err: BtError? = null, msg: String = "", repair: Boolean = false, strategy: String? = link.value.strategy) {
        link.value = LinkStatus(state, attempt, failures, err, msg, repair, strategy)
    }

    private suspend fun sleepOrWake(ms: Long) { withTimeoutOrNull(ms) { wake.receive() } }

    suspend fun run() {
        val policy = profile.reconnect
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                attempt++
                val wait = policy.delayBeforeAttempt(attempt)
                if (wait > 0) sleepOrWake(wait)
                setLink(if (attempt == 1 && !wasConnectedOnce) LinkState.CONNECTING else LinkState.RECONNECTING, attempt = attempt, failures = failures,
                    msg = if (attempt == 1 && !wasConnectedOnce) "연결하는 중…" else "다시 연결하는 중… (${attempt}번째)")
                val transport = newTransport()
                var err: BtError? = null
                streamingSince = 0L
                lastDiag.set("")
                try {
                    connectCancellable(transport)
                    // 소켓만 붙은 시점에 전략을 저장하지 않는다 — STREAMING 에서 onStrategyUsed
                    val driver = NmeaDriver(profile).also { it.reset(clock()) }
                    setLink(LinkState.CONNECTED_NO_DATA, attempt, failures, msg = "연결됨 — 데이터를 기다리는 중", strategy = transport.strategyName)
                    if (wasConnectedOnce && linkLostAnnounced) { emit(FeedbackEvent.LINK_RESTORED); linkLostAnnounced = false }
                    wasConnectedOnce = true
                    while (rtcmQueue.tryReceive().isSuccess) { } // 끊긴 사이 쌓인 낡은 보정은 버린다
                    runSession(driver, transport, attempt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = (e as? BtConnectException)?.error ?: if (e is SessionLost) e.error else BtError.classify(e)
                } finally {
                    transport.close()
                }
                // 안정적으로 받았던 연결이면 백오프를 처음부터. 잠깐 붙었다 끊기는 반복은 계속 늘어난다
                val streamedMs = if (streamingSince != 0L) clock() - streamingSince else 0L
                if (streamedMs >= stableAfterMs) { attempt = 0; failures = 0; nmeaHeals = 0 }
                failures++
                if (err == null) err = BtError.LINK_LOST
                err = BtError.refineAfterLoss(err, wasConnectedOnce, failures)
                if (wasConnectedOnce && !linkLostAnnounced && err != BtError.NO_DATA) { emit(FeedbackEvent.LINK_LOST); linkLostAnnounced = true }
                // BT-130: 소켓은 됨·GGA만 없음 → 이 연결 방식을 무음으로 표시 후 치유 재연결
                if (err == BtError.NO_DATA) {
                    transport.strategyName?.let { onSilentStrategy(it) }
                    val detail = lastDiag.get().ifBlank { "세션 진단 없음" }
                    CrashLog.record(
                        "진단 BT-130 치유예정=${nmeaHeals + 1}/${profile.nmeaHealAttempts} 연결방식=${transport.strategyName} 수신기=${profile.id} · $detail",
                        IllegalStateException(profile.noDataHint),
                    )
                }
                if (err == BtError.NO_DATA && nmeaHeals < profile.nmeaHealAttempts) {
                    nmeaHeals++
                    val waitHeal = profile.reconnect.delayBeforeAttempt(nmeaHeals + 1).coerceAtLeast(1_000)
                    setLink(LinkState.RECONNECTING, attempt, failures, err,
                        "위치(GGA) 없음 — NMEA 치유 ${nmeaHeals}/${profile.nmeaHealAttempts} · 다른 연결방식 시도 (${waitHeal / 1000}초 후)", false,
                        strategy = link.value.strategy)
                    sleepOrWake(waitHeal)
                    continue
                }
                if (err.needsUserAction) {
                    val msg = if (err == BtError.NO_DATA) "${err.title} (오류 ${err.code}) · ${NmeaActivation.fieldChecklist(profile)} · 진단은 설정·장비연결에서 확인"
                    else "${err.title} (오류 ${err.code})"
                    setLink(LinkState.NEEDS_USER, attempt, failures, err, msg, false)
                    wake.receive()
                    attempt = 0
                    continue
                }
                setLink(LinkState.RECONNECTING, attempt, failures, err,
                    "${err.title} — 자동으로 다시 연결해요 (오류 ${err.code})", policy.shouldShowRepairHint(failures))
                delay(policy.settleMs.coerceAtLeast(300))
            }
        } finally {
            link.value = LinkStatus()
        }
    }

    /** 블로킹 connect 를 IO 스레드에서 돌리되, 취소되면 소켓을 닫아 즉시 깨운다 */
    private suspend fun connectCancellable(t: LinkTransport) = withContext(io) {
        val connected = AtomicBoolean(false)
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } catch (e: CancellationException) { if (!connected.get()) t.close(); throw e }
        }
        try { t.connect(); connected.set(true) } finally { closer.cancel() }
    }

    /** 연결 수립 후 읽기/워치독/RTCM 쓰기·NMEA 자동 켜기. 끊기면 [SessionLost] 로 종료 */
    private suspend fun runSession(driver: NmeaDriver, t: LinkTransport, attempt: Int) = coroutineScope {
        var gotData = false
        writeStartedAt = 0L
        val outLock = Any()
        val ackSeen = AtomicBoolean(false)
        val bytesIn = AtomicLong(0)
        val bytesOutCmd = AtomicLong(0)
        val cmdLog = java.util.concurrent.CopyOnWriteArrayList<String>()
        val actRunning = AtomicBoolean(true)
        val actEndedAt = AtomicLong(0)
        var actSummary = ""
        fun safeWrite(b: ByteArray, off: Int = 0, len: Int = b.size) {
            synchronized(outLock) {
                writeStartedAt = clock()
                t.output.write(b, off, len)
                t.output.flush()
                writeStartedAt = 0L
            }
        }
        fun writeCmd(b: ByteArray) {
            safeWrite(b); bytesOutCmd.addAndGet(b.size.toLong())
            val line = b.toString(Charsets.US_ASCII).trim().take(80)
            if (line.isNotEmpty()) cmdLog.add("TX $line")
        }
        fun diagSnapshot(extra: String = ""): String {
            val rxHint = when {
                bytesIn.get() == 0L -> "수신0B"
                ackSeen.get() -> "수신${bytesIn.get()}B·ACK있음"
                else -> "수신${bytesIn.get()}B·ACK없음"
            }
            val cmds = cmdLog.takeLast(12).joinToString(" | ")
            val s = "방식=${t.strategyName} $rxHint 명령송신${bytesOutCmd.get()}B 문장=${driver.snapshot.sentencesTotal}" +
                (if (extra.isNotEmpty()) " $extra" else "") +
                if (cmds.isNotEmpty()) " · $cmds" else ""
            lastDiag.set(s)
            return s
        }
        // 취소 시 블로킹 read/write 를 깨우기 위해 소켓 close
        launch { try { awaitCancellation() } finally { t.close() } }
        // 읽기
        launch(io) {
            val buf = ByteArray(2048)
            var zeros = 0
            try {
                while (isActive) {
                    val n = t.input.read(buf)
                    if (n < 0) throw SessionLost(BtError.SOCKET_CLOSED, "EOF")
                    if (n == 0) { if (++zeros > 100) throw SessionLost(BtError.LINK_LOST, "read returned 0 repeatedly"); continue }
                    zeros = 0
                    bytesIn.addAndGet(n.toLong())
                    if (!ackSeen.get() && containsAscii(buf, n, "PASHR,ACK")) {
                        ackSeen.set(true); cmdLog.add("RX PASHR,ACK")
                    }
                    if (containsAscii(buf, n, "PASHR,NAK")) cmdLog.add("RX PASHR,NAK")
                    if (containsAscii(buf, n, "PASHR,PRT") || containsAscii(buf, n, "PASHR,NMO")) {
                        val snip = buf.copyOf(n).toString(Charsets.US_ASCII).replace("\r", " ").replace("\n", " ").take(100)
                        cmdLog.add("RX $snip")
                    }
                    val now = clock()
                    val msgs = driver.onBytes(buf, n, now)
                    if (msgs.isNotEmpty()) {
                        val snap = driver.snapshot
                        snap.gga?.let { if (it.raw.isNotEmpty()) onGga(it.raw) }
                        onQuality(snap.quality)
                        gnss.value = snap
                        if (!gotData && snap.gga != null) {
                            gotData = true; streamingSince = now; nmeaHeals = 0
                            t.strategyName?.let { onStrategyUsed(it) } // GGA 확인된 방식만 저장
                            setLink(LinkState.STREAMING, attempt, link.value.failures, msg = "수신 중", strategy = t.strategyName)
                        } else if (link.value.state == LinkState.STALE) {
                            setLink(LinkState.STREAMING, attempt, link.value.failures, msg = "수신 중", strategy = t.strategyName)
                        }
                    }
                }
            } catch (e: SessionLost) { throw e
            } catch (e: IOException) {
                if (!isActive) return@launch
                throw SessionLost(BtError.classify(e).let { if (it == BtError.UNKNOWN) BtError.LINK_LOST else it }, e.message ?: "read")
            }
        }
        // NMEA 자동 활성화
        launch(io) {
            try {
                val cmds = NmeaActivation.candidates(profile)
                val result = NmeaActivation.run(
                    cmds = cmds,
                    write = { writeCmd(it) },
                    hasGga = { driver.snapshot.gga != null },
                    clock = clock,
                    // R10(명령 없음): 짧게 감시만. SP60: 후보 전부 돌릴 시간
                    naturalWaitMs = if (cmds.isEmpty()) 800L else 3_000L,
                    perCmdWaitMs = 1_400L,
                    afterAllWaitMs = if (cmds.isEmpty()) 400L else 2_500L,
                    onStatus = { msg ->
                        if (link.value.state == LinkState.CONNECTED_NO_DATA)
                            setLink(LinkState.CONNECTED_NO_DATA, attempt, link.value.failures, msg = msg, strategy = t.strategyName)
                    },
                    sawAck = { ackSeen.get() },
                    clearAck = { ackSeen.set(false) },
                )
                actSummary = "NMEA ${result.outcome} ${result.elapsedMs}ms 시도${result.tried.size}개"
                val detail = diagSnapshot(actSummary) + " · " + result.log.takeLast(8).joinToString(" | ")
                CrashLog.record(
                    "NMEA 활성화 ${profile.id}: ${result.outcome} · ${result.elapsedMs}ms · 시도 ${result.tried.size}",
                    RuntimeException(detail + "\n" + result.log.joinToString("\n")),
                )
                if (result.outcome == NmeaActOutcome.GGA_SEEN || result.outcome == NmeaActOutcome.NATURAL_GGA) {
                    // 읽기 루프가 STREAMING 으로 올린다
                } else if (link.value.state == LinkState.CONNECTED_NO_DATA || link.value.state == LinkState.STALE) {
                    val holdSec = (profile.noDataHoldMs / 1000).coerceAtLeast(0)
                    setLink(LinkState.CONNECTED_NO_DATA, attempt, link.value.failures, BtError.NO_DATA,
                        "연결됨 · 위치(GGA) 없음 — ${NmeaActivation.fieldChecklist(profile)}" +
                            if (holdSec > 0) " · ${holdSec}초간 소켓 유지(진단 확인 가능)" else "",
                        strategy = t.strategyName)
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                CrashLog.record("NMEA 활성화 실패", e)
            } finally {
                actRunning.set(false)
                actEndedAt.set(clock())
                diagSnapshot(actSummary)
            }
        }
        // 워치독
        launch {
            while (isActive) {
                delay(watchdogPeriodMs)
                val now = clock()
                val ws = writeStartedAt
                if (ws != 0L && now - ws > writeStallMs) throw SessionLost(BtError.WRITE_STALLED, "write stalled ${now - ws}ms")
                when (driver.health(now)) {
                    DataHealth.STALE -> if (link.value.state != LinkState.STALE && !actRunning.get()) {
                        val s = driver.snapshot
                        val miss = driver.missingRequired().isNotEmpty() && s.sentencesTotal == 0L
                        val badRate = driver.parserStats.checksumErrorRate > 0.2
                        setLink(LinkState.STALE, attempt, link.value.failures,
                            if (miss) BtError.NO_DATA else if (badRate || s.checksumErrors > 5) BtError.BAD_CHECKSUM else null,
                            if (miss) profile.noDataHint else "데이터가 잠시 끊겼어요", strategy = t.strategyName)
                    }
                    DataHealth.DEAD -> {
                        if (actRunning.get()) continue
                        val ended = actEndedAt.get()
                        val hold = profile.noDataHoldMs
                        if (driver.snapshot.sentencesTotal == 0L && ended > 0L && hold > 0L && now - ended < hold) {
                            if (link.value.state != LinkState.CONNECTED_NO_DATA) {
                                setLink(LinkState.CONNECTED_NO_DATA, attempt, link.value.failures, BtError.NO_DATA,
                                    "연결 유지 중 · 진단 기록 확인 (${diagSnapshot()})", strategy = t.strategyName)
                            }
                            continue
                        }
                        throw SessionLost(
                            if (driver.snapshot.sentencesTotal == 0L) BtError.NO_DATA else BtError.POWER_OR_RANGE,
                            "no data · ${diagSnapshot(actSummary)}",
                        )
                    }
                    else -> {}
                }
            }
        }
        // RTCM 쓰기
        launch(io) {
            try {
                for (frame in rtcmQueue) {
                    var off = 0
                    while (off < frame.size) {
                        val n = minOf(profile.rtcmWriteChunk, frame.size - off)
                        safeWrite(frame, off, n)
                        off += n
                        if (profile.rtcmWriteGapMs > 0) delay(profile.rtcmWriteGapMs)
                    }
                }
            } catch (e: SessionLost) { throw e
            } catch (e: IOException) {
                if (isActive) throw SessionLost(BtError.WRITE_FAILED, e.message ?: "write")
            }
        }
        Unit
    }

    private fun containsAscii(buf: ByteArray, len: Int, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.size > len) return false
        outer@ for (i in 0..len - n.size) {
            for (j in n.indices) if (buf[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
