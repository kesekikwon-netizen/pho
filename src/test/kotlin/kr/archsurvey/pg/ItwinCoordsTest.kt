package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpCheck
import kr.archsurvey.pg.data.JobName
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.tag.TagDetection
import kr.archsurvey.pg.tag.TargetNaming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class ItwinCoordsTest {
    @After fun reset() { TargetNaming.style = TargetNaming.Style.TARGET }
    private fun tag(id: Int) = TagDetection(id, 0, 500.5, 400.5, DoubleArray(8))
    private fun st(i: Int, ids: List<Int>) = Still(i, "IMG_%04d.jpg".format(i), 1920, 1080, 1270.0, 1270.0, 960.0, 540.0, doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), 0, ids.map { tag(it) })

    @Test fun typed_coordinates_produce_spec_block_SRS_Position_xEast_yNorth() {
        val stills = listOf(st(1, listOf(0, 1, 2)), st(2, listOf(0, 1, 2, 3)), st(3, listOf(0, 2, 3)))
        val g = listOf(Gcp(1, 245100.123, 551200.456, 57.89), Gcp(2, 245110.5, 551205.25, 58.0), Gcp(3, 245104.0, 551215.0, 57.5))
        val xml = BlocksExchange.write(stills, g, BlocksExchange.Options("현장", 5187))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val srs = doc.getElementsByTagName("SRS").item(0)
        assertTrue(xml.contains("<Definition>EPSG:5187</Definition>"))
        assertEquals("0", doc.getElementsByTagName("Block").item(0).childNodes.let { n -> (0 until n.length).map { n.item(it) }.first { it.nodeName == "SRSId" }.textContent })
        assertTrue(srs != null)
        val cps = doc.getElementsByTagName("ControlPoint")
        assertEquals(3, cps.length)
        val first = cps.item(0) as org.w3c.dom.Element
        assertEquals("Target #1", first.getElementsByTagName("Name").item(0).textContent)
        val pos = first.getElementsByTagName("Position").item(0) as org.w3c.dom.Element
        assertEquals("245100.123", pos.getElementsByTagName("x").item(0).textContent) // x = 동E
        assertEquals("551200.456", pos.getElementsByTagName("y").item(0).textContent) // y = 북N
        assertEquals("57.89", pos.getElementsByTagName("z").item(0).textContent)
        assertEquals(3, first.getElementsByTagName("Measurement").length)
        // 좌표 없는 Target #4 → 위치 없는 ControlPoint 가 아니라 TiePoint
        val tps = doc.getElementsByTagName("TiePoint")
        assertTrue((0 until tps.length).any { (tps.item(it) as org.w3c.dom.Element).getElementsByTagName("Name").item(0)?.textContent == "Target #4" })
        val posN = doc.getElementsByTagName("Position")
        for (i in 0 until posN.length) assertFalse(posN.item(i).textContent.contains(","))
        println(xml.take(1500))
    }

    @Test fun no_targets_no_empty_gcp_txt() {
        val out = Files.createTempDirectory("e").toFile()
        ExportFolder.writeAutoTargets(Session("x", 0, listOf(st(1, emptyList())), emptyList()), File(TestData.root, "testdata/synth"), out)
        assertFalse(File(out, "gcp.txt").exists())
    }

    @Test fun job_name_rules() {
        val ex = setOf("회암사지")
        assertNull(JobName.problem("  회암사지 3호  건물 ") { it in ex })
        assertEquals("회암사지 3호 건물", JobName.clean("  회암사지 3호  건물 "))
        assertEquals("이름을 써 주세요", JobName.problem("   ") { false })
        assertTrue(JobName.problem("a/b") { false }!!.contains("쓸 수 없어요"))
        assertEquals("같은 이름의 작업이 있어요", JobName.problem("회암사지") { it in ex })
        assertTrue(JobName.problem("abc.") { false } != null)
    }

    @Test fun gcp_number_entry_and_warnings() {
        assertEquals(245100.123, GcpCheck.num("245100.123")!!, 1e-9)
        assertEquals(245100.123, GcpCheck.num("245100,123")!!, 1e-9)
        assertEquals(245100.5, GcpCheck.num("245,100.5")!!, 1e-9)
        assertNull(GcpCheck.num("abc"))
        assertNull(GcpCheck.warn(245100.0, 551200.0, 57.0, 5187))
        assertTrue(GcpCheck.warn(551200.0, 245100.0, 57.0, 5187)!!.contains("바뀐"))
        assertTrue(GcpCheck.warn(245100.0, 551200.0, 9000.0, 5187) != null)
    }

    @Test fun sample_package_with_typed_coords() {
        val synth = File(TestData.root, "testdata/synth")
        val ids = listOf(0, 1, 2)
        val stills = (1..3).map { st(it, ids) }
        val s = Session("C_typed_coords", 0, stills, listOf(Gcp(1, 245100.123, 551200.456, 57.89), Gcp(2, 245110.5, 551205.25, 58.0), Gcp(3, 245104.0, 551215.0, 57.5)), 5187)
        val out = File(TestData.root, "build/sample/C_typed_coords_itwin").apply { deleteRecursively(); mkdirs() }
        ExportFolder.write(s, synth, out)
        assertTrue(File(out, "block.xmlz").exists())
    }
}
