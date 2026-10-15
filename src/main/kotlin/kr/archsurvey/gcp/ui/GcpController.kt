package kr.archsurvey.gcp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.core.AntennaConfig
import kr.archsurvey.core.AntennaModel
import kr.archsurvey.core.AntennaRecord
import kr.archsurvey.core.DevicePoleDefaults
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.GnssSource
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.gcp.data.GcpRepo
import kr.archsurvey.gcp.measure.Gate
import kr.archsurvey.gcp.measure.MeasureGate
import kr.archsurvey.gcp.measure.MeasureResult
import kr.archsurvey.gcp.measure.Measured
import kr.archsurvey.gcp.measure.TagMeasurer
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.AppExit
import kr.archsurvey.ui.ConfirmRequest
import kr.archsurvey.ui.ConnectionController
import kr.archsurvey.ui.FeedbackHub
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.map.LocalXY
import java.time.ZoneId

/** 앱 설정 중 이 앱 것(측량 앱 SettingsStore 와 따로) */
interface GcpPrefs {
    var epochs: Int
    var lastEpsg: Int
    var volumeKey: Boolean
    var lastSiteId: Long
    /** 0.1.8: 평균 측정 중 체크포인트(MeasureCheckpoint.encode). 끝나거나 취소하면 "" */
    var measureCheckpoint: String
    /** 기기별 폴 높이(m). 없으면 null → R10 1.6 / SP60 1.8 */
    fun pole(profileId: String): Double?
    fun setPole(profileId: String, m: Double)
}

data class SaveOutcome(val ok: Boolean, val xmlName: String, val where: String, val error: String? = null)

/** 파일 쓰기·공유(Android 쪽). 시험은 가짜 */
interface GcpFilesPort {
    /** 그 차수 XML+TXT 와 유적 목록.csv 를 덮어쓴다 */
    fun saveSession(book: SiteBook, o: Obj, s: Session): SaveOutcome
    fun shareSession(book: SiteBook, o: Obj, s: Session): Boolean
    fun shareZip(plan: HandoffPlan): Boolean
    /** 0.1.17: 마지막 zip 을 둔 공용 폴더(화면 안내용). 모르면 null */
    val lastZipWhere: String? get() = null
    /** 사람이 볼 저장 위치(예: 내 파일 › Documents › iTwinGCP › 강릉유적) */
    fun whereLabel(site: Site): String
}

/** 0.1.5: 유적 → 폴더 목록(OBJECTS) → 폴더 안(OBJ) → 표적 재기(TARGETS). 지도(MAP)는 「지도에서 보기」로 */
enum class Screen { SITES, OBJECTS, OBJ, TARGETS, MAP, ID_CARD, SETTINGS }

/** 유적 목록 카드 한 장 */
data class SiteCard(val site: Site, val objCount: Int, val counts: Map<Stage, Int>, val lastAt: Long?)

/** 대상 목록 한 줄 */
data class ObjRow(val obj: Obj, val session: Session?, val stage: Stage, val measured: Int, val slots: Int)

/** 새 유적: 이름은 고르기(위치 주소 · 최근 · 날짜) + 지구 번호, 좌표계 타일. [typed] = 「직접 입력」 칸(그때만 자판) */
data class NewSiteForm(
    val suggestions: List<String> = emptyList(), val base: String? = null, val district: Int? = null,
    val epsg: Int = 5186, val locating: Boolean = false, val addressNote: String? = null, val typed: String? = null,
) {
    /** 0.1.17: 이름은 사용자가 쓴 것만(고르기·지구 번호 없음) */
    fun name(): String? = typed?.trim()?.takeIf { it.isNotEmpty() }
}

enum class NewObjStep { KIND, NUMBER }
data class NewObjForm(
    val step: NewObjStep = NewObjStep.KIND, val kind: ObjKind = ObjKind.DWELLING, val no: Int = 1, val used: Set<Int> = emptySet(),
    val label: String = SessionLabels.ALL.first(), val slots: List<Int> = listOf(1, 2, 3, 4), val nearNote: String? = null,
    /** 0.1.17: 사용자가 쓰는 폴더 이름(종류·호수 고르기 대신) */
    val title: String = "",
)
data class NewSessionForm(val label: String, val slots: List<Int>)

/** 숫자 고르기 창(번호판): 무엇을 고르는지 + 지금 값 + 쓸 수 없는 번호 */
sealed interface PickFor {
    data object AddSlot : PickFor
    data class ChangeSlot(val index: Int) : PickFor
    data object ObjNo : PickFor
    data object District : PickFor
}
data class NumPick(val forWhat: PickFor, val title: String, val value: Int, val used: Set<Int>, val near: Set<Int> = emptySet(), val max: Int = 999, val fresh: Boolean = true) {
    val taken: Boolean get() = value in used
    val ok: Boolean get() = value in 1..max && !taken
    /** 번호판 한 번 누름: 새로 열린 뒤 첫 숫자는 값을 바꿔 쓴다, 그 뒤는 뒤에 붙인다(세 자리까지) */
    fun digit(d: Int): NumPick { val v = if (fresh) d else (value * 10 + d); return copy(value = if (v > max) d else v, fresh = false) }
    fun backspace(): NumPick = copy(value = value / 10, fresh = false)
}

class GcpController(
    private val repo: GcpRepo,
    private val files: GcpFilesPort,
    val source: GnssSource,
    val prefs: GcpPrefs,
    private val antennaDb: Map<String, AntennaModel>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val clock: () -> Long = System::currentTimeMillis,
    /** 지금 위치 → 주소(V월드). 시험·오프라인은 null */
    private val addressLookup: suspend (Double, Double) -> AddressParts? = { _, _ -> null },
) {
    val ui = FeedbackHub()
    var connection: ConnectionController? = null
    var onFeedback: ((FeedbackEvent) -> Unit)? = null
    var onExitApp: (() -> Unit)? = null

    var stack by mutableStateOf(listOf(Screen.SITES)); private set
    val screen: Screen get() = stack.last()

    // ---- 유적 ----
    var sites by mutableStateOf<List<SiteCard>>(emptyList()); private set
    var newSite by mutableStateOf<NewSiteForm?>(null)
    var book by mutableStateOf<SiteBook?>(null); private set

    // ---- 창(시트) ----
    var objListOpen by mutableStateOf(false)
    var filter by mutableStateOf<Stage?>(null)
    var newObj by mutableStateOf<NewObjForm?>(null)
    var sessionSheetOpen by mutableStateOf(false)
    var newSession by mutableStateOf<NewSessionForm?>(null)
    var numPick by mutableStateOf<NumPick?>(null)
    var handoffOpen by mutableStateOf(false)
    var handoffScope by mutableStateOf(HandoffScope.PENDING)
    var menuOpen by mutableStateOf(false)
    var poleOpen by mutableStateOf(false)

    // ---- 측점 ----
    var objId by mutableStateOf<Long?>(null); private set
    var sessionId by mutableStateOf<Long?>(null); private set
    var measuringTag by mutableStateOf<Int?>(null); private set
    var progress by mutableStateOf(0f); private set
    private var measureJob: Job? = null
    private val measurer = TagMeasurer(source)

    // ---- 지도(측량 앱과 같은 평면: 원점 위경도 + 1 m 당 dp) ----
    var projection by mutableStateOf<LocalProjection?>(null); private set
    var center by mutableStateOf(LocalXY(0.0, 0.0)); private set
    var dpPerM by mutableStateOf(8.0); private set
    var follow by mutableStateOf(true)

    // ---- 설정 ----
    var advancedOpen by mutableStateOf(false)

    /** 0.1.5: 마지막에 쓴 유적이 있으면 그 폴더 목록부터(시안 「폴더 = 첫 화면」) */
    fun start() = scope.launch {
        reloadSites().join()
        val cp = MeasureCheckpoint.decode(prefs.measureCheckpoint)
        if (cp != null) { prefs.measureCheckpoint = ""; if (clock() - cp.startedAt < MeasureCheckpoint.MAX_AGE_MS && resumeInterrupted(cp)) return@launch }
        val last = prefs.lastSiteId
        if (last > 0 && sites.any { it.site.id == last } && screen == Screen.SITES) openSite(last)
    }

    /** 0.1.8 평균 측정 중 앱이 죽었으면 그 유적·대상·차수·표적 화면으로 돌아온다. 반쯤 모은 평균은 쓰지 않는다(폴이 움직였을 수 있음) → 그 표적을 다시 잰다 */
    var interrupted by mutableStateOf<MeasureCheckpoint?>(null); private set
    private suspend fun resumeInterrupted(cp: MeasureCheckpoint): Boolean {
        if (sites.none { it.site.id == cp.siteId }) return false
        openSite(cp.siteId).join()
        val b = book ?: return false
        if (b.objs.none { it.id == cp.objId } || b.sessions.none { it.id == cp.sessionId }) return true
        objId = cp.objId; sessionId = cp.sessionId
        stack = listOf(Screen.SITES, Screen.OBJECTS, Screen.OBJ, Screen.TARGETS)
        interrupted = cp
        ui.toast("표적 #${cp.tag} 평균이 ${(cp.progress * 100).toInt()}%에서 끊겼어요 · 다시 재세요")
        return true
    }

    // ================= 이동 =================
    fun go(s: Screen) { if (screen != s) stack = stack + s }
    fun back(): Boolean {
        if (measuringTag != null) { cancelMeasure(); return true }
        when {
            ui.confirm != null -> { ui.answer(false); return true }
            ui.choice != null -> { ui.dismissChoice(); return true }
            numPick != null -> { numPick = null; return true }
            newSession != null -> { newSession = null; return true }
            sessionSheetOpen -> { sessionSheetOpen = false; return true }
            newObj != null -> { newObj = null; return true }
            objListOpen -> { objListOpen = false; return true }
            newSite?.typed != null -> { newSite = newSite?.copy(typed = null); return true }
            newSite != null -> { newSite = null; return true }
            handoffOpen -> { handoffOpen = false; return true }
            poleOpen -> { poleOpen = false; return true }
            menuOpen -> { menuOpen = false; return true }
            connection?.open == true -> { connection?.leaveScreen(); return true }
        }
        if (stack.size > 1) { stack = stack.dropLast(1); if (screen == Screen.SITES) refresh(); return true }
        requestExit(); return true
    }

    // ================= 종료(측량 앱과 같은 두 단계) =================
    fun requestExit() {
        if (ui.confirm != null) return
        val cc = connection
        if (cc?.linkActive() == true) ui.ask(AppExit.deviceStep { disconnectDevice(); ui.ask(AppExit.appStep { exitApp() }) })
        else ui.ask(AppExit.appStep { exitApp() })
    }
    fun disconnectDevice() { runCatching { connection?.stopForExit() }.onFailure { kr.archsurvey.diag.CrashLog.record("수신기 연결 끊기", it) } }
    fun exitApp() { // 0.1.4: 연결이 있으면 끊기, 없어도 자동 재연결 끄기·앱 사이 잠금 놓기
        if (connection?.linkActive() == true) disconnectDevice() else runCatching { connection?.onStoppedOutside() }
        onExitApp?.invoke()
    }

    // ================= 유적 =================
    fun reloadSites() = scope.launch {
        val list = withContext(io) { repo.sites().map { s -> val b = repo.book(s.id)!!; SiteCard(s, b.objs.size, b.counts(), b.lastActivity()) } }
        sites = list
    }

    /** 새 유적 창: 최근 이름·날짜로 바로 채우고, 위치가 있으면 주소를 물어 위에 더한다. 좌표계도 위치(경도)로 고른다 */
    fun openNewSite() {
        val ll = latLon()
        val epsg = ll?.let { epsgForLon(it.second) } ?: (prefs.lastEpsg.takeIf { it in 5185..5188 } ?: 5186)
        // 0.1.17 사용자 정책: 이름은 사용자가 직접 씀(추천 목록·지구 고르기 없음). 좌표계만 위치로 미리
        newSite = NewSiteForm(emptyList(), null, null, epsg, locating = false, addressNote = null, typed = "")
    }

    fun submitNewSite() {
        val f = newSite ?: return
        val name = f.name() ?: run { ui.fail("유적 이름을 입력하세요"); return }
        scope.launch {
            val r = runCatching { withContext(io) { repo.createSite(name, f.epsg) } }
            r.onSuccess { s -> prefs.lastEpsg = s.epsg; newSite = null; ui.ok("${s.name} 만들었어요"); reloadSites(); openSite(s.id) }
            r.onFailure { ui.fail(it.message ?: "만들지 못했어요") }
        }
    }

    fun openSite(id: Long) = scope.launch {
        val b = withContext(io) { repo.book(id) } ?: return@launch
        book = b; prefs.lastSiteId = id; filter = null
        // 마지막에 바뀐 대상부터
        val last = b.objs.maxByOrNull { o -> b.sessionsOf(o).maxOfOrNull { it.changedAt } ?: o.createdAt }
        objId = last?.id; sessionId = last?.let { b.current(it)?.id }
        projection = null; anchorMap(); fitAll()
        stack = listOf(Screen.SITES, Screen.OBJECTS)
    }

    /** 장부 다시 읽기(저장·차수 바꾼 뒤) */
    private suspend fun reloadBook() { val id = book?.site?.id ?: return; book = withContext(io) { repo.book(id) } }
    fun refresh() { scope.launch { reloadBook(); reloadSites() } }

    // ================= 대상 =================
    fun counts(): Map<Stage, Int> = book?.counts() ?: Stage.entries.associateWith { 0 }
    fun toggleFilter(s: Stage) { filter = if (filter == s) null else s }

    fun rows(): List<ObjRow> {
        val b = book ?: return emptyList()
        return b.objs.map { o ->
            val s = b.current(o)
            ObjRow(o, s, b.stageOf(o), s?.let { b.active(it).size } ?: 0, s?.slots?.size ?: 0)
        }.filter { r -> filter == null || r.stage == filter }
            .sortedWith(compareBy<ObjRow> { it.stage == Stage.DONE }.thenBy(ObjOrder) { it.obj })
    }

    fun usedNos(kind: ObjKind): Set<Int> = book?.objs?.filter { it.kind == kind }?.map { it.no }?.toSet() ?: emptySet()

    /** 근처(20 m) 다른 대상이 쓰는 번호를 피한 표적 칸 + 안내 */
    private fun suggestFor(exceptObj: Long?): Pair<List<Int>, String?> {
        val b = book ?: return listOf(1, 2, 3, 4) to null
        val here = here() ?: return TargetChecks.suggestSlots(emptySet()) to null
        val others = b.othersInProgress(exceptObj)
        val near = TargetChecks.nearbyTags(here.first, here.second, others)
        if (near.isEmpty()) return TargetChecks.suggestSlots(emptySet()) to null
        val who = others.filter { (_, sh) -> sh.any { TargetChecks.horizontal(it.e, it.n, here.first, here.second) <= TargetChecks.CLASH_RADIUS_M } }.joinToString(", ") { it.first.name }
        val slots = TargetChecks.suggestSlots(near)
        return slots to "근처 20 m 안 $who 이(가) #${QrPayload.ranges(near.sorted())} 을 써요 → 이 대상은 #${QrPayload.ranges(slots)}"
    }

    fun latLon(): Pair<Double, Double>? { val g = source.gnss.value.gga ?: return null; val la = g.lat ?: return null; val lo = g.lon ?: return null; return la to lo }

    /** 지금 수신기 위치(E, N). 위치가 없으면 null */
    fun here(): Pair<Double, Double>? {
        val (la, lo) = latLon() ?: return null
        val b = book ?: return null
        val tm = kr.archsurvey.core.TmProjection.forward(la, lo, tmZone(b.site.epsg))
        return tm.easting to tm.northing
    }

    fun openNewObj() {
        val (slots, note) = suggestFor(null)
        objListOpen = false
        // 0.1.13: 차수 면은 지난 대상 것 그대로(없으면 첫 값)
        val lastLabel = book?.let { b -> b.objs.maxByOrNull { it.createdAt }?.let { o -> b.sessionsOf(o).firstOrNull()?.label } } ?: SessionLabels.ALL.first()
        // 0.1.17: 종류·호수 고르기 없음 — 이름 칸 하나(사용자가 씀)
        newObj = NewObjForm(step = NewObjStep.NUMBER, kind = ObjKind.OTHER, slots = slots, nearNote = note, label = lastLabel)
    }
    /** 마지막에 만든 대상 종류(없으면 주거지) */
    private fun lastKind(): ObjKind = book?.objs?.maxByOrNull { it.createdAt }?.kind ?: ObjKind.DWELLING

    /** 종류 타일을 누르면 바로 번호 단계(다음 번호가 미리 골라져 있음) */
    fun pickKind(k: ObjKind) { val f = newObj ?: return; val used = usedNos(k); newObj = f.copy(step = NewObjStep.NUMBER, kind = k, used = used, no = ObjNames.next(used)) }
    fun pickObjNo(n: Int) { val f = newObj ?: return; newObj = f.copy(no = n) }
    fun pickLabel(l: String) { val f = newObj ?: return; newObj = f.copy(label = l) }
    fun openObjNoPad() { val f = newObj ?: return; numPick = NumPick(PickFor.ObjNo, "${f.kind.ko} 호수", f.no, f.used) }

    fun submitNewObj() {
        val f = newObj ?: return
        val b = book ?: return
        // 화면은 이름 칸만 씀(빈칸이면 「만들기」가 꺼짐). 이름 없이 부르는 것은 옛 코드 경로(종류+호수) — 시험 호환용
        val named = f.title.isNotBlank()
        if (named) ObjNames.problem(f.title, b.objs.map { it.name })?.let { ui.fail(it); return }
        else if (f.no in f.used) { ui.fail("${ObjNames.label(f.kind, f.no)} 은(는) 이미 있어요 · 다음 번호 ${ObjNames.next(f.used)}"); return }
        scope.launch {
            val r = runCatching { withContext(io) { if (named) repo.createNamedObj(b.site.id, f.title, f.label, f.slots) else repo.createObj(b.site.id, f.kind, f.no, f.label, f.slots) } }
            r.onFailure { ui.fail(it.message ?: "만들지 못했어요"); return@launch }
            val o = r.getOrThrow()
            newObj = null
            reloadBook()
            ui.ok("${o.name} · 1차 ${f.label} 만들었어요")
            openObj(o.id)
            openTargets() // 0.1.13 새 대상 쉽게: 만들면 바로 표적 재기(예전: 대상 화면 → 「다음: 표적 재기」 한 번 더). 뒤로 = 대상 화면

        }
    }

    // ================= 측점 =================
    val obj: Obj? get() = book?.objs?.firstOrNull { it.id == objId }
    val session: Session? get() = book?.sessions?.firstOrNull { it.id == sessionId }
    fun sessionsOfObj(): List<Session> = obj?.let { o -> book?.sessionsOf(o) } ?: emptyList()
    /** 앞 차수는 잠김(보기만) — 마지막 차수만 잰다 */
    fun locked(): Boolean { val s = session ?: return true; return sessionsOfObj().lastOrNull()?.id != s.id }
    fun activeShots(): List<Shot> = session?.let { s -> book?.active(s) } ?: emptyList()
    fun shotOf(tag: Int): Shot? = activeShots().firstOrNull { it.tag == tag }
    fun stageNow(): Stage = session?.let { book?.stage(it) } ?: Stage.MEASURE

    fun openObj(id: Long) {
        objId = id
        sessionId = book?.objs?.firstOrNull { it.id == id }?.let { book?.current(it)?.id }
        objListOpen = false
        if (screen != Screen.MAP) stack = listOf(Screen.SITES, Screen.OBJECTS, Screen.OBJ)
        focusObj()
    }
    /** 폴더 안 → 표적 재기 */
    fun openTargets() { if (session == null) return; go(Screen.TARGETS) }
    /** 폴더 목록 → 유적 고르기 */
    fun openSitePicker() { stack = listOf(Screen.SITES); reloadSites()
    }

    fun pickSession(id: Long) { sessionId = id; sessionSheetOpen = false }

    fun openNewSession() {
        val prev = sessionsOfObj().lastOrNull()
        newSession = NewSessionForm(SessionLabels.next(prev?.label), prev?.slots?.takeIf { it.isNotEmpty() } ?: suggestFor(objId).first)
        sessionSheetOpen = false
    }

    fun submitNewSession() {
        val f = newSession ?: return
        val o = obj ?: return
        scope.launch {
            val s = withContext(io) { repo.createSession(o.id, f.label, f.slots) }
            newSession = null; reloadBook(); sessionId = s.id
            ui.ok("${s.no}차 · ${s.label} 시작 · 앞 차수 파일은 그대로 있어요")
        }
    }

    // ---- 표적 칸(번호판) ----
    private fun nearTags(): Set<Int> { val b = book ?: return emptySet(); val h = here() ?: return emptySet(); return TargetChecks.nearbyTags(h.first, h.second, b.othersInProgress(objId)) }

    fun openAddSlot() {
        val s = session ?: return
        if (locked()) { ui.fail(LOCKED_MSG); return }
        val near = nearTags()
        numPick = NumPick(PickFor.AddSlot, "표적 번호 (실물 표적에 적힌 번호)", TargetChecks.nextFree(s.slots.toSet() + near, (s.slots.maxOrNull() ?: 0) + 1), s.slots.toSet(), near, TargetChecks.MAX_TAG)
    }

    fun numDigit(d: Int) { numPick = numPick?.digit(d) }
    fun numBack() { numPick = numPick?.backspace() }
    fun numSet(v: Int) { numPick = numPick?.copy(value = v, fresh = true) }

    fun submitNum() {
        val p = numPick ?: return
        if (!p.ok) { ui.fail(if (p.taken) "#${p.value} 은(는) 이미 있어요" else "1~${p.max} 사이 번호를 고르세요"); return }
        when (val w = p.forWhat) {
            PickFor.ObjNo -> { pickObjNo(p.value); numPick = null }
            PickFor.District -> { newSite = newSite?.copy(district = p.value); numPick = null }
            PickFor.AddSlot, is PickFor.ChangeSlot -> {
                val s = session ?: return
                val idx = (w as? PickFor.ChangeSlot)?.index ?: -1
                val newSlots = if (idx < 0) s.slots + p.value else s.slots.toMutableList().also { it[idx] = p.value }
                scope.launch { withContext(io) { repo.setSlots(s.id, newSlots) }; numPick = null; reloadBook() }
            }
        }
    }

    // ---- 재기 ----
    fun onTargetTap(tag: Int) {
        if (measuringTag != null) { cancelMeasure(); return }
        if (locked()) { ui.fail(LOCKED_MSG); return }
        if (shotOf(tag) != null) { ui.toast("Target #$tag 은(는) 쟀어요 · 다시 재려면 길게 누르세요"); return }
        gateThen { allow -> startMeasure(tag, allow) }
    }

    /** 길게 누름: 잰 번호 = 다시 재기 확인, 안 잰 번호 = 번호 바꾸기(번호판) */
    fun onTargetLong(index: Int) {
        if (measuringTag != null) return
        if (locked()) { ui.fail(LOCKED_MSG); return }
        val s = session ?: return
        val tag = s.slots.getOrNull(index) ?: return
        if (shotOf(tag) == null) { numPick = NumPick(PickFor.ChangeSlot(index), "Target #$tag 번호 바꾸기", tag, s.slots.toSet() - tag, nearTags(), TargetChecks.MAX_TAG); return }
        ui.ask(ConfirmRequest("Target #$tag 다시 잴까요?", "새 값이 앞 값과 2 cm 넘게 다르면 「표적이 움직였나요?」를 물어요. 앞 값은 기록으로 남아요.", "다시 재기") {
            gateThen { allow -> startMeasure(tag, allow) }
        })
    }

    /** 큰 「측량하기」: 안 잰 첫 칸. 다 쟀으면 다음 빈 번호를 칸에 더하고 잰다 */
    fun nextTag(): Int? {
        val s = session ?: return null
        return s.slots.firstOrNull { shotOf(it) == null } ?: TargetChecks.nextFree(s.slots.toSet() + nearTags(), (s.slots.maxOrNull() ?: 0) + 1)
    }
    fun onMeasureButton() {
        if (measuringTag != null) { cancelMeasure(); return }
        if (obj == null) { openNewObj(); return }
        if (connection?.linkActive() == false) { openReceiver(); return }
        val tag = nextTag() ?: return
        onTargetTap(tag)
    }

    /** 볼륨 아래 키(설정에서 켰을 때) = 큰 측량 단추 */
    fun onVolumeKey(): Boolean {
        if (!prefs.volumeKey || (screen != Screen.MAP && screen != Screen.TARGETS) || measuringTag != null || ui.confirm != null || obj == null) return false
        onMeasureButton(); return true
    }

    private fun gateThen(go: (Boolean) -> Unit) {
        val r = StatusJudge.judge(source.gnss.value, source.link.value.state, source.clockMs())
        when (val g = MeasureGate.check(r)) {
            Gate.Ok -> go(false)
            Gate.AskNoSigma -> ui.ask(ConfirmRequest("정밀도 정보를 받지 못해요", "FIX 는 맞지만 수신기가 GST(정밀도)를 보내지 않아요. 그래도 잴까요?\nR10 은 웹 설정에서 GST 출력을 켜면 이 창이 안 떠요.", "그래도 재기") { go(true) })
            is Gate.Blocked -> { ui.fail("못 재요 — ${g.reasonKo}"); onFeedback?.invoke(FeedbackEvent.SURVEY_FAIL) }
        }
    }

    fun cancelMeasure() { measureJob?.cancel(); measureJob = null; measuringTag = null; progress = 0f; prefs.measureCheckpoint = ""; ui.toast("측점을 취소했어요 · 저장하지 않았어요") }

    private fun startMeasure(tag: Int, allowNoSigma: Boolean) {
        val b = book ?: return
        val s = session ?: return
        val profileId = connection?.profile?.id ?: DeviceProfiles.R10_ID
        val pole = poleM(profileId)
        val apc = apcFor(profileId, pole)
        measuringTag = tag; progress = 0f; interrupted = null
        val cp = MeasureCheckpoint(b.site.id, s.objId, s.id, tag, clock(), 0f)
        prefs.measureCheckpoint = cp.encode()
        var savedStep = 0
        measureJob = scope.launch {
            val r = measurer.measure(prefs.epochs, tmZone(b.site.epsg), apc, allowNoSigma) { p ->
                progress = p
                val step = (p * 10).toInt()   // 10% 마다 체크포인트
                if (step > savedStep) { savedStep = step; prefs.measureCheckpoint = cp.copy(progress = p).encode() }
            }
            measuringTag = null; measureJob = null; prefs.measureCheckpoint = ""
            when (r) {
                is MeasureResult.Aborted -> { ui.fail(r.reasonKo); onFeedback?.invoke(FeedbackEvent.SURVEY_FAIL) }
                is MeasureResult.Ok -> afterMeasure(s, tag, r.m, pole, apc, deviceName())
            }
        }
    }

    internal fun afterMeasure(s: Session, tag: Int, m: Measured, pole: Double, apc: Double, device: String) {
        val b = book ?: return
        val prev = b.active(s).firstOrNull { it.tag == tag }
        val moved = TargetChecks.moved(prev, m.e, m.n, m.h)
        if (prev != null && moved == null) { save(s, tag, m, pole, apc, device, note = "", resetPhoto = false); return }
        if (prev != null && moved != null) {
            val cm = String.format(java.util.Locale.US, "%.1f", moved * 100)
            ui.ask(ConfirmRequest("표적이 움직였나요?", "Target #$tag 새 값이 앞 값과 $cm cm 달라요.\n옮겼다면 새 값을 저장해요" + (if (s.photoDoneAt != null) " — 사진은 다시 찍어야 해요(「사진 필요」로 돌아가요)." else "."),
                "옮겼어요 · 새 값 저장", "실수 · 앞 값 그대로") { save(s, tag, m, pole, apc, device, note = "moved ${cm}cm", resetPhoto = s.photoDoneAt != null) })
            return
        }
        val clashes = TargetChecks.clashes(tag, m.e, m.n, b.othersInProgress(s.objId))
        if (clashes.isNotEmpty()) {
            val c = clashes.first()
            ui.ask(ConfirmRequest("번호가 겹쳐요", "${c.objName}(${c.distanceM.toInt()} m)도 Target #$tag 을(를) 써요. 한 사진에 같이 찍히면 iTwin 에서 헷갈려요.\n다른 실물 표적으로 바꾸는 걸 권해요.",
                "그래도 저장", "저장 안 함") { save(s, tag, m, pole, apc, device, note = "clash ${c.objCode}", resetPhoto = false) })
            return
        }
        save(s, tag, m, pole, apc, device, note = "", resetPhoto = false)
    }

    private fun save(s: Session, tag: Int, m: Measured, pole: Double, apc: Double, device: String, note: String, resetPhoto: Boolean) {
        scope.launch {
            withContext(io) { repo.saveShot(s.id, tag, m, pole, apc, device, note, resetPhoto) }
            reloadBook()
            val out = writeFiles(s.id)
            onFeedback?.invoke(FeedbackEvent.SURVEY_OK)
            val left = (session?.slots ?: emptyList()).count { shotOf(it) == null }
            if (out?.ok == true) ui.ok("#$tag 저장 · XML" + (if (left > 0) " · 다음 #${nextTag()}" else " · ${activeShots().size}개 · 더 재도 돼요"))   // 0.1.7: 화면 그대로 · 다음 표적 자동
            else ui.fail("Target #$tag 은(는) 장부에 저장됐지만 XML 을 쓰지 못했어요 — ${out?.error ?: "알 수 없음"}")
        }
    }

    /** 그 차수 XML·TXT·목록 쓰기(측점마다 덮어쓰기) */
    private suspend fun writeFiles(sessionId: Long): SaveOutcome? {
        val b = book ?: return null
        val s = b.sessions.firstOrNull { it.id == sessionId } ?: return null
        val o = b.objs.firstOrNull { it.id == s.objId } ?: return null
        val out = withContext(io) { runCatching { files.saveSession(b, o, s) }.getOrElse { SaveOutcome(false, Names.xmlName(o, s, b.site.epsg), "", it.message) } }
        if (out.ok) { withContext(io) { repo.markXmlSaved(s.id, clock()) }; reloadBook() }
        return out
    }

    // ---- 사진 ----
    fun openIdCard() { if (session == null) return; go(Screen.ID_CARD) }
    fun qr(): String { val b = book ?: return ""; val o = obj ?: return ""; val s = session ?: return ""; return QrPayload.of(o.code, s.no, b.site.epsg, s.slots) }

    fun markPhotoDone() {
        val s = session ?: return
        val n = activeShots().size
        if (!Pipeline.canMarkPhoto(n)) { ui.fail("표적 ${Pipeline.MIN_TARGETS}개부터 사진을 끝낼 수 있어요 (지금 $n)"); return }
        scope.launch {
            withContext(io) { repo.setPhotoDone(s.id, true) }; reloadBook()
            ui.ok("사진 끝 · 「전달 필요」로 바뀌었어요")
            if (screen == Screen.ID_CARD) back()
        }
    }

    // ================= PC로 넘기기 =================
    fun whereLabel(): String = book?.let { files.whereLabel(it.site) } ?: ""
    fun handoffCount(scope: HandoffScope): Int = book?.let { Handoff.count(it, scope, clock(), zone) } ?: 0
    fun handoffPlan(): HandoffPlan? = book?.let { Handoff.plan(it, handoffScope, clock(), zone) }

    /** 0.1.17: 마지막 zip 저장 위치(화면 한 줄) */
    var lastHandoffWhere by mutableStateOf<String?>(null)
    /** 0.1.17 한 번 누르기: 보낼 것(전달 필요)이 있으면 그것, 없으면 전체를 바로 zip → 공유 창. 고르기 창 없음 */
    fun oneTapHandoff() {
        menuOpen = false
        sendHandoff(if (handoffCount(HandoffScope.PENDING) > 0) HandoffScope.PENDING else HandoffScope.ALL)
    }

    fun sendHandoff(sc: HandoffScope = handoffScope) {
        handoffScope = sc
        val p = handoffPlan() ?: return
        if (p.sessionIds.isEmpty()) { ui.fail("보낼 대상이 없어요 · 「${sc.ko}」"); return }
        scope.launch {
            val ok = withContext(io) { runCatching { files.shareZip(p) }.getOrDefault(false) }
            if (!ok) { ui.fail("zip 을 만들지 못했어요"); return@launch }
            withContext(io) { repo.markDelivered(p.markDelivered) }
            handoffOpen = false; reloadBook()
            lastHandoffWhere = files.lastZipWhere
            ui.ok("저장됨 · " + (files.lastZipWhere ?: p.zipName) + " · 대상 ${p.sessionIds.size}개 · 공유 창에서 보낼 곳을 고르세요")
        }
    }

    // ================= 지도 =================
    /** 지도 원점: 유적의 첫 표적, 없으면 지금 위치 */
    fun anchorMap() {
        if (projection != null) return
        val first = book?.shots?.firstOrNull()
        val ll = first?.let { it.lat to it.lon } ?: latLon() ?: return
        projection = LocalProjection(ll.first, ll.second)
    }
    fun local(lat: Double, lon: Double): LocalXY? = projection?.toLocal(lat, lon)
    fun me(): LocalXY? { anchorMap(); val (la, lo) = latLon() ?: return null; return local(la, lo) }

    fun panBy(dx: Double, dy: Double) { follow = false; center = LocalXY(center.x + dx, center.y + dy) }
    fun scaleBy(k: Double, fx: Double = 0.0, fy: Double = 0.0) {
        val nd = (dpPerM * k).coerceIn(MIN_DP_PER_M, MAX_DP_PER_M); val r = dpPerM / nd
        // 손가락 아래 점이 그대로 있게
        center = LocalXY(center.x + fx * (1 - r), center.y + fy * (1 - r)); dpPerM = nd
    }
    fun zoomIn() = scaleBy(1.6)
    fun zoomOut() = scaleBy(1 / 1.6)
    fun recenter() { me()?.let { center = it }; follow = true }
    /** 지금 대상 표적(없으면 유적 전체) + 내 위치가 다 보이게 */
    fun focusObj() {
        anchorMap()
        val b = book ?: return; val s = session
        val pts = (s?.let { b.active(it) } ?: emptyList()).mapNotNull { local(it.lat, it.lon) }
        if (pts.isEmpty()) { fitAll(); return }
        fitTo(pts + listOfNotNull(me()), 12.0)
    }
    fun fitAll() {
        anchorMap()
        val b = book ?: return
        val pts = b.objs.flatMap { o -> b.current(o)?.let { b.active(it) } ?: emptyList() }.mapNotNull { local(it.lat, it.lon) } + listOfNotNull(me())
        if (pts.isEmpty()) { dpPerM = 8.0; return }
        fitTo(pts, 30.0)
    }
    private fun fitTo(pts: List<LocalXY>, minSpanM: Double) {
        follow = false
        val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }; val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
        center = LocalXY((x0 + x1) / 2, (y0 + y1) / 2)
        val span = maxOf(minSpanM, (x1 - x0) * 1.4, (y1 - y0) * 1.4)
        dpPerM = (MAP_FIT_DP / span).coerceIn(MIN_DP_PER_M, MAX_DP_PER_M)
    }
    /** 따라가기 켜져 있으면 위치가 바뀔 때마다 가운데로 */
    fun onFix() { if (projection == null) anchorMap(); if (follow) me()?.let { center = it } }

    // ================= 수신기·설정 =================
    fun openReceiver() { connection?.openScreen() }
    fun openVrs() { connection?.let { it.openScreen(); it.section = kr.archsurvey.ui.ConnSection.VRS } }

    fun poleM(profileId: String = connection?.profile?.id ?: DeviceProfiles.R10_ID): Double =
        prefs.pole(profileId) ?: (DevicePoleDefaults.forModel(AntennaDb.antennaIdForProfile(profileId)) ?: DevicePoleDefaults.R10_M)

    fun stepPole(deltaCm: Int) {
        val id = connection?.profile?.id ?: DeviceProfiles.R10_ID
        val v = (Math.round((poleM(id) + deltaCm / 100.0) * 10) / 10.0).coerceIn(0.0, 5.0)
        prefs.setPole(id, v); poleTick++
    }
    fun setPole(m: Double) { val id = connection?.profile?.id ?: DeviceProfiles.R10_ID; prefs.setPole(id, Math.round(m * 10) / 10.0); poleTick++ }
    /** 폴 높이 화면 갱신용 */
    var poleTick by mutableStateOf(0); private set

    /** 지면 → 위상중심 = 폴 높이 + 그 기기 기본 기준점의 PCO(측량 앱 AntennaRecord 그대로) */
    fun apcFor(profileId: String, pole: Double): Double {
        val model = AntennaDb.forDevice(antennaDb, profileId) ?: return pole
        return AntennaRecord.of(model, AntennaConfig(pole, model.defaultRefPoint))?.apcHeightM ?: pole
    }

    fun deviceName(): String = connection?.profile?.displayName ?: "수신기"

    fun setEpochs(n: Int) { prefs.epochs = n; poleTick++ }
    fun setVolumeKey(on: Boolean) { prefs.volumeKey = on; poleTick++ }

    companion object {
        const val LOCKED_MSG = "앞 차수는 잠겨 있어요 · 보기만 돼요"
        val EPOCH_CHOICES = listOf(5, 10, 20)
        const val MIN_DP_PER_M = 0.05; const val MAX_DP_PER_M = 400.0
        /** 맞춤 보기 때 화면 짧은 변(대략 dp) */
        const val MAP_FIT_DP = 340.0
        val EPSGS = listOf(5185, 5186, 5187, 5188)
        /** 경도로 원점 고르기: 서부 ~126° · 중부 126–128° · 동부 128–130° · 동해 130°~ */
        fun epsgForLon(lon: Double) = when { lon < 126.0 -> 5185; lon < 128.0 -> 5186; lon < 130.0 -> 5187; else -> 5188 }
        fun tmZone(epsg: Int): KoreanTmZone = KoreanTmZone.fromEpsg(epsg)
        fun epsgLabel(epsg: Int) = when (epsg) { 5185 -> "서부"; 5186 -> "중부"; 5187 -> "동부"; 5188 -> "동해"; else -> "" }
    }
}

/** 0.1.8 평균 측정 체크포인트(프로세스가 죽어도 남는 한 줄) */
data class MeasureCheckpoint(val siteId: Long, val objId: Long, val sessionId: Long, val tag: Int, val startedAt: Long, val progress: Float) {
    fun encode() = "v1|$siteId|$objId|$sessionId|$tag|$startedAt|$progress"
    companion object {
        const val MAX_AGE_MS = 30 * 60_000L
        fun decode(s: String): MeasureCheckpoint? {
            val p = s.split('|'); if (p.size != 7 || p[0] != "v1") return null
            return runCatching { MeasureCheckpoint(p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toInt(), p[5].toLong(), p[6].toFloat()) }.getOrNull()
        }
    }
}
