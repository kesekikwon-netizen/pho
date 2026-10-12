package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import kr.archsurvey.dig.AprilTagNaming
import kr.archsurvey.dig.DigKind
import java.util.Locale

/**
 * 0.1.36 iTwin Capture 사진 기준점(AprilTag) — 유구마다 따로 내보내기. Android 의존 없음(시험 가능).
 *
 * 근거(Bentley):
 *  - KB0044805 「iTwin Capture – Using April Tags as Control Points」: 기준점 이름을 「AprilTag #X」 로 쉼표 구분 텍스트에 적어
 *    Survey › Import surveys from file › Custom Text Format(Wizard) 로 불러오면, 공중삼각측량(AT)에서 「Use targets」 를 켰을 때
 *    사진에서 찾은 AprilTag 와 같은 이름끼리 저절로 합쳐진다. 머리줄은 마법사에서 지워야 하므로 TXT 에는 머리줄을 넣지 않는다.
 *  - ContextCapture 도움말 「Importing control points」: TXT/CSV 와 ContextCapture Surveys(XML) 를 받는다. 불러올 때 SRS 를 맞게 고를 것.
 *  - 「Positioning/georeferencing」: 기준점으로 맞추려면 3점 이상, 점마다 사진 2장 넘게.
 * 0.1.37: 이름·XML 은 사용자가 준 실제 iTwin Capture 파일(build-reports/0.1.37/user-itwin-sample-gangneung-1ho.xml)이 기준 —
 *   이름 「Target #N」, SurveysData(CRLF·SRS Id 0·정밀도 0.01·Measurement 없음). 웹 문서(KB0044805 의 「AprilTag #X」)보다 우선.
 *
 * 묶음: 점의 유구(featureId, 없으면 「유구밖」) × 좌표계(EPSG). 같은 묶음에서 같은 번호를 여러 번 쟀으면 **마지막 값**만 쓰고 표에 「대체됨」.
 * 높이 = 지면 높이(해발 우선, 없으면 지면 타원체고 — 다른 내보내기와 같은 규칙). 높이가 없는 점은 뺀다(표에 적음).
 */
object ItwinGcpExport {
    const val DIR = "itwin_gcp"
    const val OUTSIDE = "유구밖"

    data class Row(val featureLabel: String, val tagId: Int, val name: String, val point: PointEntity, val used: Boolean, val note: String)
    data class Group(val featureId: Long?, val label: String, val epsg: Int, val heightTag: String, val rows: List<Row>) {
        val used: List<Row> get() = rows.filter { it.used }
    }
    data class GcpFile(val path: String, val text: String)
    data class Result(val files: List<GcpFile>, val groups: List<Group>, val allWritten: Boolean, val allConflicts: List<Int>)

    fun isGcp(p: PointEntity) = DigKind.resolve(p.digKind, p.positionType) == DigKind.GCP && AprilTagNaming.parse(p.digTag) != null

    fun safe(s: String) = s.trim().replace(Regex("[^0-9A-Za-z가-힣._-]"), "_").ifBlank { "유구" }

    /** 높이 종류 글자(파일 이름용, ASCII): MSL-RECEIVER / MSL-EGM96 / ELLIPSOID / MIXED */
    fun heightTagOf(p: PointEntity): String = when {
        p.groundMsl != null -> "MSL-" + p.heightModel.ifEmpty { "RECEIVER" }.uppercase(Locale.US)
        p.groundEllipsoidalHeight != null -> "ELLIPSOID"
        else -> "NONE"
    }
    fun heightKo(tag: String): String = when {
        tag == "MSL-RECEIVER" -> "해발(수신기 지오이드 값)"
        tag == "MSL-EGM96" -> "해발(앱 EGM96 지오이드)"
        tag.startsWith("MSL-") -> "해발(${tag.removePrefix("MSL-")})"
        tag == "ELLIPSOID" -> "타원체고(GRS80)"
        tag == "MIXED" -> "섞임(해발·타원체고) — 확인 필요"
        else -> "높이 없음"
    }

    private fun z(p: PointEntity) = p.groundMsl ?: p.groundEllipsoidalHeight
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)

    fun groups(points: List<PointEntity>, labels: Map<Long, String>): List<Group> {
        val gcp = points.filter { isGcp(it) }
        val out = ArrayList<Group>()
        for ((key, l) in gcp.groupBy { it.featureId to it.crsEpsg }) {
            val (fid, epsg) = key
            val label = fid?.let { labels[it] } ?: OUTSIDE
            val rows = ArrayList<Row>()
            val withZ = l.filter { z(it) != null }
            for (p in l.filter { z(it) == null }) rows += Row(label, AprilTagNaming.parse(p.digTag)!!, AprilTagNaming.name(AprilTagNaming.parse(p.digTag)!!), p, false, "높이가 없어 뺌")
            for ((tag, same) in withZ.groupBy { AprilTagNaming.parse(it.digTag)!! }) {
                val sorted = same.sortedWith(compareBy({ it.measuredAtUtcMs }, { it.rowId }))
                val last = sorted.last()
                for (p in sorted) rows += Row(label, tag, AprilTagNaming.name(tag), p, p === last, if (p === last) (if (sorted.size > 1) "다시 잰 값(${sorted.size}회 중 마지막)" else "") else "대체됨(나중에 다시 잼)")
            }
            rows.sortWith(compareBy({ it.tagId }, { it.point.measuredAtUtcMs }))
            val tags = rows.filter { it.used }.map { heightTagOf(it.point) }.distinct()
            out += Group(fid, label, epsg, if (tags.size == 1) tags[0] else if (tags.isEmpty()) "NONE" else "MIXED", rows)
        }
        return out.sortedWith(compareBy({ it.featureId == null }, { it.label }, { it.epsg }))
    }

    fun baseName(label: String, epsg: Int, heightTag: String) = "${safe(label)}_AprilTag_EPSG${epsg}_$heightTag"

    /** iTwin 「Custom Text Format」: 머리줄 없음, 쉼표, 「이름,X(동),Y(북),Z」, UTF-8(BOM 없음), CRLF */
    fun txt(rows: List<Row>): String = rows.joinToString("") { r -> "${r.name},${f3(r.point.easting)},${f3(r.point.northing)},${f3(z(r.point)!!)}\r\n" }

    /** 0.1.37: 사용자 실제 iTwin 파일(강릉 1호)과 같은 SurveysData(CRLF·SRS Id 0·「Target #n」·정밀도 0.01) */
    fun xml(rows: List<Row>, epsg: Int, useSigma: Boolean = false): String = ItwinCaptureXml.writeSurveys(rows.map { it.tagId to it.point }, epsg, useSigma)

    /** 표(엑셀용, UTF-8 BOM): 어느 유구의 몇 번 표적이 어느 점인지, 쓴 값/대체된 값 */
    fun tableCsv(groups: List<Group>): String {
        val sb = StringBuilder("\uFEFF유구,EPSG,표적번호,iTwin이름,점번호,동(E),북(N),높이(m),높이종류,수평정밀도(cm),수직정밀도(cm),고정,측정시각(UTC),내보냄,비고\r\n")
        fun cm(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it * 100) } ?: ""
        for (g in groups) for (r in g.rows) {
            val p = r.point
            sb.append(listOf(g.label, g.epsg.toString(), r.tagId.toString(), r.name, p.pointId, f3(p.easting), f3(p.northing), z(p)?.let { f3(it) } ?: "",
                heightTagOf(p), cm(p.sigmaH), cm(p.sigmaU), if (p.fixQuality == 4) "FIX" else "아님", Formats.utcOf(p.measuredAtUtcMs), if (r.used) "예" else "아니오", r.note)
                .joinToString(",") { Formats.csvEscape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** 전체 파일에서 겹치는 번호(같은 좌표계에서 두 유구 이상이 같은 번호를 씀) */
    fun conflicts(groups: List<Group>, epsg: Int): List<Int> =
        groups.filter { it.epsg == epsg }.flatMap { g -> g.used.map { it.tagId }.distinct().map { it to g.label } }.groupBy { it.first }.filter { it.value.size > 1 }.keys.sorted()

    fun build(points: List<PointEntity>, labels: Map<Long, String>, project: String): Result {
        val gs = groups(points, labels)
        val files = ArrayList<GcpFile>()
        for (g in gs) {
            if (g.used.isEmpty()) continue
            val b = baseName(g.label, g.epsg, g.heightTag)
            files += GcpFile("$DIR/$b.txt", txt(g.used))
            files += GcpFile("$DIR/$b.xml", xml(g.used, g.epsg))
        }
        var allOk = true
        val allConf = ArrayList<Int>()
        for (epsg in gs.map { it.epsg }.distinct().sorted()) {
            val used = gs.filter { it.epsg == epsg }.flatMap { it.used }
            if (used.isEmpty()) continue
            val conf = conflicts(gs, epsg)
            if (conf.isNotEmpty()) { allOk = false; allConf += conf; continue }
            val tags = used.map { heightTagOf(it.point) }.distinct()
            val b = baseName("전체", epsg, if (tags.size == 1) tags[0] else "MIXED")
            val sorted = used.sortedBy { it.tagId }
            files += GcpFile("$DIR/$b.txt", txt(sorted))
            files += GcpFile("$DIR/$b.xml", xml(sorted, epsg))
        }
        if (gs.isNotEmpty()) {
            files += GcpFile("$DIR/표적표_gcp_table.csv", tableCsv(gs))
            files += GcpFile("$DIR/README_iTwin_AprilTag.txt", readme(project, gs, allOk, allConf))
        }
        return Result(files, gs, allOk, allConf)
    }

    fun readme(project: String, gs: List<Group>, allOk: Boolean, conf: List<Int>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("iTwin Capture 사진 기준점(AprilTag) — 작업: $project\r\n\r\n")
        sb.append("[파일]\r\n")
        for (g in gs) {
            val b = baseName(g.label, g.epsg, g.heightTag)
            val tags = g.used.map { it.tagId }
            sb.append("  ${g.label}: $b.txt / .xml · 표적 ${tags.size}개 (${tags.joinToString(", ") { "#$it" }}) · EPSG:${g.epsg} · 높이 ${heightKo(g.heightTag)}")
            if (tags.size < 3) sb.append(" · ※ 3개 미만 — iTwin 은 기준점 3개 이상(점마다 사진 2장 넘게) 있어야 맞춰요")
            sb.append("\r\n")
        }
        if (allOk) sb.append("  전체_AprilTag_EPSG….txt / .xml: 모든 유구 표적을 한 파일에(번호가 겹치지 않을 때만 만듦)\r\n")
        else sb.append("  전체 파일은 만들지 않았어요: 여러 유구가 같은 번호(${conf.joinToString(", ") { "#$it" }})를 써서 iTwin 에서 서로 섞여요. 유구별 파일을 쓰세요.\r\n")
        sb.append("  표적표_gcp_table.csv: 유구·표적 번호·점 번호(P-…)·좌표·정밀도·다시 잰 값(마지막 값만 내보냄)\r\n\r\n")
        sb.append("[TXT 열] 머리줄 없음 · 쉼표 · 이름,X,Y,Z = Target #번호, 동(Easting), 북(Northing), 지면 높이(m, 폴 높이 뺌) · UTF-8\r\n")
        sb.append("[XML] iTwin SurveysData(강릉 1호 실제 파일과 같은 꼴) · SRS Definition = EPSG:<번호> · 정밀도 0.01 m · 표적 번호 순 · 사진 측정(Measurement)은 iTwin 에서 생김\r\n\r\n")
        sb.append("[iTwin Capture Modeler 에서]\r\n")
        sb.append("  1) 그 유구 사진만 넣은 블록을 만든다(유구마다 블록 하나).\r\n")
        sb.append("  2) Surveys › Import → 그 유구의 .xml (SurveysData, 좌표계 포함). 또는 Custom Text Format(Wizard) → .txt (쉼표, 열: Name / X(Easting) / Y(Northing) / Z, 좌표계는 파일 이름의 EPSG).\r\n")
        sb.append("  3) Aerotriangulation: Positioning = Control points(타깃 포함) 조정, 「Use targets (QR Codes, AprilTags or ChiliTags)」 체크,\r\n")
        sb.append("     Settings › Targets extraction = AprilTags. 사진에서 찾은 「Target #12」 가 같은 이름 기준점과 저절로 합쳐져요.\r\n")
        sb.append("  4) 사진에 안 잡힌 표적은 사진 연결이 없으니 지워도 돼요.\r\n\r\n")
        sb.append("[주의]\r\n")
        sb.append("  · 표적 번호 = 인쇄한 AprilTag 의 ID 와 같아야 해요. 표적 묶음(family, 예: 36h11)은 iTwin 타깃 추출에서 지원하는지 먼저 확인하세요.\r\n")
        sb.append("  · 높이: 파일 이름 MSL-… = 해발(지오이드 적용), ELLIPSOID = 타원체고. iTwin SRS 의 수직 기준을 이에 맞추세요. 한 유구 모델 안에서는 같은 종류여야 해요(MIXED 면 확인).\r\n")
        sb.append("  · 표적 가운데에 폴 끝을 세워 잰 값이에요(폴 높이는 이미 뺐어요).\r\n")
        sb.append("  · 이 파일을 실제 iTwin Capture 에 불러오는 것은 현장 PC 에서 확인이 필요해요.\r\n")
        return sb.toString()
    }
}
