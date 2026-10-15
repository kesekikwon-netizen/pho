package kr.archsurvey.pg.geo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 7변수 닮음 변환(축척 1 + 회전 3 + 이동 3): dst ≈ s·R·src + t. Horn(1987) 사원수 닫힌 해.
 * src = ARCore 로컬(미터, Y 위), dst = EPSG:5187 (x=동 E, y=북 N, z=높이). 큰 좌표는 중심을 빼고 double 로 계산.
 */
class Helmert(val s: Double, val r: M3, val t: V3) {
    fun apply(p: V3): V3 = r * p * s + t

    data class Fit(
        val tf: Helmert,
        /** 기준점별 잔차(dst − 변환값) */
        val residuals: Map<Int, V3>,
        /** 하나 빼고 맞춘 뒤 그 점 오차(검사점 오차, 4점 이상일 때) */
        val looResiduals: Map<Int, V3>,
        val rmse: Double,
    ) {
        val looRmse: Double? get() = if (looResiduals.isEmpty()) null else sqrt(looResiduals.values.sumOf { it.dot(it) } / looResiduals.size)
    }

    companion object {
        /** 같은 키(표적 번호)끼리 짝. 3점 이상이고 한 줄로 서 있지 않아야 한다 */
        fun fit(src: Map<Int, V3>, dst: Map<Int, V3>): Fit? {
            val keys = src.keys.intersect(dst.keys).sorted()
            if (keys.size < 3) return null
            val tf = solve(keys.map { src.getValue(it) }, keys.map { dst.getValue(it) }) ?: return null
            val res = keys.associateWith { dst.getValue(it) - tf.apply(src.getValue(it)) }
            val loo = if (keys.size >= 4) keys.mapNotNull { k ->
                val rest = keys - k
                val t2 = solve(rest.map { src.getValue(it) }, rest.map { dst.getValue(it) }) ?: return@mapNotNull null
                k to (dst.getValue(k) - t2.apply(src.getValue(k)))
            }.toMap() else emptyMap()
            val rmse = sqrt(res.values.sumOf { it.dot(it) } / res.size)
            return Fit(tf, res, loo, rmse)
        }

        fun solve(a: List<V3>, b: List<V3>): Helmert? {
            val n = a.size
            if (n < 3) return null
            val ca = a.fold(V3.ZERO) { s, p -> s + p } * (1.0 / n)
            val cb = b.fold(V3.ZERO) { s, p -> s + p } * (1.0 / n)
            val qa = a.map { it - ca }; val qb = b.map { it - cb }
            // 한 줄 검사: 공분산 최소 고유값 대신 넓이 기준
            var area = 0.0
            for (i in 0 until n) for (j in i + 1 until n) area = maxOf(area, qa[i].cross(qa[j]).norm())
            if (area < 1e-6) return null
            var sxx = 0.0; var sxy = 0.0; var sxz = 0.0; var syx = 0.0; var syy = 0.0; var syz = 0.0; var szx = 0.0; var szy = 0.0; var szz = 0.0
            for (i in 0 until n) {
                val p = qa[i]; val q = qb[i]
                sxx += p.x * q.x; sxy += p.x * q.y; sxz += p.x * q.z
                syx += p.y * q.x; syy += p.y * q.y; syz += p.y * q.z
                szx += p.z * q.x; szy += p.z * q.y; szz += p.z * q.z
            }
            val N = arrayOf(
                doubleArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
                doubleArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
                doubleArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
                doubleArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
            )
            val q = maxEigenVector4(N)
            val r = quatToM3(q[0], q[1], q[2], q[3])
            val sa = qa.sumOf { it.dot(it) }
            var sb = 0.0
            for (i in 0 until n) sb += qb[i].dot(r * qa[i])
            val s = sb / sa
            if (s <= 0) return null
            val t = cb - r * ca * s
            return Helmert(s, r, t)
        }

        /** 대칭 4×4 의 최대 고유값 고유벡터 (Jacobi) */
        private fun maxEigenVector4(m: Array<DoubleArray>): DoubleArray {
            val a = Array(4) { m[it].copyOf() }
            val v = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }
            repeat(100) {
                var off = 0.0
                for (i in 0 until 4) for (j in i + 1 until 4) off += a[i][j] * a[i][j]
                if (off < 1e-22) return@repeat
                for (p in 0 until 4) for (q in p + 1 until 4) {
                    if (abs(a[p][q]) < 1e-300) continue
                    val th = (a[q][q] - a[p][p]) / (2 * a[p][q])
                    val t = (if (th >= 0) 1.0 else -1.0) / (abs(th) + sqrt(th * th + 1))
                    val c = 1 / sqrt(t * t + 1); val s = t * c
                    for (k in 0 until 4) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq }
                    for (k in 0 until 4) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk }
                    for (k in 0 until 4) { val vkp = v[k][p]; val vkq = v[k][q]; v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq }
                }
            }
            var best = 0
            for (i in 1 until 4) if (a[i][i] > a[best][best]) best = i
            val q = DoubleArray(4) { v[it][best] }
            val n = sqrt(q.sumOf { it * it })
            return DoubleArray(4) { q[it] / n }
        }
    }
}
