package kr.archsurvey

import android.app.Application
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.engine.SurveyEngine
import kr.archsurvey.feedback.FeedbackPlayer

class App : Application() {
    val settings by lazy { SettingsStore(this) }
    val db by lazy { SurveyDb.get(this) }
    val engine by lazy { SurveyEngine(this, { settings.btStrategy(it) }, { a, s -> settings.setBtStrategy(a, s) }) }
    val feedback by lazy { FeedbackPlayer(this, settings) }
}
