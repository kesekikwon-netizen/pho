package kr.archsurvey.pg.r10

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kr.archsurvey.App
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.engine.AndroidConnectionPlatform
import kr.archsurvey.ui.ConnectionController

/**
 * 0.0.14 R10(선택). 사용자가 「R10」을 처음 누를 때만 만든다 — 그 전에는 블루투스·잠금·엔진 아무것도 안 켬(묻지도 않음).
 * 연결 화면·잠금(「여기서 쓰기」 넘겨받기)·B1 하나씩 확인 명령·GGA 만 대체는 발굴 측량/사진 기준점과 같은 코드.
 * 다른 앱이 수신기를 가져가면 연결만 끊고 사진측량은 그대로(찍던 사진 보호).
 */
class R10Hub private constructor(val app: App) {
    val platform = AndroidConnectionPlatform(app)
    private val lockBus = kr.archsurvey.engine.AndroidLockBus(app)
    private val pairPlatform = kr.archsurvey.bt.AndroidPairPlatform(app) { platform.permissionRequester }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> kr.archsurvey.pg.diag.PgLog.e("r10 task failed", e) })
    val cc = ConnectionController(platform, scope)
    val antennaDb by lazy { runCatching { AntennaDb.parse(app.assets.open("antenna_db.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyMap()) }

    init {
        cc.lock = kr.archsurvey.ui.ReceiverLock(lockBus, app.packageName, "사진측량", { android.os.SystemClock.elapsedRealtime() }, settleMs = 1_500L)
        cc.pair = kr.archsurvey.ui.PairController(pairPlatform, cc, scope, pinSetting = { app.settings.btPin }, pinSave = { app.settings.btPin = it })
        cc.onFeedback = { runCatching { app.feedback.play(it) } }
        cc.onExitForOtherApp = { kr.archsurvey.pg.diag.PgLog.i("r10 handed over to another app · capture continues without R10") }
        kr.archsurvey.pg.diag.PgLog.i("r10 hub created")
        // 0.0.14: R10 원시 기록(도착 시각 + NMEA) — 맞춤이 없어도 늘 남김. 작업 화면이면 그 작업 폴더, 아니면 앱 폴더 r10_raw/날짜.csv
        scope.launch(Dispatchers.IO) {
            var last = 0L
            app.engine.gnss.collect { g ->
                val gga = g.gga ?: return@collect
                if (g.lastGgaAtMs == last) return@collect
                last = g.lastGgaAtMs
                val la = gga.lat ?: return@collect; val lo = gga.lon ?: return@collect
                val gst = g.freshGst(g.lastGgaAtMs)
                val e = Epoch(g.lastGgaAtMs, la, lo, gga.altitudeMsl, gga.quality.name, gga.quality == kr.archsurvey.core.FixQuality.RTK_FIXED, gst?.sigmaHoriz, gst?.sigmaAlt, gga.satellites, gga.raw)
                runCatching { append(e) }
            }
        }
    }

    /** 표적 재기: 여러 번 평균, FIX 아님·10 cm 보다 나쁘면 저장 안 함. 지면 높이 = 해발 − (폴 + 위상중심) */
    suspend fun measure(epsg: Int, onProgress: (Float) -> Unit): MeasureResult {
        val zone = kr.archsurvey.core.KoreanTmZone.entries.firstOrNull { it.epsg == epsg } ?: kr.archsurvey.core.KoreanTmZone.CENTRAL
        val pole = R10Prefs.pole(app)
        val pid = app.engine.profileFlow.value?.id ?: ""
        val apc = runCatching { kr.archsurvey.data.AntennaDb.forDevice(antennaDb, pid)?.let { m -> kr.archsurvey.core.AntennaRecord.of(m, kr.archsurvey.core.AntennaConfig(pole, m.defaultRefPoint))?.apcHeightM } }.getOrNull() ?: pole
        return TagMeasurer(app.engine).measure(R10Prefs.epochs(app), zone, apc, allowNoSigma = true, onProgress = onProgress)
    }

    /** 지금 R10 위치(없으면 null) */
    fun tagNow(): GnssTag? = runCatching { GeoTag.from(app.engine.gnss.value, app.engine.link.value.state, app.engine.clockMs()) }.getOrNull()

    private fun append(e: Epoch) {
        val f = logDir?.let { java.io.File(it, RawLogs.R10) } ?: java.io.File(app.getExternalFilesDir(null), "r10_raw/" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".csv")
        f.parentFile?.mkdirs()
        if (!f.exists()) f.writeText(RawLogs.R10_HEADER + "\n")
        f.appendText(RawLogs.r10Line(e) + "\n")
    }

    companion object {
        /** 지금 찍는 작업 폴더(CaptureScreen 이 넣고 뺌) */
        @Volatile var logDir: java.io.File? = null
        var current by mutableStateOf<R10Hub?>(null); private set
        var sheetOpen by mutableStateOf(false)
        fun get(ctx: Context): R10Hub = current ?: R10Hub(ctx.applicationContext as App).also { current = it }
        /** R10 을 한 번도 안 열었으면 null — 사진은 R10 없이 그대로 */
        fun tagIfAny(): GnssTag? = current?.tagNow()
        fun connected(): Boolean = current?.let { it.app.engine.link.value.state != kr.archsurvey.device.LinkState.IDLE } == true
    }
}
