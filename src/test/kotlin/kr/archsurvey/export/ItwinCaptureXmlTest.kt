package kr.archsurvey.export

import kr.archsurvey.data.PointEntity
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** iTwin Capture Modeler SurveysData XML. 기준: build-reports/itwin-capture-xml-samples/sample-1..4.xml (src/test/resources/itwin 에 복사본) */
class ItwinCaptureXmlTest {
    private fun pt(
        id: String, e: Double, n: Double, z: Double? = 57.272, epsg: Int = 5186, type: String = "기준점", memo: String = "",
        sh: Double? = null, su: Double? = null, ell: Double? = z?.plus(25.0),
    ) = PointEntity(
        project = "제주-발굴", pointId = id, positionType = type, memo = memo, lat = 33.5, lon = 126.4,
        ellipsoidalHeight = 80.0, altitudeMsl = 57.0, geoidSeparation = 25.0, northing = n, easting = e, crsEpsg = epsg,
        fixQuality = 4, allEpochsFixed = true, satellites = 20, hdop = 0.7, sigmaE = 0.005, sigmaN = 0.005, sigmaH = sh, sigmaU = su,
        correctionAgeSec = 1.0, baseStationId = null, epochs = 5, antennaHeight = 1.8,
        groundMsl = z, groundEllipsoidalHeight = ell, heightModel = if (z != null) "EGM96" else "",
        device = "Trimble R10", measuredAtUtcMs = 1_791_100_000_000L,
    )

    private fun sample(i: Int) = javaClass.getResource("/itwin/sample-$i.xml")!!.readText(Charsets.UTF_8)
    private fun parse(s: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))).documentElement
    private fun Element.kids(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.kid(tag: String) = kids(tag).single()
    private fun Element.childTags() = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().map { it.tagName }

    @Test fun sample1_byteForByte() {
        val pts = listOf(pt("1호", 148096.674, 98114.226, 57.272), pt("2호", 148095.809, 98115.0, 56.985), pt("3호", 148094.332, 98115.669, 57.088))
        assertEquals(sample(1), ItwinCaptureXml.write(pts, 5186))
    }

    @Test fun sample2and4_byteForByte_evenWhenMeasuredOutOfOrder() {
        val s2 = listOf(pt("3호", 148090.477, 98124.682, 57.028), pt("1호", 148094.902, 98125.579, 56.857),
            pt("4호", 148093.253, 98126.028, 56.774), pt("2호", 148093.088, 98128.449, 56.736))
        assertEquals(sample(2), ItwinCaptureXml.write(s2, 5186))
        val s4 = listOf(pt("1호", 148090.061, 98118.275, 57.15), pt("2호", 148090.164, 98119.226, 57.027), pt("3호", 148089.864, 98119.952, 57.167))
        assertEquals(sample(4), ItwinCaptureXml.write(s4, 5186))
    }

    @Test fun allSamplesSameStructureAsOurs() {
        val ours = parse(ItwinCaptureXml.write(listOf(pt("1호", 1.0, 2.0)), 5186))
        for (i in 1..4) {
            val s = parse(sample(i))
            assertEquals("SurveysData", s.tagName)
            assertEquals(s.childTags(), ours.childTags())
            val srs = s.kid("SpatialReferenceSystems").kid("SRS"); val osrs = ours.kid("SpatialReferenceSystems").kid("SRS")
            assertEquals(srs.childTags(), osrs.childTags())
            assertEquals(srs.kid("Name").textContent, osrs.kid("Name").textContent)
            assertEquals(srs.kid("Definition").textContent, osrs.kid("Definition").textContent)
            val cp = s.kid("ControlPoints").kids("ControlPoint").first(); val ocp = ours.kid("ControlPoints").kids("ControlPoint").first()
            assertEquals(listOf("Id", "SRSId", "Name", "Category", "Position", "HorizontalAccuracy", "VerticalAccuracy", "CheckPoint"), cp.childTags())
            assertEquals(cp.childTags(), ocp.childTags())
            assertEquals(cp.kid("Position").childTags(), ocp.kid("Position").childTags())
            // 표본 3 은 소수 자리가 길 뿐 꼴은 같다
            s.kid("ControlPoints").kids("ControlPoint").forEachIndexed { k, c ->
                assertEquals(k.toString(), c.kid("Id").textContent); assertEquals("Target #${k + 1}", c.kid("Name").textContent)
            }
        }
    }

    @Test fun srsFollowsJobCrs() {
        val x = parse(ItwinCaptureXml.write(listOf(pt("1호", 210000.0, 350000.0, epsg = 5187)), 5187))
        val srs = x.kid("SpatialReferenceSystems").kid("SRS")
        assertEquals("1", srs.kid("Id").textContent)
        assertEquals("EPSG:5187", srs.kid("Definition").textContent)
        assertEquals("Korea 2000 / East Belt 2010 (EPSG:5187)", srs.kid("Name").textContent)
        assertEquals("1", x.kid("ControlPoints").kids("ControlPoint").single().kid("SRSId").textContent)
        assertEquals("Korea 2000 / Central Belt 2010 (EPSG:5186)", ItwinCaptureXml.srsName(5186))
        assertEquals("EPSG:32652", ItwinCaptureXml.srsName(32652))
    }

    @Test fun targetMapping_1ho_2ho_10ho() {
        assertEquals(1L, ItwinCaptureXml.targetNumber("1호")); assertEquals(2L, ItwinCaptureXml.targetNumber("2호"))
        assertEquals(10L, ItwinCaptureXml.targetNumber("10호")); assertEquals(3L, ItwinCaptureXml.targetNumber("03호"))
        assertEquals(7L, ItwinCaptureXml.targetNumber("Target #7")); assertEquals(5L, ItwinCaptureXml.targetNumber("GCP-5"))
        assertNull(ItwinCaptureXml.targetNumber("기준점")); assertNull(ItwinCaptureXml.targetNumber("2-1호"))
        val x = parse(ItwinCaptureXml.write(listOf(pt("10호", 3.0, 3.0), pt("2호", 2.0, 2.0), pt("1호", 1.0, 1.0)), 5186))
        val cps = x.kid("ControlPoints").kids("ControlPoint")
        assertEquals(listOf("Target #1", "Target #2", "Target #10"), cps.map { it.kid("Name").textContent })
        assertEquals(listOf("0", "1", "2"), cps.map { it.kid("Id").textContent })
        assertEquals(listOf("1.0", "2.0", "3.0"), cps.map { it.kid("Position").kid("x").textContent })
    }

    @Test fun unnumberedAndDuplicateKeepOriginalNameAtEnd() {
        val es = ItwinCaptureXml.entries(listOf(pt("기준점A", 0.0, 0.0), pt("1호", 1.0, 1.0), pt("1호 재측", 2.0, 2.0), pt("2-1호", 3.0, 3.0)))
        assertEquals(listOf("Target #1", "기준점A", "1호 재측", "2-1호"), es.map { it.name })
        assertEquals(listOf(true, false, false, false), es.map { it.renamed })
        val csv = ItwinCaptureXml.namesCsv(listOf(pt("기준점A", 0.0, 0.0), pt("1호", 1.0, 1.0), pt("1호 재측", 2.0, 2.0), pt("무표고", 0.0, 0.0, z = null, ell = null)))
        assertTrue(csv.startsWith("\uFEFFitwin_id,itwin_name,field_point_id"))
        assertTrue(csv.contains("0,Target #1,1호,true"))
        assertTrue(csv.contains("같은 번호가 앞에 있어"))
        assertTrue(csv.contains("번호를 못 찾아"))
        assertTrue(csv.contains(",무표고,false,false,false,높이가 없어 XML에서 뺌"))
    }

    @Test fun keepOriginalNamesOption() {
        val x = parse(ItwinCaptureXml.write(listOf(pt("2호", 2.0, 2.0), pt("1호", 1.0, 1.0)), 5186, keepOriginalNames = true))
        assertEquals(listOf("1호", "2호"), x.kid("ControlPoints").kids("ControlPoint").map { it.kid("Name").textContent })
        assertTrue(ItwinCaptureXml.namesCsv(listOf(pt("1호", 1.0, 1.0)), true).contains("원래 이름 유지(설정)"))
    }

    @Test fun accuracyFromSigmaWithFloor() {
        assertEquals("0.01", ItwinCaptureXml.accuracy(null)); assertEquals("0.01", ItwinCaptureXml.accuracy(0.004))
        assertEquals("0.012", ItwinCaptureXml.accuracy(0.0123)); assertEquals("0.02", ItwinCaptureXml.accuracy(0.02))
        val cp = parse(ItwinCaptureXml.write(listOf(pt("1호", 1.0, 1.0, sh = 0.015, su = 0.031)), 5186)).kid("ControlPoints").kids("ControlPoint").single()
        assertEquals("0.015", cp.kid("HorizontalAccuracy").textContent); assertEquals("0.031", cp.kid("VerticalAccuracy").textContent)
    }

    @Test fun checkPointFlagAndXEastYNorthZGround() {
        val x = parse(ItwinCaptureXml.write(listOf(pt("1호", 148096.674, 98114.226, 57.272), pt("2호", 5.0, 6.0, type = "검사점"), pt("3호", 7.0, 8.0, memo = "check")), 5186))
        val cps = x.kid("ControlPoints").kids("ControlPoint")
        assertEquals(listOf("false", "true", "true"), cps.map { it.kid("CheckPoint").textContent })
        assertEquals(listOf("Full", "Full", "Full"), cps.map { it.kid("Category").textContent })
        val p = cps[0].kid("Position")
        assertEquals("148096.674", p.kid("x").textContent); assertEquals("98114.226", p.kid("y").textContent); assertEquals("57.272", p.kid("z").textContent)
        // 표고(MSL) 없으면 지면 타원체고, 둘 다 없으면 점을 뺀다
        val y = parse(ItwinCaptureXml.write(listOf(pt("1호", 1.0, 1.0, z = null, ell = 80.5), pt("2호", 1.0, 1.0, z = null, ell = null)), 5186))
        assertEquals(listOf("80.5"), y.kid("ControlPoints").kids("ControlPoint").map { it.kid("Position").kid("z").textContent })
    }

    @Test fun numberFormatAndEscaping() {
        assertEquals("98115.0", ItwinCaptureXml.num(98115.0)); assertEquals("57.15", ItwinCaptureXml.num(57.15))
        assertEquals("148096.674", ItwinCaptureXml.num(148096.6744)); assertEquals("-1.5", ItwinCaptureXml.num(-1.5))
        val s = ItwinCaptureXml.write(listOf(pt("A&B<1>", 1.0, 1.0)), 5186, keepOriginalNames = true)
        assertTrue(s.contains("<Name>A&amp;B&lt;1&gt;</Name>")); parse(s)
        assertFalse(s.startsWith("\uFEFF")); assertFalse(s.contains("\r"))
    }

    @Test fun formatIsListedInExportScreen() {
        assertTrue(ExportFormat.entries.any { it.id == "itwin_xml" && it.labelKo.startsWith("iTwin Capture XML") && it.ext == ".xml" })
        assertFalse(ExportFormat.ITWIN_XML in ExportFormat.DEFAULT); assertFalse(ExportFormat.ITWIN_XML in ExportFormat.HGIS)
    }
}
