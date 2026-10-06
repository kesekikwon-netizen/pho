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
    fun label(p: SavedPoint): String =
        if (p.status == SurveyStatus.FIXED && !p.cautionFlag) "고정" else "주의" + (reason(p)?.let { " · $it" } ?: "")
}
