package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.11 머리글(상태 알약·작업 줄·연결 막대) 문구 계산. 실기기 아님 — 순수 함수 시험 */
class HeaderModelTest {
    private fun gv(status: SurveyStatus, reason: StatusReason, q: FixQuality, sats: Int = 23, sigmaH: Double? = 0.008, sigmaV: Double? = 0.018, pdop: Double? = 1.8, age: Double? = 1.0) =
        GnssView(StatusResult(status, reason, sigmaH), q, sats, 37.0, 127.0, sigmaV, 0.9, 30, 50.0, 1000L, pdop = pdop, correctionAgeSec = age)

    private val fixed = gv(SurveyStatus.FIXED, StatusReason.OK, FixQuality.RTK_FIXED)

    @Test fun notConnectedPillIsGrey() {
        val p = HeaderModel.pill(GnssView(), null, false, 0)
        // 0.1.30 T24(UX리뷰): 「미연결」 → 「수신기 없음」(회색 바탕·주의색 글자)
        assertEquals("수신기 없음", p.fix); assertEquals(Tone.NO_RECEIVER, p.fixTone); assertEquals("0", p.sats); assertEquals("보정 —", p.correction)
    }

    @Test fun linkDownIsRed() {
        val p = HeaderModel.pill(gv(SurveyStatus.UNAVAILABLE, StatusReason.LINK_DOWN, FixQuality.RTK_FIXED), null, false, 0)
        assertEquals("수신기 끊김", p.fix); assertEquals(Tone.BAD, p.fixTone)
    }

    @Test fun fixedBlueFloatYellowWithFreshVrs() {
        val ntrip = NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 10_000)
        val p = HeaderModel.pill(fixed, ntrip, true, 10_500)
        assertEquals("23", p.sats); assertEquals("고정", p.fix); assertEquals(Tone.FIXED, p.fixTone)
        assertEquals("보정 양호", p.correction); assertEquals(Tone.OK, p.correctionTone)
        val fl = HeaderModel.pill(gv(SurveyStatus.CAUTION, StatusReason.FLOAT, FixQuality.RTK_FLOAT), ntrip, true, 10_500)
        assertEquals("유동", fl.fix); assertEquals(Tone.CAUTION, fl.fixTone)
    }

    @Test fun vrsAgingLostAndErrors() {
        assertEquals("보정 지연", HeaderModel.correction(fixed, NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 1_000), true, 6_000).first)
        assertEquals("보정 끊김", HeaderModel.correction(fixed, NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 1_000), true, 20_000).first)
        assertEquals("보정 오류", HeaderModel.correction(fixed, NtripStatus(state = NtripState.AUTH_FAILED), true, 0).first)
        assertEquals("보정 접속 중", HeaderModel.correction(fixed, NtripStatus(state = NtripState.CONNECTING), true, 0).first)
    }

    @Test fun withoutVrsUsesGgaQualityAndAge() {
        assertEquals("보정 양호", HeaderModel.correction(fixed, null, false, 0).first)
        assertEquals("보정 지연", HeaderModel.correction(fixed.copy(correctionAgeSec = 12.0), null, false, 0).first)
        assertEquals("보정 없음", HeaderModel.correction(fixed.copy(quality = FixQuality.SINGLE), null, false, 0).first)
        // 0.1.10 규칙 유지: 품질 9 = SBAS(보정됨, 위치 있음)
        assertEquals("SBAS", HeaderModel.fixLabel(FixQuality.SBAS))
        assertNotEquals("보정 없음", HeaderModel.correction(fixed.copy(quality = FixQuality.SBAS), null, false, 0).first)
    }

    @Test fun metricsLineMatchesMockupFormat() {
        assertEquals("PDOP 1.8 / H 0.008 / V 0.018", HeaderModel.metrics(fixed))
        assertEquals("PDOP — / H — / V —", HeaderModel.metrics(GnssView()))
        assertEquals("Ant.H 2.000", HeaderModel.antH(2.0))
    }

    @Test fun connectionBar() {
        val ok = HeaderModel.connBar("R10", "Trimble R10-5512", true, true, false, NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 100), true, 200)
        assertEquals("R10 · Trimble R10-5512 연결됨", ok.left); assertEquals("VRS 수신 중", ok.right); assertEquals(Tone.OK, ok.leftTone)
        val none = HeaderModel.connBar("SP60", null, false, false, false, null, true, 0)
        assertTrue(none.left.contains("연결 안 됨")); assertEquals(Tone.BAD, none.leftTone); assertEquals("VRS 대기", none.right)
        assertEquals("VRS 끔", HeaderModel.connBar("R10", null, false, false, false, null, false, 0).right)
        assertEquals("SP60", HeaderModel.shortModel("Spectra Precision SP60"))
    }

    @Test fun versionLabelShortForm() {
        assertEquals("Ver.0.1.11 · 빌드 12", versionShort("버전 0.1.11 (빌드 12)"))
        assertEquals("버전 확인 불가", versionShort("버전 확인 불가"))
    }

    @Test fun versionIs0_1_27Build28() {
        val g = File("build.gradle.kts").readText()
        assertTrue(g.contains("versionCode = 34")); assertTrue(g.contains("versionName = \"0.1.33\""))
    }

    /** 고고학 발굴 용어만: 새 화면에 토목·건설 용어가 없어야 한다 */
    @Test fun noCivilTermsInNewScreens() {
        val forbidden = listOf("성토", "절토", "시공", "준공", "검측", "측설", "노선", "도로", "정지측량", "드론")
        for (f in listOf("HomeScreen.kt", "AppHeader.kt", "HeaderModel.kt", "MapChrome.kt", "ConnectionScreen.kt", "VWorldScreen.kt")) {
            // 0.1.18: 「지도로 가기」(HTML 66)의 '지도로'가 '도로'로 잘못 걸리지 않게 빼고 검사
            val src = File("src/main/kotlin/kr/archsurvey/ui/$f").readText().replace("지도로", "")
            for (w in forbidden) assertFalse("$f 에 '$w'", src.contains(w))
            assertFalse("$f 에 자리표시자", src.contains("[앱 이름]") || src.contains("[버전"))
        }
    }
}
