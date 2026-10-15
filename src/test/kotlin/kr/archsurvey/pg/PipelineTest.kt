package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpTxt
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.live.Coverage
import kr.archsurvey.pg.live.Gates
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.live.ScaleSolver
import kr.archsurvey.pg.tag.TagDetector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** 합성 사진 16장: 검출 → 표적 3D(광선 교차) → 7변수 맞춤 → 내보내기(block.xmlz) → 구조 검사 + 샘플 폴더 */
class PipelineTest {
    private val dir = File(TestData.root, "testdata/synth")
    private val truth = JSONObject(File(dir, "truth.json").readText())

    private fun stills(): List<Still> {
        val st = truth.getJSONArray("stills")
        return (0 until st.length()).map { i ->
            val s = st.getJSONObject(i); val p = s.getJSONArray("pose")
            val (g, w, h) = TestData.gray(File(dir, s.getString("file")))
            Still(s.getInt("id"), s.getString("file"), w, h, truth.getDouble("fx"), truth.getDouble("fy"), truth.getDouble("cx"), truth.getDouble("cy"),
                DoubleArray(7) { p.getDouble(it) }, 0L, TagDetector.detect(g, w, h, decimate = 1.5f))
        }
    }
    private fun gcps(): List<Gcp> { val g = truth.getJSONObject("gcps"); return g.keys().asSequence().map { k -> val a = g.getJSONArray(k); Gcp(k.toInt(), a.getDouble(0), a.getDouble(1), a.getDouble(2)) }.sortedBy { it.target }.toList() }
    private fun settle(e: LiveEngine) { val l = CountDownLatch(1); e.post { l.countDown() }; l.await(30, TimeUnit.SECONDS) }

    @Test fun modeA_fit_from_detections() {
        val e = LiveEngine(Session("synth", 0L, stills(), gcps()))
        settle(e)
        val f = e.fit
        assertNotNull("4 표적 맞춤", f)
        println("Mode A: 축척 ${f!!.tf.s} (참 1.02) · RMSE ${"%.4f".format(f.rmse)} m · 검사점 ${"%.4f".format(f.looRmse)} m")
        assertEquals(1.02, f.tf.s, 0.002)
        assertTrue(f.rmse < 0.01); assertTrue(f.looRmse!! < 0.02)
        e.close()
    }

    @Test fun modeB_scale_from_tag_size() {
        val e = LiveEngine(Session("synth", 0L, stills(), tagSizeM = 0.20))
        settle(e)
        val sc = e.scale!!
        println("Mode B 표적 크기 축척 ${sc.s} (참 1.02) ±${sc.scatterPct}%")
        assertEquals(1.02, sc.s, 0.03)
        val bar = ScaleSolver.fromBar(e.tags, 1.0)
        assertNotNull(bar)
        assertTrue(e.localTagsTxt().lines().count { it.startsWith("Target #") } == 4)
        e.close()
    }

    @Test fun coverage_progress_grows_with_photos() {
        val st = stills()
        fun pctWith(n: Int): Int {
            val e = LiveEngine(Session("c", 0L))
            // 면: 합성 바닥을 로컬 틀로(로컬 = s·M·world + T) 5 cm 간격 점
            val loc = truth.getJSONObject("local"); val th = Math.toRadians(loc.getDouble("theta_deg")); val s = loc.getDouble("scale"); val T = loc.getJSONArray("T")
            val pts = ArrayList<Float>()
            var x = 0.0
            while (x <= 4.0) { var y = 0.0; while (y <= 3.0) {
                val z = 0.03 * x + 0.05 * y
                val a = doubleArrayOf(x, z, -y) // 동, 위, 남
                val lx = s * (Math.cos(th) * a[0] + Math.sin(th) * a[2]) + T.getDouble(0)
                val ly = s * a[1] + T.getDouble(1)
                val lz = s * (-Math.sin(th) * a[0] + Math.cos(th) * a[2]) + T.getDouble(2)
                repeat(3) { pts += lx.toFloat(); pts += ly.toFloat(); pts += lz.toFloat() }
                y += 0.025 }; x += 0.025 }
            e.addDepth(pts.toFloatArray(), null, FloatArray(pts.size / 3) { 0.9f }, pts.size / 3, false); settle(e)
            val l = CountDownLatch(1)
            st.take(n).forEach { e.addStill(it) { } }
            e.post { l.countDown() }; l.await(60, TimeUnit.SECONDS); settle(e)
            val p = e.progress
            println("사진 $n 장 → 충분 ${p.pct}% · 초록 ${p.greenPct}% · 겹침 ${p.overlapPct}% · 표적 ${p.tagsOk}/${p.tagsTotal} · 구멍 ${p.holesPct}% · 안내 ${p.hint} · 복셀 ${e.voxelCount}")
            assertTrue(e.voxelCount > 1000)
            e.close(); return p.pct
        }
        val a = pctWith(4); val b = pctWith(16)
        assertTrue("사진이 늘면 충분 % 가 늘어야 ($a → $b)", b > a)
        assertEquals("→ 오른쪽를", Gates.arrowText(10.0, 0.0, false).let { it }.replace("를", "를"))
        assertTrue(Gates.arrowText(10.0, -10.0, true).startsWith("↗ 오른쪽 위"))
        assertEquals(15.0, Coverage.spreadDeg(listOf(V3(0.0, 1.0, 0.0), V3(Math.sin(Math.toRadians(15.0)), Math.cos(Math.toRadians(15.0)), 0.0))), 1e-6)
    }

    @Test fun session_json_roundtrip_and_gcp_txt() {
        val s = Session("r", 5L, stills().take(3), gcps(), tagSizeM = 0.15)
        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.stills.size, back.stills.size); assertEquals(s.gcps, back.gcps); assertEquals(0.15, back.tagSizeM!!, 0.0)
        assertEquals(s.stills[0].tags.map { it.id }, back.stills[0].tags.map { it.id })
        // 사진 기준점 앱 TXT 그대로 + 빈칸 형식 + 0 틀은 건너뜀
        val g = GcpTxt.parse("Target #1,245100.400,512300.400,57.032\r\nTarget #2 245103.6 512300.5 57.13\r\nTarget #3,0.000,0.000,0.000\r\nAprilTag #4;245100.5;512302.5;57.14\r\n")
        assertEquals(listOf(1, 2, 4), g.map { it.target }); assertEquals(245103.6, g[1].e, 1e-9)
    }

    @Test fun blocksExchange_schema_and_sample_export() {
        val st = stills(); val g = gcps()
        val out = File(TestData.root, "sample-export"); out.deleteRecursively(); out.mkdirs()
        fun xmlOf(d: File) = ZipFile(File(d, "block.xmlz")).use { z -> val en = z.entries().toList(); assertEquals(listOf("block.xml"), en.map { it.name }); z.getInputStream(en[0]).readBytes().toString(Charsets.UTF_8) }
            .also { File(d, "_check_block.xml").writeText(it) }
        // A: 좌표 있음
        val a = ExportFolder.write(Session("synth_A_with_coords", 0L, st, g), dir, File(out, "A_with_coords_itwin"))
        xmlOf(a.dir); checkXml(File(a.dir, "_check_block.xml"), st, withPositions = true); File(a.dir, "_check_block.xml").delete()
        // B: 좌표 없음 — 0.0.9: 표적은 이름 붙은 TiePoint(좌표 없음), 기준점 TXT 없음
        val sb = Session("synth_B_no_coords", 0L, st)
        val b = ExportFolder.write(sb, dir, File(out, "B_no_coords_itwin"))
        xmlOf(b.dir); checkXml(File(b.dir, "_check_block.xml"), st, withPositions = false); File(b.dir, "_check_block.xml").delete()
        assertFalse(b.dir.list()!!.any { it.endsWith(".txt") && it.startsWith("기준점") })
        File(b.dir, "gcp_filled_for_test.txt").writeText(BlocksExchange.gcpTxt(g))
        assertTrue(File(a.dir, "기준점_EPSG5187.txt").readText().startsWith("Target #1,%.3f,%.3f,".format(java.util.Locale.US, g[0].e, g[0].n)))
        assertEquals(16, File(a.dir, "photos").listFiles()!!.size)
        assertTrue("원본 사진 바이트 그대로", File(a.dir, "photos/IMG_0001.jpg").readBytes().contentEquals(File(dir, "IMG_0001.jpg").readBytes()))
        File(out, "truth.json").writeText(truth.toString(1))
    }

    private fun checkXml(f: File, st: List<Still>, withPositions: Boolean) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val root = doc.documentElement
        assertEquals("BlocksExchange", root.tagName); assertEquals("2.1", root.getAttribute("version"))
        fun els(e: Element, n: String) = e.getElementsByTagName(n).let { l -> (0 until l.length).map { l.item(it) as Element } }
        fun txt(e: Element, n: String) = els(e, n).first().textContent
        val block = els(root, "Block").single()
        if (withPositions) {
            assertEquals("EPSG:5187", txt(root, "Definition")); assertEquals("0", txt(block, "SRSId"))
        } else assertTrue(els(root, "SpatialReferenceSystems").isEmpty())
        val photos = els(block, "Photo")
        val ids = photos.map { txt(it, "Id").toInt() }
        assertEquals(st.size, ids.toSet().size)
        photos.forEach { assertTrue(File(f.parentFile, txt(it, "ImagePath")).exists()) }
        val pg = els(block, "Photogroup").single()
        assertEquals("2000", txt(pg, "Width")); assertEquals("1500", txt(pg, "Height")); assertEquals("1650.0", txt(pg, "FocalLengthPixels"))
        val cps = els(block, if (withPositions) "ControlPoint" else "TiePoint")
        if (!withPositions) assertTrue(els(block, "ControlPoint").isEmpty())
        assertEquals(listOf("Target #1", "Target #2", "Target #3", "Target #4"), cps.map { txt(it, "Name") })
        for (cp in cps) {
            assertEquals(withPositions, els(cp, "Position").isNotEmpty())
            if (withPositions) assertFalse(txt(cp, "x") == "0.0")
            val ms = els(cp, "Measurement")
            assertTrue("표적마다 사진 2장 이상", ms.size >= 2)
            val pids = ms.map { txt(it, "PhotoId").toInt() }
            assertEquals("같은 사진 두 번 금지", pids.size, pids.toSet().size)
            assertTrue(pids.all { it in ids })
            ms.forEach { m -> val x = txt(m, "x").toDouble(); val y = txt(m, "y").toDouble(); assertTrue(x in 0.0..1999.0 && y in 0.0..1499.0) }
        }
        // 참값과 비교(-0.5 관례): 사진 1의 Target #1
        val t1 = truth.getJSONArray("stills").getJSONObject(0).getJSONObject("marks").getJSONArray("1")
        val m1 = els(cps[0], "Measurement").first { txt(it, "PhotoId") == "1" }
        assertEquals(t1.getDouble(0) - 0.5, txt(m1, "x").toDouble(), 1.0); assertEquals(t1.getDouble(1) - 0.5, txt(m1, "y").toDouble(), 1.0)
    }
}
