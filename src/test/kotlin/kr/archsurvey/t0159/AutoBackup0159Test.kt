package kr.archsurvey.t0159

import kr.archsurvey.data.AutoBackupPolicy
import kr.archsurvey.data.AutoBackupPolicy.Item
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class AutoBackup0159Test {
    private val kst = TimeZone.getTimeZone("Asia/Seoul")
    private val day = 86_400_000L
    private val t0 = 1_760_000_000_000L

    @Test fun oncePerLocalDay() {
        assertTrue(AutoBackupPolicy.dueOnSave(emptyList(), t0, kst))
        assertFalse(AutoBackupPolicy.dueOnSave(listOf(Item("auto_1.zip", t0)), t0 + 60_000, kst))
        assertTrue(AutoBackupPolicy.dueOnSave(listOf(Item("auto_1.zip", t0 - day)), t0, kst))
        // 손 백업은 셈하지 않는다
        assertTrue(AutoBackupPolicy.dueOnSave(listOf(Item("backup_1.zip", t0)), t0, kst))
    }

    @Test fun keepsNewestFiveAutoOnly() {
        val items = (1..7).map { Item("auto_$it.zip", t0 + it) } + Item("backup_x.zip", 0) + Item("before_restore_y.zip", 1)
        assertEquals(listOf("auto_2.zip", "auto_1.zip"), AutoBackupPolicy.toPrune(items))
        assertTrue(AutoBackupPolicy.toPrune(items.take(5)).isEmpty())
    }
}
