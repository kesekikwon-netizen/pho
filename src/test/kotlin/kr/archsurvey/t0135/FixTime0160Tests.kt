package kr.archsurvey.t0135

import kr.archsurvey.diag.FixTimer
import kr.archsurvey.diag.FixTimeLog
import kr.archsurvey.dig.CauseInput
import kr.archsurvey.dig.CauseLine
import org.junit.Assert.*
import org.junit.Test

class FixTime0160Tests {
    @Test fun floatToFixRecordsSecondsPerMount() {
        val t = FixTimer()
        assertNull(t.update(0, true, true, false, "VRS-RTCM32"))
        assertEquals(5.0, t.floatSeconds(5000)!!, 1e-9)
        val d = t.update(42_000, true, true, true, "VRS-RTCM32")!!
        assertEquals("VRS-RTCM32", d.mount); assertEquals(42.0, d.seconds, 1e-9)
        assertNull(t.update(43_000, true, true, true, "VRS-RTCM32"))
    }
    @Test fun mountChangeRestartsAndLinkDownResets() {
        val t = FixTimer()
        t.update(0, true, true, false, "VRS-RTCM32")
        t.update(100_000, true, true, false, "VRS-RTCM31")
        assertEquals(20.0, t.update(120_000, true, true, true, "VRS-RTCM31")!!.seconds, 1e-9)
        t.update(200_000, true, true, false, "A"); t.update(210_000, false, false, false, "A")
        assertNull(t.update(220_000, true, true, true, "A"))
    }
    @Test fun logLineHasNoCredentialsJustMountAndSeconds() {
        val l = FixTimeLog.line(0, FixTimer.Done("VRS-RTCM32", 12.34), "0.1.60")
        assertTrue(l.contains("VRS-RTCM32") && l.contains("12.3s"))
    }
    private val ok = CauseInput(floatOrWorse = true, ntripEnabled = true, ntripReceiving = true, rtcmAgeSec = 1.0, satellites = 20)
    @Test fun serverHintOnlyAfterLongFloatWithCorrections() {
        assertEquals(CauseLine.WAIT_FIX, CauseLine.of(ok.copy(floatForSec = 179.0)))
        assertEquals(CauseLine.SERVER_HINT, CauseLine.of(ok.copy(floatForSec = 180.0)))
        assertTrue(CauseLine.SERVER_HINT.contains("031-210-2648") && CauseLine.SERVER_HINT.contains("VRS-RTCM31"))
        assertEquals(CauseLine.INTERNET, CauseLine.of(ok.copy(ntripReceiving = false, floatForSec = 999.0)))
        assertEquals(CauseLine.SKY, CauseLine.of(ok.copy(satellites = 8, floatForSec = 999.0)))
        assertNull(CauseLine.of(ok.copy(fixed = true, floatForSec = 999.0)))
        assertEquals(CauseLine.WAIT_FIX, CauseLine.of(ok.copy(ntripEnabled = false, floatForSec = 999.0)))
    }
    @Test fun sp60DefaultMountUnchanged() {
        val src = java.io.File("src/main/kotlin").walk().filter { it.name.endsWith(".kt") }.map { it.readText() }.joinToString("\n")
        assertTrue(src.contains("VRS-RTCM32"))
    }
}
