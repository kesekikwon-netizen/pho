package kr.archsurvey.pg

import kr.archsurvey.pg.live.ShotPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.0.11: 장수·거리 제한 없음, 저장 공간만 막음 */
class NoGate0011Test {
    @Test fun auto_firesEvery2s_withoutMovement_forever() {
        var last = 0L; var n = 0
        for (t in 0L..400_000L step 100) if (ShotPolicy.fire(false, false, true, true, t, last)) { last = t; n++ }
        assertTrue("자동이 계속 찍혀야 함: $n", n >= 199)
    }
    @Test fun manual_alwaysFires_evenPaused_orAutoOff() {
        assertTrue(ShotPolicy.fire(true, true, false, true, 0, 0))
        assertTrue(ShotPolicy.fire(true, false, true, true, 10, 9))
    }
    @Test fun onlyStorageBlocks() {
        assertFalse(ShotPolicy.fire(true, false, true, false, 10_000, 0))
        assertFalse(ShotPolicy.fire(false, true, true, true, 10_000, 0))
    }
    @Test fun source_hasNoDistanceOrQualityOrThermalBlock() {
        val r = File("src/main/kotlin/kr/archsurvey/pg/ar/ArRenderer.kt").readText()
        assertFalse(r.contains("&& moved")); assertFalse(r.contains("REJECT reason=too_fast")); assertFalse(r.contains("REJECT reason=not_tracking")); assertFalse(r.contains("REJECT reason=paused"))
        val c = File("src/main/kotlin/kr/archsurvey/pg/ui/CaptureScreen.kt").readText()
        assertFalse(c.contains("shot REJECT reason=\${q.reason}")); assertFalse(c.contains("autoShot && tp.autoShot"))
        val f = File("src/main/kotlin/kr/archsurvey/pg/live/Fusion.kt").readText()
        assertFalse("충분 % 가 한 항목 0 에 갇히면 안 됨", f.contains("gates.min()"))
    }
}
