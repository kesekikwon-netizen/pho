package kr.archsurvey.pg.data

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 작업 폴더 파일 처리(Android 없이 시험 가능). 앱이 죽어도(프로세스 종료·배터리) 사진 한 장마다 저장돼 다시 열 수 있다.
 * session.json 은 임시파일에 쓰고 바꿔 끼우며 직전 판은 session.json.bak 로 남긴다 → 쓰다 끊겨도 백업으로 연다.
 */
class SessionFiles(val root: File) {
    data class Loaded(val session: Session, val fromBackup: Boolean, val missingPhotos: Int)

    fun dir(name: String) = File(root, name)
    fun photos(name: String) = File(dir(name), "photos").also { it.mkdirs() }

    @Throws(IOException::class)
    fun save(s: Session) {
        val d = dir(s.name); if (!d.isDirectory && !d.mkdirs()) throw IOException("폴더를 만들 수 없음")
        val cur = File(d, "session.json"); val tmp = File(d, "session.json.tmp"); val bak = File(d, "session.json.bak")
        tmp.writeText(s.toJson().toString())
        if (cur.exists()) { bak.delete(); cur.renameTo(bak) }
        if (!tmp.renameTo(cur)) { tmp.copyTo(cur, overwrite = true); tmp.delete() }
        // 0.0.15: 저장된 사진 줄은 저널에서 뺌(아직 안 들어간 줄은 남김)
        synchronized(LOCK) {
            val j = File(d, JOURNAL)
            if (j.exists()) {
                val have = s.stills.map { it.id }.toHashSet()
                val keep = readJournal(j).filter { it.id !in have }
                if (keep.isEmpty()) j.delete() else j.writeText(keep.joinToString("") { stillLine(it) + "\n" })
            }
        }
    }

    /** 0.0.15: 사진 저장 즉시 한 줄 덧붙임(2초 묶음 저장 전에 앱이 죽어도 사진이 목록에서 빠지지 않게) */
    fun journal(name: String, st: Still) {
        val d = dir(name); d.mkdirs()
        synchronized(LOCK) {
            java.io.FileOutputStream(File(d, JOURNAL), true).use { o -> o.write((stillLine(st) + "\n").toByteArray(Charsets.UTF_8)); o.fd.sync() }
        }
    }

    companion object {
        const val JOURNAL = "stills.jsonl"
        private val LOCK = Any()
        fun stillLine(st: Still): String = Session("j", 0L, listOf(st)).toJson().getJSONArray("stills").getJSONObject(0).toString()
        fun readJournal(f: File): List<Still> = if (!f.isFile) emptyList() else f.readLines().mapNotNull { l ->
            if (l.isBlank()) null else runCatching { Session.fromJson(JSONObject().put("name", "j").put("created", 0L).put("stills", org.json.JSONArray().put(JSONObject(l)))).stills.firstOrNull() }.getOrNull()
        }
    }

    /** 손상되면 백업, 사진 파일이 없는 장은 목록에서 뺀다 */
    fun load(name: String): Loaded {
        val d = dir(name)
        fun read(f: File) = runCatching { Session.fromJson(JSONObject(f.readText())) }.getOrNull()
        var fromBak = false
        val s = read(File(d, "session.json")) ?: read(File(d, "session.json.bak"))?.also { fromBak = true }
            ?: throw IOException("작업 파일을 읽을 수 없음: $name")
        // 0.0.15: 저널에만 있는 사진(마지막 저장 뒤) 되살림
        val extra = readJournal(File(d, JOURNAL)).let { j -> val have = s.stills.map { it.id }.toHashSet(); j.filter { it.id !in have }.distinctBy { it.id } }
        if (extra.isNotEmpty()) runCatching { kr.archsurvey.pg.diag.PgLog.i("session $name journal recovered ${extra.size} photo(s)") }
        val ph = File(d, "photos")
        // 0.0.16 되살리기 1: 기록은 됐는데 이름 바꾸기 전에 멈춘 조각(.part) → 완전한 JPEG 이면 이름 바꿈
        for (st in extra) { val f = File(ph, st.file); val p = File(ph, st.file + ".part"); if (!f.exists() && p.isFile && Jpeg.complete(p)) p.renameTo(f) }
        val recs = (s.stills + extra).sortedBy { it.id }
        // 0.0.16 되살리기 2: 기록 없는 IMG 파일(고아) → 최소 기록으로 다시 넣음(자세 없음, 크기는 JPEG 머리에서)
        val have = recs.map { it.file }.toHashSet(); val haveId = recs.map { it.id }.toHashSet()
        val orphans = ph.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg") && it.name !in have && it.length() > 0 }
            .mapNotNull { f -> val no = PhotoIds.fileNo(f.name) ?: return@mapNotNull null; if (no in haveId) return@mapNotNull null
                val (w, h) = Jpeg.size(f) ?: return@mapNotNull null
                Still(no, f.name, w, h, 0.8 * maxOf(w, h), 0.8 * maxOf(w, h), w / 2.0, h / 2.0, null, f.lastModified(), emptyList(), "recovered") }
        if (orphans.isNotEmpty()) runCatching { kr.archsurvey.pg.diag.PgLog.i("session $name orphan photos re-added ${orphans.size}: ${orphans.joinToString(",") { it.file }}") }
        val ok = (recs + orphans).sortedBy { it.id }.filter { File(ph, it.file).let { f -> f.exists() && f.length() > 0 } }
        val next = maxOf(s.nextPhotoId, (ok.maxOfOrNull { it.id } ?: 0) + 1)
        return Loaded(s.copy(stills = ok, nextPhotoId = next), fromBak, s.stills.size - ok.count { o -> s.stills.any { it.id == o.id } })
    }

    fun list(): List<Session> = root.listFiles().orEmpty()
        .filter { File(it, "session.json").exists() || File(it, "session.json.bak").exists() }
        .mapNotNull { runCatching { load(it.name).session }.getOrNull() }.sortedByDescending { it.created }

    /** 사진 쓰기: 실패하면 반쯤 쓴 파일을 지우고 false */
    fun writePhoto(name: String, file: String, bytes: ByteArray): Boolean {
        val f = File(photos(name), file); val tmp = File(f.path + ".part")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) throw IOException("rename")
            true
        } catch (e: Exception) { tmp.delete(); f.delete(); false }
    }

    /** S5: 이름 바꾸기(폴더+session.json). 문제 있으면 한국어 한 줄 */
    fun rename(old: String, new: String): String? {
        JobName.problem(new) { dir(it).exists() }?.let { return it }
        val n = JobName.clean(new)
        val s = load(old).session
        if (!dir(old).renameTo(dir(n))) return "이름을 바꾸지 못했어요"
        save(s.copy(name = n)); return null
    }
    fun delete(name: String): Boolean = dir(name).takeIf { it.isDirectory && it.parentFile == root }?.deleteRecursively() ?: false

    fun free(): Long = runCatching { root.usableSpace }.getOrDefault(0L)
    fun photoBytes(s: Session): Long = s.stills.sumOf { File(photos(s.name), it.file).length() }
}
