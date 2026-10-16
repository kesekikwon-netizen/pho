package kr.archsurvey.pg

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.live.LiveEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.0.20: 실기 10-16 11:47 — 285장 저장, 실시간 목록 70장(사진마다 전체 계산으로 작업 줄 밀림), 나가기 30 s 뒤 72장만 저장 */
class P0020Test {
    private fun still(id: Int) = Still(id, "IMG_%04d.jpg".format(id), 64, 48, 50.0, 50.0, 32.0, 24.0, doubleArrayOf(id * 0.3, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), id.toLong(), emptyList(), "overlap_distance")

    @Test fun burstOfStillsCoalescesRecomputeAndKeepsEveryPhoto() {
        val e = LiveEngine(Session("b", 0L)); e.saveEveryMs = 1_000_000
        assertTrue(e.idle()); val before = e.recomputes
        val n = 300
        for (i in 1..n) e.addStill(still(i)) { }
        assertTrue(e.idle(60_000))
        assertEquals(n, e.session.stills.size)
        assertEquals((1..n).toList(), e.session.stills.map { it.id })
        assertTrue("다시 계산 ${e.recomputes - before}회는 사진 수보다 훨씬 적어야", e.recomputes - before < n / 4)
        e.close()
    }

    @Test fun leaveWaitsUntilQueueIsReallyIdle() {
        val src = File("src/main/kotlin/kr/archsurvey/pg/ui/CaptureScreen.kt").readText()
        assertTrue(src.contains("while (!idleOk && waits < 10) { idleOk = engine.idle(); waits++ }"))
        assertFalse(src.contains("engine.noteNextId(ids.peek()); engine.idle()"))
    }
}
