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
     * 0.0.9 M1·M8: zip 을 공용 위치 「Documents/발굴조사/<작업명>/사진측량/」 에 저장. 같은 이름이면 덮어씀(직전본 1개 = <이름>.bak.zip).
     * 10 이상: MediaStore Files(권한 없음, 앱이 만든 파일만 보임·바꿀 수 있음). 9 이하: 공용 Documents 에 직접(WRITE_EXTERNAL_STORAGE ≤28).
     * 반환: 사람이 읽는 PC 경로 「내 파일 › Documents › 발굴조사 › <작업> › 사진측량 › <zip>」
     */
    fun publish(zip: File, job: String): String {
        val shelf: Shelf = if (Build.VERSION.SDK_INT >= 29) MediaShelf(ctx, ExportPlace.relative(job))
            else @Suppress("DEPRECATION") FileShelf(File(Environment.getExternalStoragePublicDirectory("Documents"), ExportPlace.relative(job).removePrefix("Documents/")))
        val r = ExportPlace.put(shelf, zip)
        kr.archsurvey.pg.diag.PgLog.i("publish ${r.name} bak=${r.bak} replaced=${r.replaced} api=${Build.VERSION.SDK_INT}")
        return ExportPlace.pcPath(job, r.name)
    }

    private fun mime(n: String) = when (n.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "txt" -> "text/plain"; "xml" -> "text/xml"; "xmlz" -> "application/octet-stream"; else -> "application/octet-stream"
    }
}
