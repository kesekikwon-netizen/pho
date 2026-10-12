package kr.archsurvey.gcp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.feedback.bindEngineFeedback
import kr.archsurvey.gcp.data.GcpRepo
import kr.archsurvey.gcp.engine.AndroidGcpFiles
import kr.archsurvey.gcp.engine.AndroidGcpPrefs
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.ui.ConnectionController
import kr.archsurvey.ui.TileImages

/** 수신기 엔진·연결 화면 모델은 측량 앱 그대로, 장부·화면은 GcpController */
class GcpViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as App
    val platform = AndroidConnectionPlatform(application)
    private val safeScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        kr.archsurvey.diag.CrashLog.record("화면 작업", e)
        runCatching { controller.ui.fail("문제가 생겨 작업을 멈췄어요 · 진단 기록에 남겼어요") }
    })
    val antennaDb = AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() })
    val controller: GcpController = GcpController(GcpRepo(application.gcpDb.dao()), AndroidGcpFiles(application), application.engine, AndroidGcpPrefs(application), antennaDb, safeScope)
    val connection = ConnectionController(platform, safeScope)
    private val versionName: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault("")
    val env = GcpEnv(
        mapAccess = { VWorldAccess(application.settings.mapKey, application.settings.mapDomain, application.settings.mapLineOnly) },
        saveMapKey = { k -> application.settings.mapKey = k; if (k.isBlank()) application.settings.ensureBuiltinMapKey(); TileImages.clear() },
        version = versionName,
    )

    init {
        connection.ui = controller.ui
        connection.onFeedback = { application.feedback.play(it) }
        controller.connection = connection
        controller.onFeedback = { application.feedback.play(it) }
        bindEngineFeedback(viewModelScope, application.engine.events, application.feedback::play)
        viewModelScope.launch { application.engine.stoppedOutside.collect { connection.onStoppedOutside() } }
        viewModelScope.launch { application.engine.precision.collect { r -> if (r?.gstMissing == true) controller.ui.toast("수신기가 정밀도(GST)를 보내지 않아요 — 잴 때마다 한 번 물어요") } }
        controller.start()
    }

    private var autoConnectTried = false
    fun autoConnectOnLaunch() {
        if (autoConnectTried) return
        autoConnectTried = true
        runCatching { connection.autoConnectOnLaunch() }.onFailure { kr.archsurvey.diag.CrashLog.record("자동 연결", it) }
    }
}
