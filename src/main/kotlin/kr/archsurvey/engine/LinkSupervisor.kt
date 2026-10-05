package kr.archsurvey.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtConnectException
import kr.archsurvey.bt.BtError
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
 * 개선점(이전 Engine.gnssLoop 대비)
 * - 연결 시도(블로킹 connect, 최대 수십 초)도 취소하면 소켓을 닫아 즉시 깬다.
 * - 데이터가 한 번 왔다고 백오프를 초기화하지 않는다. [stableAfterMs] 이상 **계속** 받은 뒤 끊겼을 때만 초기화
 *   (수신기가 2~3초 붙었다 끊기기를 반복하면 이전에는 대기 없이 매번 재연결했다).
 * - 쓰기가 [writeStallMs] 넘게 막히면(수신기가 보정 입력을 안 읽음) 완료를 기다리지 않고 워치독이 잡는다.
 * - 끊김 원인 구분: 소켓이 닫힘(EOF/예외)=상대가 끊음, 무수신(워치독)=전원/범위 이탈 추정, 연속 실패=[BtError.POWER_OR_RANGE].
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

    /** 지금까지 연속으로 실패한 횟수(안정적으로 받은 뒤 끊기면 0 부터 다시) */
    @Volatile var failures = 0; private set

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
                try {
                    connectCancellable(transport)
                    transport.strategyName?.let { onStrategyUsed(it) }
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
                if (streamedMs >= stableAfterMs) { attempt = 0; failures = 0 }
                failures++
                if (err == null) err = BtError.LINK_LOST
                err = BtError.refineAfterLoss(err, wasConnectedOnce, failures)
                if (wasConnectedOnce && !linkLostAnnounced) { emit(FeedbackEvent.LINK_LOST); linkLostAnnounced = true }
                if (err.needsUserAction) {
                    // 권한/블루투스 꺼짐/미등록/NMEA 미설정: 무한 재시도 금지 — 사용자 조치(또는 wake 신호) 대기
                    setLink(LinkState.NEEDS_USER, attempt, failures, err, "${err.title} (오류 ${err.code})", false)
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
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } catch (e: CancellationException) { if (!connected.get()) t.close(); throw e }
        }
        try { t.connect(); connected.set(true) } finally { closer.cancel() }
    }

    /** 연결 수립 후 읽기/워치독/RTCM 쓰기. 끊기면 [SessionLost] 로 종료 */
    private suspend fun runSession(driver: NmeaDriver, t: LinkTransport, attempt: Int) = coroutineScope {
        var gotData = false
        writeStartedAt = 0L
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
                    val now = clock()
                    val msgs = driver.onBytes(buf, n, now)
                    if (msgs.isNotEmpty()) {
                        val snap = driver.snapshot
                        snap.gga?.let { if (it.raw.isNotEmpty()) onGga(it.raw) }
                        onQuality(snap.quality)
                        gnss.value = snap
                        if (!gotData && snap.gga != null) {
                            gotData = true; streamingSince = now
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
        // 워치독: 무수신 → STALE → 재연결 / 쓰기 막힘
        launch {
            while (isActive) {
                delay(watchdogPeriodMs)
                val now = clock()
                val ws = writeStartedAt
                if (ws != 0L && now - ws > writeStallMs) throw SessionLost(BtError.WRITE_STALLED, "write stalled ${now - ws}ms")
                when (driver.health(now)) {
                    DataHealth.STALE -> if (link.value.state != LinkState.STALE) {
                        val s = driver.snapshot
                        val miss = driver.missingRequired().isNotEmpty() && s.sentencesTotal == 0L
                        val badRate = driver.parserStats.checksumErrorRate > 0.2
                        setLink(LinkState.STALE, attempt, link.value.failures,
                            if (miss) BtError.NO_DATA else if (badRate || s.checksumErrors > 5) BtError.BAD_CHECKSUM else null,
                            if (miss) profile.noDataHint else "데이터가 잠시 끊겼어요", strategy = t.strategyName)
                    }
                    // 소켓 오류 없이 데이터만 끊김: 전원 꺼짐/범위 이탈 추정(구분 불가), 처음부터 한 문장도 없으면 NMEA 설정 문제
                    DataHealth.DEAD -> throw SessionLost(if (driver.snapshot.sentencesTotal == 0L) BtError.NO_DATA else BtError.POWER_OR_RANGE, "no data")
                    else -> {}
                }
            }
        }
        // 쓰기: RTCM 큐 직렬화, 청크 단위
        launch(io) {
            try {
                for (frame in rtcmQueue) {
                    var off = 0
                    while (off < frame.size) {
                        val n = minOf(profile.rtcmWriteChunk, frame.size - off)
                        writeStartedAt = clock()
                        t.output.write(frame, off, n)
                        t.output.flush()
                        writeStartedAt = 0L
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
}
