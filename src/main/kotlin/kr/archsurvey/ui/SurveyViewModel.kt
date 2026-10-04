package kr.archsurvey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.device.SwitchableGnssSource
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.engine.AndroidMediaPlatform

/**
 * 가짜 신호(시나리오 반복)와 실제 수신기(SurveyEngine)를 설정 토글로 바꿔 끼운다.
 * 실제 수신기 모드의 연결은 연결 화면의 [연결] 버튼으로만 시작한다(자동 연결 없음).
 */
class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as App
    val fake = FakeGnssSource(FakeScenarios.fieldWalk(), loop = true)
    val platform = AndroidConnectionPlatform(application)
    val switchable = SwitchableGnssSource(viewModelScope, fake)
    val db = AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })

    private var connection: ConnectionController? = null
    private fun fakeNow(): Boolean = connection?.fakeSignal ?: true
    val store: RoomPointStore = RoomPointStore(SurveyDb.get(app), project = { application.settings.project }, device = {
        if (fakeNow()) "Trimble R10 (가짜 신호)" else (connection?.profile?.displayName ?: "수신기")
    })
    val controller: SurveyController = SurveyController(switchable, db, viewModelScope, store = store)
    val media = AndroidMediaPlatform(application, viewModelScope) { application.settings.project }
    val docs = kr.archsurvey.engine.DocBridge()
    val exportPlatform = kr.archsurvey.engine.AndroidExportPlatform(application, { application.settings.project }, media.photoStore, media.voiceStore, docs)

    val backupPlatform = kr.archsurvey.engine.AndroidBackupPlatform(application, docs)

    /** MainActivity 가 채운다: 화면 항상 켜기 설정이 바뀔 때 창 플래그 적용 */
    var onKeepScreen: (Boolean) -> Unit = {}
    private val versionName: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault("")

    init {
        fake.start(viewModelScope, realDelayMs = 700L)
        val cc = ConnectionController(platform) { isFake -> applyMode(isFake) }
        connection = cc
        controller.connection = cc
        controller.media = media
        controller.exporter = ExportController(exportPlatform, viewModelScope)
        controller.backup = BackupController(backupPlatform, viewModelScope)
        controller.projectName = application.settings.project
        controller.settingsState = SettingsState(controller, kr.archsurvey.engine.AndroidSettingsPlatform(application.settings) { onKeepScreen(it) })
        controller.jobs = JobController(controller, kr.archsurvey.engine.AndroidJobPlatform(application, application.settings, versionName), viewModelScope)
        controller.feedback = { ok -> application.feedback.play(if (ok) kr.archsurvey.feedback.FeedbackEvent.SURVEY_OK else kr.archsurvey.feedback.FeedbackEvent.SURVEY_FAIL) }
        media.recoverPending()
        applyMode(cc.fakeSignal)
    }

    private fun applyMode(isFake: Boolean) {
        controller.fakeMode = isFake
        switchable.switchTo(if (isFake) fake else application.engine)
    }

    override fun onCleared() { fake.stop(); media.stopPlayback(); media.cancelRecording() }
}
