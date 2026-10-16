package kr.archsurvey.pg

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpTxt
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.diag.Quality
import kr.archsurvey.pg.diag.ThermalPolicy
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.tag.TagDetection
import kr.archsurvey.pg.tag.TagDetector
import kr.archsurvey.pg.tag.TargetNaming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class P01Test {
    private val synth = File(TestData.root, "testdata/synth")

    @Test fun quality_sharp_ok_blur_dark_bright_rejected() {
        val (g, w, h) = TestData.gray(File(synth, "IMG_0001.jpg"))
        val ok = Quality.check(g, w, h); println("sharp=${ok.sharpness} mean=${ok.mean}")
        assertTrue("합성 사진은 통과", ok.ok)
        // 심한 흐림: 가로·세로 25 px 상자 흐림 (임계값 12는 보수적 — 현장 보정 필요)
        fun box(src: ByteArray, dx: Int, dy: Int): ByteArray { val o = ByteArray(w * h); val r = 12
            for (y in 0 until h) for (x in 0 until w) { var s = 0; var n = 0
                for (k in -r..r) { val xx = x + k * dx; val yy = y + k * dy; if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx].toInt() and 255; n++ } }
                o[y * w + x] = (s / n).toByte() }; return o }
        val bl = box(box(box(box(g, 1, 0), 0, 1), 1, 0), 0, 1)
        val b = Quality.check(bl, w, h); println("blur sharp=${b.sharpness}")
        assertEquals("blur", b.reason); assertTrue(b.message!!.startsWith("흔들렸어요"))
        assertEquals("dark", Quality.check(ByteArray(w * h) { ((g[it].toInt() and 255) / 10).toByte() }, w, h).reason)
        assertEquals("bright", Quality.check(ByteArray(w * h) { 240.toByte() }, w, h).reason)
        assertEquals("bad", Quality.check(ByteArray(4), 2, 2).reason)
    }

    @Test fun tag_min_35px_filter() {
        fun det(px: Double) = TagDetection(0, 0, 0.0, 0.0, doubleArrayOf(0.0, 0.0, px, 0.0, px, px, 0.0, px))
        val (keep, drop) = Quality.filterTags(listOf(det(34.0), det(35.0), det(80.0)))
        assertEquals(2, keep.size); assertEquals(1, drop.size); assertEquals(34.0, Quality.tagPx(drop[0]), 1e-9)
        // 진짜 검출: 표적 그림의 검은 네모 ≈ 800 px
        val (g, w, h) = TestData.gray(TestData.target(2))
        assertEquals(800.0, Quality.tagPx(TagDetector.detect(g, w, h, decimate = 1f, maxHamming = 0)[0]), 2.0)
    }

    @Test fun thermal_headroom_nan_safe() {
        assertEquals(0, ThermalPolicy.combine(0, Float.NaN)); assertEquals(1, ThermalPolicy.combine(1, -1f))
        assertEquals(2, ThermalPolicy.combine(0, 0.9f)); assertEquals(2, ThermalPolicy.combine(1, 0.97f)); assertEquals(4, ThermalPolicy.combine(4, 0.5f))
    }

    @Test fun name_fixed_target_and_parse() {
        assertEquals("Target #4", TargetNaming.name(3))
        assertEquals(4, TargetNaming.parseTargetNo("Target #4")); assertEquals(4, TargetNaming.parseTargetNo("AprilTag #4")); assertEquals(8, TargetNaming.parseTargetNo("TAG_36h11_07"))
        val xml = BlocksExchange.write(listOf(Still(1, "a.jpg", 100, 80, 90.0, 90.0, 50.25, 40.5, null, 0, listOf(TagDetection(3, 0, 10.5, 20.5, DoubleArray(8))))), emptyList(), BlocksExchange.Options("x", null))
        assertTrue(xml.contains("<Name>Target #4</Name>")); assertFalse(xml.contains("AprilTag #"))
    }

    @Test fun photogroup_intrinsics_and_auto_target_export() {
        val st = listOf(
            Still(1, "IMG_0001.jpg", 1920, 1440, 1500.0, 1502.0, 961.0, 719.0, null, 0, emptyList()),
            Still(2, "IMG_0002.jpg", 1920, 1440, 1500.4, 1501.6, 961.0, 719.0, null, 0, emptyList()),
            Still(3, "IMG_0003.jpg", 1920, 1080, 1400.0, 1400.0, 960.0, 540.0, null, 0, emptyList()))
        val xml = BlocksExchange.write(st, emptyList(), BlocksExchange.Options("pg", null))
        assertEquals("해상도별 photogroup", 2, Regex("<Photogroup>").findAll(xml).count())
        assertTrue(xml.contains("<FocalLengthPixels>1501.0</FocalLengthPixels>"))
        assertTrue(xml.contains("<PrincipalPoint>\r\n\t\t\t\t\t<x>960.5</x>\r\n\t\t\t\t\t<y>718.5</y>"))
    }
}
