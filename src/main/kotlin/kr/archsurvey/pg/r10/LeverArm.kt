package kr.archsurvey.pg.r10

import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.geo.quatToM3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 0.0.14 안테나 → 카메라 레버암(1세대 R10 은 기울기 센서 없음 → 폰 ARCore 자세로 돌림).
 * 사용자가 넣는 값(폰 몸체 기준, m): 위(+, 폰 윗변 쪽) · 앞(+, 카메라가 보는 쪽) · 오른쪽(+, 세로로 든 폰의 오른쪽). 기본 0.
 * 「카메라 위치 = 안테나 위치 + R·오프셋」. 오프셋은 안테나에서 카메라로 가는 벡터(카메라가 안테나보다 30 cm 아래면 위 = −0.30).
 *
 * ARCore 세계: Y 위(중력), 방위는 임의 → 수직 성분은 늘 정확, 수평 성분은 방위(heading)를 알아야 한다.
 * 방위는 R10 FIX 사진들의 안테나 이동(ENU)과 ARCore 카메라 이동을 2D 로 맞춰 구한다(2장 이상, 0.5 m 이상 떨어짐). 모르면 수직만 적용하고 표시.
 */
data class Lever(val up: Double = 0.0, val fwd: Double = 0.0, val right: Double = 0.0) {
    val isZero get() = up == 0.0 && fwd == 0.0 && right == 0.0
}

object LeverArm {
    /**
     * 폰 몸체 축(세로 화면 기준)을 ARCore 세계로. pose = tx,ty,tz,qx,qy,qz,qw (카메라→세계, OpenGL 카메라 축 = 센서 방향).
     * sensorDeg = CameraCharacteristics.SENSOR_ORIENTATION(뒷카메라 보통 90). 반환: (오른쪽, 위, 화면 밖=사용자 쪽) 세계 벡터.
     */
    fun deviceAxes(pose: DoubleArray, sensorDeg: Int): Triple<V3, V3, V3> {
        val r = quatToM3(pose[6], pose[3], pose[4], pose[5])
        val cx = V3(r.a[0], r.a[3], r.a[6]); val cy = V3(r.a[1], r.a[4], r.a[7]); val cz = V3(r.a[2], r.a[5], r.a[8])
        // 센서 화상을 시계 방향 sensorDeg 돌리면 세로 화면. 카메라(GL) x·y 를 화면 평면에서 같은 각도로 돌린 것이 폰 오른쪽·위
        val a = Math.toRadians(sensorDeg.toDouble())
        val right = cx * cos(a) + cy * sin(a)
        val up = cy * cos(a) - cx * sin(a)
        return Triple(right, up, cz)
    }

    /** 오프셋을 ARCore 세계 벡터로 */
    fun worldOffset(pose: DoubleArray, sensorDeg: Int, l: Lever): V3 {
        val (right, up, out) = deviceAxes(pose, sensorDeg)
        return right * l.right + up * l.up + out * (-l.fwd)
    }

    /** ARCore 세계 → ENU 회전 없는 기본 대응: E' = x, N' = −z, U = y. heading 만큼 U 축으로 더 돌림 */
    fun toEnu(w: V3, headingRad: Double): V3 {
        val e0 = w.x; val n0 = -w.z
        return V3(cos(headingRad) * e0 - sin(headingRad) * n0, sin(headingRad) * e0 + cos(headingRad) * n0, w.y)
    }

    /**
     * 방위 추정(2D 회전만, 최소제곱): ARCore 위치들 a_i 와 ENU 위치들 b_i. 중심 뺀 뒤 θ = atan2(Σ cross, Σ dot).
     * 퍼짐이 [minSpreadM] 보다 작으면 null.
     */
    fun heading(arcore: List<V3>, enu: List<V3>, minSpreadM: Double = 0.5): Double? {
        if (arcore.size < 2 || arcore.size != enu.size) return null
        val a = arcore.map { V3(it.x, -it.z, 0.0) }; val b = enu.map { V3(it.x, it.y, 0.0) }
        val ca = a.reduce { s, p -> s + p } * (1.0 / a.size); val cb = b.reduce { s, p -> s + p } * (1.0 / b.size)
        var sDot = 0.0; var sCross = 0.0; var spread = 0.0
        for (i in a.indices) {
            val p = a[i] - ca; val q = b[i] - cb
            sDot += p.x * q.x + p.y * q.y; sCross += p.x * q.y - p.y * q.x
            spread = maxOf(spread, q.norm())
        }
        if (spread < minSpreadM / 2) return null
        return atan2(sCross, sDot)
    }
}
