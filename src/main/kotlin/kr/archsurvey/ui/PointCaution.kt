package kr.archsurvey.ui

import kr.archsurvey.core.StatusThresholds
import kr.archsurvey.core.SurveyStatus

/**
 * 0.1.30 T23: 점 목록·상세에 보이는 주의 이유(**화면 표시만** — 내보내기 열은 바꾸지 않음).
 * 점에는 판정 이유가 따로 저장되지 않아 저장된 값(fixQuality·Float 표본·보정 연령·σH)에서 판정 순서(StatusJudge)대로 되짚는다.
 * 「σ 없음(고정)」 = 고정·보정 연령 정상인데 GST(σ)가 없던 점 — Float·보정 나이 초과와 구분.
 */
object PointCaution {
    const val NO_SIGMA_FIXED = "σ 없음(고정)"
    const val FLOAT = "Float"
    const val AGE_OVER = "보정 나이 초과"
    const val AGE_UNKNOWN = "보정 나이 모름"
    const val LOW_PRECISION = "정밀도 낮음"
    /** 0.1.20~0.1.29 에 수신기 없이 저장된 저정밀 점(품질 1). 수신기 단독 측위는 저장이 막혀 있어 이 표시는 그 점들만 해당 */
    const val LOW_SOURCE = "저정밀(단독)"

    fun reason(p: SavedPoint, th: StatusThresholds = StatusThresholds()): String? {
        if (!p.cautionFlag && p.status == SurveyStatus.FIXED) return null
        if (p.fixQuality == 1) return LOW_SOURCE
        if (p.fixQuality == 5 || p.floatEpochs > 0) return FLOAT
        val age = p.maxCorrectionAgeSec ?: p.correctionAgeSec
        if (age == null) return AGE_UNKNOWN
        if (age > th.maxCorrectionAgeSec) return AGE_OVER
        val s = p.sigmaHM
        if (p.fixQuality == 4 && s == null) return NO_SIGMA_FIXED
        if (s != null && s > th.fixedMaxSigmaHM + 1e-9) return LOW_PRECISION
        return null
    }

    /** 상세·목록 한 줄: 「고정」 / 「주의 · σ 없음(고정)」 */
    fun label(p: SavedPoint): String = when {
        p.status == SurveyStatus.FIXED && !p.cautionFlag -> "고정"
        reason(p) == NO_SIGMA_FIXED -> SigmaText.FIX_NO_SIGMA // 0.1.30 T23-c: 「주의」 대신 같은 문구
        else -> "주의" + (reason(p)?.let { " · $it" } ?: "")
    }
    /** 0.1.30 T23-c 표시 상태: 고정·σ만 없음 → 고정 색(저장된 주의 표시는 그대로) */
    fun displayStatus(p: SavedPoint): SurveyStatus = if (reason(p) == NO_SIGMA_FIXED) SurveyStatus.FIXED else p.status
    /** 0.1.30 UX-0130-1 점 목록 이유 줄: σ 없음(고정)은 「고정 · σ 없음」, 그 밖(Float·보정 나이 …)은 이유 그대로 */
    fun rowReason(p: SavedPoint): String? = reason(p)?.let { if (it == NO_SIGMA_FIXED) label(p) else it }
    /** 결과 칩 문구 */
    fun chip(p: SavedPoint): String = when {
        reason(p) == NO_SIGMA_FIXED -> SigmaText.FIX_NO_SIGMA
        p.status == SurveyStatus.FIXED -> "고정"
        else -> "주의"
    }
}

/**
 * 0.1.30 T23-c(게이트 결정): 「RTK 고정 + 보정 양호 + GST(σ)만 없음」 상태를 띠·위치 표시·측점 버튼·확인 창·저장 알림·목록이
 * **같은 말**로 보여 준다. 표시만 바꾼 것 — 저장 규칙(기본 = 주의 표시로 저장 · 엄격 = 막힘)은 그대로. H·V 는 「—」(추정 안 함).
 */
object SigmaText {
    const val FIX_NO_SIGMA = "고정 · σ 없음"
    /** 기본 모드 띠 부제 */
    const val BAND_SUB_NORMAL = "H·V — · 저장하면 점에 「σ 없음(고정)」 표시"
    const val CONFIRM_TITLE = "고정 · σ 없음 — 측점할까요?"
    const val CONFIRM_BODY = "수신기가 정밀도(σ, GST)를 보내지 않아 H·V 없이 저장돼요. 점에 「σ 없음(고정)」 표시가 남아요."
    const val CONFIRM_OK = "측점"
    const val SAVED_SUFFIX = " · 고정 · σ 없음"
    const val SAVED_MESSAGE = "고정 · σ 없음으로 저장했어요 (H·V 없음 · 「σ 없음(고정)」 표시)"
    const val VOICE_PROMPT = "고정, 시그마 없음. 저장할까요?"
    /** 엄격 모드 막힘(T23-b) */
    const val STRICT_BAND_MAIN = "측점 불가 · 수신기 정밀도 출력 꺼짐"
    const val STRICT_BUTTON = "측점 불가"
    const val STRICT_BUTTON_SUB = "수신기 정밀도 출력 꺼짐"
}
