package kr.archsurvey.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kr.archsurvey.App
import kr.archsurvey.bt.BtCompat
import kr.archsurvey.core.*
import kr.archsurvey.data.*
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.engine.SurveyConnectionService
import kr.archsurvey.export.ExportResult
import kr.archsurvey.export.ExportService
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.map.MapBase
import kr.archsurvey.ntrip.MountInfo
import kr.archsurvey.ntrip.NtripClient
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.photo.PhotoStore

sealed interface SurveyUi {
    data object Idle : SurveyUi
    data class Measuring(val progress: Float) : SurveyUi
    data class Saved(val rowId: Long) : SurveyUi
    data class Failed(val message: String) : SurveyUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainVm(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    val settings = a.settings
    val engine = a.engine
    private val dao = a.db.dao()
    val photos = PhotoStore(app, dao)
    private val exporter = ExportService(app, dao, photos)

    val antennaDb: Map<String, AntennaModel> = runCatching {
        AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyMap())

    var deviceId by mutableStateOf(settings.deviceId)
    val profile: DeviceProfile? get() = DeviceProfiles.byId(deviceId)
    var project by mutableStateOf(settings.project)
    var idPrefix by mutableStateOf(settings.idPrefix)
    var epochs by mutableStateOf(settings.epochs)
    var requireFixed by mutableStateOf(settings.requireFixed)
    var tmZone by mutableStateOf(settings.tmZone)
    var soundOn by mutableStateOf(settings.soundOn)
    var vibrationOn by mutableStateOf(settings.vibrationOn)
    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
    var heightUnit by mutableStateOf(settings.heightUnit)
    var ntripCfg by mutableStateOf(settings.loadNtrip())
    var ntripEnabled by mutableStateOf(settings.ntripEnabled)
    var mapBase by mutableStateOf(runCatching { MapBase.valueOf(settings.mapBase) }.getOrDefault(MapBase.SATELLITE))
    var cadOverlay by mutableStateOf(settings.cadastralOverlay)
    var mapKey by mutableStateOf(settings.mapKey)
    var mapDomain by mutableStateOf(settings.mapDomain)
    var code by mutableStateOf(settings.code)
    var pointId by mutableStateOf("")
    var memoDraft by mutableStateOf("")

    var antenna by mutableStateOf(AntennaConfig())
    val antennaModel: AntennaModel? get() = AntennaDb.forDevice(antennaDb, deviceId)

    var surveyUi by mutableStateOf<SurveyUi>(SurveyUi.Idle)
    var selectedRow by mutableStateOf<Long?>(null)
    var lastExport by mutableStateOf<ExportResult?>(null)
    var exportBusy by mutableStateOf(false)
    var mountList by mutableStateOf<List<MountInfo>>(emptyList())
    var mountMsg by mutableStateOf("")
    var toast by mutableStateOf<String?>(null)
    /** 모델 선택/변경 직후 한 번 보여주는 기준점 안내 */
    var showModelNotice by mutableStateOf(false)
    /** 사용자 입력 안테나 설정을 모델 기본값으로 덮어쓸지 확인 */
    var pendingModelChange by mutableStateOf<String?>(null)

    private val projectFlow = MutableStateFlow(project)
    val points = projectFlow.flatMapLatest { dao.observePoints(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun photosOf(rowId: Long) = dao.observePhotos(rowId)
    fun pointFlow(rowId: Long) = dao.observePoint(rowId)

    init {
        loadAntenna()
        viewModelScope.launch { pointId = PointIds.next(dao.lastPointId(project), idPrefix) }
        // 수신기가 안테나 높이를 보고하면 자동 사용 (현재 NMEA 에는 없음 → 보통 null)
        viewModelScope.launch {
            engine.receiverReportedAntennaM.collect { rep ->
                val d = deviceId ?: return@collect
                val n = AntennaResolver.applyReceiverReport(antenna, rep)
                if (n !== antenna) { antenna = n; settings.saveAntenna(d, n) }
            }
        }
    }

    private fun loadAntenna() {
        val d = deviceId ?: return
        val m = antennaModel ?: return
        antenna = settings.loadAntenna(d)?.let { if (it.refPointId.isEmpty()) it.copy(refPointId = m.defaultRefPoint) else it } ?: AntennaResolver.initial(m)
    }

    // ---- 모델 선택 ----
    fun chooseDevice(id: String) {
        if (id == deviceId) { showModelNotice = true; return }
        val newModel = AntennaDb.forDevice(antennaDb, id)
        val cur = antenna
        val saved = settings.loadAntenna(id)
        val proposed = if (newModel != null) (saved ?: AntennaResolver.proposeDefault(cur, newModel)) else cur
        if (deviceId != null && newModel != null && saved == null && AntennaResolver.needsOverwriteConfirm(cur, proposed)) {
            pendingModelChange = id // 확인 후 적용
            return
        }
        applyDevice(id, proposed)
    }

    fun confirmModelChange(overwrite: Boolean) {
        val id = pendingModelChange ?: return
        pendingModelChange = null
        val m = AntennaDb.forDevice(antennaDb, id)
        val base = if (overwrite && m != null) AntennaResolver.proposeDefault(antenna, m) else antenna.copy(refPointId = m?.defaultRefPoint ?: antenna.refPointId)
        applyDevice(id, base)
    }

    private fun applyDevice(id: String, cfg: AntennaConfig) {
        if (engine.running) stopConnection()
        deviceId = id; settings.deviceId = id
        antenna = cfg; settings.saveAntenna(id, cfg)
        showModelNotice = true
    }

    fun dismissModelNotice() { showModelNotice = false }

    // ---- 안테나 ----
    fun updateAntenna(c: AntennaConfig) {
        val d = deviceId ?: return
        val n = AntennaResolver.userEdit(c); antenna = n; settings.saveAntenna(d, n)
    }
    fun antennaResult(): AntennaResult? = antennaModel?.let { AntennaMath.compute(antenna, it) }
    fun updateHeightUnit(u: HeightUnit) { heightUnit = u; settings.heightUnit = u }

    // ---- 연결 ----
    fun btAddress(): String? = deviceId?.let { settings.btAddressFor(it) }
    fun selectBt(address: String) { deviceId?.let { settings.setBtAddress(it, address) } }

    fun connect(): Boolean {
        val p = profile ?: return false
        val addr = btAddress() ?: return false
        settings.saveNtrip(ntripCfg); settings.ntripEnabled = ntripEnabled
        SurveyConnectionService.start(getApplication(), p.id, addr)
        return true
    }

    fun stopConnection() { SurveyConnectionService.stop(getApplication()); engine.stop() }

    fun saveNtrip(c: NtripConfig, enabled: Boolean) {
        ntripCfg = c; ntripEnabled = enabled
        settings.saveNtrip(c); settings.ntripEnabled = enabled
        engine.updateNtrip(c, enabled)
        engine.retryNtripNow()
    }

    fun fetchMounts() {
        mountMsg = "목록을 가져오는 중…"
        viewModelScope.launch {
            try {
                mountList = NtripClient().fetchSourcetable(ntripCfg)
                mountMsg = if (mountList.isEmpty()) "마운트포인트 목록이 비어 있어요" else "${mountList.size}개 찾았어요"
            } catch (e: Exception) { mountMsg = "가져오지 못했어요: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    // ---- 측량 ----
    fun measure() {
        val d = profile ?: return
        val snap = engine.gnss.value
        if (snap.gga?.hasPosition != true) { fail("위치를 아직 못 받았어요. 연결 상태를 확인하세요."); return }
        if (requireFixed && snap.quality != FixQuality.RTK_FIXED) { fail("RTK 고정이 아니에요. 고정될 때까지 기다리세요."); return }
        val model = antennaModel
        val res = model?.let { AntennaMath.compute(antenna, it) }
        if (model != null && res == null && !antenna.receiverApplied) { fail("안테나 높이를 계산할 수 없어요. 높이 설정을 확인하세요."); return }
        surveyUi = SurveyUi.Measuring(0f)
        val progressJob = viewModelScope.launch { engine.measureProgress.collect { if (surveyUi is SurveyUi.Measuring) surveyUi = SurveyUi.Measuring(it) } }
        viewModelScope.launch {
            try {
                val r = engine.measure(epochs)
                if (r == null) { fail("측량 시간이 초과됐어요. 데이터가 끊겼는지 확인하세요."); return@launch }
                if (requireFixed && !r.allFixed) { fail("측량 중 RTK 고정이 풀렸어요. 다시 측량하세요."); return@launch }
                val pc = TmProjection.forward(r.lat, r.lon, tmZone)
                val cfg = antenna
                val pid = pointId.trim().ifEmpty { PointIds.next(dao.lastPointId(project), idPrefix) }
                if (dao.countId(project, pid) > 0) { fail("점 ID '$pid' 가 이미 있어요. 다른 ID 로 바꾸세요."); return@launch }
                val entity = PointEntity(
                    project = project, pointId = pid, code = code, memo = "",
                    lat = r.lat, lon = r.lon, ellipsoidalHeight = r.ellipsoidalHeight, altitudeMsl = r.altitudeMsl,
                    geoidSeparation = r.geoidSeparation, northing = pc.northing, easting = pc.easting, crsEpsg = tmZone.epsg,
                    fixQuality = r.worstQuality.code, allEpochsFixed = r.allFixed, satellites = r.satellites, hdop = r.hdop,
                    sigmaE = r.sigmaE, sigmaN = r.sigmaN, sigmaH = r.sigmaH, sigmaU = r.sigmaU,
                    correctionAgeSec = r.correctionAgeSec, baseStationId = r.baseStationId, epochs = r.epochs,
                    antennaHeight = res?.totalToApcM ?: 0.0, antennaModel = model?.dbId ?: "", antennaRefPoint = res?.refPointId ?: cfg.refPointId,
                    antennaInputMode = res?.method ?: "vertical", antennaInputValue = cfg.inputValueM,
                    antennaPcoV = res?.pcoV ?: 0.0, antennaPcoH = res?.pcoH ?: 0.0,
                    antennaExtraPartsM = res?.extraPartsM ?: 0.0, antennaBaseExtM = res?.baseExtM ?: 0.0, antennaReceiverApplied = cfg.receiverApplied,
                    groundEllipsoidalHeight = AntennaMath.groundHeight(r.ellipsoidalHeight, cfg, res),
                    groundMsl = AntennaMath.groundHeight(r.altitudeMsl, cfg, res),
                    device = d.displayName, measuredAtUtcMs = System.currentTimeMillis(),
                )
                val rowId = dao.insertPointRaw(entity) // 즉시 커밋
                selectedRow = rowId
                surveyUi = SurveyUi.Saved(rowId)
                engine.events.tryEmit(FeedbackEvent.SURVEY_OK)
            } catch (e: Exception) {
                fail("저장하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
            } finally { progressJob.cancel() }
        }
    }

    private fun fail(msg: String) {
        surveyUi = SurveyUi.Failed(msg)
        engine.events.tryEmit(FeedbackEvent.SURVEY_FAIL)
    }

    /** 다음 점으로: ID 증가 */
    fun nextPoint() {
        viewModelScope.launch {
            pointId = PointIds.next(pointId.ifEmpty { dao.lastPointId(project) }, idPrefix)
            surveyUi = SurveyUi.Idle
        }
    }

    fun dismissResult() { surveyUi = SurveyUi.Idle }

    fun saveMemo(rowId: Long, memo: String, code: String) = viewModelScope.launch { dao.updateMemoCode(rowId, memo, code) }

    fun deletePoint(rowId: Long) = viewModelScope.launch {
        val p = dao.getPoint(rowId) ?: return@launch
        dao.photosOf(rowId).forEach { photos.delete(it) }
        dao.deletePoint(p)
        if (selectedRow == rowId) selectedRow = null
    }

    suspend fun pointOf(rowId: Long) = dao.getPoint(rowId)

    // ---- 사진 ----
    suspend fun cameraTarget(rowId: Long): Uri? {
        val p = dao.getPoint(rowId) ?: return null
        val (rel, uri) = photos.newCameraTarget(p.project, p.pointId)
        settings.pendingPhoto = "$rowId|$rel" // 카메라 중 프로세스 사망 대비
        return uri
    }

    fun cameraResult(ok: Boolean) {
        val pending = settings.pendingPhoto ?: return
        settings.pendingPhoto = null
        val (row, rel) = pending.split("|", limit = 2).let { it[0].toLongOrNull() to it.getOrElse(1) { "" } }
        if (row == null || rel.isEmpty()) return
        viewModelScope.launch {
            if (ok) { if (!photos.registerCaptured(row, rel)) toast = "사진이 저장되지 않았어요" }
            else photos.file(rel).delete()
        }
    }

    /** 앱이 다시 열렸을 때: 촬영 중 프로세스가 죽어 남은 사진을 복구 */
    fun recoverPendingPhoto() {
        val pending = settings.pendingPhoto ?: return
        val (row, rel) = pending.split("|", limit = 2).let { it[0].toLongOrNull() to it.getOrElse(1) { "" } }
        if (row == null || rel.isEmpty()) { settings.pendingPhoto = null; return }
        val f = photos.file(rel)
        if (f.exists() && f.length() > 0) {
            settings.pendingPhoto = null
            viewModelScope.launch { photos.registerCaptured(row, rel) }
        }
    }

    fun galleryPicked(rowId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val p = dao.getPoint(rowId) ?: return@launch
            val n = photos.importFromUris(rowId, p.project, p.pointId, uris)
            toast = if (n == uris.size) "사진 ${n}장을 붙였어요" else "${uris.size}장 중 ${n}장만 붙였어요"
        }
    }

    fun deletePhoto(p: PhotoEntity) = viewModelScope.launch { photos.delete(p) }

    // ---- 설정 저장 ----
    fun saveBasics() {
        settings.project = project; project = settings.project
        settings.idPrefix = idPrefix; settings.epochs = epochs; epochs = settings.epochs
        settings.requireFixed = requireFixed; settings.tmZone = tmZone
        settings.soundOn = soundOn; settings.vibrationOn = vibrationOn; settings.keepScreenOn = keepScreenOn
        settings.mapBase = mapBase.name; settings.cadastralOverlay = cadOverlay
        settings.mapKey = mapKey; settings.mapDomain = mapDomain; settings.code = code
        projectFlow.value = project
        viewModelScope.launch { pointId = PointIds.next(dao.lastPointId(project), idPrefix) }
    }

    fun export(includePhotos: Boolean) {
        exportBusy = true
        viewModelScope.launch {
            try { lastExport = exporter.export(project, includePhotos) } catch (e: Exception) { toast = "내보내기 실패: ${e.message}" }
            finally { exportBusy = false }
        }
    }

    fun shareIntent() = lastExport?.let { exporter.shareIntent(it.zip) }

    fun btPermissionsMissing(): Boolean = !BtCompat.hasConnectPermission(getApplication())
}
