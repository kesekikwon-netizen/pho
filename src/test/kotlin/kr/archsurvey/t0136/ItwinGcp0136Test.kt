package kr.archsurvey.t0136

import kr.archsurvey.data.PointEntity
import kr.archsurvey.dig.AprilTagNaming
import kr.archsurvey.dig.DigKind
import kr.archsurvey.export.ExportFormat
import kr.archsurvey.export.ItwinCaptureXml
import kr.archsurvey.export.ItwinGcpExport
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.36 사진 기준점(AprilTag) 이름 규칙 · 유구별 iTwin 내보내기 내용. 실제 iTwin Capture 불러오기는 시험하지 않음(박스에 없음). */
class ItwinGcp0136Test {
    private fun pt(row: Long, id: String, tag: String, fid: Long?, e: Double, n: Double, h: Double?, at: Long = row, epsg: Int = 5186, kind: String = "gcp", type: String = "사진 기준점(AprilTag)", hm: String = "RECEIVER") = PointEntity(
        rowId = row, project = "광령리", pointId = id, positionType = type, memo = "",
        lat = 37.5, lon = 127.0, ellipsoidalHeight = 80.0, altitudeMsl = 52.0, geoidSeparation = 28.0,
        northing = n, easting = e, crsEpsg = epsg, fixQuality = 4, allEpochsFixed = true,
        satellites = 21, hdop = 0.8, sigmaE = 0.004, sigmaN = 0.004, sigmaH = 0.005, sigmaU = 0.012,
        correctionAgeSec = 1.0, baseStationId = "0149", epochs = 3, antennaHeight = 1.799, antennaModel = "TRIMBLE_R10",
        antennaInputValue = 1.6, groundEllipsoidalHeight = h?.plus(28.0), groundMsl = h, heightModel = hm, device = "R10",
        measuredAtUtcMs = 1_760_000_000_000L + at * 60_000L, digKind = kind, featureId = fid, digTag = tag,
    )
    private val labels = mapOf(7L to "2호 수혈", 8L to "3호 주거지")

    // ---------- 이름 규칙 ----------
    @Test fun tagNameIsWhatItwinDetects() {
        assertEquals("Target #0", AprilTagNaming.name(0))
        assertEquals("Target #12", AprilTagNaming.name(12))
        assertEquals(12, AprilTagNaming.parse("12")); assertEquals(0, AprilTagNaming.parse("0"))
        assertNull(AprilTagNaming.parse("")); assertNull(AprilTagNaming.parse("A")); assertNull(AprilTagNaming.parse("-1")); assertNull(AprilTagNaming.parse("1000"))
        assertEquals(0, AprilTagNaming.clamp(-3)); assertEquals(999, AprilTagNaming.clamp(5000))
    }

    @Test fun nextAndSuggest() {
        assertEquals(2, AprilTagNaming.nextAfter(1, emptyList()))
        assertEquals(5, AprilTagNaming.nextAfter(1, listOf(1, 2, 3, 4)))   // 이 유구에서 쓴 번호는 건너뜀
        assertEquals(1, AprilTagNaming.suggest(emptyList(), emptyList()))   // 처음 = 1
        assertEquals(4, AprilTagNaming.suggest(listOf(1, 2, 3), listOf(1, 2, 3)))
        assertEquals(1, AprilTagNaming.suggest(emptyList(), listOf(1, 2, 3, 4)))   // 새 유구 = 같은 표적 묶음 다시 쓰기(작업에서 가장 작은 번호)
        assertEquals(DigKind.GCP, DigKind.fromPositionType("사진 기준점(AprilTag)"))
        assertEquals(DigKind.GCP, DigKind.resolve("gcp", ""))
    }

    // ---------- 유구별 내보내기 ----------
    private val sample = listOf(
        pt(1, "P-0031", "1", 7, 148096.674, 98114.226, 57.272), pt(2, "P-0032", "2", 7, 148095.809, 98115.0, 56.985),
        pt(3, "P-0033", "3", 7, 148093.1, 98112.5, 57.01),
        pt(4, "P-0034", "2", 7, 148095.811, 98115.004, 56.987, at = 10),   // 2번 다시 잼 → 이것만
        pt(5, "P-0040", "11", 8, 148120.0, 98130.0, 57.5), pt(6, "P-0041", "12", 8, 148124.0, 98130.0, 57.6), pt(7, "P-0042", "13", 8, 148124.0, 98134.0, 57.7),
        pt(8, "P-0043", "14", 8, 148124.0, 98138.0, null),   // 높이 없음 → 뺌
        pt(9, "P-0050", "", null, 0.0, 0.0, 50.0, kind = "feature", type = "유구 경계점"),   // 표적 아님
    )

    @Test fun oneFilePerFeature_txtHasNoHeader_nameEN_Z_latestWins() {
        val r = ItwinGcpExport.build(sample, labels, "광령리")
        val paths = r.files.map { it.path }
        assertTrue(paths.toString(), "itwin_gcp/2호_수혈_AprilTag_EPSG5186_MSL-RECEIVER.txt" in paths)
        assertTrue("itwin_gcp/2호_수혈_AprilTag_EPSG5186_MSL-RECEIVER.xml" in paths)
        assertTrue("itwin_gcp/3호_주거지_AprilTag_EPSG5186_MSL-RECEIVER.txt" in paths)
        val t2 = r.files.single { it.path == "itwin_gcp/2호_수혈_AprilTag_EPSG5186_MSL-RECEIVER.txt" }.text
        assertEquals("Target #1,148096.674,98114.226,57.272\r\nTarget #2,148095.811,98115.004,56.987\r\nTarget #3,148093.100,98112.500,57.010\r\n", t2)
        assertFalse("BOM 없음", t2.startsWith("\uFEFF"))
        val t3 = r.files.single { it.path.startsWith("itwin_gcp/3호") && it.path.endsWith(".txt") }.text
        assertEquals(3, t3.lines().count { it.isNotBlank() })
        assertFalse("높이 없는 #14 는 뺌", t3.contains("#14"))
        // 표: 대체된 값과 뺀 점이 남는다
        val table = r.files.single { it.path.endsWith("gcp_table.csv") }.text
        assertTrue(table.contains("P-0032") && table.contains("대체됨"))
        assertTrue(table.contains("P-0043") && table.contains("높이가 없어 뺌"))
        assertFalse(table.contains("P-0050"))
    }

    @Test fun xmlIsSurveysDataWithAprilTagNames() {
        val r = ItwinGcpExport.build(sample, labels, "광령리")
        val x = r.files.single { it.path == "itwin_gcp/2호_수혈_AprilTag_EPSG5186_MSL-RECEIVER.xml" }.text
        // 0.1.37(의도된 변경): 사용자 실제 iTwin 파일 꼴(CRLF·SRS Id 0·정밀도 0.01)
        assertTrue(x.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<SurveysData>\r\n"))
        assertTrue(x.contains("<Definition>EPSG:5186</Definition>"))
        assertEquals(listOf("Target #1", "Target #2", "Target #3"), Regex("<Name>(Target #\\d+)</Name>").findAll(x).map { it.groupValues[1] }.toList())
        assertTrue(x.contains("<x>148095.811</x>") && x.contains("<y>98115.004</y>") && x.contains("<z>56.987</z>"))
        assertTrue(x.contains("<HorizontalAccuracy>0.01</HorizontalAccuracy>") && x.contains("<VerticalAccuracy>0.01</VerticalAccuracy>"))
    }

    @Test fun writeNamedMatchesUserSampleFormatByteForByte() {
        // 사용자 표본 sample-1 과 같은 좌표·이름을 넣으면 write() 와 같은 바이트(꼴이 바뀌지 않았음)
        val ps = listOf(pt(1, "1호", "", null, 148096.674, 98114.226, 57.272, kind = "", type = ""), pt(2, "2호", "", null, 148095.809, 98115.0, 56.985, kind = "", type = ""))
        assertEquals(ItwinCaptureXml.write(ps, 5186), ItwinCaptureXml.writeNamed(ps.map { ItwinCaptureXml.targetName(ItwinCaptureXml.targetNumber(it.pointId)!!) to it }, 5186))
    }

    @Test fun allFileOnlyWhenTagIdsDoNotCollide() {
        val r = ItwinGcpExport.build(sample, labels, "광령리")
        assertTrue(r.allWritten)
        val all = r.files.single { it.path == "itwin_gcp/전체_AprilTag_EPSG5186_MSL-RECEIVER.txt" }.text
        assertEquals(listOf("#1", "#2", "#3", "#11", "#12", "#13"), all.lines().filter { it.isNotBlank() }.map { it.substringBefore(',').removePrefix("Target ") })
        // 같은 표적 묶음(1·2·3)을 3호 주거지에서도 쓰면 → 전체 파일 없음 + README 에 이유
        val reuse = sample + listOf(pt(20, "P-0060", "1", 8, 148130.0, 98140.0, 57.9))
        val r2 = ItwinGcpExport.build(reuse, labels, "광령리")
        assertFalse(r2.allWritten); assertEquals(listOf(1), r2.allConflicts)
        assertTrue(r2.files.none { it.path.contains("전체_") })
        assertTrue(r2.files.single { it.path.endsWith("README_iTwin_AprilTag.txt") }.text.contains("전체 파일은 만들지 않았어요"))
        assertTrue("유구별 파일은 그대로", r2.files.any { it.path.startsWith("itwin_gcp/3호_주거지") && it.text.contains("Target #1,") })
    }

    @Test fun readmeDocumentsSrsHeightColumnsAndImportSteps() {
        val rd = ItwinGcpExport.build(sample, labels, "광령리").files.single { it.path.endsWith("README_iTwin_AprilTag.txt") }.text
        for (s in listOf("EPSG:5186", "해발(수신기 지오이드 값)", "머리줄 없음", "Custom Text Format", "Use targets", "Target #번호")) assertTrue(s, rd.contains(s))
    }

    @Test fun lessThanThreeTargetsWarnsInReadme_outsideFeatureAndEllipsoidNamed() {
        val ps = listOf(pt(1, "P-1", "5", null, 1.0, 2.0, 3.0, hm = ""), pt(2, "P-2", "6", null, 2.0, 2.0, 3.0))
        val r = ItwinGcpExport.build(ps, labels, "광령리")
        assertTrue(r.files.any { it.path == "itwin_gcp/유구밖_AprilTag_EPSG5186_MSL-RECEIVER.txt" })
        assertTrue(r.files.single { it.path.endsWith("README_iTwin_AprilTag.txt") }.text.contains("3개 미만"))
        val ell = listOf(pt(1, "P-1", "5", 7, 1.0, 2.0, 3.0).copy(groundMsl = null))
        assertTrue(ItwinGcpExport.build(ell, labels, "x").files.any { it.path == "itwin_gcp/2호_수혈_AprilTag_EPSG5186_ELLIPSOID.txt" })
        assertTrue(ItwinGcpExport.build(emptyList(), labels, "x").files.isEmpty())
    }

    @Test fun eveningBundleIncludesItwinGcp() {
        assertTrue(ExportFormat.ITWIN_GCP in ExportFormat.EVENING)
        // 저장 파일(표본) — 보고서용
        val out = File("build/itwin-gcp-samples-0136").also { it.mkdirs() }
        for (f in ItwinGcpExport.build(sample, labels, "광령리").files) File(out, f.path).also { it.parentFile.mkdirs() }.writeText(f.text)
    }
}
