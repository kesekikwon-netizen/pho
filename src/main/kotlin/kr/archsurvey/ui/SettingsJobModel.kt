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
    /** 0.1.26 엄격 모드: FIX만 저장. 기본 false */
    var requireFixed: Boolean get() = false; set(_) {}
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
    /** 0.1.26 엄격 품질 게이트(FIX만 저장). 기본 끔 */
    var requireFixed by mutableStateOf(p.requireFixed); private set

    var crashText by mutableStateOf(""); private set
    var showCrash by mutableStateOf(false)

    /**
     * 폴 위치만 Pref 에서 바로 싣는다. 좌표계·측정 횟수는 작업 JobConfig 가 진실원 —
     * ViewModel 이 applyJobConfig 로 맞춘 뒤 [syncGlobals] 로 Pref 미러를 갱신한다.
     * (예전처럼 여기서 p.zone 을 덮어쓰면 작업 설정과 어긋날 수 있음)
     */
    init { c.poleOnRight = p.poleRight }

    fun setEpochs(n: Int) { val v = n.coerceIn(1, 30); c.epochs = v; c.jobConfigChanged(); syncGlobals(); c.ui.ok("측정 횟수 ${v}회로 저장됨 · 다음 측점부터") }
    fun openCrash() { crashText = p.crashLog(); showCrash = true }
    fun shareCrash() = p.shareCrashLog()
    fun clearCrash() { p.clearCrashLog(); crashText = ""; c.ui.ok("오류 기록을 지웠어요") }

    /** 0.1.12: 작업의 좌표계(지역). 다른 좌표계로 저장된 점이 있으면 바꾸지 않는다(한 작업에 좌표계가 섞이지 않게) */
    fun setZone(z: KoreanTmZone, toast: Boolean = true): Boolean {
        if (z == c.zone) return true
        c.zoneChangeProblem(z)?.let { if (toast) c.ui.fail(it); return false }
        c.zone = z; c.jobConfigChanged(); syncGlobals()
        if (toast) c.ui.ok("좌표계 저장됨 · ${z.labelKo} · 이 작업에 적용")
        return true
    }
    /** 컨트롤러(작업) 값 → Pref 미러. 설정·작업정보·헤더가 같은 값을 보게 */
    fun syncGlobals() { p.zone = c.zone; p.epochs = c.epochs }
    /** @deprecated 이름만 호환 — [syncGlobals] */
    fun syncZone() = syncGlobals()
    fun setPoleRight(v: Boolean) { c.poleOnRight = v; p.poleRight = v; c.ui.ok("폴 쪽 표시: " + if (v) "오른쪽" else "왼쪽") }
    fun changeSound(v: Boolean) { sound = v; p.sound = v; c.ui.ok("소리: " + if (v) "켬" else "끔") }
    fun changeVibration(v: Boolean) { vibration = v; p.vibration = v; c.ui.ok("진동(버튼 햅틱 포함): " + if (v) "켬" else "끔") }
    fun changeKeepScreenOn(v: Boolean) { keepScreenOn = v; p.keepScreenOn = v; c.ui.ok("화면 켜두기: " + if (v) "켬" else "끔") }
    fun changeRequireFixed(v: Boolean) {
        requireFixed = v; p.requireFixed = v
        c.ui.ok(if (v) "엄격 모드 켬 · FIX(고정)만 저장" else "엄격 모드 끔 · 주의는 경고 후 저장 가능")
    }
}

data class JobInfo(val name: String, val points: Int, val photos: Int, val voices: Int, val firstAtMs: Long?, val lastAtMs: Long?, val fakePoints: Int = 0,
    /** 0.1.38 작업 폴더(화면용, 예: Documents/ExcavationSurvey/광령리 3지구). 모르면 null */
    val folder: String? = null)

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
    /** 0.1.38 새 작업 = 새 폴더. [group] = 상위 폴더(유적 등, 선택) */
    suspend fun create(name: String, group: String?) = create(name)
    /** 0.1.38 지금까지 쓴 상위 폴더 */
    fun groups(): List<String> = emptyList()
    /** 0.1.38 이 작업 폴더(화면용). 모르면 null */
    fun folderOf(name: String): String? = null
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
        if (z == null) { c.ui.fail("수신기 위치가 없어 자동으로 고를 수 없어요. 직접 고르세요."); return }
        formZone = z; c.ui.toast("현재 위치(경도 ${String.format(java.util.Locale.US, "%.2f", c.gv.lon)}) → ${z.labelKo}")
    }

    /**
     * 작업 정보 확인 → 적용.
     * 저장 후 **측량 화면**으로 이동(SmartTopo「적용」과 같은 흐름). 「나가기」는 [ToolsHub.back]으로 뒤로.
     * 좌표계는 다른 좌표계로 저장된 점이 있으면 바꾸지 않는다.
     */
    fun applyInfo() {
        error = null
        if (formZone != c.zone) {
            val prob = c.zoneChangeProblem(formZone)
            if (prob != null) { error = prob; formZone = c.zone; c.ui.fail("적용하지 못했어요 · $prob"); return }
            val ok = c.settingsState?.setZone(formZone, toast = false) ?: run { c.zone = formZone; true }
            if (ok == false) { formZone = c.zone; return }
        }
        c.geoid = formGeoid; c.mapBase = formMap
        c.jobConfigChanged()
        c.settingsState?.syncGlobals()
        loadForm() // 폼도 컨트롤러와 동일하게
        val msg = "작업 설정을 적용했어요 · ${formZone.labelKo} · Geoid ${formGeoid.shortKo} · 배경맵 ${formMap.labelKo}"
        notice = msg
        c.ui.ok(msg)
        // 0.1.14: hub 만 닫으면 환경설정 목록(settingsMenuOpen) 등이 남아 측량 화면이 안 보였다 → 전부 닫고 측량으로
        c.goToSurvey()
    }

    var current by mutableStateOf(platform.current); private set
    var list by mutableStateOf<List<JobInfo>>(emptyList()); private set
    var info by mutableStateOf<JobInfo?>(null); private set
    private var newNameState by mutableStateOf("")
    /** 직접 쓴 작업명. 비어 있지 않게 넣으면(키보드 「직접 입력」 · 코드) 고르기 이름 대신 이것을 씀 */
    var newName: String
        get() = newNameState
        set(v) { newNameState = v; if (v.isNotBlank()) nameManual = true }
    /** 0.1.38 「새 폴더」: 상위 폴더(유적 이름 등). 비우면 ExcavationSurvey 바로 아래 */
    var newGroup by mutableStateOf("")
    var groups by mutableStateOf<List<String>>(emptyList()); private set
    // ---- 0.1.39 새 작업: 키보드 없이 고르기만(유적 칩 · 단계 타일 · 지구 번호 · 날짜 자동) ----
    /** 유적(= 상위 폴더). 빈칸 = 없음 */
    var newSite by mutableStateOf("")
    var newStage by mutableStateOf(kr.archsurvey.job.SurveyStage.FULL)
    var newNo by mutableStateOf(1)
    /** 「직접 입력」(키보드) 으로 이름을 쓰는 중. 기본 false */
    var nameManual by mutableStateOf(false)
    /** 「새 유적 · 직접 입력」 칸을 연 상태 */
    var newGroupTyping by mutableStateOf(false)
    /** 지금 위치로 고른 추천 좌표계(위치가 없으면 null) */
    var recommendedZone by mutableStateOf<kr.archsurvey.core.KoreanTmZone?>(null); private set
    /** 「자세히」(높이·배경지도) 펼침 */
    var newDetailsOpen by mutableStateOf(false)
    /** 시험용 날짜 */
    var today: () -> java.util.Date = { java.util.Date() }
    val mmdd: String get() = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(today())
    /** 고른 것으로 만든 이름(겹치면 (2)) */
    val builtName: String get() = kr.archsurvey.job.NewJobName.unique(
        kr.archsurvey.job.NewJobName.build(newSite.ifBlank { null }, newStage, newNo, mmdd), (list.map { it.name } + current))
    /** 실제로 만들 이름 */
    val effectiveName: String get() = newName.trim() /* 0.1.55: 사용자가 쓴 이름만(자동 이름 없음) */
    /** 유적 칩: 지금 작업의 유적 + 최근 유적(위치→행정동 이름은 이 앱에 주소 API 가 없어 아직 못 함 — 마지막 쓴 것으로 대신) */
    val siteChips: List<String> get() = (listOfNotNull(currentSite) + groups).distinct().take(6)
    val currentSite: String? get() = currentFolder?.removePrefix(kr.archsurvey.job.JobFolders.PUBLIC_PARENT + "/")?.let { kr.archsurvey.job.JobFolders.split(it).first }
    fun pickSite(sv: String?) { newGroupTyping = false; newSite = sv.orEmpty(); newNo = kr.archsurvey.job.NewJobName.nextNo(newSite, newStage, list.map { it.name }) }
    fun pickStage(st: kr.archsurvey.job.SurveyStage) { newStage = st; newNo = kr.archsurvey.job.NewJobName.nextNo(newSite, st, list.map { it.name }) }
    private var noTouched = false
    /** 사람이 번호를 고름(목록이 늦게 와도 덮어쓰지 않음) */
    fun setNo(n: Int) { noTouched = true; newNo = n.coerceIn(1, kr.archsurvey.job.NewJobName.MAX_NO) }
    fun startNewGroup() { newGroupTyping = true; newSite = "" }
    /** 지금 작업 폴더(홈 카드·작업 정보) */
    val currentFolder: String? get() = runCatching { platform.folderOf(current) }.getOrNull()
    /** 새 작업 창 미리보기: 만들면 생길 폴더 */
    val newFolderPreview: String get() = kr.archsurvey.job.JobFolders.display(kr.archsurvey.job.JobFolders.relPath(
        newSite.ifBlank { newGroup }.trim().takeIf { it.isNotEmpty() }?.let { kr.archsurvey.job.JobFolders.sanitize(it, "폴더") },
        kr.archsurvey.job.JobFolders.sanitize(effectiveName.ifBlank { "작업명" })))
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var confirmDelete by mutableStateOf<JobInfo?>(null)

    fun refresh() {
        scope.launch {
            list = runCatching { platform.list() }.getOrDefault(emptyList())
            info = runCatching { platform.info(platform.current) }.getOrNull()
            current = platform.current
            groups = runCatching { platform.groups() }.getOrDefault(emptyList())
        }
    }

    fun openInfo() { error = null; notice = null; loadForm(); c.hub.open(ToolScreen.JOB_INFO); refresh() }
    fun openList() { error = null; notice = null; c.hub.open(ToolScreen.JOB_OPEN); refresh() }
    /** 새 작업: 이름 + 좌표계·지역·배경맵·Geoid 를 한 화면에서. 기본값 = 지금 작업 값(마지막 사용값) */
    /** 0.1.14 목업 10 「지역 자동 설정」 토글: 켜면 [autoZone] 으로 지금 위치 경도에서 원점 선택. 위치가 없으면 안내 후 꺼짐 */
    var autoZoneOn by mutableStateOf(false)
    fun changeAutoZone(on: Boolean) {
        autoZoneOn = on && c.gv.lon != null
        if (on) autoZone()
    }
    fun openNew() {
        error = null; notice = null; newName = ""; newGroup = ""; nameManual = false; noTouched = false; newGroupTyping = false; newDetailsOpen = false
        newSite = currentSite.orEmpty() /* 0.1.55: 지난 폴더 이름을 칸에 미리 적어 둠(지우거나 고칠 수 있음) */
        newStage = kr.archsurvey.job.SurveyStage.inName(current) ?: kr.archsurvey.job.SurveyStage.FULL
        newNo = kr.archsurvey.job.NewJobName.nextNo(newSite, newStage, list.map { it.name } + current)
        recommendedZone = ZoneAuto.fromLon(c.gv.lon)
        openNewForm()
    }
    private fun openNewForm() {
        loadForm() /* 0.1.51: 높이(Geoid)·배경지도·좌표계는 지난 작업 값 그대로(예전 0.1.35: 높이만 수신기 값으로 되돌림) */
        recommendedZone?.let { formZone = it } // 0.1.39 추천 좌표계 미리 선택(위치가 없으면 지금 작업 것)
        c.hub.open(ToolScreen.JOB_NEW)
        scope.launch {
            list = runCatching { platform.list() }.getOrDefault(emptyList())
            groups = runCatching { platform.groups() }.getOrDefault(emptyList())
            current = platform.current
            if (!noTouched) newNo = kr.archsurvey.job.NewJobName.nextNo(newSite, newStage, list.map { it.name })
        }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = (e as? JobException)?.message ?: UserError.of("job:$label", e, "$label 중 문제가 생겼어요", "다시 시도하세요")
                c.ui.fail("$label 실패 · $error")
            } finally { busy = false }
            if (error == null) notice?.let { c.ui.ok(it) }
        }
    }

    fun create() {
        val name = effectiveName
        val prob = JobNames.problem(name, list.map { it.name })
        if (prob != null) { error = prob; c.ui.fail(prob); return }
        val n = name.trim()
        val base = c.jobConfig()
        // 0.1.29 UX-0129-1: 새 작업은 사용자가 정한 폴 높이가 아니면 기기 기본값(R10 1.6 · SP60 1.8)으로 시작
        val pole = if (base.poleUserSet) base.poleM else kr.archsurvey.core.DevicePoleDefaults.forModel(base.antennaModelId) ?: base.poleM
        val cfg = base.copy(epsg = formZone.epsg, geoid = formGeoid, mapBase = formMap, poleM = pole)
        val g = (newSite.ifBlank { newGroup }).trim().takeIf { it.isNotEmpty() } // 0.1.39 유적 = 상위 폴더
        run("새 작업") {
            platform.create(n, g)
            configs.save(n, cfg)
            switched(n)
            notice = "‘$n’ 작업을 만들고 열었어요" + (runCatching { platform.folderOf(n) }.getOrNull()?.let { " · 폴더 $it" } ?: "")
            // 0.1.39: 만들면 바로 측량 지도로(예전: 작업 정보 화면 → 「적용」 한 번 더)
            c.goToSurvey()
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
        c.dig.restoreSelection()
        list = runCatching { platform.list() }.getOrDefault(list)
        info = runCatching { platform.info(name) }.getOrNull()
    }
}
