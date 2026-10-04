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
import kr.archsurvey.core.*
import kr.archsurvey.device.GnssSource
import kr.archsurvey.map.*
import kotlin.math.atan2
import kotlin.math.hypot

data class GnssView(
    val status: StatusResult = StatusResult(SurveyStatus.UNAVAILABLE, StatusReason.NO_DATA, null),
    val quality: FixQuality = FixQuality.NONE,
    val satellites: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
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
)

/** 측점 저장소. Android 의존 없음(시험용 가짜 구현 가능). 되돌리기는 숨김(소프트 삭제)이라 번호를 다시 쓰지 않는다. */
interface PointStore {
    suspend fun loadAll(): List<SavedPoint>
    /** 지금까지 쓴 가장 큰 번호(숨긴 점 포함). 없으면 0 */
    suspend fun maxSeq(): Int
    suspend fun save(p: SavedPoint): Long
    suspend fun hideLast()
}

/**
 * 측량 화면의 상태·동작. [GnssSource] 하나만 본다(가짜/실제 교체 가능).
 * Android 의존 없음 → JVM 단위 테스트 가능.
 */
class SurveyController(
    val source: GnssSource,
    val antennaDb: Map<String, AntennaModel>,
    private val scope: CoroutineScope,
    val projection: LocalProjection = LocalProjection(36.5, 127.0),
    zone: KoreanTmZone = KoreanTmZone.CENTRAL,
    private val epochsPerPoint: Int = 5,
    private val store: PointStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 투영 원점(EPSG:5185~5188). 이미 저장한 점은 저장 당시 값을 유지한다 */
    var zone by mutableStateOf(zone)

    val types = listOf("유구 경계점", "유물 출토 위치", "기준점", "레벨점", "기타")

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
    val typeName: String get() = types[typeIndex]
    val lastPoint: SavedPoint? get() = points.lastOrNull()

    init {
        store?.let { st ->
            scope.launch {
                runCatching {
                    seq = st.maxSeq()
                    points.addAll(st.loadAll())
                }.onFailure { message = "저장된 점을 불러오지 못했어요" }
            }
        }
        scope.launch {
            combine(source.gnss, source.link) { g, l -> g to l }.collect { (g, l) ->
                val st = StatusJudge.judge(g, l.state, source.clockMs())
                val gga = g.gga
                gv = GnssView(st, g.quality, gga?.satellites ?: 0, gga?.lat, gga?.lon)
                if (gga != null && gga.hasPosition && g.lastGgaAtMs != lastGgaStamp) {
                    lastGgaStamp = g.lastGgaAtMs
                    trail.add(projection.toLocal(gga.lat!!, gga.lon!!))
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
            return
        }
        val cfg = antenna
        val rec = AntennaRecord.of(antennaModel, cfg)
        if (rec == null) { message = "안테나 높이를 계산할 수 없어요. 높이 값을 확인하세요."; return }
        message = null
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
                return@launch
            }
            val p = avg.result()
            if (p == null) { phase = Phase.IDLE; message = "위치를 얻지 못했어요"; return@launch }
            val tm = TmProjection.forward(p.lat, p.lon, zone)
            val ground = p.ellipsoidalHeight?.let { it - rec.apcHeightM }
            val groundMsl = p.altitudeMsl?.let { it - rec.apcHeightM }
            val sp = SavedPoint(nameAtStart, typeAtStart, p.lat, p.lon, tm.northing, tm.easting, zone.epsg, ground, p.sigmaH,
                worst, worst == SurveyStatus.CAUTION, p.epochs, rec, gv.quality.code, gv.satellites, clock(),
                apcEllipsoidalM = p.ellipsoidalHeight, apcMslM = p.altitudeMsl, geoidSepM = p.geoidSeparation, groundMslM = groundMsl,
                hdop = p.hdop, sigmaE = p.sigmaE, sigmaN = p.sigmaN, sigmaU = p.sigmaU, correctionAgeSec = p.correctionAgeSec, baseStationId = p.baseStationId)
            points.add(sp); seq++
            var saveError: String? = null
            store?.let { st ->
                val idx = points.lastIndex
                // 저장은 즉시 디스크(Room 트랜잭션). 실패하면 화면에 알림
                try { val id = st.save(sp); points[idx] = sp.copy(rowId = id) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; saveError = "디스크 저장에 실패했어요 — 다시 시도하세요" }
            }
            phase = Phase.RESULT
            message = saveError ?: if (worst == SurveyStatus.CAUTION) "주의 상태에서 취득한 점이에요 (표시가 기록돼요)" else null
        }
    }

    fun cancelAcquire() {
        if (phase != Phase.ACQUIRING) return
        acquireJob?.cancel(); phase = Phase.IDLE; progress = 0f; message = "취득을 취소했어요"
    }

    fun undo() {
        if (phase == Phase.ACQUIRING) { cancelAcquire(); return }
        stopVoice()
        if (points.isEmpty()) return
        val gone = points.removeAt(points.lastIndex)
        store?.let { st -> scope.launch { runCatching { st.hideLast() }.onFailure { message = "디스크 반영에 실패했어요" } } }
        phase = Phase.IDLE
        message = "${gone.name} 되돌렸어요"
    }

    fun toggleExpand() {
        phase = when (phase) { Phase.RESULT -> Phase.RESULT_EXPANDED; Phase.RESULT_EXPANDED -> Phase.RESULT; else -> phase }
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
        return MapViewState(
            center = center, dpPerM = dpPerM,
            points = points.map { MapMarker(it.name, projection.toLocal(it.lat, it.lon), it.cautionFlag) },
            current = cur?.let { CurrentMarker(it, sigma, gv.status.status, head) },
            trail = trail.toList(),
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
