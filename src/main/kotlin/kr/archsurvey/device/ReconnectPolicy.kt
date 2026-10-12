package kr.archsurvey.device

/**
 * 재연결 정책 (순수 로직, 단위 테스트 대상).
 * delay(attempt) = min(maxDelay, initial * 2^(attempt-1)); settleMs 는 소켓 close 후 추가 대기.
 */
data class ReconnectPolicy(
    val initialDelayMs: Long,
    val maxDelayMs: Long,
    val settleMs: Long,
    /** 연속 실패가 이 횟수에 도달하면 "페어링 삭제 후 재페어링" 안내를 표시 */
    val repairHintAfterFailures: Int,
    /** 최초 연결 전 대기 (SP60: 위성 추적 시작 후 연결하도록 여유) */
    val firstConnectDelayMs: Long = 0,
    /** 0.1.42: 연속 실패가 이만큼이면 자동 재연결을 멈추고 사용자에게 맡김(7초마다 끝없이 두드리지 않게). 0 = 제한 없음 */
    val giveUpAfterFailures: Int = 12,
) {
    init {
        require(initialDelayMs > 0 && maxDelayMs >= initialDelayMs && settleMs >= 0)
    }

    /** attempt: 1부터. 반환: 다음 시도까지 대기(ms) (settle 포함) */
    fun delayBeforeAttempt(attempt: Int): Long {
        if (attempt <= 1) return firstConnectDelayMs
        val shift = (attempt - 2).coerceIn(0, 20)
        val exp = initialDelayMs shl shift
        return minOf(maxDelayMs, exp) + settleMs
    }

    fun shouldShowRepairHint(consecutiveFailures: Int) = consecutiveFailures >= repairHintAfterFailures
    fun shouldGiveUp(consecutiveFailures: Int) = giveUpAfterFailures > 0 && consecutiveFailures >= giveUpAfterFailures
}
