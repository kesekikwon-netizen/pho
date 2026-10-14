package kr.archsurvey.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupInfo(val file: File, val createdAtMs: Long, val points: Int, val photos: Int, val voices: Int, val sizeBytes: Long) {
    val name: String get() = file.name
}

class BackupException(message: String) : Exception(message)

/**
 * 3-J 데이터 백업·복구.
 * 백업 ZIP = manifest.json + survey.db(체크포인트 후 복사) + photos/(Pictures) + voices/(Recordings).
 * 복구는 ① 압축 풀어 검증(무결성·버전·경로 탈출) ② 현재 상태를 "복구 직전" 백업으로 먼저 저장 ③ DB 닫고 파일 교체 순서.
 * 복구 뒤에는 열려 있는 화면의 DB 참조가 낡았으므로 앱을 다시 시작해야 한다([restore] 의 afterRestore).
 */
class BackupService(private val ctx: Context) {
    val dir: File = File(ctx.getExternalFilesDir("Backups") ?: File(ctx.filesDir, "Backups"), "").also { it.mkdirs() }
    private val dbFile: File get() = ctx.getDatabasePath(DB_NAME)
    private val picturesDir: File get() = File(ctx.getExternalFilesDir("Pictures") ?: File(ctx.filesDir, "Pictures"), "")
    private val recordingsDir: File get() = File(ctx.getExternalFilesDir("Recordings") ?: File(ctx.filesDir, "Recordings"), "")

    companion object {
        const val DB_NAME = "survey.db"
        const val FORMAT = 1
        const val DB_VERSION = 2
        private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    /** WAL 의 내용을 본 DB 파일로 모두 반영(체크포인트)한다 */
    private fun checkpoint() {
        val db = SurveyDb.get(ctx)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    fun list(): List<BackupInfo> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.toList() ?: emptyList())
        .mapNotNull { info(it) }.sortedByDescending { it.createdAtMs }

    private fun info(f: File): BackupInfo? = try {
        ZipFile(f).use { z ->
            val m = JSONObject(z.getInputStream(z.getEntry("manifest.json") ?: return null).readBytes().toString(Charsets.UTF_8))
            BackupInfo(f, m.optLong("createdAtUtcMs", f.lastModified()), m.optInt("points"), m.optInt("photos"), m.optInt("voices"), f.length())
        }
    } catch (_: Exception) { null }

    fun create(prefix: String = "backup", versionName: String = ""): BackupInfo {
        checkpoint()
        val now = System.currentTimeMillis()
        val out = File(dir, "${prefix}_${stamp()}.zip")
        val db = SurveyDb.get(ctx).openHelper.readableDatabase
        fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val points = db.query("SELECT COUNT(*) FROM points WHERE deletedAtUtcMs IS NULL").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val manifest = JSONObject().put("format", FORMAT).put("dbVersion", DB_VERSION).put("appVersion", versionName)
            .put("createdAtUtcMs", now).put("points", points).put("photos", count("photos")).put("voices", count("voice_memos"))
        kr.archsurvey.io.AtomicFiles.zip(out) { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("survey.db")); dbFile.inputStream().use { it.copyTo(z) }; z.closeEntry()
            addTree(z, picturesDir, "photos/", skipThumbs = true)
            addTree(z, recordingsDir, "voices/")
        }
        return info(out) ?: throw BackupException("백업 파일을 확인하지 못했어요")
    }

    private fun addTree(z: ZipOutputStream, root: File, prefix: String, skipThumbs: Boolean = false) {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (skipThumbs && rel.startsWith(".thumbs/")) return@forEach
            z.putNextEntry(ZipEntry(prefix + rel)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
    }

    /** 복구 전 검사. 문제가 있으면 BackupException(사용자 문구). 통과하면 manifest 반환 */
    fun validate(zip: File): JSONObject {
        val m: JSONObject
        try {
            ZipFile(zip).use { z ->
                val me = z.getEntry("manifest.json") ?: throw BackupException("이 파일은 이 앱의 백업이 아니에요 (manifest.json 없음)")
                m = JSONObject(z.getInputStream(me).readBytes().toString(Charsets.UTF_8))
                if (z.getEntry("survey.db") == null) throw BackupException("백업에 데이터베이스(survey.db)가 없어요")
                for (e in z.entries()) if (e.name.contains("..") || e.name.startsWith("/")) throw BackupException("안전하지 않은 파일 경로가 들어 있어요")
            }
        } catch (e: BackupException) { throw e }
        catch (e: Exception) { throw BackupException("백업 파일을 읽을 수 없어요 (파일이 깨졌거나 ZIP이 아니에요)") }
        if (m.optInt("format") != FORMAT) throw BackupException("지원하지 않는 백업 형식이에요")
        if (m.optInt("dbVersion") > DB_VERSION) throw BackupException("더 새 버전 앱의 백업이에요. 앱을 먼저 업데이트하세요.")
        return m
    }

    private fun checkDb(f: File) {
        try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use {
                val ok = it.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (!ok) throw BackupException("백업 안의 데이터베이스가 손상되어 있어요")
                val hasPoints = it.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='points'", null).use { c -> c.moveToFirst() }
                if (!hasPoints) throw BackupException("백업 안의 데이터베이스에 점 표가 없어요")
            }
        } catch (e: BackupException) { throw e }
        catch (e: Exception) { throw BackupException("백업 안의 데이터베이스가 손상되어 있거나 열 수 없어요") }
    }

    /**
     * 복구. 성공하면 "복구 직전" 백업 파일을 돌려준다(되돌릴 수 있도록 보관).
     * [afterRestore] 는 파일 교체가 끝난 뒤 호출(앱 다시 시작).
     */
    fun restore(zip: File, versionName: String = "", afterRestore: () -> Unit = {}): BackupInfo {
        validate(zip)
        val stage = File(ctx.cacheDir, "restore_stage").also { it.deleteRecursively(); it.mkdirs() }
        try {
            ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory) continue
                    val dst = File(stage, e.name)
                    if (!dst.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw BackupException("안전하지 않은 파일 경로가 들어 있어요")
                    dst.parentFile?.mkdirs(); z.getInputStream(e).use { i -> kr.archsurvey.io.AtomicFiles.copy(i, dst) }
                }
            }
            checkDb(File(stage, "survey.db"))
            val safety = create("before_restore", versionName)
            SurveyDb.closeInstance()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
            dbFile.parentFile?.mkdirs()
            File(stage, "survey.db").copyTo(dbFile, overwrite = true)
            replaceDir(File(stage, "photos"), picturesDir)
            replaceDir(File(stage, "voices"), recordingsDir)
            afterRestore()
            return safety
        } finally { stage.deleteRecursively() }
    }

    private fun replaceDir(from: File, to: File) {
        to.deleteRecursively(); to.mkdirs()
        if (from.exists()) from.copyRecursively(to, overwrite = true)
    }

    fun delete(f: File) { if (f.parentFile?.canonicalPath == dir.canonicalPath) f.delete() }
}
