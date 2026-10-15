package kr.archsurvey.device

/**
 * 0.1.30 T24: 폰 내장 위치 한 개 — **지도 「내 위치」 점(표시 전용)**.
 * 측점 취득·평균·주의 승인·저장·내보내기 어디에도 쓰지 않는다(SurveyController 의 [GnssSource] 와 완전히 분리).
 * Android 의존 없음(시험에서 직접 만든다).
 */
data class PhoneFix(val lat: Double, val lon: Double, val accuracyM: Double, val atMs: Long)
