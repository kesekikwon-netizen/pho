package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.OneTap
import kr.archsurvey.pg.tag.TagDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class OneTapTest {
    private val synth = File(TestData.root, "testdata/synth")
    private fun st(id: Int, tags: List<TagDetection>) = Still(id, "IMG_%04d.jpg".format(id), 1920, 1440, 1500.0, 1500.0, 960.0, 720.0, doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), 0, tags)

    @Test fun auto_mode_picks_app_marks_when_tags_else_itwin_detect_and_zips_everything() {
        val tag = TagDetection(1, 0, 100.0, 100.0, doubleArrayOf(90.0, 90.0, 110.0, 90.0, 110.0, 110.0, 90.0, 110.0))
        val withTags = Session("A", 0, listOf(st(1, listOf(tag)), st(2, emptyList())), emptyList())
        val noTags = Session("B", 0, listOf(st(1, emptyList()), st(2, emptyList())), emptyList())
        assertEquals(OneTap.Mode.APP_MARKS, OneTap.resolve(withTags, OneTap.Mode.AUTO))
        assertEquals(OneTap.Mode.ITWIN_DETECT, OneTap.resolve(noTags, OneTap.Mode.AUTO))
        assertEquals(OneTap.Mode.ITWIN_DETECT, OneTap.resolve(withTags, OneTap.Mode.ITWIN_DETECT))
        val work = Files.createTempDirectory("ot").toFile().let { File(it, "work").apply { mkdirs() } }
        val a = OneTap.build(withTags, synth, work, m = OneTap.Mode.AUTO)
        val names = ZipFile(a.zip).use { z -> z.entries().toList().map { it.name } }
        println("zip ${a.zip.name} ${a.bytes}B: $names")
        assertEquals("A_itwin.zip", a.zip.name)
        assertTrue(names.containsAll(listOf("A_itwin/block.xmlz", "A_itwin/gcp_template.txt", "A_itwin/README_ko.txt", "A_itwin/photos/IMG_0001.jpg", "A_itwin/photos/IMG_0002.jpg", "A_itwin/block_tiepoints.xmlz")))
        val b = OneTap.build(noTags, synth, work, m = OneTap.Mode.AUTO)
        val nb = ZipFile(b.zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals("B_itwin_auto.zip", b.zip.name)
        assertTrue(nb.containsAll(listOf("B_itwin_auto/gcp.txt", "B_itwin_auto/README_ko.txt", "B_itwin_auto/photos/IMG_0001.jpg")))
        assertTrue(nb.none { it.endsWith(".xmlz") })
        // 사진은 바이트 그대로
        ZipFile(b.zip).use { z -> assertTrue(z.getInputStream(z.getEntry("B_itwin_auto/photos/IMG_0001.jpg")).readBytes().contentEquals(File(synth, "IMG_0001.jpg").readBytes())) }
        assertTrue("작업 폴더 정리", work.listFiles()!!.isEmpty())
    }
}
