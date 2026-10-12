package kr.archsurvey.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.job.JobFolderRegistry
import kr.archsurvey.job.JobFolderStore
import kr.archsurvey.job.JobFolderWriter
import kr.archsurvey.job.JobFolders
import kr.archsurvey.ui.JobFolderSnapshot
import org.json.JSONObject
import java.io.File

/** 작업 → 폴더 기록을 설정(JSON)에 */
class PrefsJobFolderStore(private val s: SettingsStore) : JobFolderStore {
    override fun all(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching { val o = JSONObject(s.jobFoldersJson.ifBlank { "{}" }); for (k in o.keys()) out[k] = o.getString(k) }
        return out
    }
    override fun put(job: String, rel: String) { s.jobFoldersJson = JSONObject(all() + (job to rel) as Map<*, *>).toString() }
    override fun remove(job: String) { s.jobFoldersJson = JSONObject((all() - job) as Map<*, *>).toString() }
}

/**
 * 0.1.38 새 작업 = 새 폴더(Android).
 * 기기 안 원본: 앱 폴더 Android/data/kr.archsurvey.field/files/Documents/ExcavationSurvey/[상위/]작업/
 * 공용 사본(Android 10 이상): Documents/ExcavationSurvey/[상위/]작업/ (MediaStore, 바뀐 파일만). 파일 앱·PC 연결로 보임.
 */
class AndroidJobFolder(private val ctx: Context, private val settings: SettingsStore) {
    val writer = JobFolderWriter(File(ctx.getExternalFilesDir("Documents") ?: File(ctx.filesDir, "Documents"), ""))
    val registry = JobFolderRegistry(PrefsJobFolderStore(settings)) { rel -> writer.dir(rel).exists() }
    private fun safe(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")

    fun relOf(job: String): String = registry.folderOf(job)
    fun displayOf(job: String): String = JobFolders.display(relOf(job))

    /** 작업 폴더를 최신으로: 측점 CSV + 사진 + 음성 복사 → 공용 사본 → 드라이브 올리기 목록 */
    suspend fun sync(job: String): JobFolderSnapshot = withContext(Dispatchers.IO) {
        val rel = relOf(job)
        val changed = ArrayList<Pair<String?, File>>()
        val dao = SurveyDb.get(ctx).dao()
        val pts = dao.pointsOf(job)
        val csv = kr.archsurvey.export.Formats.pointsCsv(pts, emptyMap()).toByteArray(Charsets.UTF_8)
        writer.writeIfChanged(rel, null, "측점_${JobFolders.split(rel).second}.csv", csv)?.let { changed.add(null to it) }
        val pics = File(ctx.getExternalFilesDir("Pictures") ?: File(ctx.filesDir, "Pictures"), safe(job))
        writer.copyTree(rel, JobFolders.PHOTOS, pics).forEach { changed.add(JobFolders.PHOTOS to it) }
        val rec = File(ctx.getExternalFilesDir("Recordings") ?: File(ctx.filesDir, "Recordings"), safe(job))
        writer.copyTree(rel, JobFolders.VOICES, rec).forEach { changed.add(JobFolders.VOICES to it) }
        for ((sub, f) in changed) publish(rel, sub, f)
        JobFolderSnapshot(rel, writer.snapshot(rel))
    }

    /** 내보내기 묶음(파일들) → 작업 폴더 내보내기/<묶음>/. 반환 = 화면에 보일 공용 경로(못 만들면 기기 안 경로) */
    suspend fun addExport(job: String, exportDir: File, zip: File?): String = withContext(Dispatchers.IO) {
        val rel = relOf(job)
        val sub = "${JobFolders.EXPORTS}/${exportDir.name}"
        val files = exportDir.listFiles()?.filter { it.isFile }.orEmpty()
        var pub = true
        for (f in files) writer.copyIn(rel, sub, f)?.let { if (!publish(rel, sub, it)) pub = false }
        zip?.let { z -> writer.copyIn(rel, JobFolders.EXPORTS, z)?.let { if (!publish(rel, JobFolders.EXPORTS, it)) pub = false } }
        if (pub && android.os.Build.VERSION.SDK_INT >= 29) "${JobFolders.display(rel)}/$sub" else File(writer.dir(rel), sub).path
    }

    /** 백업 ZIP 사본 → 지금 작업 폴더 백업/ */
    suspend fun addBackup(job: String, zip: File) = withContext(Dispatchers.IO) {
        val rel = relOf(job)
        writer.copyIn(rel, JobFolders.BACKUPS, zip)?.let { publish(rel, JobFolders.BACKUPS, it) }
    }

    /** 공용 Documents 로 한 파일(같은 이름이 있으면 덮어씀). Android 9 이하는 저장소 권한이 없어 앱 폴더에만 */
    private fun publish(rel: String, sub: String?, f: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val cr = ctx.contentResolver
            val coll = android.provider.MediaStore.Files.getContentUri("external")
            val relPath = "${JobFolders.PUBLIC_PARENT}/$rel/" + (sub?.let { "$it/" } ?: "")
            val existing = cr.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath, f.name), null)?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null }
            val uri = existing ?: cr.insert(coll, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, JobFolderWriter.mimeOf(f.name))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }) ?: error("insert")
            (cr.openOutputStream(uri, "wt") ?: error("open")).use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        }.getOrElse { kr.archsurvey.diag.CrashLog.record("작업 폴더 공용 사본", it); false }
    }
}
