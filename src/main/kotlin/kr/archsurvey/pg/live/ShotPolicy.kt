package kr.archsurvey.pg.live

import kotlin.math.max
import kotlin.math.min

/**
 * 0.0.12 촬영 규칙(사용자: 장수·거리 제한 없음). 막는 것은 저장 공간 부족뿐.
 * 자동 = 겹침 기준(이동이 화면 바닥 폭 × (1 − 겹침) 이상, 또는 20° 이상 회전) → 바로 찍음.
 *       2초 타이머는 보조(움직이는 중이면 2초마다). 가만히 서 있을 때만 같은 장면 자동 사진을 건너뜀.
 *       자세(추적) 없음 → 2초마다.
 * 수동 = 일시정지 중에도 언제나.
 */
object ShotPolicy {
    const val AUTO_INTERVAL_MS = 2000L
    const val DEFAULT_OVERLAP = 0.90
    const val MIN_DEPTH_M = 0.25
    const val ANGLE_DEG = 20.0
    const val STILL_TURN_DEG = 2.0
    /** 처리 대기 사진이 이만큼이면 자동만 다음 프레임으로 미룸(메모리 보호, 장수 제한 아님) */
    const val AUTO_BACKLOG = 3

    enum class Reason(val code: String) { MANUAL("manual"), FIRST("first"), OVERLAP_DISTANCE("overlap_distance"), OVERLAP_ANGLE("overlap_angle"), TIMER("timer"), NO_POSE_TIMER("no_pose_timer") }

    /** 마지막 사진 뒤 움직임 */
    data class Motion(val moveM: Double, val turnDeg: Double)

    /** 화면에 보이는 바닥 폭(짧은 변, m) × (1 − 겹침). 깊이는 0.25 m 아래로 보지 않음 */
    fun spacingM(depthM: Double, fx: Double, w: Int, h: Int, overlap: Double = DEFAULT_OVERLAP): Double {
        val d = max(depthM, MIN_DEPTH_M)
        val foot = d * min(w, h) / fx
        return foot * (1 - overlap.coerceIn(0.0, 0.99))
    }

    fun stationary(m: Motion, spacing: Double) = m.moveM < max(0.01, spacing * 0.1) && m.turnDeg < STILL_TURN_DEG

    fun decide(
        manual: Boolean, paused: Boolean, auto: Boolean, storageOk: Boolean, now: Long, lastShotAt: Long,
        motion: Motion?, spacing: Double, angleDeg: Double = ANGLE_DEG, backlog: Int = 0,
    ): Reason? {
        if (!storageOk) return null
        if (manual) return Reason.MANUAL
        if (paused || !auto) return null
        if (backlog >= AUTO_BACKLOG) return null
        if (lastShotAt == 0L) return Reason.FIRST
        val due = now - lastShotAt >= AUTO_INTERVAL_MS
        if (motion == null) return if (due) Reason.NO_POSE_TIMER else null
        if (motion.moveM >= spacing) return Reason.OVERLAP_DISTANCE
        if (motion.turnDeg >= angleDeg) return Reason.OVERLAP_ANGLE
        if (due && !stationary(motion, spacing)) return Reason.TIMER
        return null
    }

    /** 0.0.11 호환(시험용) */
    fun fire(manual: Boolean, paused: Boolean, auto: Boolean, storageOk: Boolean, now: Long, lastShotAt: Long): Boolean {
        if (!storageOk) return false
        if (manual) return true
        return !paused && auto && now - lastShotAt >= AUTO_INTERVAL_MS
    }
}
