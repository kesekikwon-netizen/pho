package kr.archsurvey

import android.app.Application
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.engine.SurveyEngine
import kr.archsurvey.feedback.FeedbackPlayer

class App : Application() {
    val settings by lazy { SettingsStore(this) }
    val db by lazy { SurveyDb.get(this) }
    val engine by lazy { SurveyEngine(this, { settings.btStrategy(it) }, { a, s -> settings.setBtStrategy(a, s) },
        r10Port = { settings.r10Port(it) }, saveR10Port = { a, p -> settings.setR10Port(a, p) }) }
    val feedback by lazy { FeedbackPlayer(this, settings) }

    override fun onCreate() {
        super.onCreate()
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
        kr.archsurvey.diag.CrashLog.install(filesDir, ver)
        kr.archsurvey.ui.BgImages.cacheDir = cacheDir // 0.1.58 B2
        kr.archsurvey.diag.CrashLog.mirror = runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "logs/crash-log.txt") } }.getOrNull()
        // V월드: Pref 비어 있으면 내장 키를 secure 에 심어 업데이트·UI 에서 "키 없음"처럼 보이지 않게
        runCatching { settings.ensureBuiltinMapKey() }
    }
}
