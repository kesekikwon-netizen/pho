package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.live.FrustumLines
import org.junit.Assert.*
import org.junit.Test

/** 0.0.10: 사진 장수 제한 없음(사용자 2026-10-15) */
class NoPhotoLimitTest {
    private fun still(i: Int) = Still(i, String.format(java.util.Locale.US, "IMG_%04d.jpg", i), 1920, 1440, 1500.0, 1500.0, 960.0, 720.0,
        doubleArrayOf(i * 0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), i.toLong(), emptyList())
    private val ident: (DoubleArray) -> FloatArray = { p -> FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f; it[12] = p[0].toFloat() } }

    @Test fun frustums_growWithoutCap_appendOnly_andRebuildOnUndo() {
        val f = FrustumLines()
        val all = (1..20_000).map(::still)
        assertTrue(f.sync(all.take(10), ident)); assertEquals(10 * 16 * 7, f.floats)
        assertTrue(f.sync(all, ident)); assertEquals(20_000 * 16 * 7, f.floats)
        assertFalse(f.sync(all, ident))
        assertTrue(f.sync(all.dropLast(1), ident)); assertEquals(19_999 * 16 * 7, f.floats)
    }

    @Test fun session_and_export_keep_10000plus_photos() {
        val n = 12_000
        val s = Session("big", 0L, stills = (1..n).map(::still))
        val back = Session.fromJson(org.json.JSONObject(s.toJson().toString()))
        assertEquals(n, back.stills.size)
        assertEquals("IMG_12000.jpg", back.stills.last().file)
        val xml = BlocksExchange.write(back.stills, emptyList(), BlocksExchange.Options("big"))
        assertEquals(n, Regex("<Photo>").findAll(xml).count())
        assertTrue(xml.contains("IMG_12000.jpg"))
    }
}
