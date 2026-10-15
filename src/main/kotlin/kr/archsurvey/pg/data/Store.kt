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

    /** 사용자가 쓴 이름으로 새 작업(= 폴더). 문제 있으면 IllegalArgumentException(한국어 한 줄) */
    fun newSession(name: String, arcore: Boolean): Session {
        JobName.problem(name) { dir(it).exists() }?.let { throw IllegalArgumentException(it) }
        val s = Session(JobName.clean(name), System.currentTimeMillis(), arcore = arcore)
        files.save(s)
        kr.archsurvey.pg.diag.PgLog.i("job created name=${s.name}")
        return s
    }

    /** (예전) 이름 자동 */
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
        if (src.isFile) return publishFile(src)
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
                // 0.0.8: 같은 이름 있으면 덮어씀((1)(2) 중복 방지). 다른 앱이 만든 파일은 못 지울 수 있음 → 그 경우 새로 만듦
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/사진측량/"
            runCatching {
                cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(f.name, rel), null)?.use { c ->
                    while (c.moveToNext()) {
                        val old = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                        runCatching { cr.delete(old, null, null) }.onFailure { kr.archsurvey.pg.diag.PgLog.w("export old delete failed") }
                    }
                }
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
    /** 파일 하나(zip)를 「다운로드/사진측량/」 에. 같은 이름 있으면 시스템이 (1) 붙임 */
    private fun publishFile(f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".zip")) "application/zip" else mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/사진측량")
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("다운로드 폴더에 만들 수 없음: ${f.name}")
            (cr.openOutputStream(uri) ?: throw java.io.IOException("쓸 수 없음: ${f.name}")).use { o -> f.inputStream().use { it.copyTo(o) } }
        } else {
            @Suppress("DEPRECATION")
            f.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "사진측량/${f.name}"), overwrite = true)
        }
        return "다운로드/사진측량/${f.name}"
    }
    private fun mime(n: String) = when (n.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "txt" -> "text/plain"; "xml" -> "text/xml"; "xmlz" -> "application/octet-stream"; else -> "application/octet-stream"
    }
}
