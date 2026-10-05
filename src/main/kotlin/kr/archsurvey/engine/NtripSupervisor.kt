package kr.archsurvey.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicLong
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.ntrip.*

/** 캐스터 접속 한 번(테스트에서는 가짜로 바꾼다). 정상 종료는 없고 예외/취소로만 끝난다. */
fun interface NtripRunner {
    suspend fun run(
        cfg: NtripConfig, ggaIntervalMs: Long, noDataTimeoutMs: Long,
        ggaProvider: () -> String?, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit,
    ): Nothing

    companion object {
        fun real(client: NtripClient = NtripClient()) = NtripRunner { cfg, gga, noData, provider, onState, onRtcm ->
            client.run(cfg, gga, noData, provider, onState, onRtcm)
        }
    }
}

/**
 * NTRIP 접속 감독: 접속 → RTCM 중계 → 실패 분류 → 재접속(지수 백오프)을 한 곳에서 맡는다. Android 의존 없음(JVM 시험 가능).
 *
 * 규칙
 * - **수신기 위치(GGA)가 들어오기 전에는 접속하지 않는다**(`linkStreaming`). VRS 는 내 위치가 있어야 하고, 위치 없는 접속은 트래픽·서버 부하만 쓴다.
 * - 수신기 연결이 [pauseAfterLinkLossMs] 넘게 끊기면 캐스터 접속도 닫아 데이터 사용을 멈추고, 돌아오면 다시 연다.
 * - 401/403(AUTH)·마운트 없음(MOUNT)은 **자동 재시도 안 함**(계정 잠금 방지). 사용자가 [retryNow] 를 부를 때만 다시 시도.
 *   네트워크가 바뀌었다는 신호([networkAvailable])로는 이 보류가 풀리지 않는다.
 * - 그 밖의 실패는 종류별 지수 백오프. 네트워크 복구 신호가 오면 대기를 즉시 끝낸다.
 * - 15초 이상 안정적으로 유효 RTCM 을 받은 뒤 끊기면 백오프를 처음부터 다시 시작한다(접속 직후 반복해서 끊기면 백오프가 쌓인다).
 * - RTCM 은 CRC 가 맞는 **온전한 프레임만** [rtcmSink] 로 넘긴다.
 */
class NtripSupervisor(
    private val scope: CoroutineScope,
    private val status: MutableStateFlow<NtripStatus>,
    private val clock: () -> Long,
    private val linkStreaming: StateFlow<Boolean>,
    private val ggaProvider: () -> String?,
    private val rtcmSink: (ByteArray) -> Unit,
    private val emit: (FeedbackEvent) -> Unit = {},
    private val runner: NtripRunner = NtripRunner.real(),
    private val pauseAfterLinkLossMs: Long = 60_000,
    private val stableAfterMs: Long = 15_000,
    private val noDataTimeoutMs: Long = 10_000,
    private val notRtcmAfterBytes: Int = 3_000,
    private val watchdogPeriodMs: Long = 1_000,
) {
    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val net = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lostAnnounced = false
    @Volatile private var totalBytes = 0L

    val active: Boolean get() = job?.isActive == true

    /** 0.1.11 VRS 설정 「GGA 전송」. 끄면 서버에 내 위치(GGA)를 보내지 않는다(VRS 마운트는 보정이 오지 않을 수 있음) */
    @Volatile var sendGga: Boolean = true
    /** 0.1.11 VRS 설정 「자동 재접속」. 끄면 끊겼을 때 기다리지 않고 사용자 「다시 시도」를 기다린다(인증·마운트 오류는 원래도 수동) */
    @Volatile var autoRetry: Boolean = true

    /** 설정이 바뀌었거나 처음 시작할 때. 이전 접속은 닫는다. */
    fun start(cfg: NtripConfig?, enabled: Boolean, ggaIntervalMs: Long) {
        job?.cancel(); job = null
        while (kick.tryReceive().isSuccess) { }
        while (net.tryReceive().isSuccess) { }
        lostAnnounced = false
        if (!enabled || cfg == null || !cfg.isUsable) {
            status.value = NtripStatus(NtripState.IDLE, if (!enabled) "보정 사용 안 함" else "VRS 정보를 입력하세요")
            return
        }
        totalBytes = 0
        status.value = NtripStatus()
        val ggaMs = NtripTiming.clampGgaIntervalMs(ggaIntervalMs)
        job = scope.launch { loop(cfg, ggaMs) }
    }

    fun stop() {
        job?.cancel(); job = null
        status.value = NtripStatus()
    }

    /** 사용자가 "다시 시도"를 눌렀을 때: 인증/마운트 보류도 푼다 */
    fun retryNow() { kick.trySend(Unit) }

    /** 시스템이 "인터넷 연결됨"을 알렸을 때: 백오프 대기만 앞당긴다(인증 보류는 풀지 않는다) */
    fun networkAvailable() { net.trySend(Unit) }

    private class LinkPause : RuntimeException("수신기 연결 없음 — 보정 접속 보류")

    private suspend fun loop(cfg: NtripConfig, ggaMs: Long) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (!linkStreaming.value) {
                status.update { it.copy(state = NtripState.IDLE, paused = true, nextRetryAtMs = 0,
                    message = "수신기 위치를 기다리는 중 — 위치가 들어오면 보정 서버에 접속해요") }
                linkStreaming.first { it }
            }
            attempt++
            while (net.tryReceive().isSuccess) { } // 접속 전에 쌓인 낡은 신호 버림
            val framer = Rtcm3Framer()
            var bytesThisConn = 0L // onRtcm 만 쓴다(단일 IO 스레드)
            val connectedAtA = AtomicLong(0)
            val firstFrameAtA = AtomicLong(0)
            val lastFrameAtA = AtomicLong(0)
            status.update { it.copy(state = NtripState.CONNECTING, paused = false, attempt = attempt, nextRetryAtMs = 0, failure = null,
                message = "VRS 서버에 접속 중…") }

            val failure: Throwable = try {
                coroutineScope {
                    launch { // 수신기 위치가 오래 끊기면 접속도 닫는다
                        linkStreaming.collectLatest { ok -> if (!ok) { delay(pauseAfterLinkLossMs); throw LinkPause() } }
                    }
                    launch { // 유효 RTCM 이 끊겼는지(소켓은 살아 있어도 데이터가 없는 경우) 감시
                        while (true) {
                            delay(watchdogPeriodMs)
                            val connectedAt = connectedAtA.get()
                            if (connectedAt == 0L) continue
                            val ref = lastFrameAtA.get().let { if (it != 0L) it else connectedAt }
                            if (clock() - ref > noDataTimeoutMs) throw NtripNoDataException("보정 데이터가 ${noDataTimeoutMs / 1000}초 동안 오지 않아요")
                        }
                    }
                    runner.run(cfg, ggaMs, noDataTimeoutMs + 5_000, { freshGga() },
                        { st ->
                            if (st == NtripState.CONNECTED) {
                                connectedAtA.set(clock())
                                status.update { it.copy(state = NtripState.CONNECTED, message = "접속됨 — 보정 데이터를 기다리는 중", attempt = attempt) }
                            } // RECEIVING 은 클라이언트가 아니라 "유효 프레임"을 받았을 때 직접 올린다
                        },
                        { buf, n ->
                            bytesThisConn += n; totalBytes += n
                            val now = clock()
                            framer.feed(buf, n, now) { f -> rtcmSink(f); lastFrameAtA.set(now); firstFrameAtA.compareAndSet(0, now) }
                            if (framer.framesOk == 0L && bytesThisConn >= notRtcmAfterBytes)
                                throw NtripNotRtcmException("받은 데이터가 RTCM 3 형식이 아니에요(서버 주소·마운트포인트 확인)")
                            val firstValid = framer.framesOk > 0 && status.value.state != NtripState.RECEIVING
                            status.update {
                                it.copy(rtcmBytes = totalBytes, framesOk = framer.framesOk, crcErrors = framer.crcErrors,
                                    lastRtcmAtMs = lastFrameAtA.get().let { a -> if (a != 0L) a else it.lastRtcmAtMs },
                                    state = if (framer.framesOk > 0) NtripState.RECEIVING else it.state,
                                    message = if (framer.framesOk > 0) "보정 데이터를 받고 있어요" else it.message)
                            }
                            if (firstValid && lostAnnounced) { emit(FeedbackEvent.NTRIP_OK); lostAnnounced = false }
                        })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }

            val firstFrameAt = firstFrameAtA.get()
            if (firstFrameAt != 0L && clock() - firstFrameAt >= stableAfterMs) attempt = 0 // 안정적으로 받았다면 백오프 초기화
            if (failure is LinkPause) { continue } // 맨 위에서 수신기 위치를 기다린다
            val kind = NtripFailureKind.classify(failure)
            val hadData = status.value.lastRtcmAtMs != 0L
            if (hadData && !lostAnnounced && kind != NtripFailureKind.AUTH && kind != NtripFailureKind.MOUNT) { emit(FeedbackEvent.NTRIP_LOST); lostAnnounced = true }
            when (kind) {
                NtripFailureKind.AUTH -> {
                    status.update { NtripStatus(NtripState.AUTH_FAILED, failure.message ?: "인증 실패", it.rtcmBytes, it.lastRtcmAtMs, attempt, failure = kind) }
                    emit(FeedbackEvent.NTRIP_LOST); lostAnnounced = true
                    attempt = 0
                    kick.receive() // 사용자가 직접 다시 시도할 때까지 대기. 네트워크 신호로는 풀리지 않음
                }
                NtripFailureKind.MOUNT -> {
                    status.update { NtripStatus(NtripState.ERROR, failure.message ?: "마운트포인트 오류", it.rtcmBytes, it.lastRtcmAtMs, attempt, failure = kind) }
                    attempt = 0
                    kick.receive()
                }
                else -> if (!autoRetry) {
                    status.update { NtripStatus(NtripState.ERROR,
                        "보정 연결이 끊겼어요 — 자동 재접속이 꺼져 있어 기다려요. ‘다시 시도’를 누르세요 [${kind.tag}] ${failure.message ?: ""}",
                        it.rtcmBytes, it.lastRtcmAtMs, attempt, framesOk = it.framesOk, crcErrors = it.crcErrors, failure = kind) }
                    attempt = 0
                    kick.receive()
                } else {
                    val d = kind.delayMs(attempt)
                    val retryAt = clock() + d
                    status.update { NtripStatus(NtripState.RECONNECTING,
                        "보정 연결이 끊겼어요. ${(d + 999) / 1000}초 후 다시 접속 [${kind.tag}] ${failure.message ?: ""}",
                        it.rtcmBytes, it.lastRtcmAtMs, attempt, framesOk = it.framesOk, crcErrors = it.crcErrors, failure = kind, nextRetryAtMs = retryAt) }
                    withTimeoutOrNull(d) { select<Unit> { kick.onReceive { }; net.onReceive { } } }
                }
            }
        }
    }

    private fun freshGga(): String? = if (sendGga) ggaProvider() else null
}
