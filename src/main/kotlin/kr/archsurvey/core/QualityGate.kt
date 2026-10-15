package kr.archsurvey.core

import java.util.Locale

/**
 * 0.1.58 B3(사용자 결정 2026-10-15): 저장 품질 상한. 0.1.56 M1 「플로트 확인 시트(작업당 1번)」를 대신한다.
 * - 플로트, 또는 수평 오차 > 10 cm → 저장 차단(한 줄 이유, 창 없음, 지도 그대로)
 * - 그 밖의 주의(오차 2–10 cm·보정 연령·σ 없음) → 주황 한 줄 경고 + 저장
 * 근거: SmartTopo 실데이터(도뮌동고분군)에서 σH 0.75–0.82 m 플로트 점 4/11 이 그대로 저장됨.
 */
object QualityGate {
    const val MAX_SAVE_SIGMA_H_M = 0.10

    enum class Kind { OK, WARN, BLOCK }
    data class Decision(val kind: Kind, val line: String?)

    private fun cm(m: Double) = String.format(Locale.US, "%.0f", m * 100)

    fun decide(r: StatusResult): Decision {
        if (r.status == SurveyStatus.UNAVAILABLE) return Decision(Kind.BLOCK, "지금은 측점할 수 없어요 — ${r.reason.textKo}")
        val s = r.sigmaHM
        if (r.reason == StatusReason.FLOAT) return Decision(Kind.BLOCK, "플로트예요 — 고정(FIX)될 때까지 저장하지 않아요")
        if (s != null && s > MAX_SAVE_SIGMA_H_M + 1e-9) return Decision(Kind.BLOCK, "수평 오차 ±${cm(s)}cm — 10cm가 넘어 저장하지 않아요")
        if (r.status == SurveyStatus.CAUTION) {
            val tail = s?.let { " ±${cm(it)}cm" } ?: ""
            return Decision(Kind.WARN, "◐ ${r.reason.textKo}$tail · 주의로 저장")
        }
        return Decision(Kind.OK, null)
    }
}
