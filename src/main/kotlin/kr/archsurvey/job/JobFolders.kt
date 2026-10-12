package kr.archsurvey.job

/**
 * 0.1.38 새 작업 = 새 폴더.
 * 작업마다 폴더 하나: 공용 `Documents/ExcavationSurvey/[상위 폴더/]<작업>/`
 * 안에 측점 CSV·내보내기·사진·음성·백업을 모은다. 구글 드라이브에도 같은 구조로 올린다.
 * 순수 Kotlin(시험용). 실제 파일 쓰기는 engine/AndroidJobFolder.
 */
object JobFolders {
    const val ROOT = "ExcavationSurvey"
    const val PUBLIC_PARENT = "Documents"
    const val EXPORTS = "내보내기"
    const val PHOTOS = "사진"
    const val VOICES = "음성"
    const val BACKUPS = "백업"
    private const val MAX = 40
    private val bad = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** 폴더 이름으로 쓸 수 있게: 금지 문자 → _, 공백 정리, 앞 점·뒤 점/공백 제거, 40자. 비면 「작업」 */
    fun sanitize(raw: String, fallback: String = "작업"): String {
        var s = raw.replace(bad, "_").replace(Regex("\\s+"), " ").trim()
        s = s.trimStart('.').trimEnd('.', ' ')
        if (s.length > MAX) s = s.take(MAX).trimEnd('.', ' ')
        if (s.isBlank() || s.all { it == '_' }) return fallback
        return s
    }

    /** 이미 쓰인 이름(대소문자 무시)과 겹치면 「이름 (2)」「이름 (3)」… */
    fun unique(base: String, taken: Collection<String>): String {
        val t = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in t) return base
        var i = 2
        while ("$base ($i)".lowercase() in t) i++
        return "$base ($i)"
    }

    /** ROOT 아래 상대 경로(「ExcavationSurvey/유적/작업」) */
    fun relPath(group: String?, folder: String): String =
        listOfNotNull(ROOT, group?.takeIf { it.isNotBlank() }, folder).joinToString("/")

    /** 화면에 보여 줄 경로(「Documents/ExcavationSurvey/유적/작업」) */
    fun display(rel: String): String = "$PUBLIC_PARENT/$rel"

    /** 상대 경로 → (상위 폴더, 작업 폴더). ROOT 바로 아래면 상위 폴더 null */
    fun split(rel: String): Pair<String?, String> {
        val parts = rel.removePrefix("$ROOT/").split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts[0] to parts.drop(1).joinToString("/") else null to (parts.firstOrNull() ?: rel)
    }
}

/** 작업 → 폴더 기록(SharedPreferences). 시험에서는 메모리 */
interface JobFolderStore {
    fun all(): Map<String, String>
    fun put(job: String, rel: String)
    fun remove(job: String)
}

class MemoryJobFolderStore : JobFolderStore {
    private val m = LinkedHashMap<String, String>()
    override fun all(): Map<String, String> = m.toMap()
    override fun put(job: String, rel: String) { m[job] = rel }
    override fun remove(job: String) { m.remove(job) }
}

/**
 * 작업마다 폴더를 한 번 정해 기록해 둔다(작업 이름이 같아도 지운 옛 폴더와 섞이지 않게 「(2)」).
 * [existsOnDisk] = 그 상대 경로가 이미 기기에 있는지(지운 작업이 남긴 폴더 등).
 */
class JobFolderRegistry(private val store: JobFolderStore, private val existsOnDisk: (String) -> Boolean = { false }) {
    /** 새 작업: 폴더를 정하고 기록. [group] = 「새 폴더」(유적 등 상위 폴더, 선택) */
    fun assign(job: String, group: String? = null): String {
        store.all()[job]?.let { return it }
        val g = group?.trim()?.takeIf { it.isNotEmpty() }?.let { JobFolders.sanitize(it, "폴더") }
        val base = JobFolders.sanitize(job)
        val takenRel = store.all().values.map { it.lowercase() }.toSet()
        var name = base; var i = 2
        while (JobFolders.relPath(g, name).lowercase() in takenRel || existsOnDisk(JobFolders.relPath(g, name))) { name = "$base ($i)"; i++ }
        val rel = JobFolders.relPath(g, name)
        store.put(job, rel)
        return rel
    }

    /** 기록된 폴더. 0.1.37 이전 작업은 처음 쓸 때 정한다(작업 이름 그대로) */
    fun folderOf(job: String): String = store.all()[job] ?: assign(job)

    /** 기록만 보기(정하지 않음) */
    fun peek(job: String): String? = store.all()[job]

    fun forget(job: String) = store.remove(job)

    /** 지금까지 쓴 상위 폴더(새 작업 창에서 고르기) */
    fun groups(): List<String> = store.all().values.mapNotNull { JobFolders.split(it).first }.distinct().sorted()
}
