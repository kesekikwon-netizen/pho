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
    // 0.1.57: `all() + (job to rel) as Map` 은 `as` 가 먼저 묶여 Pair→Map 형변환 오류(ClassCastException) — 새 작업 폴더 기록 실패
    override fun put(job: String, rel: String) { s.jobFoldersJson = encode(all() + mapOf(job to rel)) }
    override fun remove(job: String) { s.jobFoldersJson = encode(all() - job) }
    companion object {
        fun encode(m: Map<String, String>): String { val o = JSONObject(); for ((k, v) in m) o.put(k, v); return o.toString() }
    }
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
        // 0.1.59: 사진은 찍을 때 바로 Documents/발굴조사/발굴 측량/…/사진 으로 공용 사본을 둔다(여기서 다시 복사하지 않음)
        val rec = File(ctx.getExternalFilesDir("Recordings") ?: File(ctx.filesDir, "Recordings"), safe(job))
        writer.copyTree(rel, JobFolders.VOICES, rec).forEach { changed.add(JobFolders.VOICES to it) }
        for ((sub, f) in changed) publish(rel, sub, f)
        JobFolderSnapshot(rel, writer.snapshot(rel))
    }

    /** 0.1.56 「PC로 보내기」: zip 하나 → Documents/발굴조사/[폴더/]작업/측량/보내기/<작업>_측량.zip (덮어쓰기, 직전 1개는 이전/).
     *  반환 = (보낼 파일, 화면 경로 한 줄). Android 9 이하 = 앱 폴더 경로 */
    suspend fun addSend(job: String, zip: File): Pair<File, String> = withContext(Dispatchers.IO) {
        val jobRel = relOf(job)
        val sendRel = kr.archsurvey.job.SendFolder.sendRel(jobRel)
        val name = kr.archsurvey.job.SendFolder.zipName(job)
        val (dst, prev) = kr.archsurvey.job.SendFolder.place(writer.dir(sendRel), zip, name)
        var pub = android.os.Build.VERSION.SDK_INT >= 29
        if (pub && prev != null) publishAt("${JobFolders.PUBLIC_PARENT}/$sendRel/${kr.archsurvey.job.SendFolder.PREV}/", prev)
        if (pub && !publishAt("${JobFolders.PUBLIC_PARENT}/$sendRel/", dst)) pub = false
        dst to (if (pub) kr.archsurvey.job.SendFolder.display(jobRel, job) else kr.archsurvey.job.SendFolder.displayLocal(dst))
    }

    /** 보내기 앞 화면 경로(완료 화면과 같은 글) */
    fun sendDisplay(job: String): String =
        if (android.os.Build.VERSION.SDK_INT >= 29) kr.archsurvey.job.SendFolder.display(relOf(job), job)
        else kr.archsurvey.job.SendFolder.displayLocal(File(writer.dir(kr.archsurvey.job.SendFolder.sendRel(relOf(job))), kr.archsurvey.job.SendFolder.zipName(job)))

    /** 백업 ZIP 사본 → 지금 작업 폴더 백업/ */
    suspend fun addBackup(job: String, zip: File) = withContext(Dispatchers.IO) {
        val rel = relOf(job)
        writer.copyIn(rel, JobFolders.BACKUPS, zip)?.let { publish(rel, JobFolders.BACKUPS, it) }
    }

    /** 공용 Documents 로 한 파일(같은 이름이 있으면 덮어씀). Android 9 이하는 저장소 권한이 없어 앱 폴더에만 */
    private fun publish(rel: String, sub: String?, f: File): Boolean =
        publishAt("${JobFolders.PUBLIC_PARENT}/$rel/" + (sub?.let { "$it/" } ?: ""), f)

    private fun publishAt(relPath: String, f: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val cr = ctx.contentResolver
            val coll = android.provider.MediaStore.Files.getContentUri("external")
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
