package kr.archsurvey.gcp

import kr.archsurvey.gcp.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 0.1.21 G2: Flex(iTwin Capture Modeler) 불러오기 시험 표본을 「C_typed_coords_itwin」 으로 다시 만든다(새 배치).
 * 사진측량 앱 ItwinCoordsTest 의 C_typed_coords 와 같은 표적 3개·같은 좌표(EPSG:5187, x=동E y=북N).
 * 결과: build/sample/C_typed_coords_itwin/발굴조사/사진 기준점/C_typed_coords_itwin/C_typed_coords/C_typed_coords.xml …
 */
class FlexSample0121Test {
    private val z = ZoneId.of("Asia/Seoul")
    private val now = 1_791_730_800_000L

    @Test fun cTypedCoordsSampleInNewLayout() {
        val site = Site(1, "C_typed_coords_itwin", 5187, 0)
        val o = Obj(1, 1, ObjKind.DWELLING, 1, title = "C_typed_coords")
        val s = Session(11, 1, 1, "검출면", listOf(1, 2, 3), createdAt = now - 5000, photoDoneAt = now - 1000, changedAt = now - 2000)
        val shots = listOf(shot(1, 11, 1, 245100.123, 551200.456, 57.89), shot(2, 11, 2, 245110.5, 551205.25, 58.0), shot(3, 11, 3, 245104.0, 551215.0, 57.5))
        val plan = Handoff.plan(SiteBook(site, listOf(o), listOf(s), shots), HandoffScope.ALL, now, z)

        assertEquals("발굴조사/사진 기준점/C_typed_coords_itwin", plan.siteRel)
        val xmlEntry = plan.entries.single { it.first.endsWith(".xml") }
        assertEquals("C_typed_coords/C_typed_coords.xml", xmlEntry.first)

        val out = File("build/sample/C_typed_coords_itwin").apply { deleteRecursively() }
        for ((rel, text) in plan.entries) File(out, "${plan.siteRel}/$rel").apply { parentFile.mkdirs(); writeText(text) }

        val xml = xmlEntry.second
        assertFalse(xml.startsWith("\uFEFF")); assertTrue(xml.contains("\r\n"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("EPSG:5187", doc.getElementsByTagName("Definition").item(0).textContent)
        val cps = doc.getElementsByTagName("ControlPoint")
        assertEquals(3, cps.length)
        val want = listOf(Triple("245100.123", "551200.456", "57.89"), Triple("245110.5", "551205.25", "58.0"), Triple("245104.0", "551215.0", "57.5"))
        for (i in 0 until 3) {
            val e = cps.item(i) as org.w3c.dom.Element
            assertEquals("Target #${i + 1}", e.getElementsByTagName("Name").item(0).textContent)
            assertEquals(want[i].first, e.getElementsByTagName("x").item(0).textContent)   // x = 동(E)
            assertEquals(want[i].second, e.getElementsByTagName("y").item(0).textContent)  // y = 북(N)
            assertEquals(want[i].third, e.getElementsByTagName("z").item(0).textContent)
        }
        assertTrue(File(out, "${plan.siteRel}/C_typed_coords/C_typed_coords.xml").isFile)
        assertTrue(File(out, "${plan.siteRel}/${ItwinSheet.FILE}").isFile)
    }
}
