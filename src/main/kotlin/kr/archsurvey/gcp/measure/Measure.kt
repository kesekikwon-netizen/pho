package kr.archsurvey.gcp.measure

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.PointAverager
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.StatusReason
import kr.archsurvey.core.StatusResult
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.core.TmProjection
import kr.archsurvey.device.GnssSource

/**
 * 엄격 FIX 만: RTK 고정 + 보정 연령 정상 + σ ≤ 2 cm(측량 앱 StatusJudge 그대로).
 * 수신기가 σ(GST)를 아예 안 보내는 「고정 · σ 없음」만 한 번 묻고 잰다(R10 기본 설정). 플로트·단독·끊김·보정 오래됨·연령 모름은 막는다.
 */
sealed interface Gate {
    data object Ok : Gate
    data object AskNoSigma : Gate
    data class Blocked(val reasonKo: String) : Gate
}

object MeasureGate {
    fun check(r: StatusResult): Gate = when {
        r.status == SurveyStatus.FIXED -> Gate.Ok
        r.status == SurveyStatus.CAUTION && r.reason == StatusReason.NO_SIGMA -> Gate.AskNoSigma
        else -> Gate.Blocked(blockText(r.reason))
    }

    fun blockText(reason: StatusReason): String = when (reason) {
        StatusReason.FLOAT -> "FLOAT — 고정(FIX)될 때까지 기다리세요"
        StatusReason.LOW_PRECISION -> "고정이지만 정밀도가 2 cm보다 나빠요"
        StatusReason.OLD_CORRECTION -> "보정 데이터가 오래됐어요 — VRS 연결을 확인하세요"
        StatusReason.UNKNOWN_AGE -> "보정 연령을 알 수 없어요 — VRS 보정이 들어오는지 확인하세요"
        StatusReason.NOT_CONNECTED -> "수신기가 연결되지 않았어요"
        else -> reason.textKo
    }

    /** 상태 알약 짧은 글: FIX ±0.8 cm / FLOAT / 연결 안 됨 */
    fun pill(r: StatusResult, q: FixQuality): String = when {
        r.reason == StatusReason.NOT_CONNECTED -> "연결 안 됨"
        q == FixQuality.RTK_FIXED -> "FIX " + (r.sigmaHM?.let { String.format(java.util.Locale.US, "±%.1f cm", it * 100) } ?: "σ 없음")
        q == FixQuality.RTK_FLOAT -> "FLOAT"
        r.status == SurveyStatus.UNAVAILABLE -> when (r.reason) { StatusReason.STALE, StatusReason.LINK_DOWN, StatusReason.NO_DATA -> "수신 끊김"; else -> "단독" }
        else -> r.reason.textKo
    }
}

data class Measured(
    val lat: Double, val lon: Double, val e: Double, val n: Double,
    /** 지면 높이 = 수신기 해발(GGA, 수신기 지오이드 그대로) 평균 − 안테나 높이(폴 + 기준점→위상중심, 측량 앱 AntennaRecord 와 같음) */
    val h: Double, val apcMsl: Double, val sigmaH: Double?, val sigmaU: Double?, val epochs: Int, val fixedEpochs: Int,
)

sealed interface MeasureResult {
    data class Ok(val m: Measured) : MeasureResult
    data class Aborted(val reasonKo: String) : MeasureResult
}

class TagMeasurer(private val source: GnssSource) {
    suspend fun measure(epochs: Int, zone: KoreanTmZone, apcHeightM: Double, allowNoSigma: Boolean, onProgress: (Float) -> Unit = {}): MeasureResult {
        val avg = PointAverager(epochs)
        var abort: String? = null
        onProgress(0f)
        val done = withTimeoutOrNull(epochs * 3_000L + 8_000L) {
            combine(source.gnss, source.link) { g, l -> g to l }.first { (g, l) ->
                val now = source.clockMs()
                val r = StatusJudge.judge(g, l.state, now)
                val ok = r.status == SurveyStatus.FIXED || (allowNoSigma && r.status == SurveyStatus.CAUTION && r.reason == StatusReason.NO_SIGMA)
                if (!ok) { abort = MeasureGate.blockText(r.reason); return@first true }
                g.gga?.let { if (it.quality == FixQuality.RTK_FIXED) avg.add(it, g.freshGst(now), now) }
                onProgress(avg.progress)
                avg.done
            }
            true
        }
        onProgress(0f)
        abort?.let { return MeasureResult.Aborted("측점을 멈췄어요 — $it. 저장하지 않았어요.") }
        if (done != true) return MeasureResult.Aborted("시간 안에 ${epochs}회를 받지 못했어요. 저장하지 않았어요.")
        val p = avg.result() ?: return MeasureResult.Aborted("위치를 얻지 못했어요")
        if (!p.allFixed) return MeasureResult.Aborted("평균 중 FIX 가 아닌 값이 섞였어요. 저장하지 않았어요.")
        val msl = p.altitudeMsl ?: return MeasureResult.Aborted("수신기가 해발 높이(GGA)를 보내지 않아요")
        val tm = TmProjection.forward(p.lat, p.lon, zone)
        return MeasureResult.Ok(Measured(p.lat, p.lon, tm.easting, tm.northing, msl - apcHeightM, msl, p.sigmaH, p.sigmaU, p.epochs, p.fixedEpochs))
    }
}
