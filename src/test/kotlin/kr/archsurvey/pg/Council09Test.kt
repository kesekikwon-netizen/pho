package kr.archsurvey.pg

import kr.archsurvey.pg.data.ExportPlace
import kr.archsurvey.pg.data.FileShelf
import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpImport
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.SessionFiles
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.diag.Msg
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.export.ItwinSheet
import kr.archsurvey.pg.export.OneTap
import kr.archsurvey.pg.tag.TagDetection
import kr.archsurvey.pg.tag.TargetNaming
import kr.archsurvey.pg.ui.CaptureRules
import kr.archsurvey.pg.ui.NewJobRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 16-council-pg.md Must 합격 기준(JVM 으로 시험할 수 있는 것) */
class Council09Test {
    private val synth = File(TestData.root, "testdata/synth")
    private fun tag(id: Int) = TagDetection(id, 0, 500.5, 400.5, DoubleArray(8))
    private fun st(i: Int, ids: List<Int>) = Still(i, "IMG_%04d.jpg".format(i), 1920, 1440, 1500.0, 1500.0, 960.0, 720.0, doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), 0, ids.map { tag(it) })
    private fun four() = listOf(st(1, listOf(0, 1, 2, 3)), st(2, listOf(0, 1, 2, 3)), st(3, listOf(0, 1, 2, 3)))
    private fun blockXml(zip: File, dir: String) = ZipFile(zip).use { z ->
        val inner = z.getInputStream(z.getEntry("$dir/block.xmlz")).readBytes()
        java.util.zip.ZipInputStream(inner.inputStream()).use { it.nextEntry; it.readBytes().toString(Charsets.UTF_8) }
    }
    private fun work() = Files.createTempDirectory("c9").toFile().let { File(it, "work").apply { mkdirs() } }

    @Test fun M1_overwrite_keeps_one_zip_and_one_bak_no_copies() {
        val shelf = Files.createTempDirectory("shelf").toFile()
        val fs = FileShelf(File(shelf, "Documents/발굴조사/현장/사진측량"))
        val src = File(Files.createTempDirectory("z").toFile(), "현장_iTwin.zip")
        File(shelf, "Documents/발굴조사/현장/사진측량").mkdirs()
        File(shelf, "Documents/발굴조사/현장/사진측량/현장_iTwin (1).zip").writeText("old copy")
        src.writeText("v1"); val r1 = ExportPlace.put(fs, src)
        src.writeText("v2"); val r2 = ExportPlace.put(fs, src)
        src.writeText("v3"); ExportPlace.put(fs, src)
        val names = fs.names().sorted()
        assertEquals(listOf("현장_iTwin.bak.zip", "현장_iTwin.zip"), names)
        assertEquals("v3", File(shelf, "Documents/발굴조사/현장/사진측량/현장_iTwin.zip").readText())
        assertEquals("v2", File(shelf, "Documents/발굴조사/현장/사진측량/현장_iTwin.bak.zip").readText())
        assertFalse(r1.replaced); assertTrue(r2.replaced && r2.bak)
    }

    @Test fun M2_names_always_Target_N() {
        assertFalse("이름 설정 없음", TargetNaming::class.java.declaredFields.any { it.name == "style" })
        val s = Session("n", 0, four(), listOf(Gcp(1, 245100.0, 551200.0, 57.0), Gcp(2, 245110.0, 551205.0, 58.0)))
        val z = OneTap.build(s, synth, work()).zip
        val xml = blockXml(z, "n_iTwin")
        val txt = ZipFile(z).use { it.getInputStream(it.getEntry("n_iTwin/기준점_EPSG5187.txt")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml.contains("<Name>Target #1</Name>") && !xml.contains("AprilTag #"))
        assertTrue(txt.lines().filter { it.isNotBlank() }.all { it.startsWith("Target #") })
    }

    @Test fun M3_only_points_with_coordinates_are_written() {
        val s = Session("m3", 0, four(), listOf(Gcp(1, 245100.0, 551200.0, 57.0), Gcp(3, 245104.0, 551215.0, 57.5)))
        val z = OneTap.build(s, synth, work()).zip
        val xml = blockXml(z, "m3_iTwin")
        assertEquals(2, Regex("<ControlPoint>").findAll(xml).count())
        assertFalse(xml.contains("<TiePoint>")); assertFalse(xml.contains("Target #2")); assertFalse(xml.contains("Target #4"))
        val txt = ZipFile(z).use { it.getInputStream(it.getEntry("m3_iTwin/기준점_EPSG5187.txt")).readBytes().toString(Charsets.UTF_8) }
        assertEquals(2, txt.lines().count { it.isNotBlank() })
        assertFalse(txt.contains("0.000,0.000,0.000"))
    }

    @Test fun M4_epsg_from_file_name_and_xml() {
        val r = GcpImport.read("회암사지_EPSG5186.txt", "Target #1,200100.000,551200.000,57.000\r\nTarget #2,200110.000,551205.000,58.000\r\n".toByteArray())
        assertEquals(5186, r.epsg); assertEquals(2, r.gcps.size)
        assertEquals("중부 5186으로 맞췄어요", GcpImport.epsgLine(r.epsg!!))
        val s = Session("m4", 0, four(), r.gcps, epsg = r.epsg!!)
        assertTrue(blockXml(OneTap.build(s, synth, work()).zip, "m4_iTwin").contains("<Definition>EPSG:5186</Definition>"))
        // 사진 기준점 앱 SurveysData XML (zip 안)
        val xml = """<?xml version="1.0" encoding="utf-8"?><SurveysData><SpatialReferenceSystems><SRS><Id>0</Id><Definition>EPSG:5188</Definition></SRS></SpatialReferenceSystems>
            <ControlPoints><ControlPoint><Id>0</Id><SRSId>0</SRSId><Name>Target #3</Name><Category>Full</Category><Position><x>300100.5</x><y>551200.25</y><z>12.5</z></Position></ControlPoint></ControlPoints></SurveysData>"""
        val bo = ByteArrayOutputStream(); ZipOutputStream(bo).use { it.putNextEntry(ZipEntry("폴더/기준점.xml")); it.write(xml.toByteArray()); it.closeEntry() }
        val rz = GcpImport.read("폴더_iTwin.zip", bo.toByteArray())
        assertEquals(5188, rz.epsg); assertEquals(listOf(Gcp(3, 300100.5, 551200.25, 12.5)), rz.gcps)
    }

    @Test fun M5_no_photos_no_export() {
        val s = Session("빈", 0, emptyList(), emptyList())
        assertNotNull(ExportFolder.blockReason(s))
        val w = work()
        assertTrue(runCatching { OneTap.build(s, synth, w) }.isFailure)
        assertFalse(File(w.parentFile, OneTap.zipName(s)).exists())
    }

    @Test fun M6_one_shared_sheet_identical() {
        val shared = File("/workspace/design-program/shared/iTwin_불러오기.txt")
        if (shared.exists()) assertEquals("공용 원본과 글자까지 같음", shared.readText(Charsets.UTF_8), ItwinSheet.TEXT)
        val z = OneTap.build(Session("m6", 0, four(), emptyList()), synth, work()).zip
        val sheet = ZipFile(z).use { it.getInputStream(it.getEntry("m6_iTwin/iTwin_불러오기.txt")).readBytes().toString(Charsets.UTF_8) }
        assertEquals(ItwinSheet.TEXT, sheet)
        assertFalse(sheet.contains("마법사")); assertTrue(sheet.contains("block.xmlz"))
        val info = ZipFile(z).use { it.getInputStream(it.getEntry("m6_iTwin/작업정보.txt")).readBytes().toString(Charsets.UTF_8) }
        val withC = OneTap.build(Session("m6c", 0, four(), listOf(Gcp(1, 200100.0, 551200.0, 1.0)), epsg = 5186), synth, work()).zip
        assertTrue(ZipFile(withC).use { it.getInputStream(it.getEntry("m6c_iTwin/작업정보.txt")).readBytes().toString(Charsets.UTF_8) }.contains("EPSG:5186"))
        assertTrue(info.contains("좌표: 없음"))
    }

    @Test fun M7_no_mode_choice_zero_targets_photos_only() {
        assertFalse(OneTap::class.java.declaredClasses.any { it.simpleName == "Mode" })
        val z = OneTap.build(Session("m7", 0, listOf(st(1, emptyList()), st(2, emptyList())), emptyList()), synth, work()).zip
        val xml = blockXml(z, "m7_iTwin")
        assertTrue(xml.contains("<Photo>")); assertFalse(xml.contains("<ControlPoint>")); assertFalse(xml.contains("<TiePoint>"))
    }

    @Test fun M8_shared_root() {
        assertEquals("Documents/발굴조사/회암사지/사진측량/", ExportPlace.relative("회암사지"))
        assertEquals("내 파일 › Documents › 발굴조사 › 회암사지 › 사진측량 › 회암사지_iTwin.zip", ExportPlace.pcPath("회암사지", "회암사지_iTwin.zip"))
    }

    @Test fun M9_create_disabled_when_blank_or_existing() {
        val ex = setOf("있는작업")
        assertFalse(NewJobRule.canCreate("") { it in ex }); assertFalse(NewJobRule.canCreate("   ") { it in ex })
        assertFalse(NewJobRule.canCreate("있는작업") { it in ex }); assertTrue(NewJobRule.canCreate("새 현장") { it in ex })
    }

    @Test fun M11_mismatch_warning() {
        assertNotNull(GcpImport.mismatch(listOf(Gcp(7, 1e5, 5e5, 1.0)), setOf(1, 2, 3)))
        assertNull(GcpImport.mismatch(listOf(Gcp(1, 1e5, 5e5, 1.0), Gcp(7, 1e5, 5e5, 1.0)), setOf(1, 2, 3)))
    }

    @Test fun M13_three_bottom_three_menu() { assertEquals(3, CaptureRules.BOTTOM.size); assertEquals(listOf("좌표 입력", "결과", "로그 공유"), CaptureRules.MENU) }

    @Test fun M14_yellow_band_from_11th_photo() {
        assertFalse(CaptureRules.noTargetWarn(10, 0)); assertTrue(CaptureRules.noTargetWarn(11, 0)); assertFalse(CaptureRules.noTargetWarn(11, 1))
    }

    @Test fun S1_S5_S8() {
        assertEquals(0.20, kr.archsurvey.pg.live.LiveEngine.DEFAULT_TAG_M, 0.0)
        assertEquals("저장 공간이 1.2 GB 더 필요해요", Msg.spaceNeed(1_234_000_000))
        val sf = SessionFiles(Files.createTempDirectory("sf").toFile())
        sf.save(Session("가", 0)); assertNull(sf.rename("가", "나")); assertTrue(sf.dir("나").exists()); assertEquals("나", sf.load("나").session.name)
        assertTrue(sf.delete("나")); assertFalse(sf.dir("나").exists())
    }
}
