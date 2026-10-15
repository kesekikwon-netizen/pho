package kr.archsurvey

import android.app.Application
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.engine.SurveyEngine
import kr.archsurvey.feedback.FeedbackPlayer

/**
 * 0.0.14 사진측량: R10 은 선택. 엔진·설정은 처음 쓸 때(연결 화면을 열 때)만 만든다 → R10 을 안 쓰면 아무 일도 하지 않음.
 * 수신기 코드는 발굴 측량·사진 기준점 앱과 같은 파일(kr.archsurvey.*).
 */
class App : Application() {
    val settings by lazy { SettingsStore(this) }
    val engine by lazy { SurveyEngine(this, { settings.btStrategy(it) }, { a, s -> settings.setBtStrategy(a, s) },
        r10Port = { settings.r10Port(it) }, saveR10Port = { a, p -> settings.setR10Port(a, p) }) }
    val feedback by lazy { FeedbackPlayer(this, settings) }

    override fun onCreate() {
        super.onCreate()
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
        kr.archsurvey.diag.CrashLog.install(filesDir, ver)
        kr.archsurvey.diag.CrashLog.mirror = runCatching { getExternalFilesDir(null)?.let { java.io.File(it, "logs/crash-log.txt") } }.getOrNull()
    }
}
