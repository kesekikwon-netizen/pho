package kr.archsurvey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.engine.AndroidMediaPlatform

/**
 * 화면의 위치 공급원은 실제 수신기 엔진(블루투스 NMEA)뿐이다. 연결은 연결 화면의 [연결] 버튼으로만 시작한다(자동 연결 없음).
 * 연결 전에는 위치를 표시하지 않고 "수신기 연결 안 됨" 으로 보인다.
 */
class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as App
    val platform = AndroidConnectionPlatform(application)
    val db = AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })

    private var connection: ConnectionController? = null
    val store: RoomPointStore = RoomPointStore(SurveyDb.get(app), project = { application.settings.project }, device = {
        connection?.profile?.displayName ?: "수신기"
    })
    val controller: SurveyController = SurveyController(application.engine, db, viewModelScope, store = store, anchorOnFirstFix = true)
    val media = AndroidMediaPlatform(application, viewModelScope) { application.settings.project }
    val docs = kr.archsurvey.engine.DocBridge()
    val exportPlatform = kr.archsurvey.engine.AndroidExportPlatform(application, { application.settings.project }, media.photoStore, media.voiceStore, docs)

    val backupPlatform = kr.archsurvey.engine.AndroidBackupPlatform(application, docs)

    /** MainActivity 가 채운다: 화면 항상 켜기 설정이 바뀔 때 창 플래그 적용 */
    var onKeepScreen: (Boolean) -> Unit = {}
    private val versionName: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault("")

    init {
        val cc = ConnectionController(platform)
        connection = cc
        controller.connection = cc
        controller.setTypesList(application.settings.positionTypes)
        controller.onTypesChanged = { application.settings.positionTypes = it }
        val archStore = kr.archsurvey.data.RoomArchStore(SurveyDb.get(app), project = { application.settings.project })
        controller.arch = kr.archsurvey.arch.ArchController(
            controller, archStore, viewModelScope,
            activeProvider = { application.settings.activeArch(application.settings.project) },
            onActiveChanged = { a, g -> application.settings.setActiveArch(application.settings.project, a, g) },
        )
        controller.arch.reload()
        controller.media = media
        controller.exporter = ExportController(exportPlatform, viewModelScope)
        controller.backup = BackupController(backupPlatform, viewModelScope)
        controller.projectName = application.settings.project
        controller.settingsState = SettingsState(controller, kr.archsurvey.engine.AndroidSettingsPlatform(application.settings) { onKeepScreen(it) })
        controller.jobs = JobController(controller, kr.archsurvey.engine.AndroidJobPlatform(application, application.settings, versionName), viewModelScope)
        controller.feedback = { ok -> application.feedback.play(if (ok) kr.archsurvey.feedback.FeedbackEvent.SURVEY_OK else kr.archsurvey.feedback.FeedbackEvent.SURVEY_FAIL) }
        media.recoverPending()
    }

    override fun onCleared() { media.stopPlayback(); media.cancelRecording() }
}
