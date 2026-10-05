package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.archsurvey.core.KoreanTmZone

/** 설정 값의 저장소 (Android 에서는 SharedPreferences) */
interface SettingsPlatform {
    var zone: KoreanTmZone
    var poleRight: Boolean
    var sound: Boolean
    var vibration: Boolean
    var keepScreenOn: Boolean
    /** 한 점 측정 횟수(0.1.9). 기본 3 */
    var epochs: Int get() = 3; set(_) {}
    /** 오류 기록(앱 내부 파일) 읽기·공유·지우기 */
    fun crashLog(): String = ""
    fun shareCrashLog() {}
    fun clearCrashLog() {}
}

/** 설정 화면 상태. 바꾸면 곧바로 저장하고 화면(좌표 원점·폴 위치)에 적용한다. */
class SettingsState(private val c: SurveyController, private val p: SettingsPlatform) {
    var sound by mutableStateOf(p.sound); private set
    var vibration by mutableStateOf(p.vibration); private set
    var keepScreenOn by mutableStateOf(p.keepScreenOn); private set

    var crashText by mutableStateOf(""); private set
    var showCrash by mutableStateOf(false)

    init { c.zone = p.zone; c.poleOnRight = p.poleRight; c.epochs = p.epochs.coerceIn(1, 30) }

    fun setEpochs(n: Int) { val v = n.coerceIn(1, 30); c.epochs = v; p.epochs = v; c.ui.toast("측정 횟수 ${v}회로 저장됨 · 다음 측점부터") }
    fun openCrash() { crashText = p.crashLog(); showCrash = true }
    fun shareCrash() = p.shareCrashLog()
    fun clearCrash() { p.clearCrashLog(); crashText = ""; c.ui.toast("오류 기록을 지웠어요") }

    fun setZone(z: KoreanTmZone) { c.zone = z; p.zone = z; c.ui.toast("좌표 원점 저장됨 · ${z.labelKo}") }
    fun setPoleRight(v: Boolean) { c.poleOnRight = v; p.poleRight = v }
    fun changeSound(v: Boolean) { sound = v; p.sound = v }
    fun changeVibration(v: Boolean) { vibration = v; p.vibration = v }
    fun changeKeepScreenOn(v: Boolean) { keepScreenOn = v; p.keepScreenOn = v }
}

data class JobInfo(val name: String, val points: Int, val photos: Int, val voices: Int, val firstAtMs: Long?, val lastAtMs: Long?, val fakePoints: Int = 0)

class JobException(message: String) : Exception(message)

/** 작업(프로젝트) 관리에 필요한 기능. 시험에서는 가짜로 바꾼다. */
interface JobPlatform {
    val current: String
    suspend fun list(): List<JobInfo>
    suspend fun info(name: String): JobInfo
    /** 새 작업을 만들고 그 작업으로 전환. 이름이 비었거나 이미 있으면 JobException */
    suspend fun create(name: String)
    suspend fun open(name: String)
    /** 작업과 그 점·사진·음성을 지운다. 먼저 ‘삭제 직전’ 백업을 만들고 그 이름을 돌려준다 */
    suspend fun delete(name: String): String
}

/** 작업 이름 규칙: 앞뒤 공백 제거, 1~30자, 폴더 이름에 못 쓰는 문자 불가 */
object JobNames {
    private val bad = Regex("[\\\\/:*?\"<>|]")
    fun problem(raw: String, existing: Collection<String>): String? {
        val n = raw.trim()
        return when {
            n.isEmpty() -> "작업 이름을 입력하세요"
            n.length > 30 -> "작업 이름은 30자까지예요"
            bad.containsMatchIn(n) -> "이름에 \\ / : * ? \" < > | 는 쓸 수 없어요"
            existing.any { it.equals(n, ignoreCase = true) } -> "같은 이름의 작업이 이미 있어요"
            else -> null
        }
    }
}

class JobController(private val c: SurveyController, private val platform: JobPlatform, private val scope: CoroutineScope) {
    var current by mutableStateOf(platform.current); private set
    var list by mutableStateOf<List<JobInfo>>(emptyList()); private set
    var info by mutableStateOf<JobInfo?>(null); private set
    var newName by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var confirmDelete by mutableStateOf<JobInfo?>(null)

    fun refresh() {
        scope.launch {
            list = runCatching { platform.list() }.getOrDefault(emptyList())
            info = runCatching { platform.info(platform.current) }.getOrNull()
            current = platform.current
        }
    }

    fun openInfo() { error = null; notice = null; c.hub.open(ToolScreen.JOB_INFO); refresh() }
    fun openList() { error = null; notice = null; c.hub.open(ToolScreen.JOB_OPEN); refresh() }
    fun openNew() { error = null; notice = null; newName = ""; c.hub.open(ToolScreen.JOB_NEW); refresh() }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = (e as? JobException)?.message ?: "$label 중 문제가 생겼어요 (${e.javaClass.simpleName})"
            } finally { busy = false }
        }
    }

    fun create() {
        val prob = JobNames.problem(newName, list.map { it.name })
        if (prob != null) { error = prob; return }
        val n = newName.trim()
        run("새 작업") {
            platform.create(n)
            switched(n)
            notice = "‘$n’ 작업을 만들고 열었어요"
            c.hub.open(ToolScreen.JOB_INFO)
        }
    }

    fun open(name: String) {
        if (name == current) { notice = "이미 열려 있는 작업이에요"; return }
        run("작업 열기") { platform.open(name); switched(name); notice = "‘$name’ 작업을 열었어요" }
    }

    fun askDelete(j: JobInfo) { confirmDelete = j }
    fun delete() {
        val j = confirmDelete ?: return
        confirmDelete = null
        run("작업 삭제") {
            val backupName = platform.delete(j.name)
            if (j.name == current) switched(platform.current)
            notice = "‘${j.name}’ 작업을 지웠어요. 지우기 직전 상태는 백업 ($backupName) 에 있어요."
            refresh()
        }
    }

    private suspend fun switched(name: String) {
        current = name
        c.projectName = name
        c.reloadFromStore()
        list = runCatching { platform.list() }.getOrDefault(list)
        info = runCatching { platform.info(name) }.getOrNull()
    }
}
