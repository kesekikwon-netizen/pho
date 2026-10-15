package kr.archsurvey.pg.geo

import kotlin.math.sqrt

data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(dot(this))
    fun unit(): V3 { val n = norm(); return if (n < 1e-12) this else this * (1.0 / n) }
    companion object { val ZERO = V3(0.0, 0.0, 0.0) }
}

/** 3×3 행렬(행 우선) */
class M3(val a: DoubleArray) {
    operator fun times(v: V3) = V3(a[0] * v.x + a[1] * v.y + a[2] * v.z, a[3] * v.x + a[4] * v.y + a[5] * v.z, a[6] * v.x + a[7] * v.y + a[8] * v.z)
    fun transpose() = M3(doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]))
    companion object { fun identity() = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) }
}

/** 단위 사원수 (w, x, y, z) → 회전 행렬 */
fun quatToM3(w: Double, x: Double, y: Double, z: Double): M3 = M3(doubleArrayOf(
    1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
    2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
    2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
))

/** 여러 광선(원점 o, 방향 d)에 가장 가까운 점(최소제곱). 광선 2개 이상, 서로 평행하면 null */
fun triangulateRays(origins: List<V3>, dirs: List<V3>): V3? {
    if (origins.size < 2) return null
    val A = DoubleArray(9); val b = DoubleArray(3)
    for (i in origins.indices) {
        val d = dirs[i].unit(); val o = origins[i]
        val m = doubleArrayOf(1 - d.x * d.x, -d.x * d.y, -d.x * d.z, -d.y * d.x, 1 - d.y * d.y, -d.y * d.z, -d.z * d.x, -d.z * d.y, 1 - d.z * d.z)
        for (k in 0 until 9) A[k] += m[k]
        b[0] += m[0] * o.x + m[1] * o.y + m[2] * o.z
        b[1] += m[3] * o.x + m[4] * o.y + m[5] * o.z
        b[2] += m[6] * o.x + m[7] * o.y + m[8] * o.z
    }
    return solve3(A, b)
}

fun solve3(A: DoubleArray, b: DoubleArray): V3? {
    val det = A[0] * (A[4] * A[8] - A[5] * A[7]) - A[1] * (A[3] * A[8] - A[5] * A[6]) + A[2] * (A[3] * A[7] - A[4] * A[6])
    if (kotlin.math.abs(det) < 1e-9) return null
    fun d(c0: DoubleArray) = c0[0] * (c0[4] * c0[8] - c0[5] * c0[7]) - c0[1] * (c0[3] * c0[8] - c0[5] * c0[6]) + c0[2] * (c0[3] * c0[7] - c0[4] * c0[6])
    val ax = A.copyOf().also { it[0] = b[0]; it[3] = b[1]; it[6] = b[2] }
    val ay = A.copyOf().also { it[1] = b[0]; it[4] = b[1]; it[7] = b[2] }
    val az = A.copyOf().also { it[2] = b[0]; it[5] = b[1]; it[8] = b[2] }
    return V3(d(ax) / det, d(ay) / det, d(az) / det)
}
