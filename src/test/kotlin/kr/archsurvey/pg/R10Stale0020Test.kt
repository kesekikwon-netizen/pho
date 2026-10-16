package kr.archsurvey.pg

import kr.archsurvey.pg.live.ShotPolicy
import kr.archsurvey.pg.r10.GeoTag
import kr.archsurvey.pg.r10.GnssTag
import org.junit.Assert.*
import org.junit.Test

class R10Stale0020Test {
    private fun tag(age: Long) = GnssTag(37.0, 127.0, 50.0, "RTK_FIXED", age <= 2000, 0.01, 0.02, age, 20, 25.0)

    @Test fun staleR10NotStamped() {
        assertNotNull(GeoTag.forPhoto(tag(500)).tag); assertFalse(GeoTag.forPhoto(tag(500)).stale)
        assertNotNull(GeoTag.forPhoto(tag(3000)).tag)
        val s = GeoTag.forPhoto(tag(23_000)); assertNull(s.tag); assertTrue(s.stale)
        val none = GeoTag.forPhoto(null); assertNull(none.tag); assertFalse(none.stale)
        val src = java.io.File("src/main/kotlin/kr/archsurvey/pg/ui/CaptureScreen.kt").readText()
        assertTrue(src.contains("R10 위치 끊김"))
    }

    @Test fun autoMinGapButManualAlwaysAndNoCountLimit() {
        val m = ShotPolicy.Motion(10.0, 0.0)
        assertNull(ShotPolicy.decide(false, false, true, true, 1_100, 1_000, m, 0.1))
        assertNotNull(ShotPolicy.decide(false, false, true, true, 1_300, 1_000, m, 0.1))
        assertEquals(ShotPolicy.Reason.MANUAL, ShotPolicy.decide(true, false, true, true, 1_001, 1_000, m, 0.1))
        var last = 1L; var n = 0
        for (t in 2L..1_000_000L step 300) { if (ShotPolicy.decide(false, false, true, true, t, last, m, 0.1) != null) { last = t; n++ } }
        assertTrue(n > 3000)
    }
}
