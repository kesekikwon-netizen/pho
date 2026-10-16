package kr.archsurvey.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.1.60 AK1: 마운트별 Float→Fixed 소요 시간.
 * 서버 쪽 VRS 문제(보정은 오는데 Fixed가 안 됨)와 수신기·하늘 문제를 가려내려고 현장 로그에 남긴다.
 * 순수 상태기 [FixTimer] + 파일 기록 [FixTimeLog]. 마운트 이름·초만 쓰고 좌표·아이디·비밀번호는 쓰지 않는다.
 */
class FixTimer {
    /** Fixed가 아닌 위치(단독·DGPS·Float)가 시작된 시각. null = Fixed이거나 위치 없음 */
    var floatSinceMs: Long? = null; private set
    private var floatMount: String = ""

    data class Done(val mount: String, val seconds: Double)

    /** [fixed] 지금 RTK Fixed, [hasPosition] 위치가 있음(Fixed 아님 포함), [live] 수신기 연결 중 */
    fun update(nowMs: Long, live: Boolean, hasPosition: Boolean, fixed: Boolean, mount: String): Done? {
        if (!live) { floatSinceMs = null; return null }
        if (fixed) {
            val s = floatSinceMs; floatSinceMs = null
            return if (s != null && nowMs >= s) Done(floatMount.ifBlank { "(보정 없음)" }, (nowMs - s) / 1000.0) else null
        }
        if (!hasPosition) { floatSinceMs = null; return null }
        val m = mount.trim()
        if (floatSinceMs == null || m != floatMount) { floatSinceMs = nowMs; floatMount = m } // 마운트를 바꾸면 새로 잰다
        return null
    }

    fun floatSeconds(nowMs: Long): Double? = floatSinceMs?.let { (nowMs - it).coerceAtLeast(0L) / 1000.0 }
}

object FixTimeLog {
    const val MAX_BYTES = 100_000
    @Volatile var files: List<File> = emptyList()

    fun line(stampMs: Long, d: FixTimer.Done, ver: String = CrashLog.appVersion): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(stampMs))
        return "$stamp\t$ver\t${CrashLog.redact(d.mount)}\tFloat→Fixed\t${String.format(Locale.US, "%.1f", d.seconds)}s\n"
    }

    @Synchronized
    fun record(stampMs: Long, d: FixTimer.Done) {
        val entry = line(stampMs, d)
        for (f in files) runCatching {
            f.parentFile?.mkdirs(); f.appendText(entry)
            if (f.length() > MAX_BYTES) { val t = f.readText(); f.writeText(t.takeLast(MAX_BYTES / 2).substringAfter('\n')) }
        }
    }

    fun read(): String = runCatching { files.firstOrNull()?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
}
