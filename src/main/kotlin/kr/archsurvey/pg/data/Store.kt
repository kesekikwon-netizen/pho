package kr.archsurvey.pg.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 작업 = 앱 폴더(Android/data/kr.archsurvey.pg/files/pg/<작업명>/) 안 session.json + photos/ */
class Store(private val ctx: Context) {
    val root: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "pg").also { it.mkdirs() }
    val files get() = SessionFiles(root)
    fun dir(name: String) = files.dir(name)
    fun photos(name: String) = files.photos(name)
    fun list(): List<Session> = files.list()
    fun load(name: String): Session = files.load(name).session
    fun loadChecked(name: String): SessionFiles.Loaded = files.load(name)
    fun save(s: Session) { runCatching { files.save(s) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("session save failed ${s.name}", it) } }

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
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("다운로드 폴더에 만들 수 없음: ${f.name}")
                (cr.openOutputStream(uri) ?: throw java.io.IOException("쓸 수 없음: ${f.name}")).use { o -> f.inputStream().use { it.copyTo(o) } }
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
