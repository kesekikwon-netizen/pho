package kr.archsurvey.engine

import kr.archsurvey.ntrip.*
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException

/**
 * 보정(NTRIP) 상태. 화면은 기존 필드(state/message/rtcmBytes/lastRtcmAtMs/attempt)만 써도 동작한다.
 * [lastRtcmAtMs] 는 **온전한 RTCM 3 프레임**(CRC 통과)을 마지막으로 받은 시각(SystemClock.elapsedRealtime 기준 ms)이다.
 * 쓰레기 바이트(오류 페이지 등)는 "수신 중"으로 치지 않는다.
 */
data class NtripStatus(
    val state: NtripState = NtripState.IDLE,
    val message: String = "",
    val rtcmBytes: Long = 0,
    val lastRtcmAtMs: Long = 0,
    val attempt: Int = 0,
    /** 수신기 위치(GGA)가 없어 접속을 보류 중(데이터 절약). state 는 IDLE */
    val paused: Boolean = false,
    val framesOk: Long = 0,
    val crcErrors: Long = 0,
    val failure: NtripFailureKind? = null,
    /** 다음 재접속 예정 시각(같은 시계). 0 = 없음 */
    val nextRetryAtMs: Long = 0,
) {
    /** 마지막 유효 RTCM 프레임 이후 경과(ms). 아직 못 받았으면 null */
    fun rtcmAgeMs(nowMs: Long): Long? = if (lastRtcmAtMs == 0L) null else (nowMs - lastRtcmAtMs).coerceAtLeast(0)

    fun freshness(nowMs: Long): RtcmFreshness {
        if (state != NtripState.RECEIVING) return RtcmFreshness.NONE
        val age = rtcmAgeMs(nowMs) ?: return RtcmFreshness.NONE
        return when {
            age < RtcmFreshness.FRESH_MS -> RtcmFreshness.FRESH
            age < RtcmFreshness.AGING_MS -> RtcmFreshness.AGING
            else -> RtcmFreshness.LOST
        }
    }
}

/** 보정 데이터 신선도(화면의 "연령" 표시용). 1 Hz VRS 기준 제안값 [현장검증] */
enum class RtcmFreshness {
    NONE, FRESH, AGING, LOST;
    companion object { const val FRESH_MS = 3_000L; const val AGING_MS = 10_000L }
}

/** NTRIP 실패 분류. [tag] 는 화면(ErrorCards)이 문구를 고르는 기존 단서(예외 클래스명)와 호환되게 유지한다. */
enum class NtripFailureKind(val tag: String, val autoRetry: Boolean, val baseDelayMs: Long, val maxDelayMs: Long) {
    AUTH("NtripAuthException", false, 0, 0),
    MOUNT("NtripMountException", false, 0, 0),
    DNS("UnknownHostException", true, 2_000, 30_000),
    NETWORK("ConnectException", true, 2_000, 30_000),
    TIMEOUT("SocketTimeoutException", true, 2_000, 30_000),
    SERVER_ERROR("NtripServerException", true, 5_000, 60_000),
    SERVER_CLOSED("NtripProtocolException", true, 1_000, 15_000),
    NO_DATA("NtripNoDataException", true, 1_000, 15_000),
    /** 0.1.58 B6: 기준국 변경 뒤 끊김 → 곧바로 다시 접속 */
    BASE_CHANGED("NtripBaseChangeException", true, 300, 3_000),
    NOT_RTCM("NtripNotRtcmException", true, 30_000, 60_000),
    UNKNOWN("Exception", true, 1_000, 15_000);

    /** attempt: 1부터. 지수 백오프(기본 ×2) 후 상한 */
    fun delayMs(attempt: Int): Long =
        if (!autoRetry) 0 else minOf(maxDelayMs, baseDelayMs shl (attempt - 1).coerceIn(0, 6))

    companion object {
        fun classify(e: Throwable): NtripFailureKind = when (e) {
            is NtripAuthException -> AUTH
            is NtripMountException -> MOUNT
            is NtripTimeoutException -> TIMEOUT
            is NtripNoDataException -> NO_DATA
            is kr.archsurvey.ntrip.NtripBaseChangeException -> BASE_CHANGED
            is NtripNotRtcmException -> NOT_RTCM
            is NtripServerException -> SERVER_ERROR
            // 코루틴 스택 복구가 예외를 복사하면서 원인을 한 단계 감쌀 수 있으므로 원인 사슬 전체를 본다
            is NtripNetworkException -> if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is UnknownHostException }) DNS else NETWORK
            is NtripProtocolException -> SERVER_CLOSED
            is UnknownHostException -> DNS
            is SocketTimeoutException -> TIMEOUT
            is ConnectException, is NoRouteToHostException -> NETWORK
            is java.io.IOException -> {
                val m = e.message?.lowercase().orEmpty()
                if ("reset" in m || "broken pipe" in m || "abort" in m || "closed" in m) SERVER_CLOSED else UNKNOWN
            }
            else -> UNKNOWN
        }
    }
}
