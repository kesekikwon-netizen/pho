package kr.archsurvey

import kr.archsurvey.arch.*
import kr.archsurvey.core.NE
import org.junit.Assert.*
import org.junit.Test

class GridMathTests {
    private val o = NE(500000.0, 200000.0)

    @Test fun columnNames() {
        assertEquals("A", CellNames.column(0)); assertEquals("Z", CellNames.column(25))
        assertEquals("AA", CellNames.column(26)); assertEquals("AB", CellNames.column(27)); assertEquals("AZ", CellNames.column(51))
        assertEquals("BA", CellNames.column(52))
        assertEquals("A-1", CellNames.cell(0, 0)); assertEquals("C-4", CellNames.cell(2, 3))
    }

    /** 방위 90°(동쪽)으로 O→D: 열은 동쪽으로, 행은 왼쪽(= 북쪽)으로 늘어난다 */
    @Test fun axesEastAndNorth() {
        val g = GridDef(0, 1, "G", o, NE(o.n, o.e + 10), 2.0, 5, 4)
        assertEquals(90.0, g.azimuthDeg, 1e-9)
        val p = g.toNE(3.0, 1.0) // 열 방향 3 m, 행 방향 1 m
        assertEquals(o.n + 1.0, p.n, 1e-9); assertEquals(o.e + 3.0, p.e, 1e-9)
        val (u, v) = g.toUV(p); assertEquals(3.0, u, 1e-9); assertEquals(1.0, v, 1e-9)
    }

    @Test fun axesNorthDirection() {
        val g = GridDef(0, 1, "G", o, NE(o.n + 10, o.e), 1.0, 3, 3)
        assertEquals(0.0, g.azimuthDeg, 1e-9)
        val p = g.toNE(2.0, 1.0)   // 열=북쪽 2m, 행=왼쪽(서쪽) 1m
        assertEquals(o.n + 2.0, p.n, 1e-9); assertEquals(o.e - 1.0, p.e, 1e-9)
    }

    @Test fun roundTripRotated() {
        val g = GridMath.fromOriginAzimuth("G", 1, o, 37.5, 2.0, 6, 5)
        for ((u, v) in listOf(0.3 to 0.7, 5.5 to 9.1, 11.9 to 0.1)) {
            val (u2, v2) = g.toUV(g.toNE(u, v)); assertEquals(u, u2, 1e-9); assertEquals(v, v2, 1e-9)
        }
    }

    @Test fun locateInsideCells() {
        val g = GridDef(0, 1, "G", o, NE(o.n, o.e + 10), 2.0, 5, 4)
        val r = GridMath.locate(g, g.toNE(3.0, 5.0), 0.01)
        assertEquals(CellKind.INSIDE, r.kind); assertEquals("B-3", r.cell); assertEquals(1, r.col); assertEquals(2, r.row)
        assertEquals(1.0, r.inCellU!!, 1e-9); assertEquals(1.0, r.inCellV!!, 1e-9)
        assertEquals("A-1", GridMath.locate(g, g.toNE(0.5, 0.5), 0.01).cell)
        assertEquals("E-4", GridMath.locate(g, g.toNE(9.5, 7.5), 0.01).cell)
    }

    @Test fun locateNearBoundaryUsesMaxSigma2cm() {
        val g = GridDef(0, 1, "G", o, NE(o.n, o.e + 10), 2.0, 5, 4)
        // 열 경계 u=2.0 에서 1 cm → σ 0.5 cm 라도 2 cm 안이면 경계 근처
        val r = GridMath.locate(g, g.toNE(2.01, 1.0), 0.005)
        assertEquals(CellKind.NEAR_BOUNDARY, r.kind); assertEquals("B-1", r.cell); assertEquals("A-1", r.alsoCell)
        assertEquals("B-1 / A-1 경계 근처", r.label)
        // 5 cm 는 σ 가 작으면 경계 근처 아님, σ 가 크면 경계 근처
        assertEquals(CellKind.INSIDE, GridMath.locate(g, g.toNE(2.05, 1.0), 0.01).kind)
        assertEquals(CellKind.NEAR_BOUNDARY, GridMath.locate(g, g.toNE(2.05, 1.0), 0.06).kind)
        // 모서리: 열·행 두 경계 모두
        val corner = GridMath.locate(g, g.toNE(4.01, 2.01), 0.01)
        assertEquals(CellKind.NEAR_BOUNDARY, corner.kind); assertEquals("C-2", corner.cell); assertEquals("B-1", corner.alsoCell)
    }

    @Test fun outerEdgeIsNotNearBoundaryButOutsideBeyondTolerance() {
        val g = GridDef(0, 1, "G", o, NE(o.n, o.e + 10), 2.0, 5, 4)
        assertEquals(CellKind.INSIDE, GridMath.locate(g, g.toNE(0.01, 1.0), 0.01).kind) // 바깥 테두리 안쪽 1cm: 이웃 칸 없음
        assertEquals(CellKind.OUTSIDE, GridMath.locate(g, g.toNE(-0.5, 1.0), 0.01).kind)
        assertEquals(CellKind.OUTSIDE, GridMath.locate(g, g.toNE(10.5, 1.0), 0.01).kind)
        assertEquals(CellKind.OUTSIDE, GridMath.locate(g, g.toNE(3.0, 8.5), 0.01).kind)
        assertEquals("그리드 밖", GridMath.locate(g, g.toNE(-3.0, 0.0), null).label)
        // 테두리에서 2cm 이내 바깥도 가장 가까운 칸으로 본다
        assertEquals("A-1", GridMath.locate(g, g.toNE(-0.01, 1.0), 0.01).cell)
    }

    @Test fun linesAndCenters() {
        val g = GridDef(0, 1, "G", o, NE(o.n, o.e + 10), 2.0, 5, 4)
        assertEquals(6 + 5, GridMath.lines(g).size)
        assertEquals(20, GridMath.cellCenters(g).size)
        val c = GridMath.cellCenters(g).first { it.first == "A-1" }.second
        assertEquals(o.e + 1.0, c.e, 1e-9); assertEquals(o.n + 1.0, c.n, 1e-9)
    }

    @Test fun twoPointSetup() {
        val s = GridMath.fromTwoPoints("G", 1, o, NE(o.n, o.e + 10.3), 2.0, null, 4)
        assertEquals(5, s.grid!!.cols); assertNull(s.warning)
        val near = GridMath.fromTwoPoints("G", 1, o, NE(o.n, o.e + 0.6), 0.5, null, 2)
        assertNotNull(near.warning); assertEquals(1, near.grid!!.cols)
        assertNull(GridMath.fromTwoPoints("G", 1, o, o, 1.0, null, 2).grid)
        assertNull(GridMath.fromTwoPoints("G", 1, o, NE(o.n, o.e + 10), 0.0, null, 2).grid)
        assertEquals(8, GridMath.fromTwoPoints("G", 1, o, NE(o.n, o.e + 10), 1.0, 8, 3).grid!!.cols)
    }

    @Test fun duplicateThreshold() {
        assertEquals(0.05, DuplicateCheck.threshold(0.01), 1e-12)
        assertEquals(0.05, DuplicateCheck.threshold(null), 1e-12)
        assertEquals(0.08, DuplicateCheck.threshold(0.04), 1e-12)
        val ex = listOf("P-0001" to NE(10.0, 10.0), "P-0002" to NE(10.0, 10.04))
        assertEquals("P-0002", DuplicateCheck.find(NE(10.0, 10.045), 0.01, ex)!!.key)
        assertNull(DuplicateCheck.find(NE(10.0, 10.2), 0.01, ex))
        assertEquals("P-0002", DuplicateCheck.find(NE(10.0, 10.07), 0.04, ex)!!.key) // 가장 가까운 것(3 cm)
        assertNull(DuplicateCheck.find(NE(10.0, 10.07), 0.01, listOf("P-0001" to NE(10.0, 10.0))))
    }

    @Test fun outlineSummary() {
        val sq = listOf(NE(0.0, 0.0), NE(0.0, 4.0), NE(3.0, 4.0), NE(3.0, 0.0))
        val closed = OutlineMath.summarize(sq, true)
        assertEquals(12.0, closed.areaSqm!!, 1e-9); assertEquals(14.0, closed.perimeterM, 1e-9); assertFalse(closed.selfCrossing)
        val open = OutlineMath.summarize(sq, false)
        assertNull(open.areaSqm); assertEquals(11.0, open.perimeterM, 1e-9); assertFalse(open.closed)
        // 3점 미만은 면으로 닫을 수 없음
        assertFalse(OutlineMath.summarize(sq.take(2), true).closed)
        // 나비넥타이 모양은 자기 교차
        val bow = listOf(NE(0.0, 0.0), NE(2.0, 2.0), NE(0.0, 2.0), NE(2.0, 0.0))
        assertTrue(OutlineMath.summarize(bow, true).selfCrossing)
        // 열린 선 교차
        val zig = listOf(NE(0.0, 0.0), NE(2.0, 2.0), NE(2.0, 0.0), NE(0.0, 2.0))
        assertTrue(OutlineMath.crosses(zig, false))
        assertFalse(OutlineMath.crosses(sq, false))
        assertEquals("E-007", OutlineMath.label(7))
    }
}
