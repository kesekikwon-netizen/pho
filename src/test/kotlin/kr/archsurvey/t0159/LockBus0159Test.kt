package kr.archsurvey.t0159

import kr.archsurvey.engine.AndroidLockBus
import org.junit.Assert.assertTrue
import org.junit.Test

class LockBus0159Test {
    @Test fun pgIsAPeer() {
        assertTrue(AndroidLockBus.PACKAGES.containsAll(listOf("kr.archsurvey.field", "kr.archsurvey.gcp", "kr.archsurvey.pg", "kr.archsurvey.pg.debug")))
    }
}
