package kr.archsurvey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.engine.AndroidMediaPlatform
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.feedback.bindEngineFeedback

/**
 * 위치 공급원: 수신기(블루투스 NMEA)만. 0.1.30 T24: 폰 위치는 지도 「폰 위치(참고)」 점·따라가기 표시 전용(측점·저장에 안 씀).
 * 수신기 연결은 연결 화면의 [연결] 버튼으로만 시작한다(자동 연결은 설정 옵션).
 */
class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as App
    val platform = AndroidConnectionPlatform(application)
    val db = AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })

    private var connection: ConnectionController? = null
    val store: RoomPointStore = RoomPointStore(SurveyDb.get(app), project = { application.settings.project }, device = {
        connection?.profile?.displayName ?: "수신기"
    })
    /** 화면 작업에서 난 예외: 앱을 끄지 않고 기록 + 안내(0.1.9). 측정 중이었다면 대기 상태로 되돌린다 */
    private val safeScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        kr.archsurvey.diag.CrashLog.record("화면 작업", e)
        runCatching { controller.recoverFromError() }
    })
    /** 0.1.30 T24: 폰 위치 — 지도 점 표시 전용. 권한은 [ensurePhoneLocationPermission] */
    val phoneGps = kr.archsurvey.device.PhoneGpsSource(application)
    /** 측점 공급원은 수신기 엔진뿐(폰으로 자동 전환 없음) */
    val controller: SurveyController = SurveyController(application.engine, db, safeScope, epochsPerPoint = application.settings.epochsPerPoint, store = store, anchorOnFirstFix = true)
    val media = AndroidMediaPlatform(application, viewModelScope) { application.settings.project }
    val docs = kr.archsurvey.engine.DocBridge()
    val exportPlatform = kr.archsurvey.engine.AndroidExportPlatform(application, { application.settings.project }, media.photoStore, media.voiceStore, docs)

    val backupPlatform = kr.archsurvey.engine.AndroidBackupPlatform(application, docs)
    val driveAuth = kr.archsurvey.engine.DriveAuthBridge()

    /** 앱 첫 실행 화면에서 한 번: 지난번 성공 설정으로 자동 연결(설정에서 끌 수 있음) */
    private var autoConnectTried = false
    /** 0.1.32: 이 화면 모델에서 한 번만(회전으로 Activity 가 다시 만들어져도 다시 연결하지 않음) */
    fun autoConnectOnLaunch() {
        if (autoConnectTried) return
        autoConnectTried = true
        runCatching { connection?.autoConnectOnLaunch() }.onFailure { kr.archsurvey.diag.CrashLog.record("자동 연결", it) }
    }

    /** 0.1.12: 앱 시작 때 직전 작업이 있으면 true → 홈 대신 그 작업의 측량 화면으로 바로 연다 */
    var resumeJob: Boolean = false; private set

    /** 시작 안내 문구: 어떤 작업·설정으로 열었는지 */
    fun resumeNotice(): String = "‘${application.settings.project}’ 작업을 이어서 열었어요 · EPSG:${controller.zone.epsg} · Geoid ${controller.geoid.shortKo} · 배경맵 ${controller.mapBase.labelKo}"

    /** MainActivity 가 채운다: 화면 항상 켜기 설정이 바뀔 때 창 플래그 적용 */
    var onKeepScreen: (Boolean) -> Unit = {}
    private val versionName: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault("")

    init {
        val cc = ConnectionController(platform, safeScope)
        cc.ui = controller.ui
        // BT 프로필 ↔ 안테나 모델 단일화(서랍·홈·GNSS·안테나 시트 기기명 일치)
        cc.onProfileChanged = { p ->
            kr.archsurvey.data.AntennaDb.antennaIdForProfile(p.id)?.let { controller.setAntennaModel(it) }
        }
        connection = cc
        controller.connection = cc
        // 시작 시 이미 고른 프로필이 있으면 안테나도 맞춤
        kr.archsurvey.data.AntennaDb.antennaIdForProfile(cc.profile.id)?.let { controller.setAntennaModel(it) }
        controller.setTypesList(application.settings.positionTypes)
        controller.onTypesChanged = { application.settings.positionTypes = it }
        controller.markerStyle = kr.archsurvey.map.MarkerStyle.decode(application.settings.markerStyle)
        controller.onMarkerStyleChanged = { application.settings.markerStyle = it.encode() }
        val archStore = kr.archsurvey.data.RoomArchStore(SurveyDb.get(app), project = { application.settings.project })
        controller.arch = kr.archsurvey.arch.ArchController(
            controller, archStore, viewModelScope,
            activeProvider = { application.settings.activeArch(application.settings.project) },
            onActiveChanged = { a, g -> application.settings.setActiveArch(application.settings.project, a, g) },
        )
        controller.arch.reload()
        val bgFiles = kr.archsurvey.engine.AndroidBgFiles(app, docs, viewModelScope)
        controller.bg = kr.archsurvey.ui.bg.BackgroundController(
            projection = { controller.projection },
            store = kr.archsurvey.georef.JsonFileBgLayerStore(bgFiles.layerDir),
            pickFiles = { cb -> bgFiles.pick(cb) },
            deleteFiles = { bgFiles.deleteCopy(it) },
            cornersDir = bgFiles.layerDir,
        )
        controller.tilt = TiltController(kr.archsurvey.engine.AndroidTiltPlatform(app, application.settings))
        controller.media = media
        controller.exporter = ExportController(exportPlatform, viewModelScope)
        controller.backup = BackupController(backupPlatform, viewModelScope)
        val drivePlatform = kr.archsurvey.engine.AndroidDrivePlatform(application, driveAuth)
        controller.drive = DriveSyncController(
            platform = drivePlatform,
            settings = kr.archsurvey.engine.AndroidDriveSettings(application.settings),
            scope = viewModelScope,
            projectName = { application.settings.project },
            createBackupZip = { backupPlatform.create() },
            pointsCsvBytes = { project ->
                val pts = SurveyDb.get(application).dao().pointsOf(project)
                kr.archsurvey.export.Formats.pointsCsv(pts, emptyMap()).toByteArray(Charsets.UTF_8)
            },
        )
        controller.projectName = application.settings.project
        controller.settingsState = SettingsState(controller, kr.archsurvey.engine.AndroidSettingsPlatform(application.settings, { onKeepScreen(it) }, application))
        val jobCfgs = kr.archsurvey.engine.AndroidJobConfigPlatform(application.settings)
        controller.jobs = JobController(controller, kr.archsurvey.engine.AndroidJobPlatform(application, application.settings, versionName), viewModelScope, jobCfgs)
        // 0.1.12 V월드 키(암호화 저장, 비면 제품 기본 키) — 지도는 vworld.access 메모리 사본
        application.settings.ensureBuiltinMapKey()
        controller.vworld = VWorldSettingsState(kr.archsurvey.engine.AndroidMapPlatform(application.settings), viewModelScope)
        controller.mapKeyProvider = { controller.vworld.access }
        // 0.1.12 직전 작업 복원: 마지막에 열었던 작업(settings.project) + 그 작업의 설정(좌표계·Geoid·배경맵·안테나·측정 횟수)
        val project = application.settings.project
        val saved = jobCfgs.load(project)
        resumeJob = saved != null || application.settings.hasProject
        if (saved != null) { controller.applyJobConfig(saved); controller.settingsState?.syncZone() } else jobCfgs.save(project, controller.jobConfig())
        controller.onJobConfigChanged = { jobCfgs.save(application.settings.project, it) }
        controller.feedback = { ok -> application.feedback.play(if (ok) FeedbackEvent.SURVEY_OK else FeedbackEvent.SURVEY_FAIL) }
        // 0.1.19: Engine FIX/LINK/NTRIP 이벤트 → FeedbackPlayer (이전엔 emit 만 하고 재생 경로 없음)
        bindEngineFeedback(viewModelScope, application.engine.events, application.feedback::play)
        cc.onFeedback = { application.feedback.play(it) }
        // 0.1.32: 알림 「연결 종료」 → 화면도 연결 안 함 상태로(「연결 중」에 멈추지 않게)
        viewModelScope.launch { application.engine.stoppedOutside.collect { cc.onStoppedOutside() } }
        // 0.1.28 T14 음성 「저장」: 같은 onMeasure 경로 · 마이크 중재자는 controller.mic(음성 메모와 공유)
        val vc = kr.archsurvey.voicecmd.VoiceCommandController(controller.voiceHost, kr.archsurvey.engine.AndroidVoicePlatform(application, application.settings, media), controller.mic)
        controller.voice = vc
        controller.voiceHeard = { application.feedback.play(FeedbackEvent.VOICE_HEARD) }
        viewModelScope.launch { while (true) { kotlinx.coroutines.delay(200); if (vc.enabled) runCatching { vc.tick() }.onFailure { kr.archsurvey.diag.CrashLog.record("음성 저장", it) } } }
        media.recoverPending()
        // 0.1.30 T23: SP60 GST/GSA 자동 요청 결과 → 연결당 한 번 안내
        viewModelScope.launch { application.engine.precision.collect { controller.onPrecisionResult(it) } }
        controller.precisionRetry = { application.engine.retryPrecision() }
        // 0.1.30 T24: 폰 위치는 지도 점에만. 수신기가 받는 중(STREAMING/STALE)이면 폰 위치를 끈다(점이 어차피 숨음·배터리)
        viewModelScope.launch { phoneGps.fix.collect { controller.onPhoneLocation(it) } }
        viewModelScope.launch {
            application.engine.link.collect { st ->
                val receiverLive = st.state == kr.archsurvey.device.LinkState.STREAMING || st.state == kr.archsurvey.device.LinkState.STALE
                if (receiverLive) phoneGps.stopListening() else if (phoneGps.hasPermission()) phoneGps.ensureStarted()
            }
        }
        ensurePhoneLocationPermission()
    }

    /** 지도 「폰 위치(참고)」 점용 위치 권한. 이미 있으면 즉시 시작 */
    fun ensurePhoneLocationPermission() {
        if (phoneGps.hasPermission()) { if (!receiverLive()) phoneGps.ensureStarted(); return }
        val req = platform.permissionRequester
        if (req == null) return
        req(kr.archsurvey.device.PhoneGpsSource.PERMISSIONS) { ok ->
            if (ok && !receiverLive()) phoneGps.ensureStarted()
        }
    }

    private fun receiverLive(): Boolean = application.engine.link.value.state.let {
        it == kr.archsurvey.device.LinkState.STREAMING || it == kr.archsurvey.device.LinkState.STALE
    }

    fun onAppBackground() { runCatching { controller.drive?.onAppBackground() }.onFailure { kr.archsurvey.diag.CrashLog.record("Drive 자동 백업", it) } }

    /** Activity 가 앞에 오면 MainActivity.onResume 에서 부른다(권한 통로 연결 뒤) */
    fun restoreVoice() { runCatching { controller.voice?.restore() }.onFailure { kr.archsurvey.diag.CrashLog.record("음성 저장 복원", it) } }

    override fun onCleared() { controller.voice?.release(); controller.tilt?.pause(); media.stopPlayback(); media.cancelRecording() }
}
