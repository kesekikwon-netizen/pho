package kr.archsurvey.t0158

import kr.archsurvey.georef.TiffRaster
import kr.archsurvey.georef.TiffUnsupportedException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TiffRaster0158Test {
    private fun res(n: String) = File(javaClass.classLoader!!.getResource("tiff0158/$n")!!.toURI())
    private val ref by lazy { JSONObject(res("ref.json").readText()) }

    private fun checkFull(name: String, gray: Boolean = false, alpha: Boolean = false) {
        val img = TiffRaster.decode(res(name), 100_000)
        val pts = ref.getJSONArray(name)
        for (i in 0 until pts.length()) {
            val p = pts.getJSONArray(i); val r = p.getInt(0); val c = p.getInt(1)
            val v = img.argb[r * img.width + c]
            val R = (v shr 16) and 0xFF; val G = (v shr 8) and 0xFF; val B = v and 0xFF; val A = (v ushr 24)
            if (gray) { assertEquals("$name $r,$c", p.getInt(2), R); assertEquals(R, G); assertEquals(R, B) }
            else { assertEquals("$name $r,$c R", p.getInt(2), R); assertEquals("$name G", p.getInt(3), G); assertEquals("$name B", p.getInt(4), B) }
            if (alpha) assertEquals(p.getInt(5), A) else assertEquals(255, A)
        }
    }

    @Test fun lzw() = checkFull("rgb_lzw.tif")
    @Test fun lzwPredictor() = checkFull("rgb_lzw_pred.tif")
    @Test fun deflatePredictor() = checkFull("rgb_deflate.tif")
    @Test fun uncompressed() = checkFull("rgb_none.tif")
    @Test fun grayLzw() = checkFull("gray_lzw.tif", gray = true)
    @Test fun rgbaLzw() = checkFull("rgba_lzw.tif", alpha = true)

    @Test fun allFormatsDecodeIdentically() {
        val a = TiffRaster.decode(res("rgb_lzw.tif"), 100_000).argb
        for (n in listOf("rgb_lzw_pred.tif", "rgb_deflate.tif", "rgb_none.tif")) assertArrayEquals(n, a, TiffRaster.decode(res(n), 100_000).argb)
    }

    @Test fun downsampleLongSide_nearest() {
        val full = TiffRaster.decode(res("rgb_lzw.tif"), 100_000)
        val s = TiffRaster.decode(res("rgb_lzw.tif"), 200)
        assertEquals(200, s.width); assertEquals(Math.round(301 / (517 / 200.0)).toInt(), s.height)
        assertEquals(517, s.srcWidth); assertEquals(301, s.srcHeight)
        for ((oy, ox) in listOf(0 to 0, s.height - 1 to s.width - 1, 50 to 120)) {
            val sy = ((oy + 0.5) * 301 / s.height).toInt(); val sx = ((ox + 0.5) * 517 / s.width).toInt()
            assertEquals(full.argb[sy * 517 + sx], s.argb[oy * s.width + ox])
        }
    }

    @Test fun defaultMaxSide2048() = assertEquals(2048, TiffRaster.DEFAULT_MAX_SIDE)

    @Test fun nodataTransparent() {
        val img = TiffRaster.decode(res("nodata.tif"), 100_000)
        assertEquals(0, img.argb[3 * img.width + 5])
        assertEquals(255, img.argb[20 * img.width + 5] ushr 24)
    }

    @Test fun tiledUnsupported_clearMessage() {
        val e = assertThrows(TiffUnsupportedException::class.java) { TiffRaster.decode(res("tiled.tif")) }
        assertTrue(e.message!!.contains("타일"))
    }

    /** 사용자 실제 정사영상(박스에만 있음, 저장소엔 없음) — 있으면 끝까지 읽히는지 */
    @Test fun realFieldOrthoIfPresent() {
        val dir = File("/workspace/st/tif"); assumeTrue(dir.isDirectory)
        dir.listFiles { f -> f.name.endsWith(".tif") }!!.forEach { f ->
            val img = TiffRaster.decode(f)
            assertTrue(f.name, maxOf(img.width, img.height) <= 2048)
            assertTrue(f.name + " 거의 비어 있음", img.argb.count { (it ushr 24) != 0 && (it and 0xFFFFFF) != 0 } > img.argb.size / 10)
        }
        val rf = File(dir, "ref.json"); if (!rf.exists()) return
        val o = JSONObject(rf.readText())
        for (k in o.keys()) {
            val img = TiffRaster.decode(File(dir, k)); val e = o.getJSONObject(k)
            assertEquals(k, e.getInt("w"), img.width); assertEquals(k, e.getInt("h"), img.height)
            val pts = e.getJSONArray("pts")
            for (i in 0 until pts.length()) { val p = pts.getJSONArray(i); val v = img.argb[p.getInt(0) * img.width + p.getInt(1)]
                assertEquals("$k $i", listOf(p.getInt(2), p.getInt(3), p.getInt(4)), listOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)) }
        }
    }
}
