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

    @Test fun one_package_zip_named_iTwin_with_shared_sheet() {
        val tag = TagDetection(1, 0, 100.0, 100.0, doubleArrayOf(90.0, 90.0, 110.0, 90.0, 110.0, 110.0, 90.0, 110.0))
        val withTags = Session("A", 0, listOf(st(1, listOf(tag)), st(2, emptyList())), emptyList())
        val work = Files.createTempDirectory("ot").toFile().let { File(it, "work").apply { mkdirs() } }
        val a = OneTap.build(withTags, synth, work)
        val names = ZipFile(a.zip).use { z -> z.entries().toList().map { it.name } }
        println("zip ${a.zip.name} ${a.bytes}B: $names")
        assertEquals("A_iTwin.zip", a.zip.name)
        assertEquals(setOf("A_iTwin/block.xmlz", "A_iTwin/iTwin_불러오기.txt", "A_iTwin/작업정보.txt", "A_iTwin/photos/IMG_0001.jpg", "A_iTwin/photos/IMG_0002.jpg", "A_iTwin/poses.csv", "A_iTwin/project.json"), names.toSet())
        ZipFile(a.zip).use { z -> assertTrue(z.getInputStream(z.getEntry("A_iTwin/photos/IMG_0001.jpg")).readBytes().contentEquals(File(synth, "IMG_0001.jpg").readBytes())) }
        assertTrue("작업 폴더 정리", work.listFiles()!!.isEmpty())
    }
}
