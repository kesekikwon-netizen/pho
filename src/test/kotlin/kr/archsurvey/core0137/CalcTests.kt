package kr.archsurvey

import kr.archsurvey.core.*
import org.junit.Assert.*
import org.junit.Test

class CalcTests {
    private val o = NE(0.0, 0.0)

    @Test fun distanceAndBearingBasics() {
        assertEquals(5.0, Calc.distance(o, NE(3.0, 4.0)), 1e-12)
        assertEquals(0.0, Calc.bearingDeg(o, NE(10.0, 0.0)), 1e-9)      // 정북
        assertEquals(90.0, Calc.bearingDeg(o, NE(0.0, 10.0)), 1e-9)     // 정동
        assertEquals(180.0, Calc.bearingDeg(o, NE(-10.0, 0.0)), 1e-9)   // 정남
        assertEquals(270.0, Calc.bearingDeg(o, NE(0.0, -10.0)), 1e-9)   // 정서
        assertEquals(315.0, Calc.bearingDeg(o, NE(1.0, -1.0)), 1e-9)
    }

    @Test fun compassNames() {
        assertEquals("북", Calc.compass8(0.0)); assertEquals("북", Calc.compass8(359.0)); assertEquals("북동", Calc.compass8(45.0))
        assertEquals("동", Calc.compass8(90.0)); assertEquals("남", Calc.compass8(180.0)); assertEquals("서", Calc.compass8(270.0)); assertEquals("북서", Calc.compass8(315.0))
        assertEquals("북", Calc.compass8(22.4)); assertEquals("북동", Calc.compass8(22.6))
    }

    @Test fun dmsFormatting() {
        assertEquals("45°30′00″", Calc.dms(45.5))
        assertEquals("0°00′00″", Calc.dms(0.0))
        assertEquals("0°00′00″", Calc.dms(359.99999999)) // 60초 올림이 360°→0° 로
        assertEquals("12°34′56″", Calc.dms(12.0 + 34.0 / 60 + 56.0 / 3600))
    }

    @Test fun offsetRoundTrip() {
        val a = NE(1000.0, 2000.0)
        val b = Calc.offset(a, 37.0, 25.0)
        assertEquals(25.0, Calc.distance(a, b), 1e-9)
        assertEquals(37.0, Calc.bearingDeg(a, b), 1e-9)
        val c = Calc.offset(a, 90.0, 10.0)
        assertEquals(1000.0, c.n, 1e-9); assertEquals(2010.0, c.e, 1e-9)
    }

    @Test fun areaOfKnownShapes() {
        val sq = listOf(NE(0.0, 0.0), NE(0.0, 10.0), NE(10.0, 10.0), NE(10.0, 0.0))
        assertEquals(100.0, Calc.polygonArea(sq), 1e-9)
        assertEquals(100.0, Calc.polygonArea(sq.reversed()), 1e-9)       // 방향과 무관
        assertEquals(40.0, Calc.perimeter(sq), 1e-9)
        val tri = listOf(NE(0.0, 0.0), NE(0.0, 4.0), NE(3.0, 0.0))
        assertEquals(6.0, Calc.polygonArea(tri), 1e-9); assertEquals(12.0, Calc.perimeter(tri), 1e-9)
        assertEquals(0.0, Calc.polygonArea(listOf(o, NE(1.0, 1.0))), 0.0)
        assertEquals(10.0, Calc.perimeter(listOf(o, NE(10.0, 0.0)), closed = true), 1e-9)
        // 큰 좌표값(TM)에서도 정밀도 유지
        val big = sq.map { NE(it.n + 500000.0, it.e + 200000.0) }
        assertEquals(100.0, Calc.polygonArea(big), 1e-4)
    }

    @Test fun openPolylineDoesNotClose() {
        val l = listOf(NE(0.0, 0.0), NE(0.0, 3.0), NE(4.0, 3.0))
        assertEquals(7.0, Calc.perimeter(l, closed = false), 1e-9)
        assertEquals(12.0, Calc.perimeter(l, closed = true), 1e-9)
    }

    @Test fun detectsBowtie() {
        val ok = listOf(NE(0.0, 0.0), NE(0.0, 10.0), NE(10.0, 10.0), NE(10.0, 0.0))
        val bow = listOf(NE(0.0, 0.0), NE(10.0, 10.0), NE(0.0, 10.0), NE(10.0, 0.0))
        assertFalse(Calc.selfIntersects(ok)); assertTrue(Calc.selfIntersects(bow))
        assertFalse(Calc.selfIntersects(listOf(NE(0.0, 0.0), NE(0.0, 1.0), NE(1.0, 1.0))))
    }

    @Test fun pyeong() { assertEquals(30.25, Calc.toPyeong(100.0), 0.01) }

    @Test fun guidanceText() {
        val g = Guidance.from(NE(100.0, 200.0), 10.0, NE(103.0, 196.0), 10.5)
        assertEquals(5.0, g.distance, 1e-12); assertEquals(3.0, g.dN, 1e-12); assertEquals(-4.0, g.dE, 1e-12)
        assertEquals("북쪽으로 3.000 m", g.northText); assertEquals("서쪽으로 4.000 m", g.eastText)
        assertEquals(0.5, g.dH!!, 1e-12); assertEquals("북서", g.compass); assertFalse(g.arrived)
        assertTrue(Guidance.from(o, null, NE(0.03, 0.03), null).arrived)
        assertNull(Guidance.from(o, null, NE(1.0, 0.0), 3.0).dH)
        assertEquals(0.0, Guidance.from(o, null, o, null).bearingDeg, 0.0)
    }
}
