package kr.archsurvey.export

import kr.archsurvey.arch.CellNames
import kr.archsurvey.arch.GridDef
import kr.archsurvey.data.PointEntity
import kr.archsurvey.dig.DigKind
import kr.archsurvey.dig.DrawingScale
import kr.archsurvey.dig.LevelKind
import kr.archsurvey.dig.SectionLabels
import java.util.Locale

/** 내보내기용 윤곽(유구·트렌치·조사구역). [vertexRowIds] 는 순서대로 */
data class DigOutline(val id: Long, val label: String, val kind: String, val featureType: String, val closed: Boolean, val vertexRowIds: List<Long>)

/**
 * 0.1.35 저녁 도면용 묶음: 레이어별 DXF(조사구역·그리드·트렌치·유구·유물·레벨·토층·기준점·기타 + 점번호·해발)와 야장 CSV.
 * 글자 크기 = 축척 분모 × 2.5 mm(1:20 → 0.05 m, 1:100 → 0.25 m, 1:200 → 0.5 m). Android 의존 없음.
 */
object DigExport {
    const val L_AREA = "조사구역"; const val L_GRID = "그리드"; const val L_TRENCH = "트렌치"; const val L_FEATURE = "유구"
    const val L_ARTIFACT = "유물"; const val L_LEVEL = "레벨"; const val L_SECTION = "토층"; const val L_BM = "기준점"; const val L_OTHER = "기타"
    const val L_NO = "점번호"; const val L_ELEV = "해발"
    /** 레이어 이름과 AutoCAD 색 번호 */
    val LAYERS = listOf(L_AREA to 7, L_GRID to 8, L_TRENCH to 4, L_FEATURE to 1, L_ARTIFACT to 2, L_LEVEL to 3, L_SECTION to 5, L_BM to 6, L_OTHER to 9, L_NO to 7, L_ELEV to 3)

    private fun h(p: PointEntity) = p.groundMsl ?: p.groundEllipsoidalHeight
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)

    fun layerOf(p: PointEntity): String = when (DigKind.resolve(p.digKind, p.positionType)) {
        DigKind.FEATURE -> L_FEATURE; DigKind.ARTIFACT -> L_ARTIFACT; DigKind.LEVEL -> L_LEVEL; DigKind.SECTION -> L_SECTION
        DigKind.TRENCH -> L_TRENCH; DigKind.STAKE -> L_GRID; DigKind.BM -> L_BM; DigKind.AREA -> L_AREA; null -> L_OTHER
    }

    fun drawing(points: List<PointEntity>, outlines: List<DigOutline>, grids: List<GridDef>, scaleDenominator: Int = DrawingScale.DEFAULT): DxfWriter.Drawing {
        val th = DrawingScale.textHeightM(scaleDenominator)
        val off = th * 0.4
        val ents = ArrayList<DxfWriter.Ent>()
        val byRow = points.associateBy { it.rowId }
        // 그리드: 칸 선 + 칸 이름(칸 가운데)
        for (g in grids) {
            for (i in 0..g.cols) { val a = g.toNE(i * g.spacingM, 0.0); val b = g.toNE(i * g.spacingM, g.rows * g.spacingM); ents += DxfWriter.EPoly(L_GRID, listOf(a.e to a.n, b.e to b.n), false) }
            for (j in 0..g.rows) { val a = g.toNE(0.0, j * g.spacingM); val b = g.toNE(g.cols * g.spacingM, j * g.spacingM); ents += DxfWriter.EPoly(L_GRID, listOf(a.e to a.n, b.e to b.n), false) }
            if (g.cols * g.rows <= 2500) for (j in 0 until g.rows) for (i in 0 until g.cols) {
                val c = g.toNE((i + 0.5) * g.spacingM, (j + 0.5) * g.spacingM)
                ents += DxfWriter.EText(L_GRID, c.e, c.n, th, CellNames.cell(i, j))
            }
        }
        // 윤곽: 유구·트렌치·조사구역
        for (o in outlines) {
            val layer = when (o.kind) { "trench" -> L_TRENCH; "area" -> L_AREA; else -> L_FEATURE }
            val v = o.vertexRowIds.mapNotNull { byRow[it] }.map { it.easting to it.northing }
            if (v.size >= 2) ents += DxfWriter.EPoly(layer, v, o.closed && v.size >= 3)
            if (v.isNotEmpty()) {
                val ce = v.sumOf { it.first } / v.size; val cn = v.sumOf { it.second } / v.size
                ents += DxfWriter.EText(layer, ce, cn, th * 1.2, o.label)
            }
        }
        // 토층 단면선 A — A′
        val sec = points.filter { DigKind.resolve(it.digKind, it.positionType) == DigKind.SECTION && it.digTag.isNotBlank() }
        for (line in SectionLabels.completeLines(sec.map { it.digTag })) {
            val a = sec.last { it.digTag == line }; val b = sec.last { it.digTag == line + SectionLabels.PRIME }
            ents += DxfWriter.EPoly(L_SECTION, listOf(a.easting to a.northing, b.easting to b.northing), false)
        }
        // 점: 점 + 이름표 + 해발
        for (p in points) {
            val layer = layerOf(p)
            val k = DigKind.resolve(p.digKind, p.positionType)
            ents += DxfWriter.EPoint(layer, p.easting, p.northing, h(p))
            val label = when (k) {
                DigKind.ARTIFACT -> p.artifactNo?.let { "유물 $it" }
                DigKind.LEVEL -> LevelKind.fromId(p.levelKind)?.labelKo
                DigKind.SECTION -> p.digTag.ifBlank { null }
                DigKind.BM, DigKind.STAKE -> p.digTag.ifBlank { null }
                else -> null
            }
            if (label != null) ents += DxfWriter.EText(layer, p.easting + off, p.northing + off, th, label)
            ents += DxfWriter.EText(L_NO, p.easting + off, p.northing + off + (if (label != null) th * 1.2 else 0.0), th * 0.8, p.pointId)
            h(p)?.let { ents += DxfWriter.EText(L_ELEV, p.easting + off, p.northing - off - th, th * 0.8, f3(it)) }
        }
        return DxfWriter.Drawing(LAYERS, ents)
    }

    /** 야장 CSV(UTF-8 BOM, 엑셀 한글). 한 줄 = 한 점 */
    fun fieldCsv(points: List<PointEntity>, outlines: List<DigOutline>, photos: Map<Long, Int> = emptyMap()): String {
        val names = outlines.associate { it.id to it.label }
        val sb = StringBuilder("\uFEFF점번호,종류,유구,유물번호,레벨,꼬리표,칸,북(N),동(E),해발(m),수평정밀도(cm),수직정밀도(cm),고정,측정시각(UTC),사진수,메모\r\n")
        fun cm(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it * 100) } ?: ""
        for (p in points) {
            val k = DigKind.resolve(p.digKind, p.positionType)
            val row = listOf(
                p.pointId, k?.labelKo ?: p.positionType, p.featureId?.let { names[it] } ?: "", p.artifactNo?.toString() ?: "",
                LevelKind.fromId(p.levelKind)?.labelKo ?: "", p.digTag, p.cellName,
                String.format(Locale.US, "%.3f", p.northing), String.format(Locale.US, "%.3f", p.easting), h(p)?.let { f3(it) } ?: "",
                cm(p.sigmaH), cm(p.sigmaU), if (p.fixQuality == 4) "FIX" else "아님", Formats.utcOf(p.measuredAtUtcMs), (photos[p.rowId] ?: 0).toString(), p.memo,
            )
            sb.append(row.joinToString(",") { Formats.csvEscape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** 오늘만 내보내기: [dayOf] (ms → yyyy-MM-dd) 가 [today] 와 같은 점 */
    fun todayOnly(points: List<PointEntity>, today: String, dayOf: (Long) -> String): List<PointEntity> = points.filter { dayOf(it.measuredAtUtcMs) == today }
}
