package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * iTwin Capture Modeler(Flex) 「Surveys › Import › Surveys data(XML)」 기준점 파일.
 * 꼴은 사용자 표본 build-reports/itwin-capture-xml-samples/sample-1..4.xml 과 같게 쓴다:
 * BOM 없음, LF, 탭 들여쓰기, `<?xml version="1.0" encoding="utf-8"?>`, 뿌리 SurveysData,
 * SRS 하나(Id 1, Definition EPSG:<작업 좌표계>), ControlPoint(Id 0부터, SRSId 1, Name, Category Full,
 * Position x=동(E)·y=북(N)·z=지면 표고, HorizontalAccuracy, VerticalAccuracy, CheckPoint).
 *
 * 이름: 기본은 현장 번호 「1호」「2호」「10호」 → 「Target #1」「Target #2」「Target #10」(iTwin 이 QR/타깃에서 읽는 이름과 맞춤).
 * 숫자 한 묶음만 있을 때만 바꾼다(앞 0 은 뗌). 숫자가 없거나 둘 이상이거나 같은 번호가 이미 쓰였으면 원래 이름을 그대로 둔다.
 */
object ItwinCaptureXml {
    /** iTwin 기본 정확도(표본 값). 측정 시그마가 없거나 이보다 작으면 이 값 */
    const val MIN_ACCURACY_M = 0.01

    data class Entry(val point: PointEntity, val name: String, val targetNo: Long?, val renamed: Boolean, val checkPoint: Boolean)

    private val DIGITS = Regex("[0-9]+")

    /** 「1호」→1, 「T-03」→3, 「Target #7」→7. 숫자 묶음이 정확히 하나일 때만 */
    fun targetNumber(pointId: String): Long? {
        val runs = DIGITS.findAll(pointId).map { it.value }.toList()
        if (runs.size != 1) return null
        val v = runs[0].trimStart('0').ifEmpty { "0" }
        return if (v.length > 15) null else v.toLong()
    }

    fun targetName(n: Long) = "Target #$n"

    /** 검사점: 위치 종류나 메모에 「검사점」「검증점」「check」가 있으면 true(그 밖은 표본처럼 false) */
    fun isCheckPoint(p: PointEntity): Boolean {
        val s = (p.positionType + " " + p.memo).lowercase()
        return "검사점" in s || "검증점" in s || "check" in s
    }

    /**
     * 내보낼 순서·이름. Target 번호가 있는 점은 번호 오름차순, 나머지는 측정 순서대로 뒤에.
     * [keepOriginalNames] = true 면 이름을 바꾸지 않는다(순서만 같은 규칙).
     */
    fun entries(points: List<PointEntity>, keepOriginalNames: Boolean = false): List<Entry> {
        val used = HashSet<Long>()
        val raw = points.map { p ->
            val n = targetNumber(p.pointId)
            val first = n != null && used.add(n)
            val no = if (first) n else null
            val name = if (!keepOriginalNames && no != null) targetName(no) else p.pointId
            Entry(p, name, no, name != p.pointId, isCheckPoint(p))
        }
        val (numbered, other) = raw.partition { it.targetNo != null }
        return numbered.sortedBy { it.targetNo } + other
    }

    fun srsName(epsg: Int): String = when (epsg) {
        5185 -> "Korea 2000 / West Belt 2010 (EPSG:5185)"
        5186 -> "Korea 2000 / Central Belt 2010 (EPSG:5186)"
        5187 -> "Korea 2000 / East Belt 2010 (EPSG:5187)"
        5188 -> "Korea 2000 / East Sea Belt 2010 (EPSG:5188)"
        5179 -> "Korea 2000 / Unified CS (EPSG:5179)"
        else -> "EPSG:$epsg"
    }

    /** 표본처럼 군더더기 0 을 뗀 숫자(98115.0, 57.15, 0.01). 소수 [d] 자리에서 반올림 */
    fun num(v: Double, d: Int = 3): String {
        val s = BigDecimal(v).setScale(d, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if ('.' in s) s else "$s.0"
    }

    fun accuracy(sigma: Double?): String {
        val a = if (sigma == null || sigma.isNaN() || sigma < MIN_ACCURACY_M) MIN_ACCURACY_M else sigma
        return num(a, 3)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }

    /** 한 좌표계 점들 → SurveysData XML(문자열, UTF-8 로 쓸 것). 높이 없는 점은 z 를 0 으로 쓰지 않고 뺀다 */
    fun write(points: List<PointEntity>, epsg: Int, keepOriginalNames: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<SurveysData>\n")
        sb.append("\t<SpatialReferenceSystems>\n\t\t<SRS>\n\t\t\t<Id>1</Id>\n")
        sb.append("\t\t\t<Name>").append(esc(srsName(epsg))).append("</Name>\n")
        sb.append("\t\t\t<Definition>EPSG:").append(epsg).append("</Definition>\n")
        sb.append("\t\t</SRS>\n\t</SpatialReferenceSystems>\n\t<ControlPoints>\n")
        entries(points.filter { MoreFormats.groundZ(it) != null }, keepOriginalNames).forEachIndexed { i, e ->
            val p = e.point
            sb.append("\t\t<ControlPoint>\n")
            sb.append("\t\t\t<Id>").append(i).append("</Id>\n")
            sb.append("\t\t\t<SRSId>1</SRSId>\n")
            sb.append("\t\t\t<Name>").append(esc(e.name)).append("</Name>\n")
            sb.append("\t\t\t<Category>Full</Category>\n")
            sb.append("\t\t\t<Position>\n")
            sb.append("\t\t\t\t<x>").append(num(p.easting)).append("</x>\n")
            sb.append("\t\t\t\t<y>").append(num(p.northing)).append("</y>\n")
            sb.append("\t\t\t\t<z>").append(num(MoreFormats.groundZ(p)!!)).append("</z>\n")
            sb.append("\t\t\t</Position>\n")
            sb.append("\t\t\t<HorizontalAccuracy>").append(accuracy(p.sigmaH)).append("</HorizontalAccuracy>\n")
            sb.append("\t\t\t<VerticalAccuracy>").append(accuracy(p.sigmaU)).append("</VerticalAccuracy>\n")
            sb.append("\t\t\t<CheckPoint>").append(e.checkPoint).append("</CheckPoint>\n")
            sb.append("\t\t</ControlPoint>\n")
        }
        sb.append("\t</ControlPoints>\n</SurveysData>\n")
        return sb.toString()
    }

    /**
     * 0.1.36: 이름을 정해서 쓰기(사진 기준점 「AprilTag #12」). 꼴은 [write] 와 바이트 단위로 같고 이름·순서만 넘겨받는다.
     * 높이 없는 점은 뺀다. 검사점은 [isCheckPoint] 규칙 그대로
     */
    fun writeNamed(named: List<Pair<String, PointEntity>>, epsg: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<SurveysData>\n")
        sb.append("\t<SpatialReferenceSystems>\n\t\t<SRS>\n\t\t\t<Id>1</Id>\n")
        sb.append("\t\t\t<Name>").append(esc(srsName(epsg))).append("</Name>\n")
        sb.append("\t\t\t<Definition>EPSG:").append(epsg).append("</Definition>\n")
        sb.append("\t\t</SRS>\n\t</SpatialReferenceSystems>\n\t<ControlPoints>\n")
        named.filter { MoreFormats.groundZ(it.second) != null }.forEachIndexed { i, (name, p) ->
            sb.append("\t\t<ControlPoint>\n")
            sb.append("\t\t\t<Id>").append(i).append("</Id>\n")
            sb.append("\t\t\t<SRSId>1</SRSId>\n")
            sb.append("\t\t\t<Name>").append(esc(name)).append("</Name>\n")
            sb.append("\t\t\t<Category>Full</Category>\n")
            sb.append("\t\t\t<Position>\n")
            sb.append("\t\t\t\t<x>").append(num(p.easting)).append("</x>\n")
            sb.append("\t\t\t\t<y>").append(num(p.northing)).append("</y>\n")
            sb.append("\t\t\t\t<z>").append(num(MoreFormats.groundZ(p)!!)).append("</z>\n")
            sb.append("\t\t\t</Position>\n")
            sb.append("\t\t\t<HorizontalAccuracy>").append(accuracy(p.sigmaH)).append("</HorizontalAccuracy>\n")
            sb.append("\t\t\t<VerticalAccuracy>").append(accuracy(p.sigmaU)).append("</VerticalAccuracy>\n")
            sb.append("\t\t\t<CheckPoint>").append(isCheckPoint(p)).append("</CheckPoint>\n")
            sb.append("\t\t</ControlPoint>\n")
        }
        sb.append("\t</ControlPoints>\n</SurveysData>\n")
        return sb.toString()
    }

    /** UX-0127-1: 높이(지면 표고·타원체고)가 없어 XML 에서 빠지는 점 번호(측정 순서) */
    fun droppedNoHeight(points: List<PointEntity>): List<String> = points.filter { MoreFormats.groundZ(it) == null }.map { it.pointId }

    /** 이름 대조표(UTF-8 BOM CSV): 현장 번호 ↔ iTwin 이름. XML 을 표본과 똑같이 두려고 원래 이름은 여기에 적는다 */
    fun namesCsv(points: List<PointEntity>, keepOriginalNames: Boolean = false): String {
        val sb = StringBuilder("\uFEFFitwin_id,itwin_name,field_point_id,renamed,check_point,has_height,note\r\n")
        val withZ = points.filter { MoreFormats.groundZ(it) != null }
        entries(withZ, keepOriginalNames).forEachIndexed { i, e ->
            val note = when {
                e.renamed -> ""
                keepOriginalNames -> "원래 이름 유지(설정)"
                targetNumber(e.point.pointId) == null -> "번호를 못 찾아 원래 이름 유지"
                else -> "같은 번호가 앞에 있어 원래 이름 유지"
            }
            sb.append(listOf(i.toString(), Formats.csvEscape(e.name), Formats.csvEscape(e.point.pointId), e.renamed.toString(), e.checkPoint.toString(), "true", note).joinToString(","))
                .append("\r\n")
        }
        for (p in points.filter { MoreFormats.groundZ(it) == null })
            sb.append(listOf("", "", Formats.csvEscape(p.pointId), "false", "false", "false", "높이가 없어 XML에서 뺌").joinToString(",")).append("\r\n")
        return sb.toString()
    }
}
