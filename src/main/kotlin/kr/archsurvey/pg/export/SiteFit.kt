package kr.archsurvey.pg.export

import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.tag.TargetNaming
import java.util.Locale

/** 0.0.12 기준점 맞춤 점검(정보만, 막지 않음): RMSE, 최대 잔차, 점별 잔차(cm) */
object SiteFit {
    data class Row(val target: Int, val dE: Double, val dN: Double, val dH: Double) { val total get() = Math.sqrt(dE * dE + dN * dN + dH * dH) }
    data class Summary(val rmseCm: Double, val maxCm: Double, val maxTarget: Int, val looRmseCm: Double?, val rows: List<Row>)

    fun summary(f: Helmert.Fit): Summary {
        val rows = f.residuals.entries.sortedBy { it.key }.map { (k, v) -> Row(k, v.x * 100, v.y * 100, v.z * 100) }
        val mx = rows.maxByOrNull { it.total }!!
        return Summary(f.rmse * 100, mx.total, mx.target, f.looRmse?.let { it * 100 }, rows)
    }

    /** 화면 한 줄 */
    fun line(s: Summary) = String.format(Locale.US, "RMSE %.1f cm · 최대 잔차 %.1f cm (표적 #%d)", s.rmseCm, s.maxCm, s.maxTarget)
    fun rowLine(r: Row) = String.format(Locale.US, "표적 #%d  %.1f cm (동 %+.1f 북 %+.1f 높이 %+.1f)", r.target, r.total, r.dE, r.dN, r.dH)

    /** 작업정보.txt 덧붙임(파일 이름 규칙: Target #N) */
    fun text(f: Helmert.Fit?, poses: Int, photos: Int): String = buildString {
        val nl = "\r\n"
        append("사진 초기 자세: ").append(poses).append("/").append(photos).append("장 block.xmlz 에 들어 있음").append(nl)
        if (f == null) return@buildString
        val s = summary(f)
        append(String.format(Locale.US, "기준점 맞춤(휴대폰 미리보기): RMSE %.1f cm, 최대 잔차 %.1f cm (%s)", s.rmseCm, s.maxCm, TargetNaming.name(TargetNaming.tagId(s.maxTarget)))).append(nl)
        s.looRmseCm?.let { append(String.format(Locale.US, "검사점 오차(하나 빼기): %.1f cm", it)).append(nl) }
        for (r in s.rows) append(String.format(Locale.US, "  %s: %.1f cm (dE %+.1f, dN %+.1f, dH %+.1f)", TargetNaming.name(TargetNaming.tagId(r.target)), r.total, r.dE, r.dN, r.dH)).append(nl)
    }
}
