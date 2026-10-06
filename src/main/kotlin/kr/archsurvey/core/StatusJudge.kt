package kr.archsurvey.core

import kr.archsurvey.device.LinkState

/** 상태 띠 3단계. 색+아이콘+글자 3중 표기는 UI 몫. */
enum class SurveyStatus(val labelKo: String) {
    FIXED("고정"), CAUTION("주의"), UNAVAILABLE("불가")
}

/** 판정 이유 — 오류 카드/문구용 */
enum class StatusReason(val textKo: String) {
    OK("RTK 고정"),
    FLOAT("플로트 — 아직 고정 전"),
    LOW_PRECISION("고정이지만 정밀도가 낮아요"),
    NO_SIGMA("정밀도 값을 받지 못했어요"),
    OLD_CORRECTION("보정 데이터가 오래됐어요"),
    /** 0.1.26: GGA 보정연령(필드13) 없음/파싱불가 — 신선으로 가정하지 않음. 기본=경고·저장, 엄격=차단 */
    UNKNOWN_AGE("보정 연령을 알 수 없어요"),
    SINGLE_OR_DGPS("보정 없이 단독 측위"),
    /** 0.1.11: DGPS·SBAS(품질 2·9) — 코드 보정은 있으나 RTK 아님(측점 불가는 그대로) */
    CODE_CORRECTED("코드 보정(DGPS·SBAS) — RTK 아님"),
    NO_POSITION("위치를 아직 못 찾았어요"),
    NO_DATA("수신기 데이터가 없어요"),
    STALE("수신 데이터가 잠시 끊겼어요"),
    /** 0.1.30 T24 UX리뷰 문구: 끊긴 직후·다시 연결 중 */
    LINK_DOWN("수신기 끊김"),
    /** 0.1.30 T24 UX리뷰 문구(예전 「수신기 연결 안 됨」). 폰 위치로 대신하지 않는다 */
    NOT_CONNECTED("수신기 없음"),
}

/** 임계값은 **제안값**(design.md §5C.15, MASTER-PLAN §10 #8). 현장 시험으로 조정한다 [현장검증]. */
data class StatusThresholds(
    /** 고정 허용 수평 σ(m) 이하 */
    val fixedMaxSigmaHM: Double = 0.02,
    /** 마지막 GGA 이후 이 시간이 넘으면 불가(끊김) */
    val staleAfterMs: Long = 5_000,
    /**
     * RTK 고정이어도 보정 연령(GGA 필드13 + 수신 후 경과)이 이 값(초)을 넘으면 주의.
     * 기본 10 s 유지. R10 디스플레이 age 캡≈9 s·Report max DQI=2 등은 [현장확인] — 임계를 9로 내리지 않고
     * age-unknown·엄격 모드로 보완한다.
     */
    val maxCorrectionAgeSec: Double = 10.0,
)

data class StatusResult(val status: SurveyStatus, val reason: StatusReason, val sigmaHM: Double?)

enum class SavePolicy { ALLOW, ALLOW_WITH_WARNING, BLOCK }

object StatusJudge {
    private const val EPS = 1e-9

    fun judge(snap: GnssSnapshot, link: LinkState, nowMs: Long, th: StatusThresholds = StatusThresholds()): StatusResult {
        val sigma = snap.gst?.sigmaHoriz
        // 0.1.30 T24: 수신기(블루투스 NMEA)만 판정한다. 폰 위치 경로 없음
        when (link) {
            LinkState.STREAMING -> {}
            LinkState.IDLE -> return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NOT_CONNECTED, null)
            LinkState.STALE -> return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.STALE, sigma)
            else -> return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.LINK_DOWN, sigma)
        }
        val gga = snap.gga ?: return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NO_DATA, null)
        if (nowMs - snap.lastGgaAtMs > th.staleAfterMs) return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.STALE, sigma)
        if (!gga.hasPosition) return StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NO_POSITION, sigma)
        return when (gga.quality) {
            FixQuality.RTK_FIXED -> {
                // age null ≠ 신선: 필드13 없음·공백 파싱 실패는 UNKNOWN_AGE(주의). ?: 0.0 금지.
                val age = snap.correctionAgeNowSec(nowMs)
                when {
                    age == null -> StatusResult(SurveyStatus.CAUTION, StatusReason.UNKNOWN_AGE, sigma)
                    age > th.maxCorrectionAgeSec -> StatusResult(SurveyStatus.CAUTION, StatusReason.OLD_CORRECTION, sigma)
                    sigma == null -> StatusResult(SurveyStatus.CAUTION, StatusReason.NO_SIGMA, null)
                    sigma > th.fixedMaxSigmaHM + EPS -> StatusResult(SurveyStatus.CAUTION, StatusReason.LOW_PRECISION, sigma)
                    else -> StatusResult(SurveyStatus.FIXED, StatusReason.OK, sigma)
                }
            }
            FixQuality.RTK_FLOAT -> StatusResult(SurveyStatus.CAUTION, StatusReason.FLOAT, sigma)
            FixQuality.NONE -> StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NO_POSITION, sigma)
            FixQuality.DGPS, FixQuality.SBAS -> StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.CODE_CORRECTED, sigma)
            else -> StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS, sigma)
        }
    }

    /**
     * 저장 정책.
     * - 기본(strictMode=false): 고정=저장, 주의=경고 후 저장, 불가=차단
     * - 엄격(strictMode=true): FIX(고정)만 저장. 주의(유동·보정나이·age없음·σ 등)도 차단
     */
    fun savePolicy(status: SurveyStatus, strictMode: Boolean = false): SavePolicy = when (status) {
        SurveyStatus.FIXED -> SavePolicy.ALLOW
        SurveyStatus.CAUTION -> if (strictMode) SavePolicy.BLOCK else SavePolicy.ALLOW_WITH_WARNING
        SurveyStatus.UNAVAILABLE -> SavePolicy.BLOCK
    }
}
