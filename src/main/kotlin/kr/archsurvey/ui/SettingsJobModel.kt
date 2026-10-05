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

    fun setEpochs(n: Int) { val v = n.coerceIn(1, 30); c.epochs = v; p.epochs = v; c.jobConfigChanged(); c.ui.toast("측정 횟수 ${v}회로 저장됨 · 다음 측점부터") }
    fun openCrash() { crashText = p.crashLog(); showCrash = true }
    fun shareCrash() = p.shareCrashLog()
    fun clearCrash() { p.clearCrashLog(); crashText = ""; c.ui.toast("오류 기록을 지웠어요") }

    /** 0.1.12: 작업의 좌표계(지역). 다른 좌표계로 저장된 점이 있으면 바꾸지 않는다(한 작업에 좌표계가 섞이지 않게) */
    fun setZone(z: KoreanTmZone): Boolean {
        if (z == c.zone) return true
        c.zoneChangeProblem(z)?.let { c.ui.toast(it); return false }
        c.zone = z; p.zone = z; c.jobConfigChanged(); c.ui.toast("좌표계 저장됨 · ${z.labelKo} · 이 작업에 적용")
        return true
    }
    /** 작업을 바꿔 좌표계가 바뀌었을 때 전역 기본값도 맞춤(다른 화면이 읽는 값) */
    fun syncZone() { p.zone = c.zone; p.epochs = c.epochs }
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

class JobController(
    private val c: SurveyController, private val platform: JobPlatform, private val scope: CoroutineScope,
    /** 0.1.12 작업별 설정 저장소 */
    private val configs: JobConfigPlatform = MemoryJobConfigPlatform(),
) {
    // 0.1.12 작업 정보 확인 / 새 작업 창(SmartTopo 와 같은 순서: 작업명·좌표계·지역·배경맵·Geoid)
    var formZone by mutableStateOf(c.zone)
    var formGeoid by mutableStateOf(c.geoid)
    var formMap by mutableStateOf(c.mapBase)

    private fun loadForm() { formZone = c.zone; formGeoid = c.geoid; formMap = c.mapBase }

    /** 「자동」: 수신기 위치 경도로 지역 고르기. 위치가 없으면 안내만 */
    fun autoZone() {
        val z = ZoneAuto.fromLon(c.gv.lon)
        if (z == null) { c.ui.toast("수신기 위치가 없어 자동으로 고를 수 없어요. 직접 고르세요."); return }
        formZone = z; c.ui.toast("현재 위치(경도 ${String.format(java.util.Locale.US, "%.2f", c.gv.lon)}) → ${z.labelKo}")
    }

    /** 작업 정보 확인 → 적용. 좌표계는 다른 좌표계로 저장된 점이 있으면 바꾸지 않는다 */
    fun applyInfo() {
        error = null
        if (formZone != c.zone) {
            val prob = c.zoneChangeProblem(formZone)
            if (prob != null) { error = prob; formZone = c.zone; return }
            c.settingsState?.setZone(formZone) ?: run { c.zone = formZone }
        }
        c.geoid = formGeoid; c.mapBase = formMap
        c.jobConfigChanged()
        notice = "작업 설정을 적용했어요 · ${formZone.labelKo} · Geoid ${formGeoid.shortKo} · 배경맵 ${formMap.labelKo}"
    }

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

    fun openInfo() { error = null; notice = null; loadForm(); c.hub.open(ToolScreen.JOB_INFO); refresh() }
    fun openList() { error = null; notice = null; c.hub.open(ToolScreen.JOB_OPEN); refresh() }
    /** 새 작업: 이름 + 좌표계·지역·배경맵·Geoid 를 한 화면에서. 기본값 = 지금 작업 값(마지막 사용값) */
    fun openNew() { error = null; notice = null; newName = ""; loadForm(); c.hub.open(ToolScreen.JOB_NEW); refresh() }

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
        val cfg = c.jobConfig().copy(epsg = formZone.epsg, geoid = formGeoid, mapBase = formMap)
        run("새 작업") {
            platform.create(n)
            configs.save(n, cfg)
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
            configs.delete(j.name)
            if (j.name == current) switched(platform.current)
            notice = "‘${j.name}’ 작업을 지웠어요. 지우기 직전 상태는 백업 ($backupName) 에 있어요."
            refresh()
        }
    }

    private suspend fun switched(name: String) {
        current = name
        c.projectName = name
        // 0.1.12: 그 작업의 설정(좌표계·지오이드·배경맵·안테나)으로 화면을 맞춘다. 저장된 게 없으면 지금 값을 그 작업 것으로 저장
        val cfg = configs.load(name)
        if (cfg != null) { c.applyJobConfig(cfg); c.settingsState?.syncZone() } else configs.save(name, c.jobConfig())
        loadForm()
        c.reloadFromStore()
        list = runCatching { platform.list() }.getOrDefault(list)
        info = runCatching { platform.info(name) }.getOrNull()
    }
}
