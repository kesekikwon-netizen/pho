package kr.archsurvey.device

import kotlinx.coroutines.flow.StateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.core.GnssSnapshot

enum class LinkState { IDLE, CONNECTING, CONNECTED_NO_DATA, STREAMING, STALE, RECONNECTING, NEEDS_USER }

data class LinkStatus(
    val state: LinkState = LinkState.IDLE,
    val attempt: Int = 0,
    val failures: Int = 0,
    val error: BtError? = null,
    val message: String = "",
    val repairHint: Boolean = false,
    val strategy: String? = null,
)

/**
 * 측위 데이터 공급원. 실제 수신기(`engine.SurveyEngine`, 블루투스 NMEA)와
 * 가짜 소스(`FakeGnssSource`, 시나리오 재생)가 같은 인터페이스를 구현한다.
 * UI·상태 판정은 이 인터페이스만 본다.
 */
interface GnssSource {
    /** 연결 상태 */
    val link: StateFlow<LinkStatus>
    /** 최신 측위 스냅샷 (끊겨도 마지막 값이 남을 수 있으므로 신선도는 [clockMs]와 `lastGgaAtMs`로 판단) */
    val gnss: StateFlow<GnssSnapshot>
    /** 스냅샷의 `lastGgaAtMs`와 같은 시계의 현재 시각(ms) */
    fun clockMs(): Long
    fun stop()
}
