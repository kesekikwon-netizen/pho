package kr.archsurvey.ai

import kr.archsurvey.bt.BtError
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.StatusReason
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.Phase

/**
 * AI 연동용 **읽기 전용** 상태 요약 `StatusSnapshot v1` (MASTER-PLAN §5).
 *
 * 규칙(테스트로 강제: `AiSnapshotTests`):
 *  - 필드는 **열거형·숫자·시각·불리언**뿐이다. 문자열 필드 없음 → 좌표·VRS 계정·접속 주소·점 이름·자유 문장이 들어갈 자리가 없다.
 *  - 이 파일과 같은 패키지에는 **네트워크 전송 코드가 없다**(전송은 이 플랜 범위 밖). 만들어진 값은 메모리에 있다가 사라진다.
 *  - 만드는 일은 읽기만 한다. 측점 취득·저장 경로를 호출하거나 바꾸지 않는다.
 *
 * 필드를 추가할 때는 같은 규칙(타입 제한 + 금지 이름 검사)을 통과해야 한다.
 */
data class StatusSnapshot(
    /** 스키마 버전. 필드가 바뀌면 올린다. */
    val schema: Int = SCHEMA_VERSION,
    /** 만든 시각(기기 벽시계, epoch ms) — 호출자가 넘긴다. */
    val atMs: Long,
    val appVersionCode: Int,
    // ── 수신기 연결 ──
    val link: LinkState,
    val linkAttempt: Int,
    val linkFailures: Int,
    /** 연결 오류(없으면 null). 오류 코드 체계 BT-1xx 의 열거형 */
    val btError: BtError?,
    // ── 측위 ──
    val surveyStatus: SurveyStatus,
    val statusReason: StatusReason,
    val fixQuality: FixQuality,
    val satellites: Int,
    val hdop: Double?,
    /** 수평 정밀도(σ, m) */
    val sigmaHM: Double?,
    val correctionAgeSec: Double?,
    /** 마지막 GGA 이후 경과(ms). 한 번도 못 받았으면 null */
    val dataAgeMs: Long?,
    val sentencesTotal: Long,
    val checksumErrors: Long,
    // ── 보정(VRS) ── 상태만. 계정·주소·마운트포인트는 포함하지 않는다.
    val ntrip: NtripState?,
    // ── 앱/저장 ──
    val phase: Phase,
    val savedPointCount: Int,
    val batteryPct: Int?,
) {
    /** 값이 없으면 null 로 두는 평평한 맵(전송 없이 화면·로그 미리보기용). 값은 숫자·불리언·열거형 이름(영문 상수)뿐. */
    fun toFlatMap(): Map<String, Any?> = linkedMapOf(
        "schema" to schema, "atMs" to atMs, "appVersionCode" to appVersionCode,
        "link" to link.name, "linkAttempt" to linkAttempt, "linkFailures" to linkFailures, "btError" to btError?.name,
        "surveyStatus" to surveyStatus.name, "statusReason" to statusReason.name, "fixQuality" to fixQuality.name,
        "satellites" to satellites, "hdop" to hdop, "sigmaHM" to sigmaHM, "correctionAgeSec" to correctionAgeSec,
        "dataAgeMs" to dataAgeMs, "sentencesTotal" to sentencesTotal, "checksumErrors" to checksumErrors,
        "ntrip" to ntrip?.name, "phase" to phase.name, "savedPointCount" to savedPointCount, "batteryPct" to batteryPct,
    )

    /** JSON 문자열(직접 조립 — 값이 숫자·열거형 이름·null 뿐이라 따옴표 이스케이프가 필요 없다). */
    fun toJson(): String = toFlatMap().entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) { null -> "null"; is String -> "\"$v\""; else -> v.toString() }
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

/** 현재 앱 상태 조각들을 읽어 [StatusSnapshot] 으로 줄여 주는 순수 함수. 부작용 없음. */
object StatusSnapshotBuilder {
    fun build(
        nowMs: Long,
        appVersionCode: Int,
        gnss: GnssSnapshot,
        link: LinkStatus,
        sourceClockMs: Long,
        phase: Phase,
        savedPointCount: Int,
        ntrip: NtripState? = null,
        batteryPct: Int? = null,
        thresholds: kr.archsurvey.core.StatusThresholds = kr.archsurvey.core.StatusThresholds(),
    ): StatusSnapshot {
        val judged = StatusJudge.judge(gnss, link.state, sourceClockMs, thresholds)
        val gga = gnss.gga
        return StatusSnapshot(
            atMs = nowMs, appVersionCode = appVersionCode,
            link = link.state, linkAttempt = link.attempt, linkFailures = link.failures, btError = link.error,
            surveyStatus = judged.status, statusReason = judged.reason, fixQuality = gnss.quality,
            satellites = gga?.satellites ?: 0, hdop = gga?.hdop, sigmaHM = judged.sigmaHM,
            correctionAgeSec = gga?.correctionAgeSec,
            dataAgeMs = if (gnss.lastGgaAtMs > 0) (sourceClockMs - gnss.lastGgaAtMs).coerceAtLeast(0) else null,
            sentencesTotal = gnss.sentencesTotal, checksumErrors = gnss.checksumErrors,
            ntrip = ntrip, phase = phase, savedPointCount = savedPointCount, batteryPct = batteryPct?.coerceIn(0, 100),
        )
    }
}

/**
 * 설정의 "AI 연동" 스위치 자리. **꺼짐 고정** — 켜는 경로와 전송 구현이 이 플랜에는 없다.
 * (켜기를 허용하려면 서버 방식·좌표 미전송 동의 등 MASTER-PLAN §13 #9 결정이 먼저 필요.)
 */
object AiLinkSetting {
    const val LABEL = "AI 연동"
    const val NOTE = "꺼짐 · 준비 중"
    /** 항상 false. 저장소·설정 파일을 읽지 않는다. */
    val enabled: Boolean get() = false
    /** 상태를 밖으로 보내는 코드가 이 앱에 들어 있는가 — 현재 아니오 */
    const val TRANSMISSION_IMPLEMENTED = false
}
