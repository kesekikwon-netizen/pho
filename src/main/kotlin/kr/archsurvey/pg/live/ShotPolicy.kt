package kr.archsurvey.pg.live

/**
 * 0.0.11 촬영 규칙(사용자 2026-10-15 「장수·거리 제한 두지 마라」).
 * 막는 것은 저장 공간 부족뿐. 거리·각도·속도·표적·충분·발열·품질은 안내만.
 * 자동 = 일시정지 아님 + 2초마다. 수동 = 일시정지 중에도 언제나.
 */
object ShotPolicy {
    const val AUTO_INTERVAL_MS = 2000L
    fun fire(manual: Boolean, paused: Boolean, auto: Boolean, storageOk: Boolean, now: Long, lastShotAt: Long): Boolean {
        if (!storageOk) return false
        if (manual) return true
        return !paused && auto && now - lastShotAt >= AUTO_INTERVAL_MS
    }
}
