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
    val store: RoomPointStore = RoomPointStore(SurveyDb.get(app), project = "작업1", device = {
        if (fakeNow()) "Trimble R10 (가짜 신호)" else (connection?.profile?.displayName ?: "수신기")
    })
    val controller: SurveyController = SurveyController(switchable, db, viewModelScope, store = store)
    val media = AndroidMediaPlatform(application, viewModelScope, "작업1")

    init {
        fake.start(viewModelScope, realDelayMs = 700L)
        val cc = ConnectionController(platform) { isFake -> applyMode(isFake) }
        connection = cc
        controller.connection = cc
        controller.media = media
        media.recoverPending()
        applyMode(cc.fakeSignal)
    }

    private fun applyMode(isFake: Boolean) {
        controller.fakeMode = isFake
        switchable.switchTo(if (isFake) fake else application.engine)
    }

    override fun onCleared() { fake.stop(); media.stopPlayback(); media.cancelRecording() }
}
