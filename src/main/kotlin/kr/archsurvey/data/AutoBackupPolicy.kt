package kr.archsurvey.data

import java.util.Calendar
import java.util.TimeZone

/**
 * 0.1.59 B16 자동 백업 규칙(순수 Kotlin).
 * - 점을 저장할 때: 오늘(현지 날짜) 자동 백업이 없으면 하나.
 * - 내보내기 직전: 항상 하나.
 * - 자동 백업(이름 auto_)은 최근 [KEEP]개만 남기고 오래된 것부터 지운다. 손으로 만든 백업·복구 직전 백업은 건드리지 않는다.
 */
object AutoBackupPolicy {
    const val PREFIX = "auto"
    const val KEEP = 5

    data class Item(val name: String, val createdAtMs: Long)

    fun isAuto(name: String) = name.startsWith(PREFIX + "_")

    private fun day(ms: Long, tz: TimeZone): Int { val c = Calendar.getInstance(tz); c.timeInMillis = ms; return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }

    fun dueOnSave(existing: List<Item>, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean =
        existing.none { isAuto(it.name) && day(it.createdAtMs, tz) == day(nowMs, tz) }

    /** [existing] 에 새 백업을 더한 뒤 지울 자동 백업 이름들 */
    fun toPrune(existing: List<Item>): List<String> =
        existing.filter { isAuto(it.name) }.sortedByDescending { it.createdAtMs }.drop(KEEP).map { it.name }
}
