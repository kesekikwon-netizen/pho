package kr.archsurvey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios

/** 프로토타입: 가짜 GNSS(시나리오 반복)로 화면 흐름을 돌린다. 실제 수신기 연결은 Phase 1. */
class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    val fake = FakeGnssSource(FakeScenarios.fieldWalk(), loop = true)
    val db = AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })
    val controller = SurveyController(fake, db, viewModelScope)

    init { fake.start(viewModelScope, realDelayMs = 700L) }

    override fun onCleared() { fake.stop() }
}
