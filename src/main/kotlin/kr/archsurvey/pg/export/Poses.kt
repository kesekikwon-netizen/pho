package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.geo.Helmert
import kr.archsurvey.pg.geo.M3
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import kr.archsurvey.pg.live.TagMap
import kr.archsurvey.pg.tag.TargetNaming

/**
 * 0.0.12 사진 초기 자세(iTwin BlocksExchange Pose).
 * ARCore pose = 세계←카메라, 카메라 축 OpenGL(x 오른쪽, y 위, −z 앞). iTwin 「XRightYDown」 카메라 = x 오른쪽, y 아래, z 앞.
 * iTwin Rotation = 세계→카메라 행렬, Center = 카메라 중심(세계). 화소 = f·(Xc/Zc) + 주점.
 * 좌표가 있으면 표적으로 맞춘 7변수 변환(로컬 → EPSG)을 적용. 맞춤이 안 되면 자세를 넣지 않는다(엉뚱한 좌표 방지).
 */
object Poses {
    data class Pose(val rotation: M3, val center: V3)

    private val D = M3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0))

    fun mul(a: M3, b: M3): M3 {
        val x = a.a; val y = b.a
        return M3(DoubleArray(9) { k -> val i = k / 3; val j = k % 3; x[3 * i] * y[j] + x[3 * i + 1] * y[3 + j] + x[3 * i + 2] * y[6 + j] })
    }

    /** 카메라→세계 회전(OpenGL 축) */
    fun camToWorld(p: DoubleArray): M3 = quatToM3(p[6], p[3], p[4], p[5])

    /** tf = null 이면 로컬(ARCore) 좌표 그대로 */
    fun itwin(p: DoubleArray, tf: Helmert?): Pose {
        val rgl = camToWorld(p)
        val c = V3(p[0], p[1], p[2])
        val rh = tf?.r ?: M3.identity()
        val camAxesWorld = mul(mul(rh, rgl), D) // 열 = iTwin 카메라 축(세계)
        val center = tf?.apply(c) ?: c
        return Pose(camAxesWorld.transpose(), center)
    }

    /** 사진들로 표적 3D(로컬) 위치 */
    fun tagPositions(stills: List<Still>): Map<Int, V3> {
        val m = TagMap()
        for (s in stills) {
            val p = s.pose ?: continue
            val r = camToWorld(p); val pos = V3(p[0], p[1], p[2])
            fun ray(u: Double, v: Double) = r * V3((u - s.cx) / s.fx, -(v - s.cy) / s.fy, -1.0)
            for (t in s.tags) m.add(TargetNaming.targetNo(t.id), TagMap.Obs(s.id, pos, ray(t.cx, t.cy), (0 until 4).map { k -> ray(t.corners.getOrElse(2 * k) { t.cx }, t.corners.getOrElse(2 * k + 1) { t.cy }) }))
        }
        return m.positions()
    }

    fun fit(s: Session): Helmert.Fit? =
        if (s.gcps.size < 3) null else Helmert.fit(tagPositions(s.stills), s.gcps.associate { it.target to V3(it.e, it.n, it.h) })

    /** 내보낼 자세들: 좌표 있음 → 맞춤 성공 때만(EPSG), 좌표 없음 → 로컬 */
    fun forExport(s: Session): Map<Int, Pose> {
        val withCoords = s.gcps.isNotEmpty()
        val tf = if (withCoords) (fit(s)?.tf ?: return emptyMap()) else null
        return s.stills.mapNotNull { st -> st.pose?.let { st.id to itwin(it, tf) } }.toMap()
    }
}
