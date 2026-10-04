package kr.archsurvey.arch

import kr.archsurvey.core.Calc
import kr.archsurvey.core.NE
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** 칸 이름: 열 문자(A..Z, 26 넘으면 AA..) + "-" + 행 번호(1부터). 예) A-3, AA-12 */
object CellNames {
    fun column(index: Int): String {
        require(index >= 0)
        var n = index; val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }
    fun cell(col: Int, row: Int) = "${column(col)}-${row + 1}"
}

/**
 * 발굴 그리드 정의. 원점 O, 방향점 D. O→D 가 열이 늘어나는 방향(A→B→C),
 * 그 방향의 왼쪽 90° 가 행이 늘어나는 방향이다. 좌표는 모두 평면(N,E, m).
 */
data class GridDef(
    val id: Long = 0,
    val areaId: Long = 0,
    val name: String,
    val origin: NE,
    /** 방향점 D (O 에서 열 방향으로 놓인 점) */
    val direction: NE,
    val spacingM: Double,
    val cols: Int,
    val rows: Int,
    /** 그리드 좌표가 속한 평면 좌표계 EPSG (5185~5188). 0 = 모름 */
    val crsEpsg: Int = 0,
) {
    /** 열 방향 방위각(° 북 기준 시계방향) */
    val azimuthDeg: Double get() = Calc.bearingDeg(origin, direction)
    private val az get() = Math.toRadians(azimuthDeg)
    /** 열 방향 단위벡터 (dN, dE) */
    val uN get() = cos(az); val uE get() = sin(az)
    /** 행 방향 단위벡터 = 열 방향의 왼쪽 90° */
    val vN get() = sin(az); val vE get() = -cos(az)
    val widthM get() = cols * spacingM
    val heightM get() = rows * spacingM

    /** 그리드 좌표 (u=열 방향 m, v=행 방향 m) → 평면 좌표 */
    fun toNE(u: Double, v: Double) = NE(origin.n + u * uN + v * vN, origin.e + u * uE + v * vE)

    /** 평면 좌표 → 그리드 좌표 */
    fun toUV(p: NE): Pair<Double, Double> {
        val dn = p.n - origin.n; val de = p.e - origin.e
        return (dn * uN + de * uE) to (dn * vN + de * vE)
    }

    fun cellCorners(col: Int, row: Int): List<NE> = listOf(
        toNE(col * spacingM, row * spacingM), toNE((col + 1) * spacingM, row * spacingM),
        toNE((col + 1) * spacingM, (row + 1) * spacingM), toNE(col * spacingM, (row + 1) * spacingM),
    )
}

enum class CellKind { INSIDE, NEAR_BOUNDARY, OUTSIDE, NO_GRID }

/**
 * 점이 어느 칸에 있는지의 판정 결과.
 * [cell] 은 가장 가까운 칸(범위 안일 때), [alsoCell] 은 경계 근처일 때 맞닿은 다른 칸.
 * [inCellU]/[inCellV] 는 칸 안 위치(m, 칸 왼쪽-아래 모서리 기준).
 */
data class CellResult(
    val kind: CellKind,
    val cell: String? = null,
    val alsoCell: String? = null,
    val col: Int = -1,
    val row: Int = -1,
    val inCellU: Double? = null,
    val inCellV: Double? = null,
    val u: Double = 0.0,
    val v: Double = 0.0,
) {
    /** 화면·내보내기에 쓰는 한 줄. 예) "A-3", "A-3 / B-3 경계 근처", "그리드 밖" */
    val label: String get() = when (kind) {
        CellKind.INSIDE -> cell ?: ""
        CellKind.NEAR_BOUNDARY -> if (alsoCell != null) "$cell / $alsoCell 경계 근처" else "$cell"
        CellKind.OUTSIDE -> "그리드 밖"
        CellKind.NO_GRID -> ""
    }
}

object GridMath {
    /** 경계 근처로 보는 거리: max(σ, 2cm) */
    fun boundaryTolerance(sigmaM: Double?): Double = max(sigmaM ?: 0.0, 0.02)

    fun locate(g: GridDef, p: NE, sigmaM: Double? = null): CellResult {
        val (u, v) = g.toUV(p)
        val tol = boundaryTolerance(sigmaM)
        val s = g.spacingM
        val w = g.widthM; val h = g.heightM
        if (u < -tol || v < -tol || u > w + tol || v > h + tol) return CellResult(CellKind.OUTSIDE, u = u, v = v)
        val c0 = floor(u / s).toInt().coerceIn(0, g.cols - 1)
        val r0 = floor(v / s).toInt().coerceIn(0, g.rows - 1)
        // 칸 선 가까이에 있으면 맞닿은 칸도 알려 준다 (그리드 바깥 테두리는 제외)
        val du = u - c0 * s; val dv = v - r0 * s
        var alsoC = c0; var alsoR = r0
        val nearLeft = du < tol && c0 > 0; val nearRight = (s - du) < tol && c0 < g.cols - 1
        val nearBottom = dv < tol && r0 > 0; val nearTop = (s - dv) < tol && r0 < g.rows - 1
        if (nearLeft) alsoC = c0 - 1 else if (nearRight) alsoC = c0 + 1
        if (nearBottom) alsoR = r0 - 1 else if (nearTop) alsoR = r0 + 1
        val near = alsoC != c0 || alsoR != r0
        return CellResult(
            kind = if (near) CellKind.NEAR_BOUNDARY else CellKind.INSIDE,
            cell = CellNames.cell(c0, r0), alsoCell = if (near) CellNames.cell(alsoC, alsoR) else null,
            col = c0, row = r0, inCellU = du, inCellV = dv, u = u, v = v,
        )
    }

    /** 그리드선 선분들(안쪽·바깥 테두리 모두). 지도 그리기용 */
    fun lines(g: GridDef): List<Pair<NE, NE>> {
        val out = ArrayList<Pair<NE, NE>>()
        for (c in 0..g.cols) out += g.toNE(c * g.spacingM, 0.0) to g.toNE(c * g.spacingM, g.heightM)
        for (r in 0..g.rows) out += g.toNE(0.0, r * g.spacingM) to g.toNE(g.widthM, r * g.spacingM)
        return out
    }

    /** 칸 이름이 붙는 자리(칸 중심) */
    fun cellCenters(g: GridDef): List<Pair<String, NE>> {
        val out = ArrayList<Pair<String, NE>>()
        for (r in 0 until g.rows) for (c in 0 until g.cols) out += CellNames.cell(c, r) to g.toNE((c + 0.5) * g.spacingM, (r + 0.5) * g.spacingM)
        return out
    }

    /** 기준점 2개로 그리드를 만든다. 가로 칸 수 기본값 = floor(거리/간격). 거리가 1 m 미만이면 경고 문구를 돌려준다. */
    data class Setup(val grid: GridDef?, val warning: String?)
    fun fromTwoPoints(name: String, areaId: Long, o: NE, d: NE, spacingM: Double, cols: Int?, rows: Int): Setup {
        val dist = Calc.distance(o, d)
        if (spacingM <= 0.0) return Setup(null, "간격은 0보다 커야 해요")
        if (dist < 0.001) return Setup(null, "두 점이 같은 자리예요")
        val warn = if (dist < 1.0) "두 점 사이가 ${"%.2f".format(dist)}m 로 가까워요. 방향이 틀어질 수 있어요" else null
        val c = cols ?: floor(dist / spacingM).toInt().coerceAtLeast(1)
        if (c < 1 || rows < 1) return Setup(null, "칸 수는 1 이상이어야 해요")
        return Setup(GridDef(0, areaId, name, o, d, spacingM, c, rows), warn)
    }

    /** 원점 좌표 + 방위각 직접 입력 */
    fun fromOriginAzimuth(name: String, areaId: Long, o: NE, azimuthDeg: Double, spacingM: Double, cols: Int, rows: Int): GridDef =
        GridDef(0, areaId, name, o, Calc.offset(o, azimuthDeg, 10.0), spacingM, cols, rows)
}

/** 같은 점을 다시 찍었는지 보는 판정. 기준: max(5 cm, 정밀도의 2배) 이내 */
object DuplicateCheck {
    fun threshold(sigmaM: Double?): Double = max(0.05, 2.0 * (sigmaM ?: 0.0))
    data class Hit(val key: String, val distanceM: Double)
    fun find(newPos: NE, sigmaM: Double?, existing: List<Pair<String, NE>>): Hit? {
        val th = threshold(sigmaM)
        return existing.map { Hit(it.first, Calc.distance(newPos, it.second)) }.filter { it.distanceM <= th }.minByOrNull { it.distanceM }
    }
}

/** 유구 외곽 계산 */
object OutlineMath {
    data class Summary(val vertices: Int, val closed: Boolean, val areaSqm: Double?, val perimeterM: Double, val selfCrossing: Boolean)
    fun summarize(pts: List<NE>, closed: Boolean): Summary {
        val closedOk = closed && pts.size >= 3
        return Summary(
            vertices = pts.size, closed = closedOk,
            areaSqm = if (closedOk) abs(Calc.polygonArea(pts)) else null,
            perimeterM = if (pts.size >= 2) Calc.perimeter(pts, closedOk) else 0.0,
            selfCrossing = crosses(pts, closedOk),
        )
    }
    /** 열린 선이든 닫힌 면이든 서로 가로지르는 변이 있는지 */
    fun crosses(pts: List<NE>, closed: Boolean): Boolean {
        if (closed) return pts.size >= 4 && Calc.selfIntersects(pts)
        val n = pts.size
        if (n < 4) return false
        fun ccw(a: NE, b: NE, c: NE) = (c.n - a.n) * (b.e - a.e) > (b.n - a.n) * (c.e - a.e)
        fun inter(a: NE, b: NE, c: NE, d: NE) = ccw(a, c, d) != ccw(b, c, d) && ccw(a, b, c) != ccw(a, b, d)
        for (i in 0 until n - 1) for (j in i + 2 until n - 1) if (inter(pts[i], pts[i + 1], pts[j], pts[j + 1])) return true
        return false
    }
    fun label(seq: Int) = "E-%03d".format(seq)
}
