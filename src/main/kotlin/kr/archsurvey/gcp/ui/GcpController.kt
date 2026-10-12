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
import java.time.ZoneId

/** 앱 설정 중 이 앱 것(측량 앱 SettingsStore 와 따로) */
interface GcpPrefs {
    var epochs: Int
    var operator: String
    var lastEpsg: Int
    var volumeKey: Boolean
    var lastSiteId: Long
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
    /** 사람이 볼 저장 위치(예: 내 파일 › Documents › iTwinGCP › 강릉유적) */
    fun whereLabel(site: Site): String
}

enum class Screen { SITES, BOARD, SITE_MAP, MEASURE, ID_CARD, SETTINGS }

/** 유적 목록 카드 한 장 */
data class SiteCard(val site: Site, val objCount: Int, val counts: Map<Stage, Int>, val lastAt: Long?)

/** 대상 보드 한 줄 */
data class ObjRow(val obj: Obj, val session: Session?, val stage: Stage, val measured: Int, val slots: Int, val xmlName: String?)

data class NewSiteForm(val name: String = "", val code: String = "", val epsg: Int = 5186, val error: String? = null)
data class NewObjForm(
    val kind: ObjKind, val featureNo: Int = 1, val subtype: String = "수혈", val letter: String = "A",
    val otherNo: Int = 1, val custom: String? = null, val label: String = "검출면", val slots: List<Int> = listOf(1, 2, 3, 4),
    val nearNote: String? = null,
) {
    fun name(): String = custom?.takeIf { it.isNotBlank() } ?: when (kind) {
        ObjKind.FEATURE -> AutoNumber.featureName(featureNo, subtype)
        ObjKind.SECTION -> AutoNumber.sectionName(letter)
        ObjKind.OTHER -> AutoNumber.otherName(otherNo)
    }
}
data class NewSessionForm(val label: String = "완토", val slots: List<Int>)
/** 표적 번호 바꾸기 창: [index] = 칸 위치(-1 = 새 칸 「+ 표적」) */
data class SlotEdit(val index: Int, val text: String, val error: String? = null)

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

    // ---- 보드 ----
    var filter by mutableStateOf<Stage?>(null)
    var kindTab by mutableStateOf<ObjKind?>(null)
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var newObj by mutableStateOf<NewObjForm?>(null)
    var handoffOpen by mutableStateOf(false)
    var handoffScope by mutableStateOf(HandoffScope.PENDING)

    // ---- 측점 ----
    var objId by mutableStateOf<Long?>(null); private set
    var sessionId by mutableStateOf<Long?>(null); private set
    var measuringTag by mutableStateOf<Int?>(null); private set
    var progress by mutableStateOf(0f); private set
    var newSession by mutableStateOf<NewSessionForm?>(null)
    var slotEdit by mutableStateOf<SlotEdit?>(null)
    var renaming by mutableStateOf<String?>(null)
    private var measureJob: Job? = null
    private val measurer = TagMeasurer(source)

    // ---- 설정 ----
    var advancedOpen by mutableStateOf(false)

    fun start() { reloadSites() }

    // ================= 이동 =================
    fun go(s: Screen) { if (screen != s) stack = stack + s }
    fun back(): Boolean {
        if (measuringTag != null) { cancelMeasure(); return true }
        when {
            ui.confirm != null -> { ui.answer(false); return true }
            ui.choice != null -> { ui.dismissChoice(); return true }
            slotEdit != null -> { slotEdit = null; return true }
            renaming != null -> { renaming = null; return true }
            newSession != null -> { newSession = null; return true }
            newObj != null -> { newObj = null; return true }
            newSite != null -> { newSite = null; return true }
            handoffOpen -> { handoffOpen = false; return true }
            connection?.open == true -> { connection?.leaveScreen(); return true }
            searchOpen -> { searchOpen = false; query = ""; return true }
        }
        if (stack.size > 1) { stack = stack.dropLast(1); if (screen == Screen.BOARD || screen == Screen.SITES) refresh(); return true }
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
    fun exitApp() { if (connection?.linkActive() == true) disconnectDevice(); onExitApp?.invoke() }

    // ================= 유적 =================
    fun reloadSites() = scope.launch {
        val list = withContext(io) { repo.sites().map { s -> val b = repo.book(s.id)!!; SiteCard(s, b.objs.size, b.counts(), b.lastActivity()) } }
        sites = list
    }

    fun openNewSite() { newSite = NewSiteForm(epsg = prefs.lastEpsg.takeIf { it in 5185..5188 } ?: 5186) }

    fun submitNewSite() {
        val f = newSite ?: return
        scope.launch {
            val r = runCatching { withContext(io) { repo.createSite(f.name, f.code, f.epsg) } }
            r.onSuccess { s -> prefs.lastEpsg = s.epsg; newSite = null; ui.ok("${s.name} (${s.code}) 만들었어요"); reloadSites(); openSite(s.id) }
            r.onFailure { newSite = f.copy(error = it.message ?: "만들지 못했어요") }
        }
    }

    fun openSite(id: Long) = scope.launch {
        val b = withContext(io) { repo.book(id) } ?: return@launch
        book = b; prefs.lastSiteId = id; filter = null; kindTab = null; query = ""; searchOpen = false
        stack = listOf(Screen.SITES, Screen.BOARD)
    }

    /** 장부 다시 읽기(저장·차수·이름 바꾼 뒤) */
    private suspend fun reloadBook() { val id = book?.site?.id ?: return; book = withContext(io) { repo.book(id) } }
    fun refresh() { scope.launch { reloadBook(); reloadSites() } }

    // ================= 보드 =================
    fun counts(): Map<Stage, Int> = book?.counts() ?: Stage.entries.associateWith { 0 }
    fun toggleFilter(s: Stage) { filter = if (filter == s) null else s }

    fun rows(): List<ObjRow> {
        val b = book ?: return emptyList()
        val q = query.trim().lowercase()
        return b.objs.map { o ->
            val s = b.current(o)
            val act = s?.let { b.active(it).size } ?: 0
            ObjRow(o, s, b.stageOf(o), act, s?.slots?.size ?: 0, s?.takeIf { it.xmlSavedAt != null }?.let { Names.xmlName(o, it, b.site.epsg) })
        }.filter { r -> filter == null || r.stage == filter }
            .filter { r -> kindTab == null || r.obj.kind == kindTab }
            .filter { r -> q.isEmpty() || r.obj.code.lowercase().contains(q) || r.obj.name.lowercase().contains(q) || r.obj.oldNames.any { it.lowercase().contains(q) } }
            .sortedWith(compareBy<ObjRow> { it.stage.ordinal }.thenBy { it.obj.code })
    }

    fun nextFeatureNo() = AutoNumber.nextFeatureNo(book?.objs?.filter { it.kind == ObjKind.FEATURE }?.map { it.name } ?: emptyList())
    fun nextLetter() = AutoNumber.nextSectionLetter(book?.objs?.filter { it.kind == ObjKind.SECTION }?.map { it.name } ?: emptyList())
    fun nextOtherNo() = (book?.objs?.count { it.kind == ObjKind.OTHER } ?: 0) + 1
    fun nextCode(): String { val b = book ?: return ""; return ObjIds.format(b.site.code, prefs.operator, ObjIds.nextSeq(b.objs.filter { it.operator == prefs.operator }.map { it.seq })) }

    /** 근처(20 m) 다른 대상이 쓰는 번호를 피한 표적 칸 + 안내 */
    private fun suggestFor(exceptObj: Long?): Pair<List<Int>, String?> {
        val b = book ?: return listOf(1, 2, 3, 4) to null
        val here = here() ?: return TargetChecks.suggestSlots(emptySet()) to null
        val others = b.othersInProgress(exceptObj)
        val near = TargetChecks.nearbyTags(here.first, here.second, others)
        if (near.isEmpty()) return TargetChecks.suggestSlots(emptySet()) to null
        val who = others.filter { (_, sh) -> sh.any { TargetChecks.horizontal(it.e, it.n, here.first, here.second) <= TargetChecks.CLASH_RADIUS_M } }.joinToString(", ") { it.first.code }
        val slots = TargetChecks.suggestSlots(near)
        return slots to "근처 20 m 안 $who 이(가) #${QrPayload.ranges(near.sorted())} 을 써요 → 이 대상은 #${QrPayload.ranges(slots)}"
    }

    /** 지금 수신기 위치(E, N). 위치가 없으면 null */
    fun here(): Pair<Double, Double>? {
        val g = source.gnss.value.gga ?: return null
        val la = g.lat ?: return null; val lo = g.lon ?: return null
        val b = book ?: return null
        val tm = kr.archsurvey.core.TmProjection.forward(la, lo, tmZone(b.site.epsg))
        return tm.easting to tm.northing
    }

    fun openNewObj(kind: ObjKind) {
        val (slots, note) = suggestFor(null)
        newObj = NewObjForm(kind, featureNo = nextFeatureNo(), letter = nextLetter(), otherNo = nextOtherNo(), slots = slots, nearNote = note)
    }

    fun submitNewObj() {
        val f = newObj ?: return
        val b = book ?: return
        val name = f.name().trim()
        if (name.isEmpty()) { ui.fail("대상 이름을 넣으세요"); return }
        if (b.objs.any { it.name == name }) { ui.fail("‘$name’ 이(가) 이미 있어요 · 번호를 바꾸세요"); return }
        scope.launch {
            val o = withContext(io) { repo.createObj(b.site.id, f.kind, name, prefs.operator, f.label, f.slots) }
            newObj = null
            reloadBook()
            ui.ok("${o.code} · ${o.name} 만들었어요")
            openObj(o.id)
        }
    }

    // ================= 측점 화면 =================
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
        go(Screen.MEASURE)
    }

    fun pickSession(id: Long) { sessionId = id }

    fun openNewSession() {
        val prev = sessionsOfObj().lastOrNull()
        val label = AutoNumber.SESSION_LABELS.getOrElse((AutoNumber.SESSION_LABELS.indexOf(prev?.label) + 1).coerceAtLeast(1)) { "기타" }
        newSession = NewSessionForm(label, prev?.slots?.takeIf { it.isNotEmpty() } ?: suggestFor(objId).first)
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

    // ---- 표적 칸 ----
    fun openAddSlot() {
        val s = session ?: return
        if (locked()) { ui.fail(LOCKED_MSG); return }
        val blocked = s.slots.toSet() + nearTags()
        slotEdit = SlotEdit(-1, TargetChecks.nextFree(blocked, (s.slots.maxOrNull() ?: 0) + 1).toString())
    }

    private fun nearTags(): Set<Int> { val b = book ?: return emptySet(); val h = here() ?: return emptySet(); return TargetChecks.nearbyTags(h.first, h.second, b.othersInProgress(objId)) }

    fun submitSlot() {
        val e = slotEdit ?: return
        val s = session ?: return
        val n = e.text.trim().toIntOrNull()
        if (n == null || !TargetChecks.validTag(n)) { slotEdit = e.copy(error = "0~${TargetChecks.MAX_TAG} 숫자를 넣으세요"); return }
        val others = s.slots.filterIndexed { i, _ -> i != e.index }
        if (n in others) { slotEdit = e.copy(error = "#$n 은(는) 이미 이 차수에 있어요"); return }
        val newSlots = if (e.index < 0) s.slots + n else s.slots.toMutableList().also { it[e.index] = n }
        scope.launch { withContext(io) { repo.setSlots(s.id, newSlots) }; slotEdit = null; reloadBook() }
    }

    // ---- 재기 ----
    fun onTargetTap(tag: Int) {
        if (measuringTag != null) return
        if (locked()) { ui.fail(LOCKED_MSG); return }
        if (shotOf(tag) != null) { ui.toast("Target #$tag 은(는) 쟀어요 · 다시 재려면 길게 누르세요"); return }
        gateThen { allow -> startMeasure(tag, allow) }
    }

    /** 길게 누름: 잰 번호 = 다시 재기 확인, 안 잰 번호 = 번호 바꾸기 */
    fun onTargetLong(index: Int) {
        if (measuringTag != null) return
        if (locked()) { ui.fail(LOCKED_MSG); return }
        val s = session ?: return
        val tag = s.slots.getOrNull(index) ?: return
        if (shotOf(tag) == null) { slotEdit = SlotEdit(index, tag.toString()); return }
        ui.ask(ConfirmRequest("Target #$tag 다시 잴까요?", "새 값이 앞 값과 2 cm 넘게 다르면 「표적이 움직였나요?」를 물어요. 앞 값은 기록으로 남아요.", "다시 재기") {
            gateThen { allow -> startMeasure(tag, allow) }
        })
    }

    /** 볼륨 아래 키(설정에서 켰을 때): 안 잰 첫 칸 */
    fun onVolumeKey(): Boolean {
        if (!prefs.volumeKey || screen != Screen.MEASURE || measuringTag != null || ui.confirm != null) return false
        val tag = session?.slots?.firstOrNull { shotOf(it) == null } ?: return false
        onTargetTap(tag); return true
    }

    private fun gateThen(go: (Boolean) -> Unit) {
        val r = StatusJudge.judge(source.gnss.value, source.link.value.state, source.clockMs())
        when (val g = MeasureGate.check(r)) {
            Gate.Ok -> go(false)
            Gate.AskNoSigma -> ui.ask(ConfirmRequest("정밀도(σ)를 받지 못해요", "FIX 는 맞지만 수신기가 GST(정밀도)를 보내지 않아요. 그래도 잴까요?\nR10 은 웹 설정에서 GST 출력을 켜면 이 창이 안 떠요.", "그래도 재기") { go(true) })
            is Gate.Blocked -> { ui.fail("못 재요 — ${g.reasonKo}"); onFeedback?.invoke(FeedbackEvent.SURVEY_FAIL) }
        }
    }

    fun cancelMeasure() { measureJob?.cancel(); measureJob = null; measuringTag = null; progress = 0f; ui.toast("측점을 취소했어요 · 저장하지 않았어요") }

    private fun startMeasure(tag: Int, allowNoSigma: Boolean) {
        val b = book ?: return
        val s = session ?: return
        val profileId = connection?.profile?.id ?: DeviceProfiles.R10_ID
        val pole = poleM(profileId)
        val apc = apcFor(profileId, pole)
        measuringTag = tag; progress = 0f
        measureJob = scope.launch {
            val r = measurer.measure(prefs.epochs, tmZone(b.site.epsg), apc, allowNoSigma) { progress = it }
            measuringTag = null; measureJob = null
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
            ui.ask(ConfirmRequest("번호가 겹쳐요", "${c.objCode} ${c.objName}(${c.distanceM.toInt()} m)도 Target #$tag 을(를) 써요. 한 사진에 같이 찍히면 iTwin 에서 헷갈려요.\n다른 실물 표적으로 바꾸는 걸 권해요.",
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
            if (out?.ok == true) ui.ok("Target #$tag 저장 · XML 저장됨" + if (left > 0) " · 남은 표적 $left" else "")
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

    fun shareXml() {
        val b = book ?: return; val o = obj ?: return; val s = session ?: return
        if (b.active(s).isEmpty()) { ui.fail("아직 잰 표적이 없어요"); return }
        if (!files.shareSession(b, o, s)) ui.fail("공유하지 못했어요")
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

    fun undoPhotoDone() {
        val s = session ?: return
        ui.ask(ConfirmRequest("사진 끝 취소", "이 차수를 다시 「사진 필요」로 돌릴까요?", "되돌리기") {
            scope.launch { withContext(io) { repo.setPhotoDone(s.id, false) }; reloadBook() }
        })
    }

    // ---- 이름 바꾸기 ----
    fun submitRename() {
        val n = renaming ?: return; val o = obj ?: return
        scope.launch {
            val r = runCatching { withContext(io) { repo.renameObj(o.id, n) } }
            r.onSuccess { renaming = null; reloadBook(); val s = session; if (s != null && book?.active(s)?.isNotEmpty() == true) writeFiles(s.id); ui.ok("이름을 바꿨어요 · ID ${o.code} 는 그대로") }
            r.onFailure { ui.fail(it.message ?: "바꾸지 못했어요") }
        }
    }

    // ================= PC로 넘기기 =================
    fun handoffCount(scope: HandoffScope): Int = book?.let { Handoff.count(it, scope, clock(), zone) } ?: 0
    fun handoffPlan(): HandoffPlan? = book?.let { Handoff.plan(it, handoffScope, clock(), zone, prefs.operator) }

    fun sendHandoff() {
        val p = handoffPlan() ?: return
        if (p.sessionIds.isEmpty()) { ui.fail("보낼 대상이 없어요 · 「${handoffScope.ko}」"); return }
        scope.launch {
            val ok = withContext(io) { runCatching { files.shareZip(p) }.getOrDefault(false) }
            if (!ok) { ui.fail("zip 을 만들지 못했어요"); return@launch }
            withContext(io) { repo.markDelivered(p.markDelivered) }
            handoffOpen = false; reloadBook()
            ui.ok("${p.zipName} · 대상 ${p.sessionIds.size}개 · 공유 창에서 보낼 곳을 고르세요")
        }
    }

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
    /** 폴 높이 화면 갱신용 */
    var poleTick by mutableStateOf(0); private set

    /** 지면 → 위상중심 = 폴 높이 + 그 기기 기본 기준점의 PCO(측량 앱 AntennaRecord 그대로) */
    fun apcFor(profileId: String, pole: Double): Double {
        val model = AntennaDb.forDevice(antennaDb, profileId) ?: return pole
        return AntennaRecord.of(model, AntennaConfig(pole, model.defaultRefPoint))?.apcHeightM ?: pole
    }

    fun deviceName(): String = connection?.profile?.displayName ?: "수신기"

    fun setEpochs(n: Int) { prefs.epochs = n; poleTick++ }
    fun setOperator(op: String) { prefs.operator = op; poleTick++ }
    fun setVolumeKey(on: Boolean) { prefs.volumeKey = on; poleTick++ }

    companion object {
        const val LOCKED_MSG = "앞 차수는 잠겨 있어요 · 보기만 돼요"
        val EPOCH_CHOICES = listOf(5, 10, 20)
        fun tmZone(epsg: Int): KoreanTmZone = KoreanTmZone.fromEpsg(epsg)
        fun epsgLabel(epsg: Int) = when (epsg) { 5185 -> "서부"; 5186 -> "중부"; 5187 -> "동부"; 5188 -> "동해"; else -> "" }
    }
}
