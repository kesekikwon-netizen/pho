package kr.archsurvey.ui

import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.StatusReason
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.engine.RtcmFreshness
import kr.archsurvey.ntrip.NtripState
import java.util.Locale

/** 상단 상태 알약·연결 막대의 색 구분. 화면은 [Tone] 을 테마 토큰 색으로 바꾼다 */
enum class Tone { NONE, FIXED, CAUTION, BAD, OK,
    /** 0.1.30 T24 「수신기 없음」: 회색 바탕 + 주의색 글자 */
    NO_RECEIVER }

/** 상단 상태 알약(목업 02~23 공통): 위성 수 | 보정 상태 | 고정·유동·미연결 */
data class HeaderPill(val sats: String, val correction: String, val correctionTone: Tone, val fix: String, val fixTone: Tone)

/** 홈 하단 연결 막대(목업 02): "R10 · 이름 연결됨" + 오른쪽 "VRS 수신 중" */
data class ConnBar(val left: String, val leftTone: Tone, val right: String, val rightTone: Tone)

/**
 * 0.1.11 머리글 문구 계산(순수 함수, JVM 시험 가능).
 * 판정(StatusJudge)과 보정 상태(NtripStatus)를 그대로 옮길 뿐, 새 판정을 만들지 않는다.
 */
object HeaderModel {
    private fun f3(x: Double) = String.format(Locale.US, "%.3f", x)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    /** 0.1.30 T24 머리글·상태 띠 공통 문구(UX리뷰) */
    /** 0.1.30 T23-c: 고정인데 GST 없음 — H/V 옆 작은 표시 */
    const val SIGMA_MISSING = "σ 없음"
    const val NO_RECEIVER = "수신기 없음"
    const val RECEIVER_LOST = "수신기 끊김"
    const val CONNECTING = "연결 중"

    fun fixLabel(q: FixQuality): String = when {
        q == FixQuality.RTK_FIXED -> "고정"
        q == FixQuality.RTK_FLOAT -> "유동"
        q == FixQuality.SINGLE -> "단독"
        q == FixQuality.DGPS -> "DGPS"
        q == FixQuality.SBAS -> "SBAS"
        q == FixQuality.NONE -> "위치 없음"
        else -> q.short
    }

    fun pill(gv: GnssView, ntrip: NtripStatus?, ntripEnabled: Boolean, nowMs: Long): HeaderPill {
        val reason = gv.status.reason
        // 0.1.30 T24: 수신기 없을 때 폰 위치로 채우지 않는다 — 「수신기 없음」(회색·주의색 글자, 누르면 연결 화면)
        if (reason == StatusReason.NOT_CONNECTED) return HeaderPill("0", "보정 —", Tone.NONE, NO_RECEIVER, Tone.NO_RECEIVER)
        if (reason == StatusReason.LINK_DOWN) return if (gv.link == kr.archsurvey.device.LinkState.CONNECTING || gv.link == kr.archsurvey.device.LinkState.CONNECTED_NO_DATA)
            HeaderPill("0", "보정 —", Tone.NONE, CONNECTING, Tone.NONE)
        else HeaderPill("0", "보정 —", Tone.NONE, RECEIVER_LOST, Tone.BAD)
        val fixTone = when (gv.displayStatus) { SurveyStatus.FIXED -> Tone.FIXED; SurveyStatus.CAUTION -> Tone.CAUTION; SurveyStatus.UNAVAILABLE -> Tone.BAD }
        val (corr, corrTone) = correction(gv, ntrip, ntripEnabled, nowMs)
        val fix = fixLabel(gv.quality)
        return HeaderPill(gv.satellites.toString(), corr, corrTone, fix, fixTone)
    }

    fun correction(gv: GnssView, ntrip: NtripStatus?, ntripEnabled: Boolean, nowMs: Long): Pair<String, Tone> {
        if (ntripEnabled && ntrip != null) {
            when (ntrip.state) {
                NtripState.RECEIVING -> return when (ntrip.freshness(nowMs)) {
                    RtcmFreshness.FRESH -> "보정 양호" to Tone.OK
                    RtcmFreshness.AGING -> "보정 지연" to Tone.CAUTION
                    RtcmFreshness.LOST -> "보정 끊김" to Tone.BAD
                    RtcmFreshness.NONE -> "보정 대기" to Tone.NONE
                }
                NtripState.CONNECTING, NtripState.CONNECTED, NtripState.RECONNECTING -> return "보정 접속 중" to Tone.NONE
                NtripState.AUTH_FAILED, NtripState.ERROR -> return "보정 오류" to Tone.BAD
                NtripState.IDLE -> if (!gv.quality.isCorrected) return "보정 대기" to Tone.NONE
            }
        }
        // VRS 를 쓰지 않거나 대기 중: 수신기가 알려 주는 GGA 품질·보정 연령으로만 말한다
        val age = gv.correctionAgeSec
        return when {
            !gv.quality.isCorrected -> "보정 없음" to Tone.NONE
            age == null -> "보정 수신" to Tone.OK
            age <= 5.0 -> "보정 양호" to Tone.OK
            else -> "보정 지연" to Tone.CAUTION
        }
    }

    /**
     * "PDOP 1.8 / H 0.008 / V 0.018" — 값이 없으면 "—" (H·V 는 GST 1σ, m).
     * 0.1.30 T23: PDOP(GSA)가 없고 GGA HDOP 가 있으면 「HDOP」라고 이름을 바꿔 보여 준다. σ 자리는 절대 HDOP 로 채우지 않는다.
     */
    fun metrics(gv: GnssView): String {
        val dop = when {
            gv.pdop != null -> "PDOP " + f1(gv.pdop)
            gv.hdop != null -> "HDOP " + f1(gv.hdop)
            else -> "PDOP —"
        }
        return dop + " / H " + (gv.status.sigmaHM?.let { f3(it) } ?: "—") + " / V " + (gv.sigmaVM?.let { f3(it) } ?: "—") +
            if (gv.sigmaMissingOnFix) " · $SIGMA_MISSING" else ""
    }

    fun antH(inputM: Double): String = "Ant.H " + f3(inputM)

    fun shortModel(displayName: String): String = displayName.substringAfterLast(' ').ifBlank { displayName }

    /**
     * 홈 하단 연결 막대. [deviceName] 은 폰에 등록된 블루투스 이름(없으면 주소).
     * [linkLive] = 위치 데이터 수신 중, [linkUp] = 소켓은 붙음(데이터 대기 포함).
     */
    fun connBar(model: String, deviceName: String?, linkLive: Boolean, linkUp: Boolean, connecting: Boolean, ntrip: NtripStatus?, ntripEnabled: Boolean, nowMs: Long): ConnBar {
        val dev = listOfNotNull(model, deviceName?.takeIf { it.isNotBlank() && it != model }).joinToString(" · ")
        val (left, lt) = when {
            linkLive -> "$dev 연결됨" to Tone.OK
            linkUp -> "$dev · 위치 데이터 대기" to Tone.CAUTION
            connecting -> "$dev 연결 중" to Tone.NONE
            else -> "수신기 연결 안 됨 · 눌러서 연결" to Tone.BAD
        }
        val (right, rt) = when {
            !ntripEnabled -> "VRS 끔" to Tone.NONE
            ntrip == null -> "VRS 대기" to Tone.NONE
            else -> when (ntrip.state) {
                NtripState.RECEIVING -> if (ntrip.freshness(nowMs) == RtcmFreshness.LOST) "VRS 끊김" to Tone.BAD else "VRS 수신 중" to Tone.OK
                NtripState.CONNECTING, NtripState.CONNECTED, NtripState.RECONNECTING -> "VRS 접속 중" to Tone.NONE
                NtripState.AUTH_FAILED -> "VRS 인증 실패" to Tone.BAD
                NtripState.ERROR -> "VRS 오류" to Tone.BAD
                NtripState.IDLE -> "VRS 대기" to Tone.NONE
            }
        }
        return ConnBar(left, lt, right, rt)
    }
}
