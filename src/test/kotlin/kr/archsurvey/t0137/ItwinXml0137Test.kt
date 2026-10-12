package kr.archsurvey.t0137

import kr.archsurvey.data.PointEntity
import kr.archsurvey.dig.AprilTagNaming
import kr.archsurvey.export.ItwinCaptureXml
import kr.archsurvey.export.ItwinGcpExport
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 0.1.37: 사용자가 준 실제 iTwin Capture 파일(강릉 1호 · EPSG:5187 · Target #1–4)과 바이트 단위로 같은 꼴인지.
 * 표본에서 Measurement(사진 위 점 찍기, iTwin 안에서 생김)만 빼면 우리 출력과 같아야 한다.
 */
class ItwinXml0137Test {
    private val sampleBytes = File("src/test/resources/itwin/gangneung-1ho.xml").readBytes()
    private val stripped = String(sampleBytes, Charsets.UTF_8).replace(Regex("\t\t\t<Measurement>\r\n.*?\t\t\t</Measurement>\r\n", RegexOption.DOT_MATCHES_ALL), "")

    private fun pt(row: Long, tag: Int, e: Double, n: Double, z: Double, fid: Long? = 1, sh: Double? = 0.006, su: Double? = 0.013, at: Long = row) = PointEntity(
        rowId = row, project = "강릉", pointId = "P-%04d".format(row), positionType = "사진 기준점(AprilTag)", memo = "",
        lat = 37.7, lon = 128.9, ellipsoidalHeight = z + 26.0, altitudeMsl = z + 1.6, geoidSeparation = 26.0,
        northing = n, easting = e, crsEpsg = 5187, fixQuality = 4, allEpochsFixed = true,
        satellites = 21, hdop = 0.7, sigmaE = sh, sigmaN = sh, sigmaH = sh, sigmaU = su,
        correctionAgeSec = 1.0, baseStationId = "0149", epochs = 3, antennaHeight = 1.799, antennaModel = "TRIMBLE_R10",
        antennaInputValue = 1.6, groundEllipsoidalHeight = z + 26.0, groundMsl = z, heightModel = "RECEIVER", device = "R10",
        measuredAtUtcMs = 1_760_000_000_000L + at * 60_000L, digKind = "gcp", featureId = fid, digTag = tag.toString(),
    )
    // 표본 좌표(강릉 1호). 잰 순서를 섞어도 파일은 1,2,3,4 순
    private val pts = listOf(
        pt(3, 3, 194877.654, 573994.437, 2.87), pt(1, 1, 194884.215, 573986.096, 4.192),
        pt(4, 4, 194874.974, 573993.995, 3.982), pt(2, 2, 194880.652, 573989.204, 2.883),
    )

    @Test fun sampleIsUtf8NoBomCrlfTabs() {
        assertFalse(sampleBytes.size >= 3 && sampleBytes[0] == 0xEF.toByte())
        assertTrue(stripped.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<SurveysData>\r\n\t<SpatialReferenceSystems>"))
        assertEquals(4, Regex("<ControlPoint>").findAll(stripped).count())
        assertFalse(stripped.contains("<Measurement>"))
    }

    @Test fun ourXmlEqualsUserSampleWithoutMeasurements_byteForByte() {
        val ours = ItwinCaptureXml.writeSurveys(pts.map { AprilTagNaming.parse(it.digTag)!! to it }, 5187)
        assertEquals(stripped, ours)
        assertArrayEquals(stripped.toByteArray(Charsets.UTF_8), ours.toByteArray(Charsets.UTF_8))
    }

    @Test fun perFeatureFileFromExportEqualsSample() {
        val r = ItwinGcpExport.build(pts, mapOf(1L to "1호"), "강릉")
        val f = r.files.single { it.path == "itwin_gcp/1호_AprilTag_EPSG5187_MSL-RECEIVER.xml" }
        assertEquals(stripped, f.text)
        val txt = r.files.single { it.path == "itwin_gcp/1호_AprilTag_EPSG5187_MSL-RECEIVER.txt" }.text
        assertEquals("Target #1,194884.215,573986.096,4.192\r\nTarget #2,194880.652,573989.204,2.883\r\nTarget #3,194877.654,573994.437,2.870\r\nTarget #4,194874.974,573993.995,3.982\r\n", txt)
    }

    @Test fun namesAreTargetN_matchingDetectedTargetsInUserFile() {
        assertEquals("Target #1", AprilTagNaming.name(1))
        val names = Regex("<Name>(Target #\\d+)</Name>").findAll(String(sampleBytes, Charsets.UTF_8)).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Target #1", "Target #2", "Target #3", "Target #4"), names)
    }

    /** Bentley 「Control Point Properties」: Name · Category(Full/Horizontal/Vertical) · 위치 · Horizontal/VerticalAccuracy · Check point */
    @Test fun schemaElementsAndOrderMatchSampleAndBentleyFields() {
        fun parse(s: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(s.byteInputStream()).documentElement
        val ours = parse(ItwinCaptureXml.writeSurveys(pts.map { it.digTag.toInt() to it }, 5187))
        val sample = parse(String(sampleBytes, Charsets.UTF_8))
        fun kids(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
        fun shape(e: Element): String = e.tagName + kids(e).filter { it.tagName != "Measurement" }.joinToString(",", "(", ")") { shape(it) }
        assertEquals(shape(sample), shape(ours))
        val cps = ours.getElementsByTagName("ControlPoint")
        for (i in 0 until cps.length) {
            val cp = cps.item(i) as Element
            fun v(t: String) = cp.getElementsByTagName(t).item(0).textContent
            assertTrue(v("Category") in setOf("Full", "Horizontal", "Vertical"))
            assertEquals("0", v("SRSId")); assertEquals("false", v("CheckPoint"))
            assertEquals("0.01", v("HorizontalAccuracy")); assertEquals("0.01", v("VerticalAccuracy"))
        }
        assertEquals("EPSG:5187", (ours.getElementsByTagName("Definition").item(0)).textContent)
        assertEquals("Korea 2000 / East Belt 2010 (EPSG:5187)", ours.getElementsByTagName("Name").item(0).textContent)
    }

    @Test fun srsNamesForAllKoreanBelts() {
        for ((e, n) in listOf(5185 to "West Belt 2010", 5186 to "Central Belt 2010", 5187 to "East Belt 2010", 5188 to "East Sea Belt 2010")) {
            val x = ItwinCaptureXml.writeSurveys(listOf(1 to pt(1, 1, 1.0, 2.0, 3.0).copy(crsEpsg = e)), e)
            assertTrue(x.contains("<Name>Korea 2000 / $n (EPSG:$e)</Name>\r\n\t\t\t<Definition>EPSG:$e</Definition>"))
        }
    }

    @Test fun optionalSigmaAccuracy_minimum1cm() {
        val x = ItwinCaptureXml.writeSurveys(pts.map { it.digTag.toInt() to it }, 5187, useSigma = true)
        assertTrue(x.contains("<HorizontalAccuracy>0.01</HorizontalAccuracy>"))   // σ 0.6 cm → 최소 1 cm
        assertTrue(x.contains("<VerticalAccuracy>0.013</VerticalAccuracy>"))
    }

    @Test fun reMeasuredTagUsesLatestValue_noHeightDropped() {
        val again = pts + pt(9, 2, 194880.660, 573989.210, 2.890, at = 50) + pt(10, 5, 1.0, 1.0, 0.0).copy(groundMsl = null, groundEllipsoidalHeight = null)
        val x = ItwinGcpExport.build(again, mapOf(1L to "1호"), "강릉").files.single { it.path.endsWith("1호_AprilTag_EPSG5187_MSL-RECEIVER.xml") }.text
        assertTrue(x.contains("<x>194880.66</x>") && x.contains("<z>2.89</z>"))
        assertFalse(x.contains("194880.652"))
        assertEquals(4, Regex("<ControlPoint>").findAll(x).count())
        File("/workspace/survey-app/build-reports/0.1.37").mkdirs()
        File("/workspace/survey-app/build-reports/0.1.37/sample-output-1호_AprilTag_EPSG5187_MSL-RECEIVER.xml").writeText(ItwinGcpExport.build(pts, mapOf(1L to "1호"), "강릉").files.single { it.path.endsWith(".xml") && it.path.contains("1호_") }.text)
    }
}
