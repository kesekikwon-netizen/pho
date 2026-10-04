package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.archsurvey.arch.ArchTypes
import kr.archsurvey.arch.CellKind
import kr.archsurvey.arch.DuplicateCheck
import kr.archsurvey.arch.GridMath
import kr.archsurvey.core.*
import kr.archsurvey.device.GnssSource
import kr.archsurvey.map.*
import kotlin.math.atan2
import kotlin.math.hypot

data class GnssView(
    val status: StatusResult = StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NOT_CONNECTED, null),
    val quality: FixQuality = FixQuality.NONE,
    val satellites: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    /** 수직 정확도 σ(m). GST 가 없으면 null */
    val sigmaVM: Double? = null,
    /** 수평 위치 정밀도 감소율. 없으면 null */
    val hdop: Double? = null,
    /** 보이는 위성 수(GSV). 없으면 null */
    val satsInView: Int? = null,
)

/** 화면이 보는 측점. 저장소([PointStore])가 있으면 디스크에도 남는다. */
data class SavedPoint(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val northing: Double,
    val easting: Double,
    val crsEpsg: Int,
    /** 지면 높이(타원체고) = 수신기 타원체고 − 안테나 위상중심 높이. 계산 불가면 null */
    val groundHeightM: Double?,
    val sigmaHM: Double?,
    val status: SurveyStatus,
    val cautionFlag: Boolean,
    val epochs: Int,
    val antenna: AntennaRecord,
    val fixQuality: Int = 0,
    val satellites: Int = 0,
    val measuredAtMs: Long = 0L,
    val rowId: Long = 0L,
    val memo: String = "",
    /** 3-D 좌표·표고: 안테나 위상중심(APC) 기준 값과 지오이드. 지면값은 APC 값 − 안테나 높이 */
    val apcEllipsoidalM: Double? = null,
    val apcMslM: Double? = null,
    val geoidSepM: Double? = null,
    val groundMslM: Double? = null,
    val hdop: Double? = null,
    val sigmaE: Double? = null,
    val sigmaN: Double? = null,
    val sigmaU: Double? = null,
    val correctionAgeSec: Double? = null,
    val baseStationId: String? = null,
    /** 고고학: 발굴 구역·그리드·칸 이름(예: "A-3", "A-3 / B-3 경계 근처", "그리드 밖")·칸 안 위치(m) */
    val areaId: Long? = null,
    val gridId: Long? = null,
    val cellName: String = "",
    val cellU: Double? = null,
    val cellV: Double? = null,
)

enum class DupChoice { RESURVEY, NEW_POINT, DISCARD }

/** 중복 점 경고: 방금 잰 값 [fresh] 가 기존 점 [existing] 에서 [distM] m 안에 있다 */
data class DupPrompt(val fresh: SavedPoint, val existing: SavedPoint, val distM: Double, val worst: SurveyStatus)

/** 측점 저장소. Android 의존 없음(시험용 가짜 구현 가능). 되돌리기는 숨김(소프트 삭제)이라 번호를 다시 쓰지 않는다. */
interface PointStore {
    suspend fun loadAll(): List<SavedPoint>
    /** 지금까지 쓴 가장 큰 번호(숨긴 점 포함). 없으면 0 */
    suspend fun maxSeq(): Int
    suspend fun save(p: SavedPoint): Long
    suspend fun hideLast()
    /** 위치 종류·메모 수정 (좌표는 바꾸지 않는다) */
    suspend fun updateMeta(rowId: Long, type: String, memo: String) {}
    /** 재측량: 점 번호·종류·메모·사진은 두고 측정값만 새 값으로 바꾸며, 이전 측정값은 이력으로 남긴다 */
    suspend fun resurvey(rowId: Long, updated: SavedPoint) {}
    /** 방금 한 재측량을 되돌린다: 가장 최근 이력을 지우고 [old] 값으로 복원 */
    suspend fun undoResurvey(rowId: Long, old: SavedPoint) {}
}

/**
 * 측량 화면의 상태·동작. [GnssSource] 하나만 본다(가짜/실제 교체 가능).
 * Android 의존 없음 → JVM 단위 테스트 가능.
 */
class SurveyController(
    val source: GnssSource,
    val antennaDb: Map<String, AntennaModel>,
    private val scope: CoroutineScope,
    projection: LocalProjection = LocalProjection(36.5, 127.0),
    /** true = 처음 받은 실제 위치(또는 마지막 점)를 지도 그림의 기준으로 삼는다 */
    private val anchorOnFirstFix: Boolean = false,
    zone: KoreanTmZone = KoreanTmZone.CENTRAL,
    private val epochsPerPoint: Int = 5,
    private val store: PointStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 지도 그림용 기준 위치. 측량 좌표 계산에는 쓰지 않는다 */
    var projection by mutableStateOf(projection)
    private var anchored = false
    /** 투영 원점(EPSG:5185~5188). 이미 저장한 점은 저장 당시 값을 유지한다 */
    var zone by mutableStateOf(zone)

    /** 위치 종류 목록(편집 가능, 이름 8자 이하). 기본 = 고고학 3종(유구 경계점·유물 출토 위치·그리드 모서리) + 기준점·레벨점·기타 */
    val types = mutableStateListOf<String>().apply { addAll(kr.archsurvey.arch.ArchTypes.seed) }
    /** 종류 목록이 바뀌면 저장 */
    var onTypesChanged: ((List<String>) -> Unit)? = null
    /** 고고학: 발굴 구역·그리드·외곽 */
    var arch: kr.archsurvey.arch.ArchController = kr.archsurvey.arch.ArchController(this, null, scope)
    /** 중복 점 경고 시트 */
    var dupPrompt by mutableStateOf<DupPrompt?>(null)
    /** 재측량 대기 중인 점: 다음 측점이 이 점의 새 값이 된다 */
    var resurveyTarget by mutableStateOf<SavedPoint?>(null)
    private var resultName: String? = null
    private var resurveyUndo: SavedPoint? = null

    fun setTypesList(list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { kr.archsurvey.arch.ArchTypes.seed }
        val keep = types.getOrNull(typeIndex)
        types.clear(); types.addAll(clean)
        typeIndex = keep?.let { clean.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        onTypesChanged?.invoke(clean)
    }
    /** 새 종류 추가. 문제가 있으면 문구를 돌려준다 */
    fun addType(name: String): String? { val pr = kr.archsurvey.arch.ArchTypes.problem(name, types); if (pr == null) setTypesList(types + name.trim()); return pr }
    fun renameType(old: String, name: String): String? {
        if (old == name.trim()) return null
        val pr = kr.archsurvey.arch.ArchTypes.problem(name, types - old); if (pr != null) return pr
        setTypesList(types.map { if (it == old) name.trim() else it }); return null
    }
    fun removeType(name: String): String? { if (types.size <= 1) return "종류는 1개 이상 있어야 해요"; setTypesList(types - name); return null }
    fun moveType(name: String, delta: Int) {
        val i = types.indexOf(name); val j = i + delta
        if (i < 0 || j < 0 || j >= types.size) return
        val l = types.toMutableList(); l.removeAt(i); l.add(j, name); setTypesList(l)
    }

    /** 수신기 연결 화면 상태 (없으면 연결 UI 숨김) */
    var connection: ConnectionController? = null
    /** 내보내기 화면 상태 */
    var exporter: ExportController? = null
    /** 백업·복구 화면 상태 */
    var backup: BackupController? = null
    /** 점 목록·찾아가기·계산기·설정·작업 관리 화면 */
    val hub = ToolsHub(this)
    /** 측점 결과 알림(진동·소리). true=저장됨, false=실패 */
    var feedback: ((Boolean) -> Unit)? = null

    /** 사진·음성 메모 (없으면 화면 시안 동작) */
    var media: MediaPlatform? = null
    val photos = mutableStateListOf<PhotoItem>()
    val voices = mutableStateListOf<VoiceItem>()
    var playingVoiceId by mutableStateOf<Long?>(null)

    /** 지금 열려 있는 작업 이름(표시용) */
    var projectName by mutableStateOf("작업1")
    /** 설정 화면 상태 (없으면 설정 화면 숨김) */
    var settingsState: SettingsState? = null
    /** 작업 관리 상태 */
    var jobs: JobController? = null

    /** 저장소에서 점을 다시 읽는다 (작업을 바꾼 뒤) */
    private fun anchorTo(lat: Double, lon: Double) { projection = LocalProjection(lat, lon); anchored = true; trail.clear() }
    private fun toLocalNow(lat: Double, lon: Double) = projection.toLocal(lat, lon)

    private fun anchorToLastPoint() {
        if (anchorOnFirstFix && !anchored) points.lastOrNull()?.let { projection = LocalProjection(it.lat, it.lon); anchored = true }
    }

    fun reloadFromStore() {
        val st = store ?: return
        acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f
        photos.clear(); voices.clear()
        dupPrompt = null; resurveyTarget = null; resultName = null; resurveyUndo = null
        arch.reload()
        scope.launch {
            runCatching { val m = st.maxSeq(); val all = st.loadAll(); seq = m; points.clear(); points.addAll(all) }
                .onFailure { message = "저장된 점을 불러오지 못했어요" }
        }
    }

    var gv by mutableStateOf(GnssView())
    var phase by mutableStateOf(Phase.IDLE)
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf<String?>(null)
    var typeIndex by mutableIntStateOf(0)
    var poleOnRight by mutableStateOf(true)
    var drawerOpen by mutableStateOf(false)
    var antennaSheetOpen by mutableStateOf(false)
    var voiceSeconds by mutableIntStateOf(0)
    var dpPerM by mutableStateOf(12.0)
    var follow by mutableStateOf(true)
    private var panCenter by mutableStateOf(LocalXY(0.0, 0.0))

    var antennaModelId by mutableStateOf("TRIMBLE_R10")
    var antenna by mutableStateOf(AntennaResolver.initial(antennaDb.getValue("TRIMBLE_R10")))

    val points = mutableStateListOf<SavedPoint>()
    private val trail = mutableStateListOf<LocalXY>()
    private var lastGgaStamp = -1L
    private var acquireJob: Job? = null
    private var voiceJob: Job? = null

    val antennaModel: AntennaModel get() = antennaDb.getValue(antennaModelId)
    val antennaResult: AntennaResult? get() = AntennaMath.compute(antenna, antennaModel)
    /** 점 번호 카운터. 되돌려도 줄이지 않는다(번호 재사용 금지). */
    private var seq by mutableIntStateOf(0)
    val nextName: String get() = "P-%04d".format(seq + 1)
    val typeName: String get() = types.getOrNull(typeIndex) ?: types.firstOrNull() ?: "기타"
    val lastPoint: SavedPoint? get() = resultName?.let { n -> points.firstOrNull { it.name == n } } ?: points.lastOrNull()

    init {
        store?.let { st ->
            scope.launch {
                runCatching {
                    seq = st.maxSeq()
                    points.addAll(st.loadAll())
                    anchorToLastPoint()
                }.onFailure { message = "저장된 점을 불러오지 못했어요" }
            }
        }
        scope.launch {
            combine(source.gnss, source.link) { g, l -> g to l }.collect { (g, l) ->
                val st = StatusJudge.judge(g, l.state, source.clockMs())
                val gga = g.gga
                // 연결이 없으면 마지막 위치도 지도에 남기지 않는다
                val shown = st.reason != StatusReason.NOT_CONNECTED && st.reason != StatusReason.LINK_DOWN
                gv = GnssView(st, g.quality, if (shown) gga?.satellites ?: 0 else 0, if (shown) gga?.lat else null, if (shown) gga?.lon else null,
                    if (shown) g.gst?.sigmaAlt else null, if (shown) gga?.hdop else null, if (shown) g.satsInView else null)
                if (shown && gga != null && gga.hasPosition && g.lastGgaAtMs != lastGgaStamp) {
                    lastGgaStamp = g.lastGgaAtMs
                    if (anchorOnFirstFix && !anchored) anchorTo(gga.lat!!, gga.lon!!)
                    trail.add(toLocalNow(gga.lat!!, gga.lon!!))
                    while (trail.size > 80) trail.removeAt(0)
                }
            }
        }
    }

    // ---------------- 측점 ----------------

    fun onMeasure() {
        if (phase == Phase.ACQUIRING || phase == Phase.RECORDING) return
        val st = gv.status
        if (StatusJudge.savePolicy(st.status) == SavePolicy.BLOCK) {
            message = "지금은 측점할 수 없어요 — ${st.reason.textKo}"
            feedback?.invoke(false)
            return
        }
        val cfg = antenna
        val rec = AntennaRecord.of(antennaModel, cfg)
        if (rec == null) { message = "안테나 높이를 계산할 수 없어요. 높이 값을 확인하세요."; return }
        message = null; dupPrompt = null; arch.lastSummary = null
        phase = Phase.ACQUIRING; progress = 0f
        val typeAtStart = typeName; val nameAtStart = nextName
        acquireJob?.cancel()
        acquireJob = scope.launch {
            val avg = PointAverager(epochsPerPoint)
            var worst = st.status
            var aborted: StatusReason? = null
            combine(source.gnss, source.link) { g, l -> g to l }.first { (g, l) ->
                val r = StatusJudge.judge(g, l.state, source.clockMs())
                if (r.status == SurveyStatus.UNAVAILABLE) { aborted = r.reason; return@first true }
                if (r.status == SurveyStatus.CAUTION) worst = SurveyStatus.CAUTION
                g.gga?.let { avg.add(it, g.gst) }
                progress = avg.progress
                avg.done
            }
            val ar = aborted
            if (ar != null) {
                phase = Phase.IDLE
                message = "신호가 ‘불가’가 되어 취득을 멈췄어요 (${ar.textKo}). 저장하지 않았어요."
                feedback?.invoke(false)
                return@launch
            }
            val p = avg.result()
            if (p == null) { phase = Phase.IDLE; message = "위치를 얻지 못했어요"; return@launch }
            val tm = TmProjection.forward(p.lat, p.lon, zone)
            val ground = p.ellipsoidalHeight?.let { it - rec.apcHeightM }
            val groundMsl = p.altitudeMsl?.let { it - rec.apcHeightM }
            val cell = arch.locate(NE(tm.northing, tm.easting), p.sigmaH)
            val cellName = when (cell.kind) { CellKind.INSIDE, CellKind.NEAR_BOUNDARY, CellKind.OUTSIDE -> cell.label; CellKind.NO_GRID -> "" }
            val sp = SavedPoint(nameAtStart, typeAtStart, p.lat, p.lon, tm.northing, tm.easting, zone.epsg, ground, p.sigmaH,
                worst, worst == SurveyStatus.CAUTION, p.epochs, rec, gv.quality.code, gv.satellites, clock(),
                apcEllipsoidalM = p.ellipsoidalHeight, apcMslM = p.altitudeMsl, geoidSepM = p.geoidSeparation, groundMslM = groundMsl,
                hdop = p.hdop, sigmaE = p.sigmaE, sigmaN = p.sigmaN, sigmaU = p.sigmaU, correctionAgeSec = p.correctionAgeSec, baseStationId = p.baseStationId,
                areaId = arch.activeAreaId, gridId = if (cell.kind == CellKind.NO_GRID) null else arch.activeGrid?.id, cellName = cellName,
                cellU = cell.inCellU, cellV = cell.inCellV)
            val target = resurveyTarget
            if (target != null) { resurveyTarget = null; applyResurvey(target, sp, worst); return@launch }
            val dup = findDuplicate(sp, NE(tm.northing, tm.easting))
            if (dup != null) {
                phase = Phase.IDLE
                dupPrompt = DupPrompt(sp, dup.first, dup.second, worst)
                return@launch
            }
            commitNew(sp, worst)
        }
    }

    /** 같은 구역의 기존 점 중 max(5 cm, 정밀도의 2배) 이내에 있는 가장 가까운 점 */
    private fun findDuplicate(fresh: SavedPoint, ne: NE, ignore: String? = null): Pair<SavedPoint, Double>? {
        val cands = points.filter { it.areaId == fresh.areaId && it.name != ignore }
        val hit = DuplicateCheck.find(ne, fresh.sigmaHM, cands.map { it.name to pointNE(it) }) ?: return null
        return cands.first { it.name == hit.key } to hit.distanceM
    }

    /** 새 점으로 저장 (디스크 + 화면). 외곽 모드면 꼭짓점으로 이어 붙인다 */
    private suspend fun commitNew(sp: SavedPoint, worst: SurveyStatus) {
        resultName = null; resurveyUndo = null
        val named = if (sp.name == nextName) sp else sp.copy(name = nextName)
        points.add(named); seq++
        var saveError: String? = null
        store?.let { st ->
            val idx = points.lastIndex
            // 저장은 즉시 디스크(Room 트랜잭션). 실패하면 화면에 알림
            try { val id = st.save(named); points[idx] = named.copy(rowId = id); if (arch.outlineMode && named.type == ArchTypes.OUTLINE_POINT) arch.attachVertex(id) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; saveError = "디스크 저장에 실패했어요 — 다시 시도하세요" }
        }
        phase = Phase.RESULT
        photos.clear(); voices.clear()
        feedback?.invoke(saveError == null)
        message = saveError ?: if (worst == SurveyStatus.CAUTION) "주의 상태에서 취득한 점이에요 (표시가 기록돼요)" else null
    }

    /** 재측량: 번호·종류·메모·사진은 두고 측정값만 새 값으로. 이전 값은 이력에 남는다 */
    private suspend fun applyResurvey(target: SavedPoint, fresh: SavedPoint, worst: SurveyStatus) {
        val idx = points.indexOfFirst { it.rowId == target.rowId && it.name == target.name }
        if (idx < 0) { phase = Phase.IDLE; message = "재측량할 점을 찾지 못했어요"; return }
        val old = points[idx]
        val upd = fresh.copy(name = old.name, type = old.type, memo = old.memo, rowId = old.rowId)
        var err: String? = null
        store?.let { st -> try { st.resurvey(old.rowId, upd) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; err = "디스크 저장에 실패했어요 — 다시 시도하세요" } }
        if (err != null) { phase = Phase.IDLE; message = err; feedback?.invoke(false); return }
        points[idx] = upd
        arch.bumpRevision(old.rowId)
        resultName = upd.name; resurveyUndo = old
        dupPrompt = null
        phase = Phase.RESULT
        refreshMedia()
        feedback?.invoke(true)
        val moved = Calc.distance(pointNE(old), pointNE(upd))
        message = "${upd.name} 재측량 완료 · 이전 값과 ${"%.3f".format(moved)} m 차이 (이전 값은 이력에 보관)" + if (worst == SurveyStatus.CAUTION) " · 주의 상태" else ""
    }

    /** 점 상세의 [재측량]: 다음 측점이 이 점의 새 값이 된다 */
    fun startResurvey(p: SavedPoint) {
        resurveyTarget = p; dupPrompt = null; hub.closeAll(); drawerOpen = false
        message = "${p.name} 재측량 — 점 위에 서서 측점 버튼을 누르세요"
    }
    fun cancelResurvey() { resurveyTarget = null; message = "재측량을 취소했어요" }

    /** 중복 점 경고 시트의 선택 */
    fun resolveDup(choice: DupChoice) {
        val d = dupPrompt ?: return
        dupPrompt = null
        when (choice) {
            DupChoice.RESURVEY -> scope.launch { applyResurvey(d.existing, d.fresh, d.worst) }
            DupChoice.NEW_POINT -> scope.launch { commitNew(d.fresh, d.worst) }
            DupChoice.DISCARD -> { phase = Phase.IDLE; message = "취소했어요 — 방금 측점은 저장하지 않았어요" }
        }
    }

    fun cancelAcquire() {
        if (phase != Phase.ACQUIRING) return
        acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f; message = "취득을 취소했어요"
    }

    fun undo() {
        if (phase == Phase.ACQUIRING) { cancelAcquire(); return }
        if (dupPrompt != null) { resolveDup(DupChoice.DISCARD); return }
        val prev = resurveyUndo
        if (prev != null && (phase == Phase.RESULT || phase == Phase.RESULT_EXPANDED)) {
            val i = points.indexOfFirst { it.rowId == prev.rowId && it.name == prev.name }
            resurveyUndo = null; resultName = null
            if (i >= 0) {
                points[i] = prev
                store?.let { st -> scope.launch { runCatching { st.undoResurvey(prev.rowId, prev) }.onFailure { message = "디스크 반영에 실패했어요" } } }
                arch.dropRevision(prev.rowId)
            }
            phase = Phase.IDLE; photos.clear(); voices.clear()
            message = "${prev.name} 재측량을 되돌렸어요 (이전 값으로 복원)"
            return
        }
        if (phase == Phase.RECORDING) media?.cancelRecording()
        media?.stopPlayback(); playingVoiceId = null
        stopVoice()
        if (points.isEmpty()) return
        val gone = points.removeAt(points.lastIndex)
        if (gone.rowId > 0) arch.detachVertex(gone.rowId)
        store?.let { st -> scope.launch { runCatching { st.hideLast() }.onFailure { message = "디스크 반영에 실패했어요" } } }
        phase = Phase.IDLE
        photos.clear(); voices.clear()
        message = "${gone.name} 되돌렸어요"
    }

    fun toggleExpand() {
        phase = when (phase) { Phase.RESULT -> Phase.RESULT_EXPANDED; Phase.RESULT_EXPANDED -> Phase.RESULT; else -> phase }
    }

    // ---------------- 사진 · 음성 메모 (마지막 점에 붙는다) ----------------

    /** 마지막 점의 사진·음성 목록을 디스크에서 다시 읽는다 */
    fun refreshMedia() {
        val m = media ?: return
        val rid = lastPoint?.rowId ?: 0L
        if (rid == 0L) { photos.clear(); voices.clear(); return }
        scope.launch {
            val ph = runCatching { m.photos(rid) }.getOrDefault(emptyList())
            val vo = runCatching { m.voices(rid) }.getOrDefault(emptyList())
            photos.clear(); photos.addAll(ph); voices.clear(); voices.addAll(vo)
        }
    }

    private fun mediaTarget(): SavedPoint? {
        val p = lastPoint
        if (p == null) { message = "먼저 측점을 하세요"; return null }
        if (p.rowId == 0L) { message = "이 점이 디스크에 저장되지 않아 사진·음성을 붙일 수 없어요"; return null }
        return p
    }

    fun addPhotoCamera() {
        val m = media ?: run { message = "사진 기능을 쓸 수 없어요"; return }
        val p = mediaTarget() ?: return
        if (photos.size >= MediaLimits.PHOTOS) { message = "사진은 점당 ${MediaLimits.PHOTOS}장까지예요"; return }
        m.takePhoto(p.name, p.rowId) { ok -> refreshMedia(); if (!ok) message = "사진이 저장되지 않았어요 (촬영 취소 또는 실패)" }
    }

    fun addPhotoPicker() {
        val m = media ?: run { message = "사진 기능을 쓸 수 없어요"; return }
        val p = mediaTarget() ?: return
        val remaining = MediaLimits.PHOTOS - photos.size
        if (remaining <= 0) { message = "사진은 점당 ${MediaLimits.PHOTOS}장까지예요"; return }
        m.pickPhotos(p.name, p.rowId, remaining) { n ->
            refreshMedia()
            if (n > 0) message = "사진 ${n}장을 앱 폴더에 복사했어요 (원본은 그대로)"
        }
    }

    fun deletePhoto(item: PhotoItem) {
        val m = media ?: return
        scope.launch { runCatching { m.deletePhoto(item.id) }.onFailure { message = "사진을 지우지 못했어요" }; refreshMedia() }
    }

    fun deleteVoice(item: VoiceItem) {
        val m = media ?: return
        if (playingVoiceId == item.id) { m.stopPlayback(); playingVoiceId = null }
        scope.launch { runCatching { m.deleteVoice(item.id) }.onFailure { message = "음성을 지우지 못했어요" }; refreshMedia() }
    }

    fun playVoice(item: VoiceItem) {
        val m = media ?: return
        if (playingVoiceId == item.id) { m.stopPlayback(); playingVoiceId = null; return }
        val ok = m.play(item.rel) { playingVoiceId = null }
        if (ok) playingVoiceId = item.id else message = "음성을 재생하지 못했어요"
    }

    /** 녹음 시작/끝. 실제 녹음 기능이 연결되어 있으면 마이크 권한 → 녹음(최대 30초) → 저장 */
    fun toggleVoice() {
        val m = media ?: return toggleVoiceMock()
        if (phase == Phase.RECORDING) { finishVoice(); return }
        if (phase != Phase.RESULT && phase != Phase.RESULT_EXPANDED) return
        val p = mediaTarget() ?: return
        if (voices.size >= MediaLimits.VOICES) { message = "음성 메모는 점당 ${MediaLimits.VOICES}개까지예요"; return }
        if (!m.hasMicPermission()) {
            m.requestMicPermission { ok -> if (ok) toggleVoice() else message = "마이크 권한이 꺼져 있어요 — 설정 > 앱 > 권한 > 마이크 를 허용하세요" }
            return
        }
        m.stopPlayback(); playingVoiceId = null
        val err = m.startRecording(p.name)
        if (err != null) { message = err; return }
        phase = Phase.RECORDING; voiceSeconds = 0
        voiceJob = scope.launch { while (voiceSeconds < MediaLimits.VOICE_SECONDS) { delay(1000); voiceSeconds++ }; finishVoice() }
    }

    private fun finishVoice() {
        val m = media ?: return
        voiceJob?.cancel(); voiceJob = null
        if (phase == Phase.RECORDING) phase = Phase.RESULT
        val rid = lastPoint?.rowId ?: 0L
        scope.launch {
            val v = runCatching { m.finishRecording(rid) }.getOrNull()
            if (v == null) message = "녹음이 너무 짧거나 저장에 실패해서 남기지 않았어요" else voices.add(v)
        }
    }

    /** 음성 메모 **화면 시안만**: 실제 녹음은 하지 않는다(Phase 1). 30초 자동 정지 타이머만 흉내. */
    fun toggleVoiceMock() {
        if (phase == Phase.RECORDING) { stopVoice(); return }
        if (phase != Phase.RESULT && phase != Phase.RESULT_EXPANDED) return
        phase = Phase.RECORDING; voiceSeconds = 0
        voiceJob = scope.launch { while (voiceSeconds < 30) { delay(1000); voiceSeconds++ }; stopVoice() }
    }

    private fun stopVoice() {
        voiceJob?.cancel(); voiceJob = null
        if (phase == Phase.RECORDING) phase = Phase.RESULT
    }

    /** 점 하나의 평면 좌표(현재 원점 기준). 저장 당시 원점이 같으면 저장값을 쓰고, 다르면 경위도에서 다시 계산한다 */
    fun pointNE(p: SavedPoint): kr.archsurvey.core.NE =
        if (p.crsEpsg == zone.epsg) kr.archsurvey.core.NE(p.northing, p.easting)
        else TmProjection.forward(p.lat, p.lon, zone).let { kr.archsurvey.core.NE(it.northing, it.easting) }

    /** 현재 위치의 평면 좌표(위치가 없으면 null) */
    fun currentNE(): kr.archsurvey.core.NE? {
        val la = gv.lat ?: return null; val lo = gv.lon ?: return null
        return TmProjection.forward(la, lo, zone).let { kr.archsurvey.core.NE(it.northing, it.easting) }
    }

    /** 위치 종류·메모 수정 (화면 목록 + 저장소) */
    fun updatePointMeta(name: String, type: String, memo: String) {
        val i = points.indexOfFirst { it.name == name }
        if (i < 0) return
        val old = points[i]
        val upd = old.copy(type = type.trim().ifEmpty { old.type }, memo = memo.trim())
        points[i] = upd
        store?.let { st -> if (upd.rowId > 0) scope.launch { runCatching { st.updateMeta(upd.rowId, upd.type, upd.memo) }.onFailure { message = "디스크 반영에 실패했어요" } } }
    }

    /** 지도를 이 점으로 옮긴다 (따라가기 해제) */
    fun focusOn(p: SavedPoint) { follow = false; panCenter = projection.toLocal(p.lat, p.lon) }

    fun cycleType() { typeIndex = (typeIndex + 1) % types.size }

    // ---------------- 지도 ----------------

    fun zoomIn() { dpPerM = (dpPerM * 1.5).coerceAtMost(80.0) }
    fun zoomOut() { dpPerM = (dpPerM / 1.5).coerceAtLeast(2.0) }
    fun recenter() { follow = true }
    fun panBy(dxM: Double, dyM: Double, currentCenter: LocalXY) { follow = false; panCenter = LocalXY(currentCenter.x + dxM, currentCenter.y + dyM) }

    fun mapState(): MapViewState {
        val cur = if (gv.lat != null && gv.lon != null) projection.toLocal(gv.lat!!, gv.lon!!) else null
        val center = if (follow) (cur ?: LocalXY(20.0, 16.0)) else panCenter
        val head = if (trail.size >= 2) {
            val a = trail[trail.size - 2]; val b = trail.last()
            if (hypot(b.x - a.x, b.y - a.y) > 0.05) Math.toDegrees(atan2(b.x - a.x, b.y - a.y)) else null
        } else null
        val sigma = gv.status.sigmaHM ?: 0.5
        val g = arch.activeGrid
        val ref = TmProjection.forward(projection.lat0, projection.lon0, zone)
        fun loc(ne: NE) = LocalXY(ne.e - ref.easting, ne.n - ref.northing)
        val cellNow = if (g != null) arch.currentCell() else null
        return MapViewState(
            center = center, dpPerM = dpPerM, 
            points = points.map { MapMarker(it.name, projection.toLocal(it.lat, it.lon), it.cautionFlag) },
            current = cur?.let { CurrentMarker(it, sigma, gv.status.status, head) },
            trail = trail.toList(),
            gridLines = if (g != null && g.cols + g.rows <= 400) GridMath.lines(g).map { loc(it.first) to loc(it.second) } else emptyList(),
            gridLabels = if (g != null && g.cols * g.rows <= 1200 && g.spacingM * dpPerM >= 26) GridMath.cellCenters(g).map { MapText(it.first, loc(it.second)) } else emptyList(),
            cellHighlight = if (g != null && cellNow != null && cellNow.col >= 0) g.cellCorners(cellNow.col, cellNow.row).map { loc(it) } else emptyList(),
            gridColorIdx = arch.activeArea?.colorIdx ?: 0,
            outlines = arch.outlineViews().map { MapOutline(it.outline.label, it.vertices.map { v -> loc(v) }, it.outline.closed, !it.outline.finished, arch.areas.firstOrNull { a -> a.id == it.outline.areaId }?.colorIdx ?: 0) },
        )
    }

    // ---------------- 안테나 높이 ----------------

    fun setAntennaModel(id: String) {
        if (id == antennaModelId) return
        antennaModelId = id
        antenna = AntennaResolver.proposeDefault(antenna, antennaModel)
    }
    fun setRefPoint(id: String) { antenna = antenna.copy(refPointId = id) }
    fun stepAntennaCm(deltaCm: Int) { antenna = AntennaResolver.userEdit(antenna.copy(inputValueM = AntennaMath.step(antenna.inputValueM, deltaCm))) }
}
