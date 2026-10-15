package kr.archsurvey.gcp.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * iTwin Capture SurveysData XML · TXT. 측량 앱 0.1.37 `ItwinCaptureXml.writeSurveys` · `ItwinGcpExport.txt` 와 같은 글자 그대로 옮김
 * (기준 = 사용자 실제 iTwin Capture 파일 강릉 1호, Measurement 만 없음):
 * UTF-8(BOM 없음) · CRLF · 탭 들여쓰기 · SRS Id 0 · SRSId 0 · 이름 「Target #N」(N = 실제 표적 번호) · Category Full ·
 * x=동(E) y=북(N) z=지면 높이 · 정밀도 0.01 · CheckPoint false · 표적 번호 오름차순.
 */
object ItwinXml {
    const val ACCURACY_M = 0.01

    fun targetName(tag: Int) = "Target #$tag"

    fun srsName(epsg: Int): String = when (epsg) {
        5185 -> "Korea 2000 / West Belt 2010 (EPSG:5185)"
        5186 -> "Korea 2000 / Central Belt 2010 (EPSG:5186)"
        5187 -> "Korea 2000 / East Belt 2010 (EPSG:5187)"
        5188 -> "Korea 2000 / East Sea Belt 2010 (EPSG:5188)"
        5179 -> "Korea 2000 / Unified CS (EPSG:5179)"
        else -> "EPSG:$epsg"
    }

    /** 군더더기 0 을 뗀 숫자(98115.0, 57.15, 0.01). 소수 [d] 자리 반올림 */
    fun num(v: Double, d: Int = 3): String {
        val s = BigDecimal(v).setScale(d, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if ('.' in s) s else "$s.0"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }

    /** 한 차수의 지금 표적(active) → SurveysData. 같은 번호가 여럿이면 마지막 값 */
    fun write(shots: List<Shot>, epsg: Int): String {
        val nl = "\r\n"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>").append(nl).append("<SurveysData>").append(nl)
        sb.append("\t<SpatialReferenceSystems>").append(nl).append("\t\t<SRS>").append(nl).append("\t\t\t<Id>0</Id>").append(nl)
        sb.append("\t\t\t<Name>").append(esc(srsName(epsg))).append("</Name>").append(nl)
        sb.append("\t\t\t<Definition>EPSG:").append(epsg).append("</Definition>").append(nl)
        sb.append("\t\t</SRS>").append(nl).append("\t</SpatialReferenceSystems>").append(nl).append("\t<ControlPoints>").append(nl)
        latest(shots).forEachIndexed { i, p ->
            sb.append("\t\t<ControlPoint>").append(nl)
            sb.append("\t\t\t<Id>").append(i).append("</Id>").append(nl)
            sb.append("\t\t\t<SRSId>0</SRSId>").append(nl)
            sb.append("\t\t\t<Name>").append(esc(targetName(p.tag))).append("</Name>").append(nl)
            sb.append("\t\t\t<Category>Full</Category>").append(nl)
            sb.append("\t\t\t<Position>").append(nl)
            sb.append("\t\t\t\t<x>").append(num(p.e)).append("</x>").append(nl)
            sb.append("\t\t\t\t<y>").append(num(p.n)).append("</y>").append(nl)
            sb.append("\t\t\t\t<z>").append(num(p.h)).append("</z>").append(nl)
            sb.append("\t\t\t</Position>").append(nl)
            sb.append("\t\t\t<HorizontalAccuracy>").append(num(ACCURACY_M)).append("</HorizontalAccuracy>").append(nl)
            sb.append("\t\t\t<VerticalAccuracy>").append(num(ACCURACY_M)).append("</VerticalAccuracy>").append(nl)
            sb.append("\t\t\t<CheckPoint>false</CheckPoint>").append(nl)
            sb.append("\t\t</ControlPoint>").append(nl)
        }
        sb.append("\t</ControlPoints>").append(nl).append("</SurveysData>").append(nl)
        return sb.toString()
    }

    /** iTwin 「Custom Text Format」: 머리줄 없음 · 쉼표 · 「Target #N,E,N,Z」 · CRLF (측량 앱 ItwinGcpExport.txt 와 같음) */
    fun txt(shots: List<Shot>): String = latest(shots).joinToString("") { p -> "${targetName(p.tag)},${f3(p.e)},${f3(p.n)},${f3(p.h)}\r\n" }

    /** 0.1.18 M2: 참고용/좌표.csv — UTF-8 BOM · 머리줄 · 이름 원문(Target #N) · CRLF */
    fun refCsv(shots: List<Shot>): String = "\uFEFF이름,E(동),N(북),높이\r\n" + latest(shots).joinToString("") { p -> "${targetName(p.tag)},${f3(p.e)},${f3(p.n)},${f3(p.h)}\r\n" }

    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)

    /** active 만, 번호마다 마지막에 잰 값, 번호 오름차순 */
    fun latest(shots: List<Shot>): List<Shot> =
        shots.filter { it.active }.groupBy { it.tag }.map { (_, l) -> l.maxWith(compareBy({ it.measuredAt }, { it.id })) }.sortedBy { it.tag }
}
