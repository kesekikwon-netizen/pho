package kr.archsurvey.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 평면 좌표(N 북, E 동, 미터) */
data class NE(val n: Double, val e: Double)

/** 거리·방위·면적 등 현장 계산. 모두 평면(TM) 좌표 기준이며 순수 함수라 JVM 단위 시험 대상이다. */
object Calc {
    /** 두 점 사이 수평 거리(m) */
    fun distance(a: NE, b: NE): Double = hypot(b.n - a.n, b.e - a.e)

    /** a→b 방위각: 북(0°)에서 시계 방향, 0 이상 360 미만 */
    fun bearingDeg(a: NE, b: NE): Double {
        val deg = Math.toDegrees(atan2(b.e - a.e, b.n - a.n))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    private val COMPASS = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    fun compass8(bearing: Double): String = COMPASS[(((bearing % 360.0 + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8]

    /** 도·분·초 문자열. 예) 45°30′15″ (초는 반올림, 60초 올림 처리) */
    fun dms(deg: Double): String {
        var total = Math.round(((deg % 360.0) + 360.0) % 360.0 * 3600.0)
        if (total >= 360L * 3600) total -= 360L * 3600
        val d = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return "${d}°%02d′%02d″".format(m, s)
    }

    /** 기준점에서 방위각·거리만큼 떨어진 점 */
    fun offset(from: NE, bearingDeg: Double, dist: Double): NE {
        val r = Math.toRadians(bearingDeg)
        return NE(from.n + dist * cos(r), from.e + dist * sin(r))
    }

    /** 다각형 면적(m²). 신발끈 공식, 점 순서대로 닫는다. 3점 미만이면 0 */
    fun polygonArea(pts: List<NE>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) { val a = pts[i]; val b = pts[(i + 1) % pts.size]; s += a.e * b.n - b.e * a.n }
        return abs(s) / 2.0
    }

    /** 둘레(m). [closed] 이면 마지막 점에서 첫 점으로 닫는다 */
    fun perimeter(pts: List<NE>, closed: Boolean = true): Double {
        if (pts.size < 2) return 0.0
        var s = 0.0
        for (i in 0 until pts.size - 1) s += distance(pts[i], pts[i + 1])
        if (closed && pts.size >= 3) s += distance(pts.last(), pts.first())
        return s
    }

    /** 선분이 서로 교차하는 변이 있으면 true (면적이 꼬인 순서로 계산되는 것을 알리기 위함) */
    fun selfIntersects(pts: List<NE>): Boolean {
        val n = pts.size
        if (n < 4) return false
        fun ccw(a: NE, b: NE, c: NE) = (c.n - a.n) * (b.e - a.e) > (b.n - a.n) * (c.e - a.e)
        fun inter(a: NE, b: NE, c: NE, d: NE) = ccw(a, c, d) != ccw(b, c, d) && ccw(a, b, c) != ccw(a, b, d)
        for (i in 0 until n) for (j in i + 1 until n) {
            val i2 = (i + 1) % n; val j2 = (j + 1) % n
            if (i == j2 || i2 == j) continue // 이웃한 변
            if (inter(pts[i], pts[i2], pts[j], pts[j2])) return true
        }
        return false
    }

    const val SQM_PER_PYEONG = 3.305785
    fun toPyeong(sqm: Double) = sqm / SQM_PER_PYEONG
}

/** 점 찾아가기 안내: 현재 위치에서 목표까지 */
data class Guidance(val dN: Double, val dE: Double, val distance: Double, val bearingDeg: Double, val dH: Double?) {
    val compass: String get() = Calc.compass8(bearingDeg)
    /** 북쪽(+)/남쪽(-), 동쪽(+)/서쪽(-) 안내 문구 */
    val northText: String get() = if (dN >= 0) "북쪽으로 %.3f m".format(java.util.Locale.US, dN) else "남쪽으로 %.3f m".format(java.util.Locale.US, -dN)
    val eastText: String get() = if (dE >= 0) "동쪽으로 %.3f m".format(java.util.Locale.US, dE) else "서쪽으로 %.3f m".format(java.util.Locale.US, -dE)
    /** 5cm 안이면 도착 */
    val arrived: Boolean get() = distance <= 0.05

    companion object {
        fun from(cur: NE, curH: Double?, target: NE, targetH: Double?): Guidance {
            val d = Calc.distance(cur, target)
            return Guidance(target.n - cur.n, target.e - cur.e, d, if (d < 1e-9) 0.0 else Calc.bearingDeg(cur, target),
                if (curH != null && targetH != null) targetH - curH else null)
        }
    }
}
