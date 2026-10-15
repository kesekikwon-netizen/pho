package kr.archsurvey.pg.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 작업 = 앱 폴더(Android/data/kr.archsurvey.pg/files/pg/<작업명>/) 안 session.json + photos/ */
class Store(private val ctx: Context) {
    val root: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "pg").also { it.mkdirs() }
    fun dir(name: String) = File(root, name)
    fun photos(name: String) = File(dir(name), "photos").also { it.mkdirs() }

    fun list(): List<Session> = root.listFiles().orEmpty().filter { File(it, "session.json").exists() }
        .mapNotNull { runCatching { load(it.name) }.getOrNull() }.sortedByDescending { it.created }

    fun load(name: String): Session = Session.fromJson(JSONObject(File(dir(name), "session.json").readText()))

    fun save(s: Session) {
        val d = dir(s.name); d.mkdirs()
        val tmp = File(d, "session.json.tmp"); tmp.writeText(s.toJson().toString())
        tmp.renameTo(File(d, "session.json"))
    }

    /** 이름은 날짜·시각으로 자동(타자 없음) */
    fun newSession(arcore: Boolean): Session {
        val base = "3D_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        var n = base; var i = 2
        while (dir(n).exists()) n = "${base}_${i++}"
        return Session(n, System.currentTimeMillis(), arcore = arcore).also { save(it) }
    }

    /**
     * 내보내기 폴더를 폰의 「다운로드/사진측량/<작업명>_itwin/」 로 복사(USB 로 PC 에서 바로 보임).
     * 10 이상: MediaStore.Downloads(권한 없음). 9 이하: 공용 Download 폴더에 직접(WRITE_EXTERNAL_STORAGE).
     */
    fun publish(src: File): String {
        val rel = "사진측량/${src.name}"
        val files = src.walkTopDown().filter { it.isFile }.toList()
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            for (f in files) {
                val sub = f.parentFile!!.relativeTo(src).path.let { if (it.isEmpty()) "" else "/$it" }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime(f.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$rel$sub")
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
                cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
        } else {
            @Suppress("DEPRECATION")
            val dst = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), rel)
            src.copyRecursively(dst, overwrite = true)
        }
        return "다운로드/$rel"
    }
    private fun mime(n: String) = when (n.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "txt" -> "text/plain"; "xml" -> "text/xml"; "xmlz" -> "application/octet-stream"; else -> "application/octet-stream"
    }
}
