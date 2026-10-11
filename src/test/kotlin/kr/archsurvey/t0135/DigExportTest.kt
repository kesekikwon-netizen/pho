package kr.archsurvey.t0135

import kr.archsurvey.arch.GridDef
import kr.archsurvey.core.NE
import kr.archsurvey.data.PointEntity
import kr.archsurvey.export.DigExport
import kr.archsurvey.export.DigOutline
import kr.archsurvey.export.DxfWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.35 도면 DXF(레이어별·축척 글자)·야장 CSV. ezdxf 검증용 표본을 build/dxf-samples-0135 에 남긴다. */
class DigExportTest {
    private fun pt(row: Long, id: String, type: String, n: Double, e: Double, h: Double?, kind: String = "", fid: Long? = null, art: Int? = null, lv: String = "", tag: String = "") = PointEntity(
        rowId = row, project = "광령리", pointId = id, positionType = type, memo = "",
        lat = 37.5, lon = 127.0, ellipsoidalHeight = 80.0, altitudeMsl = 52.0, geoidSeparation = 28.0,
        northing = n, easting = e, crsEpsg = 5186, fixQuality = 4, allEpochsFixed = true,
        satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.012, sigmaH = 0.008, sigmaU = 0.015,
        correctionAgeSec = 1.0, baseStationId = "0001", epochs = 3, antennaHeight = 1.799, antennaModel = "TRIMBLE_R10", antennaRefPoint = "bottom_of_quick_release", antennaInputMode = "vertical", antennaPcoV = 0.199, antennaPcoH = 0.0,
        antennaInputValue = 1.6, antennaReceiverApplied = false, groundEllipsoidalHeight = h?.plus(28.0), groundMsl = h,
        heightModel = "RECEIVER", device = "R10", measuredAtUtcMs = 1_760_000_000_000L + row * 60_000L,
        digKind = kind, featureId = fid, artifactNo = art, levelKind = lv, digTag = tag, cellName = if (row > 4) "A-1" else "",
    )

    private val pts = listOf(
        pt(1, "P-0001", "유구 경계점", 100.0, 200.0, 50.0, "feature", 7), pt(2, "P-0002", "유구 경계점", 100.0, 204.0, 50.0, "feature", 7),
        pt(3, "P-0003", "유구 경계점", 104.0, 204.0, 50.0, "feature", 7), pt(4, "P-0004", "유구 경계점", 104.0, 200.0, 50.0, "feature", 7),
        pt(5, "P-0005", "유물 출토 위치", 102.0, 202.0, 49.62, "artifact", 7, 13), pt(6, "P-0006", "레벨점", 101.0, 201.0, 49.40, "level", 7, lv = "floor"),
        pt(7, "P-0007", "토층 단면", 99.0, 199.0, 50.1, "section", tag = "A"), pt(8, "P-0008", "토층 단면", 105.0, 205.0, 50.1, "section", tag = "A′"),
        pt(9, "P-0009", "기준점", 90.0, 190.0, 51.234, "bm", tag = "BM-1"),
        pt(10, "구-07", "기타", 95.0, 195.0, null),   // 옛 이름·종류 그대로
    )
    private val outs = listOf(DigOutline(7, "2호 수혈", "feature", "수혈", true, listOf(1, 2, 3, 4)))
    private val grid = GridDef(1, 1, "A", NE(98.0, 198.0), NE(98.0, 208.0), 5.0, 2, 2, 5186)

    @Test fun layersAndScaleTextHeights() {
        val d100 = DigExport.drawing(pts, outs, listOf(grid), 100)
        // 0.1.36(의도된 변경): 「사진기준점」 레이어 추가(AprilTag)
        assertEquals(listOf("조사구역", "그리드", "트렌치", "유구", "유물", "레벨", "토층", "기준점", "사진기준점", "기타", "점번호", "해발"), d100.layers.map { it.first })
        val texts = d100.ents.filterIsInstance<DxfWriter.EText>()
        assertTrue(texts.any { it.layer == "유물" && it.text == "유물 13" && Math.abs(it.heightM - 0.25) < 1e-9 })
        assertTrue(texts.any { it.layer == "레벨" && it.text == "바닥" })
        assertTrue(texts.any { it.layer == "기준점" && it.text == "BM-1" })
        assertTrue(texts.any { it.layer == "점번호" && it.text == "구-07" })
        assertTrue(texts.any { it.layer == "해발" && it.text == "49.620" })
        assertTrue(texts.any { it.layer == "그리드" && it.text == "B-2" })
        val polys = d100.ents.filterIsInstance<DxfWriter.EPoly>()
        assertTrue(polys.any { it.layer == "유구" && it.closed && it.pts.size == 4 })
        assertTrue(polys.any { it.layer == "토층" && it.pts.size == 2 })
        assertEquals(6, polys.count { it.layer == "그리드" })   // 2x2 칸 → 세로 3 + 가로 3
        val d20 = DigExport.drawing(pts, outs, listOf(grid), 20).ents.filterIsInstance<DxfWriter.EText>().first { it.text == "유물 13" }
        val d200 = DigExport.drawing(pts, outs, listOf(grid), 200).ents.filterIsInstance<DxfWriter.EText>().first { it.text == "유물 13" }
        assertEquals(0.05, d20.heightM, 1e-9); assertEquals(0.5, d200.heightM, 1e-9)
        assertEquals("기타", DigExport.layerOf(pts.last()))
        assertEquals("유구", DigExport.layerOf(pts[0].copy(digKind = "")))   // 옛 점: 위치 종류 글자로
        // 표본(ezdxf 로 따로 검증)
        val out = File("build/dxf-samples-0135").apply { mkdirs() }
        for (s in listOf(20, 100, 200)) File(out, "drawing_1-$s.dxf").writeBytes(DxfWriter.writeDrawing(DigExport.drawing(pts, outs, listOf(grid), s)))
        val txt = String(File(out, "drawing_1-100.dxf").readBytes(), DxfWriter.cp949())
        assertTrue(txt.contains("ANSI_949")); assertTrue(txt.contains("\n유구\n")); assertTrue(txt.trimEnd().endsWith("EOF"))
    }

    @Test fun fieldBookCsv() {
        val csv = DigExport.fieldCsv(pts, outs, mapOf(5L to 2))
        val lines = csv.removePrefix("\uFEFF").trim().split("\r\n")
        assertEquals(11, lines.size)
        assertTrue(lines[0].startsWith("점번호,종류,유구,유물번호,레벨"))
        assertTrue(lines[5].startsWith("P-0005,유물,2호 수혈,13,,,A-1,102.000,202.000,49.620,0.8,1.5,FIX,"))
        assertTrue(lines[5].contains(",2,"))
        assertTrue(lines[6].contains(",바닥,"))
        assertTrue(lines[10].startsWith("구-07,기타,"))
    }

    @Test fun todayFilter() {
        val keep = DigExport.todayOnly(pts, "B") { if (it < 1_760_000_000_000L + 5 * 60_000L) "A" else "B" }
        assertEquals(listOf("P-0005", "P-0006", "P-0007", "P-0008", "P-0009", "구-07"), keep.map { it.pointId })
    }

    @Test fun legacyPointDxfUnchangedByRefactor() {
        val s = DxfWriter.build(listOf(kr.archsurvey.export.DxfPoint("P-1", 200.0, 100.0, 50.0)))
        assertTrue(s.contains("\nPT_NO\n")); assertTrue(s.contains("AC1015"))
    }
}
