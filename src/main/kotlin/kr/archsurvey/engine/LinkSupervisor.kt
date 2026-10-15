package kr.archsurvey.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
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
    /** 0.1.30 T23: SP60 GST/GSA 자동 요청 결과(연결마다 최대 1번) */
    private val onPrecisionResult: (kr.archsurvey.bt.PrecisionResult) -> Unit = {},
    /** 0.1.30 T23: 이 수신기 자동 요청 횟수 상한(Engine 은 주소별 앱 실행 단위로 넘긴다) */
    private val precisionBudget: kr.archsurvey.bt.PrecisionBudget = kr.archsurvey.bt.PrecisionBudget(),
    private val precision: kr.archsurvey.bt.PrecisionActivator = kr.archsurvey.bt.PrecisionActivator(clock),
    /** 0.1.30 T23-b: 사용자 [자동 켜기 다시] 신호(SP60 만). 자동 1번이 끝난 뒤에만 받고, 한 번에 한 순서씩 */
    private val precisionRetry: ReceiveChannel<Unit>? = null,
    /** 0.1.32: 제조사 고유 NMEA 신호로 판단한 수신기 종류 id(ReceiverDetect.fromNmea). 세션마다 처음 한 번 */
    private val onSignature: (String) -> Unit = {},
    /** 0.1.33-r10: 소켓이 붙었던 세션이 끝날 때마다(오류 코드·지속 시간·바이트·RTCM 쓰기). CrashLog 에도 남긴다 */
    private val onSessionEnd: (SessionEnd) -> Unit = {},
    /**
     * 0.1.33: R10 블루투스 포트 출력 자동 켜기(Trimble DCOL 64h). null 이면 끔 — Engine 은 R10 일 때만 넘긴다
     * ([kr.archsurvey.bt.R10AutoOptions.forProfile]). SP60 경로는 이 값과 무관하게 그대로.
     */
    private val r10Auto: kr.archsurvey.bt.R10AutoOptions? = null,
    /** 0.1.33: R10 자동설정 결과(켜기·보충 각각). 화면 안내·진단용 */
    private val onR10Auto: (kr.archsurvey.bt.R10AutoResult) -> Unit = {},
) {
    /** 64h APPFILE 전송 번호. 연결을 넘어 1씩 증가(0–255 순환) */
    private val dcolTx = java.util.concurrent.atomic.AtomicInteger(0)
    class SessionLost(val error: BtError, msg: String) : IOException(msg)

    companion object {
        /** 0.1.33-r10: 위치는 오는데 RTCM 쓰기가 막혀 보정 전달만 멈춘 상태의 상태줄 문구 */
        const val RTCM_BLOCKED_MSG = "수신 중 · 보정(RTCM)이 수신기에 들어가지 않아요"
    }

    /**
     * 0.1.33-r10 세션 종료 진단. 0.1.32 까지는 BT-130 만 기록돼서, GGA 를 받다가 끊긴 세션(R10 현장 2026-10-11)이
     * 왜 끊겼는지(수신기가 끊음·RTCM 쓰기 막힘/실패·무수신) 기록에 남지 않았다.
     */
    data class SessionEnd(
        val error: BtError, val detail: String, val strategy: String?, val connectedMs: Long, val streamedMs: Long,
        val bytesIn: Long, val sentences: Long, val types: Set<String>, val rtcmBytesOut: Long, val rtcmFrames: Long, val maxWriteMs: Long,
    ) {
        fun summary(profileId: String) = "진단 세션 종료 ${error.code} 수신기=$profileId 방식=$strategy 연결${connectedMs / 1000}s " +
            "수신중${streamedMs / 1000}s 수신${bytesIn}B 문장=$sentences 종류=${types.sorted().joinToString("/").ifEmpty { "-" }} " +
            "RTCM송신${rtcmBytesOut}B/${rtcmFrames}개 최장쓰기${maxWriteMs}ms · $detail"
    }
    // 세션 통계(세션마다 runSession 시작 때 0)
    private val sesBytesIn = AtomicLong(0)
    private val sesRtcmBytes = AtomicLong(0)
    private val sesRtcmFrames = AtomicLong(0)
    private val sesMaxWriteMs = AtomicLong(0)
    @Volatile private var sesDriver: NmeaDriver? = null

    @Volatile private var wasConnectedOnce = false
    /** 0.1.19 QA-0119-2: GGA 까지 받은(STREAMING) 세션이 한 번이라도 있었는지. 소켓만 붙은 것은 「성공」이 아니다 */
    @Volatile private var streamedOnce = false
    @Volatile private var linkLostAnnounced = false
    @Volatile private var streamingSince = 0L
    @Volatile private var writeStartedAt = 0L
    /** 직전 세션 진단 요약(TX/RX·바이트·명령). BT-130 CrashLog 에 붙인다 */
    private val lastDiag = AtomicReference("")

    /** 지금까지 연속으로 실패한 횟수(안정적으로 받은 뒤 끊기면 0 부터 다시) */
    @Volatile var failures = 0; private set
    /** 0.1.41 R10 자동설정이 GGA 를 못 받았을 때 카드에 붙일 한 줄 진단(결과·ENQ·일련번호 응답·연결 방식) */
    @Volatile private var r10Brief = ""
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
                var errMsg = ""
                var connectedAt = 0L
                streamingSince = 0L
                lastDiag.set("")
                try {
                    connectCancellable(transport)
                    connectedAt = clock()
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
                    errMsg = e.message.orEmpty()
                } finally {
                    transport.close()
                }
                if (connectedAt != 0L) {
                    val d = sesDriver?.snapshot
                    val end = SessionEnd(
                        error = err ?: BtError.LINK_LOST, detail = errMsg.take(160), strategy = transport.strategyName,
                        connectedMs = clock() - connectedAt, streamedMs = if (streamingSince != 0L) clock() - streamingSince else 0L,
                        bytesIn = sesBytesIn.get(), sentences = d?.sentencesTotal ?: 0L, types = d?.seenTypes ?: emptySet(),
                        rtcmBytesOut = sesRtcmBytes.get(), rtcmFrames = sesRtcmFrames.get(), maxWriteMs = sesMaxWriteMs.get(),
                    )
                    runCatching { onSessionEnd(end) }
                    CrashLog.record(end.summary(profile.id), IllegalStateException(end.error.title))
                }
                // 안정적으로 받았던 연결이면 백오프를 처음부터. 잠깐 붙었다 끊기는 반복은 계속 늘어난다
                val streamedMs = if (streamingSince != 0L) clock() - streamingSince else 0L
                if (streamedMs >= stableAfterMs) { attempt = 0; failures = 0; nmeaHeals = 0 }
                failures++
                if (err == null) err = BtError.LINK_LOST
                err = BtError.refineAfterLoss(err, wasConnectedOnce, failures)
                // 성공(STREAMING) 뒤 끊김만 LINK_LOST. 소켓만 붙고 실패하면 ConnectionController 의 CONNECT_FAIL 한 번만(이중 실패음 방지)
                if (streamedOnce && !linkLostAnnounced && err != BtError.NO_DATA) { emit(FeedbackEvent.LINK_LOST); linkLostAnnounced = true }
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
                    val msg = if (err == BtError.NO_DATA) "${err.title} (오류 ${err.code}) · ${NmeaActivation.fieldChecklist(profile)}${r10Brief.takeIf { it.isNotEmpty() }?.let { " · 진단 $it" } ?: ""} · 진단은 설정·장비연결에서 확인"
                    else "${err.title} (오류 ${err.code})"
                    setLink(LinkState.NEEDS_USER, attempt, failures, err, msg, false)
                    wake.receive()
                    attempt = 0
                    continue
                }
                // 0.1.42: 끝없이 두드리지 않기 — 연속 실패가 상한이면 멈추고 「다시 시도」를 기다림(다른 앱·다른 폰이 수신기를 쓰는 중일 수 있음)
                if (policy.shouldGiveUp(failures)) {
                    setLink(LinkState.NEEDS_USER, attempt, failures, err,
                        "연결이 ${failures}번 연속 안 돼서 자동 재연결을 멈췄어요 (오류 ${err.code}) · 수신기 전원·다른 앱/컨트롤러 연결을 확인하고 「다시 시도」", policy.shouldShowRepairHint(failures))
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
        var sigSeen = false
        writeStartedAt = 0L
        sesBytesIn.set(0); sesRtcmBytes.set(0); sesRtcmFrames.set(0); sesMaxWriteMs.set(0); sesDriver = driver
        val outLock = Any()
        // 0.1.30 T23: RTCM 프레임 한 개를 다 쓸 때까지 잡는 잠금. 명령($PASH)은 이 잠금을 잡고 써서 프레임 중간에 끼지 않는다
        val frameLock = kotlinx.coroutines.sync.Mutex()
        val pashr = kr.archsurvey.bt.PashrLineAssembler()
        val pashReplies = kotlinx.coroutines.channels.Channel<String>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        val ackSeen = AtomicBoolean(false)
        val bytesIn = AtomicLong(0)
        val bytesOutCmd = AtomicLong(0)
        val cmdLog = java.util.concurrent.CopyOnWriteArrayList<String>()
        val actRunning = AtomicBoolean(true)
        // 0.1.33 R10 자동설정: 수신 스트림의 ACK/NAK/DCOL 프레임, 문장 종류별 개수(이 연결)
        val dcolScanner = kr.archsurvey.bt.DcolScanner()
        val dcolEvents = kotlinx.coroutines.channels.Channel<kr.archsurvey.bt.DcolEvent>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        val typeCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
        val r10Port = AtomicReference<Int?>(null)
        val actEndedAt = AtomicLong(0)
        var actSummary = ""
        // 0.1.33-r10: RTCM 쓰기가 막혔는데 위치(NMEA)는 계속 오는 동안 true. 세션은 유지하고 보정 전달만 멈춘다
        val rtcmBlocked = AtomicBoolean(false)
        /** 막힘 진단·상태 표시는 막힐 때마다 한 번(풀리면 다시 false) */
        val rtcmBlockNoted = AtomicBoolean(false)
        fun streamingLink() = setLink(LinkState.STREAMING, attempt, link.value.failures,
            if (rtcmBlocked.get()) BtError.WRITE_STALLED else null,
            if (rtcmBlocked.get()) RTCM_BLOCKED_MSG else "수신 중", strategy = t.strategyName)
        fun safeWrite(b: ByteArray, off: Int = 0, len: Int = b.size) {
            synchronized(outLock) {
                val t0 = clock()
                writeStartedAt = t0
                t.output.write(b, off, len)
                t.output.flush()
                writeStartedAt = 0L
                val dt = clock() - t0
                if (dt > sesMaxWriteMs.get()) sesMaxWriteMs.set(dt)
            }
        }
        fun writeCmd(b: ByteArray) {
            safeWrite(b); bytesOutCmd.addAndGet(b.size.toLong())
            val line = b.toString(Charsets.US_ASCII).trim().take(80)
            if (line.isNotEmpty()) cmdLog.add("TX $line")
        }
        /** DCOL 바이너리 명령. RTCM 프레임 한 개를 다 쓴 뒤에만(frameLock) 쓴다 */
        suspend fun writeDcol(b: ByteArray, label: String) {
            frameLock.withLock { safeWrite(b) }
            bytesOutCmd.addAndGet(b.size.toLong())
            cmdLog.add("TX $label")
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
                    bytesIn.addAndGet(n.toLong()); sesBytesIn.addAndGet(n.toLong())
                    if (!ackSeen.get() && containsAscii(buf, n, "PASHR,ACK")) {
                        ackSeen.set(true); cmdLog.add("RX PASHR,ACK")
                    }
                    if (containsAscii(buf, n, "PASHR,NAK")) cmdLog.add("RX PASHR,NAK")
                    if (!sigSeen && (containsAscii(buf, n, "\$PASH") || containsAscii(buf, n, "\$PTNL"))) {
                        kr.archsurvey.device.ReceiverDetect.fromNmea(buf.copyOf(n).toString(Charsets.US_ASCII))?.let { sigSeen = true; onSignature(it) }
                    }
                    if (containsAscii(buf, n, "PASHR,PRT") || containsAscii(buf, n, "PASHR,NMO")) {
                        val snip = buf.copyOf(n).toString(Charsets.US_ASCII).replace("\r", " ").replace("\n", " ").take(100)
                        cmdLog.add("RX $snip")
                    }
                    for (line in pashr.feed(buf, n)) pashReplies.trySend(line)
                    if (r10Auto != null) for (e in dcolScanner.feed(buf, n)) {
                        if (e !is kr.archsurvey.bt.DcolEvent.Frame) cmdLog.add("RX $e")
                        dcolEvents.trySend(e)
                    }
                    val now = clock()
                    val msgs = driver.onBytes(buf, n, now)
                    if (msgs.isNotEmpty()) {
                        for (m in msgs) typeCounts.getOrPut(sentenceType(m)) { AtomicLong(0) }.incrementAndGet()
                        val snap = driver.snapshot
                        snap.gga?.let { if (it.raw.isNotEmpty()) onGga(it.raw) }
                        onQuality(snap.quality)
                        gnss.value = snap
                        if (!gotData && snap.gga != null) {
                            gotData = true; streamingSince = now; nmeaHeals = 0; streamedOnce = true
                            t.strategyName?.let { onStrategyUsed(it) } // GGA 확인된 방식만 저장
                            streamingLink()
                        } else if (link.value.state == LinkState.STALE) {
                            streamingLink()
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
                val auto = r10Auto
                val result = if (auto != null) {
                    // 0.1.33 R10: 자연 NMEA 2 s → ENQ → 포트 17/18/19 탐색(64h) → GGA·GST·GSA·GSV
                    val r = kr.archsurvey.bt.R10AutoConfig(
                        send = { b, label -> writeDcol(b, label) }, events = dcolEvents,
                        count = { typeCounts[it]?.get() ?: 0L }, clock = clock, store = auto.store, timing = auto.timing,
                        nextTx = { dcolTx.getAndIncrement() and 0xFF }, extended = auto.extended,
                        onStatus = { msg ->
                            if (link.value.state == LinkState.CONNECTED_NO_DATA)
                                setLink(LinkState.CONNECTED_NO_DATA, attempt, link.value.failures, msg = msg, strategy = t.strategyName)
                        },
                    ).enableOutput()
                    if ((r.outcome == kr.archsurvey.bt.R10AutoOutcome.PORT_FOUND || r.outcome == kr.archsurvey.bt.R10AutoOutcome.GGA_ONLY_FALLBACK)) r10Port.set(r.port)
                    r10Brief = if (r.gotGga) "" else "R10 ${r.outcome} · ENQ ${r.enq} · 일련번호 ${r.twoWay} · ${t.strategyName ?: "-"}"
                    if (r.outcome != kr.archsurvey.bt.R10AutoOutcome.NATURAL)
                        CrashLog.record("진단 ${r.summary()}", RuntimeException(r.log.joinToString("\n")))
                    runCatching { onR10Auto(r) }
                    val anyAck = r.tried.any { "ACK" in it } || r.enq == "ACK"
                    kr.archsurvey.bt.NmeaActResult(
                        outcome = when {
                            r.gotGga && r.outcome == kr.archsurvey.bt.R10AutoOutcome.NATURAL -> NmeaActOutcome.NATURAL_GGA
                            r.gotGga || driver.snapshot.gga != null -> NmeaActOutcome.GGA_SEEN
                            anyAck -> NmeaActOutcome.ACK_SEEN_NO_GGA
                            else -> NmeaActOutcome.NO_RESPONSE
                        },
                        tried = r.tried, log = r.log, elapsedMs = r.elapsedMs,
                    )
                } else NmeaActivation.run(
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
        // 0.1.33 R10: GGA 는 오는데 GST/GSA 가 5 s 동안 없으면 같은 포트 탐색으로 GST·GSA·GSV 보충(연결당 1번, GGA 는 끄지 않음)
        r10Auto?.let { auto -> launch(io) {
            try {
                while (isActive && (actRunning.get() || driver.snapshot.gga == null)) delay(200)
                val r = kr.archsurvey.bt.R10AutoConfig(
                    send = { b, label -> writeDcol(b, label) }, events = dcolEvents,
                    count = { typeCounts[it]?.get() ?: 0L }, clock = clock, store = auto.store, timing = auto.timing,
                    nextTx = { dcolTx.getAndIncrement() and 0xFF }, probeWhenNatural = auto.probeWhenNatural,
                ).addPrecision(r10Port.get())
                if (r.outcome != kr.archsurvey.bt.R10AutoOutcome.PRECISION_OK)
                    CrashLog.record("진단 ${r.summary()}", RuntimeException(r.log.joinToString("\n")))
                runCatching { onR10Auto(r) }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                CrashLog.record("R10 자동설정(보충) 오류", e)
            }
        } }
        // 0.1.30 T23: SP60 정밀도 문장(GST/GSA) 검사·요청 — GGA 확인 뒤 따로(연결당 1번). 실패해도 세션은 그대로
        if (profile.id == kr.archsurvey.device.DeviceProfiles.SP60_ID) launch(io) {
            try {
                while (isActive && (actRunning.get() || driver.snapshot.gga == null)) delay(200)
                val r = precision.run(
                    send = { body -> frameLock.withLock { writeCmd(kr.archsurvey.bt.PrecisionCommands.line(body).toByteArray(Charsets.US_ASCII)) } },
                    replies = pashReplies,
                    seenTypes = { driver.snapshot.seenTypes },
                    budget = precisionBudget,
                )
                CrashLog.record("진단 ${r.summary()}", RuntimeException(r.log.joinToString("\n")))
                onPrecisionResult(r)
                // 0.1.30 T23-b: 사용자 재시도 — 자동 실행 중 눌린 것은 버리고, 그 뒤 누를 때마다 한 순서(자동 상한과 별도)
                val retry = precisionRetry ?: return@launch
                while (retry.tryReceive().isSuccess) { }
                while (isActive) {
                    retry.receive()
                    val u = precision.run(
                        send = { body -> frameLock.withLock { writeCmd(kr.archsurvey.bt.PrecisionCommands.line(body).toByteArray(Charsets.US_ASCII)) } },
                        replies = pashReplies,
                        seenTypes = { driver.snapshot.seenTypes },
                        budget = kr.archsurvey.bt.PrecisionBudget(1),
                        userRetry = true,
                    )
                    CrashLog.record("진단(사용자 재시도) ${u.summary()}", RuntimeException(u.log.joinToString("\n")))
                    onPrecisionResult(u)
                    while (retry.tryReceive().isSuccess) { }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                CrashLog.record("정밀도 자동요청 오류", e)
            }
        }
        // 워치독
        launch {
            while (isActive) {
                delay(watchdogPeriodMs)
                val now = clock()
                val ws = writeStartedAt
                if (ws != 0L && now - ws > writeStallMs) {
                    // 0.1.33-r10: 위치가 계속 오면 끊지 않는다. 다시 연결해도 수신기가 RTCM 을 안 받으면 같은 자리에서 또 막혀
                    // 1~2분마다 위치까지 끊겼다(현장 2026-10-11). 위치까지 끊겼을 때만 예전처럼 새 연결
                    if (driver.health(now) != DataHealth.OK) throw SessionLost(BtError.WRITE_STALLED, "write stalled ${now - ws}ms")
                    // 0.1.34 검토: 판정과 표시 사이에 쓰기가 끝났으면(writeStartedAt=0) 표시를 되돌린다 —
                    // 쓰기 쪽 해제(compareAndSet(true,false))보다 늦게 켜져 「막힘」이 남는 경합 방지
                    if (rtcmBlocked.compareAndSet(false, true) && writeStartedAt == 0L) rtcmBlocked.compareAndSet(true, false)
                    else if (rtcmBlocked.get() && !rtcmBlockNoted.getAndSet(true)) {
                        if (link.value.state == LinkState.STREAMING) streamingLink()
                        CrashLog.record(
                            "진단 RTCM 전달 막힘 수신기=${profile.id} 방식=${t.strategyName} 막힘${now - ws}ms " +
                                "RTCM송신${sesRtcmBytes.get()}B/${sesRtcmFrames.get()}개 · 위치 수신은 유지(세션 안 끊음)",
                            IllegalStateException(BtError.WRITE_STALLED.title),
                        )
                    }
                }
                when (driver.health(now)) {
                    DataHealth.STALE -> if (link.value.state != LinkState.STALE && !actRunning.get()) {
                        val s = driver.snapshot
                        val miss = driver.missingRequired().isNotEmpty() && s.sentencesTotal == 0L
                        val badRate = driver.parserStats.checksumErrorRate > 0.2
                        setLink(LinkState.STALE, attempt, link.value.failures,
                            if (miss) BtError.NO_DATA else if (badRate || s.checksumErrors > 5) BtError.BAD_CHECKSUM else null,
                            if (miss) profile.noDataHint + (r10Brief.takeIf { it.isNotEmpty() }?.let { " · 진단 $it" } ?: "") else "데이터가 잠시 끊겼어요", strategy = t.strategyName)
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
                    sesRtcmFrames.incrementAndGet()
                    frameLock.withLock {
                        var off = 0
                        while (off < frame.size) {
                            val n = minOf(profile.rtcmWriteChunk, frame.size - off)
                            safeWrite(frame, off, n)
                            off += n
                            sesRtcmBytes.addAndGet(n.toLong())
                            if (profile.rtcmWriteGapMs > 0) delay(profile.rtcmWriteGapMs)
                        }
                    }
                    // 막혔던 쓰기가 풀림 → 막힌 동안 쌓인 낡은 보정은 버리고 새 보정부터 이어 보낸다
                    if (rtcmBlocked.compareAndSet(true, false)) {
                        rtcmBlockNoted.set(false)
                        var dropped = 0
                        while (rtcmQueue.tryReceive().isSuccess) dropped++
                        if (link.value.state == LinkState.STREAMING) streamingLink()
                        CrashLog.record("진단 RTCM 전달 재개 수신기=${profile.id} 막힌 동안 쌓인 보정 ${dropped}개 버림", IllegalStateException("RTCM resumed"))
                    }
                }
            } catch (e: SessionLost) { throw e
            } catch (e: IOException) {
                if (isActive) throw SessionLost(BtError.WRITE_FAILED, e.message ?: "write")
            }
        }
        Unit
    }

    private fun sentenceType(m: kr.archsurvey.core.NmeaMessage): String = when (m) {
        is kr.archsurvey.core.GgaMessage -> "GGA"; is kr.archsurvey.core.GstMessage -> "GST"; is kr.archsurvey.core.GsaMessage -> "GSA"
        is kr.archsurvey.core.GsvMessage -> "GSV"; is kr.archsurvey.core.ZdaMessage -> "ZDA"
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
