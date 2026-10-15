package kr.archsurvey.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 오류 기록: 잡히지 않은 예외(앱 종료)와 백그라운드에서 삼킨 예외를 앱 내부 파일에 남긴다.
 * 개인정보: 비밀번호·좌표는 쓰지 않는다(예외 메시지·스택만). 파일은 최대 [MAX_BYTES], 넘으면 앞부분을 버린다.
 * 설정 > 오류 기록에서 보기·공유·지우기.
 */
object CrashLog {
    const val MAX_BYTES = 200_000
    @Volatile private var file: File? = null
    @Volatile var appVersion: String = ""

    /** Application.onCreate 에서 한 번. 기존 처리기(시스템)는 그대로 이어서 호출한다 → 앱은 원래대로 종료되지만 기록은 남는다 */
    fun install(dir: File, version: String) {
        file = File(dir, "crash-log.txt"); appVersion = version
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { record("앱 종료(잡히지 않은 예외) · 스레드 ${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 시험·백그라운드 작업용: 파일 위치만 정한다 */
    fun useFile(f: File) { file = f }
    /** 0.1.55: PC(adb pull)에서 앱을 켜지 않고도 볼 수 있게 Android/data/<패키지>/files/logs/crash-log.txt 에도 같이 쓴다 */
    @Volatile var mirror: File? = null

    @Synchronized
    fun record(where: String, e: Throwable) {
        val f = file ?: return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val entry = "=== $stamp · $appVersion · $where\n${redact(sw.toString())}\n"
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(entry)
            if (f.length() > MAX_BYTES) { val txt = f.readText(); f.writeText(txt.takeLast(MAX_BYTES / 2)) }
        }
        mirror?.let { m -> runCatching {
            m.parentFile?.mkdirs(); m.appendText(entry)
            if (m.length() > MAX_BYTES) { val txt = m.readText(); m.writeText(txt.takeLast(MAX_BYTES / 2)) }
        } }
        runCatching { android.util.Log.e("ArchSurvey", "$where: ${e.javaClass.simpleName}: ${redact(e.message.orEmpty())}") }
    }

    /** 보기·공유용 읽기. 0.1.18: 예전 버전이 남긴 기록에도 비밀번호가 섞여 있을 수 있어 읽을 때 한 번 더 가린다 */
    fun read(): String = redact(runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
    fun count(): Int = Regex("^=== ", RegexOption.MULTILINE).findAll(read()).count()
    fun clear() { runCatching { file?.delete() } }

    /** 혹시 메시지에 비밀번호 같은 값이 섞이지 않도록 'password=…', 'Authorization: …' 꼴을 가린다 */
    fun redact(s: String): String = s
        .replace(Regex("(?i)(authorization:\\s*(?:basic|digest)\\s+)[^\\r\\n]+"), "$1***")
        // 0.1.18: password=x · password: x · "password":"x" · 비밀번호=x 꼴(공백·쉼표·괄호·따옴표 전까지)
        .replace(Regex("(?i)(\"?(?:password|passwd|pwd|pw|ntrip_pw|비밀번호)\"?\\s*[=:]\\s*\"?)[^\\s,)\"]+"), "$1***")
}
