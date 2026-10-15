package kr.archsurvey.t0158

import kr.archsurvey.core.*
import org.junit.Assert.*
import org.junit.Test

class QualityGate0158Test {
    private fun r(s: SurveyStatus, reason: StatusReason, sig: Double?) = StatusResult(s, reason, sig)

    @Test fun floatBlocked() {
        val d = QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.FLOAT, 0.03))
        assertEquals(QualityGate.Kind.BLOCK, d.kind); assertTrue(d.line!!.contains("플로트"))
    }
    @Test fun over10cmBlocked() {
        val d = QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.LOW_PRECISION, 0.11))
        assertEquals(QualityGate.Kind.BLOCK, d.kind); assertTrue(d.line!!.contains("±11cm"))
        assertFalse(d.line!!.contains("\n"))
    }
    @Test fun between2and10Warn() {
        val d = QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.LOW_PRECISION, 0.05))
        assertEquals(QualityGate.Kind.WARN, d.kind); assertTrue(d.line!!.contains("±5cm"))
    }
    @Test fun exactly10Warn() = assertEquals(QualityGate.Kind.WARN, QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.LOW_PRECISION, 0.10)).kind)
    @Test fun fixedOk() = assertEquals(QualityGate.Kind.OK, QualityGate.decide(r(SurveyStatus.FIXED, StatusReason.OK, 0.01)).kind)
    @Test fun noSigmaWarnNotBlock() = assertEquals(QualityGate.Kind.WARN, QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.NO_SIGMA, null)).kind)
    @Test fun smartTopoFieldCase_0_76m_blocked() = assertEquals(QualityGate.Kind.BLOCK, QualityGate.decide(r(SurveyStatus.CAUTION, StatusReason.FLOAT, 0.762)).kind)
}
