package kr.archsurvey.georef

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** 평면 점. 래스터는 (열, 행) 화소 모서리 기준, 지도 좌표는 (동, 북). */
data class XY(val x: Double, val y: Double)

/** x' = a·x + b·y + c,  y' = d·x + e·y + f */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun apply(x: Double, y: Double) = XY(a * x + b * y + c, d * x + e * y + f)
    fun apply(p: XY) = apply(p.x, p.y)
    val determinant: Double get() = a * e - b * d
    fun inverse(): Affine? {
        val det = determinant
        if (abs(det) < 1e-300) return null
        val ia = e / det; val ib = -b / det; val id = -d / det; val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }
    /** this 를 먼저, 그다음 next 를 적용하는 합성 */
    fun then(next: Affine) = Affine(
        next.a * a + next.b * d, next.a * b + next.b * e, next.a * c + next.b * f + next.c,
        next.d * a + next.e * d, next.d * b + next.e * e, next.d * c + next.e * f + next.f)
    fun toList() = listOf(a, b, c, d, e, f)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        fun fromList(v: List<Double>) = Affine(v[0], v[1], v[2], v[3], v[4], v[5])
    }
}

/** 변환 분해(표시·경고용). 반사(행 방향이 아래로 향하는 래스터)는 정상이라 reflects 로만 알린다. */
data class AffineParts(val scaleX: Double, val scaleY: Double, val rotationDeg: Double, val shearDeg: Double, val reflects: Boolean)

fun Affine.decompose(): AffineParts {
    val sx = hypot(a, d); val sy = hypot(b, e)
    val cos = if (sx * sy == 0.0) 0.0 else (a * b + d * e) / (sx * sy)
    val shear = Math.toDegrees(Math.asin(cos.coerceIn(-1.0, 1.0)))
    return AffineParts(sx, sy, Math.toDegrees(atan2(d, a)), shear, determinant < 0)
}

/** 수동 정렬 기준점 1쌍: src=도면(화소 열/행 또는 DXF X/Y), dst=실제 좌표(동/북, 작업 좌표계) */
data class ControlPoint(val src: XY, val dst: XY)

enum class FitKind { SIMILARITY_2PT, AFFINE_3PT_EXACT, AFFINE_LSQ }

enum class FitWarning {
    /** 3점: 해가 정확히 맞아 잔차 0, 오류를 못 잡는다 → 검산용 1점 더 권고 */
    NO_REDUNDANCY,
    /** 점들이 거의 일직선(경계 이상) */
    NEARLY_COLLINEAR,
    /** 잔차가 설정 임계 초과 */
    LARGE_RESIDUAL,
    /** 축척이 방향별로 5% 넘게 다름 (도면 왜곡 또는 점 오류 의심) */
    ANISOTROPIC_SCALE,
    /** 전단(직교 어긋남) 2° 초과 */
    SHEARED,
}

enum class RejectReason { TOO_FEW_POINTS, POINTS_TOO_CLOSE, COLLINEAR, DEGENERATE_RESULT }

/** [임시값] 현장 도면으로 정해야 한다. 모두 생성자 인자로 바꿀 수 있다. */
data class FitPolicy(
    /** 점 배치의 가로세로비(√(λmin/λmax))가 이 값 미만이면 거부 */
    val minAspectReject: Double = 0.05,
    /** 이 값 미만이면 경고 */
    val aspectWarn: Double = 0.2,
    /** 도면 좌표(화소 등) 단위로 두 점이 이보다 가까우면 거부 */
    val minSeparationSrc: Double = 5.0,
    /** 점별 잔차가 이 값(목적 좌표 단위, m)을 넘으면 경고 */
    val residualWarn: Double = 1.0,
    val anisotropyWarn: Double = 0.05,
    val shearWarnDeg: Double = 2.0,
)

data class FitResult(
    val affine: Affine, val kind: FitKind,
    /** 점별 잔차(목적 좌표 단위, 보통 m) */
    val residuals: List<Double>, val rms: Double, val maxResidual: Double,
    val aspect: Double, val parts: AffineParts, val warnings: Set<FitWarning>,
)

sealed class FitOutcome {
    data class Ok(val result: FitResult) : FitOutcome()
    data class Rejected(val reason: RejectReason, val message: String) : FitOutcome()
}

/** 수동 정렬 계산 (UI 없는 순수 로직). */
object AffineFitter {
    /**
     * 점 개수에 따라 자동 선택: 2점=닮음(이동+회전+균일 축척, 4자유도), 3점=아핀 정확해, 4점 이상=아핀 최소제곱.
     * [flipSrcY] true 이면 도면 y 가 아래로 증가하는 래스터(화소 행)로 보고 2점 닮음에서 y 반전을 포함한다.
     * (2점으로는 반사 여부를 알 수 없으므로 래스터는 반드시 true. 3점 이상은 자료로 결정되어 무관.)
     */
    fun fit(points: List<ControlPoint>, flipSrcY: Boolean = false, policy: FitPolicy = FitPolicy()): FitOutcome {
        if (points.size < 2) return FitOutcome.Rejected(RejectReason.TOO_FEW_POINTS, "기준점이 2개 이상 필요해요")
        // 서로 너무 가까운 점 쌍
        for (i in points.indices) for (j in i + 1 until points.size) {
            if (hypot(points[i].src.x - points[j].src.x, points[i].src.y - points[j].src.y) < policy.minSeparationSrc)
                return FitOutcome.Rejected(RejectReason.POINTS_TOO_CLOSE, "기준점 ${i + 1}번과 ${j + 1}번이 너무 가까워요. 멀리 떨어진 점을 고르세요")
        }
        val aspect = aspect(points.map { it.src })
        if (points.size >= 3) {
            if (aspect < policy.minAspectReject)
                return FitOutcome.Rejected(RejectReason.COLLINEAR, "기준점이 거의 한 줄 위에 있어요. 한 줄에서 벗어난 점을 고르세요")
        }
        val (affine, kind) = if (points.size == 2) similarity2(points, flipSrcY) to FitKind.SIMILARITY_2PT
        else solveAffine(points) to (if (points.size == 3) FitKind.AFFINE_3PT_EXACT else FitKind.AFFINE_LSQ)
        val af = affine ?: return FitOutcome.Rejected(RejectReason.DEGENERATE_RESULT, "계산이 성립하지 않아요. 기준점을 다시 고르세요")
        if (abs(af.determinant) < 1e-12 * (1.0 + abs(af.a) + abs(af.e))) return FitOutcome.Rejected(RejectReason.DEGENERATE_RESULT, "계산 결과가 한 점으로 모여요. 기준점을 확인하세요")
        val res = points.map { p -> val q = af.apply(p.src); hypot(q.x - p.dst.x, q.y - p.dst.y) }
        val rms = sqrt(res.sumOf { it * it } / res.size)
        val parts = af.decompose()
        val w = mutableSetOf<FitWarning>()
        if (points.size == 3) w += FitWarning.NO_REDUNDANCY
        if (points.size >= 3 && aspect < policy.aspectWarn) w += FitWarning.NEARLY_COLLINEAR
        if ((res.maxOrNull() ?: 0.0) > policy.residualWarn) w += FitWarning.LARGE_RESIDUAL
        if (abs(parts.scaleX / parts.scaleY - 1.0) > policy.anisotropyWarn) w += FitWarning.ANISOTROPIC_SCALE
        if (abs(parts.shearDeg) > policy.shearWarnDeg) w += FitWarning.SHEARED
        return FitOutcome.Ok(FitResult(af, kind, res, rms, res.maxOrNull() ?: 0.0, aspect, parts, w))
    }

    /** 점 배치의 가로세로비 √(λmin/λmax) (공분산 고유값). 0=일직선, 1=원형 분포. */
    fun aspect(pts: List<XY>): Double {
        val n = pts.size; if (n < 2) return 0.0
        val mx = pts.sumOf { it.x } / n; val my = pts.sumOf { it.y } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { val dx = p.x - mx; val dy = p.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val disc = sqrt(maxOf(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = maxOf(0.0, tr / 2 - disc)
        return if (l1 <= 0) 0.0 else sqrt(l2 / l1)
    }

    private fun similarity2(p: List<ControlPoint>, flipY: Boolean): Affine? {
        val fy = if (flipY) -1.0 else 1.0
        // src' = (x, fy·y) 를 복소수로 보고 d = s·src' + t
        val s1 = XY(p[0].src.x, fy * p[0].src.y); val s2 = XY(p[1].src.x, fy * p[1].src.y)
        val dsx = s2.x - s1.x; val dsy = s2.y - s1.y
        val ddx = p[1].dst.x - p[0].dst.x; val ddy = p[1].dst.y - p[0].dst.y
        val den = dsx * dsx + dsy * dsy; if (den == 0.0) return null
        val sr = (ddx * dsx + ddy * dsy) / den; val si = (ddy * dsx - ddx * dsy) / den
        // d = (sr + i·si)(x + i·fy·y) + t
        val a = sr; val b = -si * fy; val d = si; val e = sr * fy
        val c = p[0].dst.x - (a * p[0].src.x + b * p[0].src.y)
        val f = p[0].dst.y - (d * p[0].src.x + e * p[0].src.y)
        return Affine(a, b, c, d, e, f)
    }

    /** 중심화한 정규방정식. n=3 이면 정확해와 같다. */
    private fun solveAffine(p: List<ControlPoint>): Affine? {
        val n = p.size
        val msx = p.sumOf { it.src.x } / n; val msy = p.sumOf { it.src.y } / n
        val mdx = p.sumOf { it.dst.x } / n; val mdy = p.sumOf { it.dst.y } / n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var txx = 0.0; var txy = 0.0; var tyx = 0.0; var tyy = 0.0 // T = Σ (d−md)(s−ms)^T
        for (q in p) {
            val x = q.src.x - msx; val y = q.src.y - msy; val u = q.dst.x - mdx; val v = q.dst.y - mdy
            sxx += x * x; sxy += x * y; syy += y * y
            txx += u * x; txy += u * y; tyx += v * x; tyy += v * y
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-18 * (sxx + syy) * (sxx + syy) || det == 0.0) return null
        // A = T · S^-1
        val i00 = syy / det; val i01 = -sxy / det; val i11 = sxx / det
        val a = txx * i00 + txy * i01; val b = txx * i01 + txy * i11
        val d = tyx * i00 + tyy * i01; val e = tyx * i01 + tyy * i11
        return Affine(a, b, mdx - (a * msx + b * msy), d, e, mdy - (d * msx + e * msy))
    }
}
