package kr.archsurvey

import android.app.Application
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.engine.SurveyEngine
import kr.archsurvey.feedback.FeedbackPlayer
import kr.archsurvey.gcp.data.GcpDb

/** 사진 기준점 앱. 수신기 엔진·설정 저장소·소리는 측량 앱과 같은 코드(kr.archsurvey.*), 장부(유적·대상·차수·표적)는 GcpDb */
class App : Application() {
    val settings by lazy { SettingsStore(this) }
    val gcpDb by lazy { GcpDb.get(this) }
    val engine by lazy { SurveyEngine(this, { settings.btStrategy(it) }, { a, s -> settings.setBtStrategy(a, s) },
        r10Port = { settings.r10Port(it) }, saveR10Port = { a, p -> settings.setR10Port(a, p) }) }
    val feedback by lazy { FeedbackPlayer(this, settings) }

    override fun onCreate() {
        super.onCreate()
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
        kr.archsurvey.diag.CrashLog.install(filesDir, ver)
        kr.archsurvey.diag.CrashLog.mirror = runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "logs/crash-log.txt") } }.getOrNull()
        runCatching { settings.ensureBuiltinMapKey() }
    }
}
