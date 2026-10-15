package kr.archsurvey.pg.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 진단 로그: logcat 태그 「PG」(릴리스에도 남김 — proguard 에 Log 제거 규칙 없음) +
 * 파일 Android/data/kr.archsurvey.pg/files/logs/pg.log (2 MB 넘으면 pg.1.log, pg.2.log 로 밀고 3개까지) +
 * 잡히지 않은 예외 → logs/crash_<시각>.txt. 좌표값·비밀번호·키 같은 민감 값은 쓰지 않는다(표적 번호·화소·개수·시간만).
 */
object PgLog {
    const val TAG = "PG"
    private const val MAX = 2L * 1024 * 1024
    private const val FILES = 3
    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pg-log").apply { isDaemon = true } }
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }
        dir = d
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = File(d, "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
                f.writeText("thread=${t.name}\napp=${appVer(ctx)} device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n\n" + Log.getStackTraceString(e))
                Log.e(TAG, "CRASH ${t.name}", e)
                write("E", "CRASH thread=${t.name} ${e.javaClass.name}: ${e.message} → ${f.name}", sync = true)
            }
            prev?.uncaughtException(t, e)
        }
        i("start app=${appVer(ctx)} device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} logs=${d.path}")
    }

    private fun appVer(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    fun d(m: String) { runCatching { Log.d(TAG, m) }; write("D", m) }
    fun i(m: String) { runCatching { Log.i(TAG, m) }; write("I", m) }
    fun w(m: String) { runCatching { Log.w(TAG, m) }; write("W", m) }
    fun e(m: String, t: Throwable? = null) { runCatching { Log.e(TAG, m, t) }; write("E", m + (t?.let { "\n" + Log.getStackTraceString(it) } ?: "")) }

    private fun write(lv: String, m: String, sync: Boolean = false) {
        val d = dir ?: return
        val line = synchronized(ts) { ts.format(Date()) } + " $lv/${Thread.currentThread().name}: $m\n"
        val job = Runnable {
            runCatching {
                val f = File(d, "pg.log")
                if (f.length() > MAX) {
                    for (k in FILES - 1 downTo 1) {
                        val src = if (k == 1) f else File(d, "pg.${k - 1}.log")
                        val dst = File(d, "pg.$k.log")
                        if (src.exists()) { dst.delete(); src.renameTo(dst) }
                    }
                }
                f.appendText(line)
            }
        }
        if (sync) job.run() else runCatching { io.execute(job) }
    }

    /** 로그·크래시 파일을 zip 하나로 묶어 공유 창(사용자가 앱을 고름) */
    fun share(ctx: Context) {
        val d = dir ?: return
        val zip = File(d, "share/pg-logs.zip").apply { parentFile?.mkdirs() }
        ZipOutputStream(zip.outputStream()).use { z ->
            d.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        i("log share zip=${zip.path} bytes=${zip.length()}")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "로그 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
