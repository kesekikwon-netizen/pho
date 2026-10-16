package kr.archsurvey.pg.ui

import android.opengl.GLSurfaceView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.archsurvey.pg.ar.ArRenderer
import kr.archsurvey.pg.ar.ArSupport
import kr.archsurvey.pg.ar.Overlay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.live.Gates
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.pg.tag.TagDetector
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.ui.Minimal
import kr.archsurvey.ui.ModeChip
import kr.archsurvey.ui.Sunlight
import kr.archsurvey.ui.TapHaptics
import kr.archsurvey.ui.releaseTap
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CaptureScreen(store: Store, name: String, onBack: () -> Unit, onResult: () -> Unit, onFallback: () -> Unit = onBack, onGcp: () -> Unit = {}) {
    val ctx = LocalContext.current
    var leaving by remember { mutableStateOf(false) }
    val view = LocalView.current
    val loaded = remember { runCatching { store.loadChecked(name) }.getOrNull() }
    if (loaded == null) { LaunchedEffect(Unit) { kr.archsurvey.pg.diag.PgLog.e("capture: session unreadable $name"); onBack() }; return }
    // 0.0.16: 사진 번호(원자적, 늘기만) · 병렬 저장기(버리지 않음)
    val ids = remember { kr.archsurvey.pg.data.PhotoIds(kr.archsurvey.pg.data.PhotoIds.startFor(loaded.session, store.photos(name))) }
    val saver = remember { kr.archsurvey.pg.data.PhotoSaver(2) }
    var savingLeft by remember { mutableStateOf(0) }
    // 0.0.14: R10 레버암·센서 방향을 작업에 적어 둠(R10 없으면 기본 0 그대로)
    val engine = remember { LiveEngine(loaded.session.copy(lever = kr.archsurvey.pg.r10.R10Prefs.lever(ctx), apcM = kr.archsurvey.pg.r10.R10Prefs.apc(ctx), heightRef = kr.archsurvey.pg.r10.R10Prefs.heightRef(ctx), sensorDeg = runCatching { kr.archsurvey.pg.r10.Sensor.backDeg(ctx) }.getOrDefault(90))) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var msg by remember { mutableStateOf<String?>(when {
        loaded.fromBackup -> kr.archsurvey.pg.diag.Msg.SESSION_BROKEN
        loaded.session.stills.isNotEmpty() -> kr.archsurvey.pg.diag.Msg.SESSION_RESTORED
        !kr.archsurvey.pg.tag.AprilTagNative.available() -> kr.archsurvey.pg.diag.Msg.NATIVE_MISSING
        else -> null }) }
    var policyMsg by remember { mutableStateOf<String?>(null) }
    var autoShot by remember { mutableStateOf(true) }
    var view3d by remember { mutableStateOf(LiveEngine.View3D.POINTS) }
    var keepShooting by remember { mutableStateOf(false) }
    val opened = remember { ArSupport.open(ctx) }
    // 0.0.12: 센서 실제 크기(mm) — EXIF FocalLength·FocalPlaneResolution 용
    val sensorMm = remember(opened) { opened?.let { ArSupport.sensorSizeMm(ctx, it.session) } }
    val save: (kr.archsurvey.pg.data.Session) -> Unit = { store.save(it) }
    var lastDone by remember { mutableStateOf(false) }

    var paused by remember { mutableStateOf(false) }
    var hotNow by remember { mutableStateOf(false) }
    var bandSmall by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("pg_prefs", android.content.Context.MODE_PRIVATE) }
    // 0.0.15: 실시간 보기는 점군만(영상 바꾸기 없음 — 두 번 탭은 「따라가기」)
    val showVideo = false
    var freeView by remember { mutableStateOf(false) }
    var showThumb by remember { mutableStateOf(prefs.getBoolean("show_thumb", true)) }
    // 나가기: GL 스레드를 먼저 멈추고(onPause 는 GL 스레드가 끝날 때까지 기다림) 저장 → 세션 닫기는 다른 스레드에서(메인 스레드 멈춤 방지)
    fun leave(reason: String, then: () -> Unit) {
        if (leaving) return
        leaving = true
        kr.archsurvey.pg.diag.PgLog.i("capture leave reason=$reason photos=${engine.session.stills.size} pending=${saver.pending.get()} nextId=${ids.peek()}")
        // 0.0.16: 새 촬영을 멈추고(GL 멈춤) 남은 저장을 다 마친 뒤 마지막 저장 → 나가기. 버리지 않음
        runCatching { glView?.onPause() }
        Thread({
            val ok = saver.drain { n -> view.post { savingLeft = n } }
            engine.noteNextId(ids.peek())
            // 0.0.20: 다 끝날 때까지(예전엔 30 s 뒤 그냥 저장 → 목록 누락, 다음 열 때 저널로 되살림)
            var idleOk = false; var waits = 0
            while (!idleOk && waits < 10) { idleOk = engine.idle(); waits++ }
            if (!idleOk) kr.archsurvey.pg.diag.PgLog.e("live queue not idle after ${waits * 30}s")
            runCatching { store.save(engine.session) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("save on leave failed", it) }
            kr.archsurvey.pg.diag.PgLog.i("capture drained ok=$ok idle=$idleOk photos=${engine.session.stills.size}")
            view.post { savingLeft = 0; then() }
        }, "pg-leave").start()
    }
    // 0.0.14: R10 을 켰을 때만 원시 R10·AR 궤적을 이 작업 폴더에 남김(맞춤은 저장·내보내기 때)
    DisposableEffect(kr.archsurvey.pg.r10.R10Hub.current) {
        val hub = kr.archsurvey.pg.r10.R10Hub.current
        if (hub != null) {
            val dir = store.dir(name); kr.archsurvey.pg.r10.R10Hub.logDir = dir
            val tf = java.io.File(dir, kr.archsurvey.pg.r10.RawLogs.AR)
            val q = java.util.concurrent.LinkedBlockingQueue<String>()
            val wr = Thread { runCatching { if (!tf.exists()) tf.writeText(kr.archsurvey.pg.r10.RawLogs.AR_HEADER + "\n"); while (true) { val l = q.take(); if (l.isEmpty()) break; val sb = StringBuilder(l).append('\n'); while (true) { val m = q.poll() ?: break; if (m.isEmpty()) { tf.appendText(sb.toString()); return@runCatching }; sb.append(m).append('\n') }; tf.appendText(sb.toString()) } } }.apply { isDaemon = true; setName("ar-track"); start() }
            kr.archsurvey.pg.ar.ArTrackSink.sink = { t, p, sid -> q.offer(kr.archsurvey.pg.r10.RawLogs.arLine(kr.archsurvey.pg.r10.ArSample(t, p, sid))) }
            onDispose { kr.archsurvey.pg.ar.ArTrackSink.sink = null; kr.archsurvey.pg.r10.R10Hub.logDir = null; q.offer(""); wr.hashCode() }
        } else onDispose { }
    }
    DisposableEffect(Unit) {
        engine.onChange = { view.post { tick++ } }
        onDispose {
            engine.onChange = null
            runCatching { glView?.onPause() }
            val s = opened?.session
            engine.saveCloud(store.dir(name))
            // 0.0.16: 남은 저장을 마친 뒤 마지막 저장·닫기(다른 스레드, 메인 멈춤 없음)
            Thread({ saver.drain(); engine.noteNextId(ids.peek()); engine.post { runCatching { store.save(engine.session) } }; engine.closeAfterPending(); saver.shutdown() }, "pg-drain").start()
            Thread({ runCatching { s?.pause(); s?.close() }.onFailure { kr.archsurvey.pg.diag.PgLog.e("session close failed", it) }; kr.archsurvey.pg.diag.PgLog.i("arcore session closed") }, "pg-close").start()
        }
    }
    BackHandler(enabled = true) { if (engine.session.stills.isNotEmpty()) leave("system_back", onResult) else leave("system_back", onBack) } // S4
    val renderer = remember(opened) {
        opened?.let { op ->
            ArRenderer(op.session, engine, op.depth, onOverlay = { o -> view.post { overlay = o } }, onShot = { shot ->
                engine.pendingShots.incrementAndGet()
                // 0.0.16: 번호는 받는 순간 원자적으로(저장 순서와 무관, 다시 쓰지 않음)
                val id = ids.take(); val file = kr.archsurvey.pg.data.PhotoIds.fileName(id)
                val sensorDeg = engine.session.sensorDeg
                saver.submit { try {
                    val q = kr.archsurvey.pg.diag.Quality.check(shot.gray, shot.w, shot.h)
                    if (!q.ok) { // 0.0.11: 품질은 안내만(저장은 함)
                        kr.archsurvey.pg.diag.PgLog.i("shot ADVISE quality=${q.reason} sharp=%.1f mean=${q.mean} clipped=%.2f".format(q.sharpness, q.clipped))
                        view.post { policyMsg = q.message }
                    }
                    val gnss0 = kr.archsurvey.pg.r10.GeoTag.forPhoto(kr.archsurvey.pg.r10.R10Hub.tagIfAny())
                    val gnss = gnss0.tag
                    if (gnss0.stale) { // 0.0.20: 막지 않고 안내만, 이 사진은 R10 위치 없음
                        kr.archsurvey.pg.diag.PgLog.w("r10 stale photo=$file -> no R10 position")
                        view.post { policyMsg = "R10 위치 끊김 — 이 사진은 R10 좌표 없이 저장해요" }
                    }
                    if (gnss != null) kr.archsurvey.pg.diag.PgLog.i("r10 photo=$file q=${gnss.quality} fix=${gnss.fixed} lat=%.9f lon=%.9f alt=${gnss.altMsl} sH=${gnss.sigmaH} age=${gnss.ageMs}".format(gnss.lat, gnss.lon))
                    val td0 = System.nanoTime()
                    // P0.1 #5: 저장 사진은 원 해상도 검출(4 MP 이하 decimate 1, 넘으면 2), 오류 비트 0, 35 px 미만 버림
                    val all = TagDetector.detect(shot.gray, shot.w, shot.h, decimate = if (shot.w * shot.h > 4_000_000) 2f else 1f, maxHamming = 0)
                    val (tags, small) = kr.archsurvey.pg.diag.Quality.filterTags(all)
                    small.forEach { kr.archsurvey.pg.diag.PgLog.i("tag DROP photo=$file Target#${it.target} size=%.0fpx < 35".format(kr.archsurvey.pg.diag.Quality.tagPx(it))) }
                    tags.filter { kr.archsurvey.pg.diag.Quality.tagPx(it) < kr.archsurvey.pg.diag.Quality.TAG_GOOD_PX }.forEach { kr.archsurvey.pg.diag.PgLog.d("tag SMALL photo=$file Target#${it.target} size=%.0fpx (goal 60)".format(kr.archsurvey.pg.diag.Quality.tagPx(it))) }
                    kr.archsurvey.pg.diag.PgLog.i("photo $file ${shot.w}x${shot.h} jpeg=${shot.jpeg.size}B tags=${tags.size} detect=%.0fms".format((System.nanoTime() - td0) / 1e6))
                    tags.forEach { t -> kr.archsurvey.pg.diag.PgLog.d("  tag photo=$file Target#${t.target} id=${t.id} ham=${t.hamming} c=(%.1f,%.1f) corners=${t.corners.joinToString(",") { "%.1f".format(it) }}".format(t.cx, t.cy)) }
                    val still = Still(id, file, shot.w, shot.h, shot.fx, shot.fy, shot.cx, shot.cy, shot.pose, System.currentTimeMillis(), tags, shot.reason, gnss = gnss, speed = shot.speed, tMs = shot.tMs, arSid = shot.arSid)
                    // 0.0.16: 조각 한 번 쓰기 → EXIF 한 번(렌즈 + R10) → 기록(저널) → 이름 바꿈. 기록 없는 파일 없음
                    val ok = kr.archsurvey.pg.data.PhotoWrite.save(store.files, name, still, shot.jpeg) { part ->
                        ExifInterface(part).apply {
                            setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER); setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                            setAttribute(ExifInterface.TAG_SOFTWARE, "kr.archsurvey.pg")
                            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                            val now = System.currentTimeMillis()
                            val sw = sensorMm?.first; val sh = sw?.let { it * shot.h / shot.w }
                            val lv = kr.archsurvey.pg.export.ExifLens.compute(shot.fx, shot.fy, shot.w, shot.h, sw, sh, now, kr.archsurvey.pg.export.ExifLens.uniqueId(name, file, now))
                            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, lv.dateTime); setAttribute(ExifInterface.TAG_DATETIME, lv.dateTime)
                            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, lv.subSec); setAttribute(ExifInterface.TAG_SUBSEC_TIME, lv.subSec)
                            setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, lv.uniqueId)
                            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, lv.focal35.toString())
                            lv.focalMm?.let { setAttribute(ExifInterface.TAG_FOCAL_LENGTH, kr.archsurvey.pg.export.ExifLens.rational(it)) }
                            lv.planeXRes?.let { setAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, kr.archsurvey.pg.export.ExifLens.rational(it)) }
                            lv.planeYRes?.let { setAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, kr.archsurvey.pg.export.ExifLens.rational(it)) }
                            if (lv.planeXRes != null) setAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, "3")
                            setAttribute(ExifInterface.TAG_IMAGE_WIDTH, shot.w.toString()); setAttribute(ExifInterface.TAG_IMAGE_LENGTH, shot.h.toString())
                            // R10 FIX 면 안테나 원시값(맞춤 전) — 같은 한 번의 저장에
                            if (gnss != null && gnss.fixed) {
                                val ypr = shot.pose?.let { kr.archsurvey.pg.r10.GeoCam.ypr(it, sensorDeg, null) }
                                kr.archsurvey.pg.r10.GeoExif.apply(this, gnss.lat, gnss.lon, gnss.altMsl, gnss, levered = false, pitch = ypr?.second, roll = ypr?.third)
                            }
                            saveAttributes()
                        }
                    }
                    if (!ok) {
                        kr.archsurvey.pg.diag.PgLog.e("photo write failed $file free=${store.files.free()}")
                        view.post { msg = kr.archsurvey.pg.diag.Msg.SHOT_SAVE_FAIL }
                        return@submit
                    }
                    engine.addStill(still, save)
                    view.post { TapHaptics.tap(view) }
                } finally { engine.pendingShots.decrementAndGet() } }
            })
        }
    }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life, opened) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> runCatching { opened?.session?.resume(); glView?.onResume(); kr.archsurvey.pg.diag.PgLog.i("session resumed") }.onFailure { msg = if (it is com.google.ar.core.exceptions.CameraNotAvailableException) kr.archsurvey.pg.diag.Msg.CAM_BUSY else kr.archsurvey.pg.diag.Msg.AR_OPEN_FAIL; kr.archsurvey.pg.diag.PgLog.e("session resume failed", it) }
                Lifecycle.Event.ON_PAUSE -> runCatching { engine.saveCloud(store.dir(name)); engine.post { store.save(engine.session) }; glView?.onPause(); opened?.session?.pause(); kr.archsurvey.pg.diag.PgLog.i("session paused") }
                else -> {}
            }
        }
        life.lifecycle.addObserver(obs)
        if (life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { opened?.session?.resume() }
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(showVideo, showThumb, renderer) { renderer?.showVideo = showVideo; renderer?.showThumb = showThumb
        prefs.edit().putBoolean("show_video", showVideo).putBoolean("show_thumb", showThumb).apply()
        kr.archsurvey.pg.diag.PgLog.i("view video=$showVideo thumb=$showThumb") }
    LaunchedEffect(paused) { renderer?.paused = paused; kr.archsurvey.pg.diag.PgLog.i("capture paused=$paused") }
    LaunchedEffect(autoShot) { renderer?.auto = autoShot; kr.archsurvey.pg.diag.PgLog.i("auto shot=$autoShot") }
    DisposableEffect(Unit) {
        val cb = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { engine.trim(); msg = kr.archsurvey.pg.diag.Msg.MEMORY_LOW } }
            override fun onConfigurationChanged(c: android.content.res.Configuration) { kr.archsurvey.pg.diag.PgLog.i("config changed w=${c.screenWidthDp} h=${c.screenHeightDp}") }
            @Deprecated("") override fun onLowMemory() { engine.trim() }
        }
        ctx.registerComponentCallbacks(cb); onDispose { ctx.unregisterComponentCallbacks(cb) }
    }
    LaunchedEffect(opened) { if (opened == null) { kr.archsurvey.pg.diag.PgLog.w("capture: AR open failed → fallback"); msg = kr.archsurvey.pg.diag.Msg.AR_OPEN_FAIL; kotlinx.coroutines.delay(1500); onFallback() } else if (!opened.depth && !Minimal.on) msg = kr.archsurvey.pg.diag.Msg.NO_DEPTH }
    LaunchedEffect(Unit) {
        kr.archsurvey.pg.diag.PgLog.i("capture open session=$name arcore=${opened != null} depth=${opened?.depth} raw=${opened?.rawDepth}")
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var last = -1
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val st = pm.currentThermalStatus
                val head = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
                if (st != last) kr.archsurvey.pg.diag.PgLog.i("thermal status=$st headroom10s=$head") else kr.archsurvey.pg.diag.PgLog.d("thermal status=$st headroom10s=$head")
                last = st
                val tp = kr.archsurvey.pg.diag.ThermalPolicy.of(kr.archsurvey.pg.diag.ThermalPolicy.combine(st, head))
                val liveDepth = if (tp.depthMs == 0L) 0L else maxOf(500L, tp.depthMs)
                if (renderer != null && renderer.depthIntervalMs != liveDepth) kr.archsurvey.pg.diag.PgLog.i("S5 throttle thermal=$st headroom=$head liveDepthMs=$liveDepth coverageMs=${maxOf(1000L, tp.meshMs)} autoShot=${tp.autoShot}")
                renderer?.depthIntervalMs = liveDepth; engine.meshIntervalMs = maxOf(1000L, tp.meshMs)
                renderer?.auto = autoShot // 0.0.11: 발열은 안내만(자동 촬영 멈추지 않음)
                policyMsg = tp.message
                // S9: 단계 3↑ 미리보기 끔 + 경고 띠(단계 2↑ 갱신 간격 2배는 ThermalPolicy)
                val hot = st >= 3
                if (hot != hotNow) { kr.archsurvey.pg.diag.PgLog.i("S9 thermal stage=$st thumbOff=$hot"); hotNow = hot }
                renderer?.showThumb = showThumb && !hot
                // 0.0.19: 문구는 ThermalPolicy 하나로(멈춘다는 말 없음)
            }
            val free = store.files.free()
            val okSpace = kr.archsurvey.pg.diag.StoragePolicy.canShoot(free)
            if (renderer?.shotsAllowed != okSpace) kr.archsurvey.pg.diag.PgLog.i("storage free=${free / 1048576}MB shotsAllowed=$okSpace")
            renderer?.shotsAllowed = okSpace
            if (!okSpace) policyMsg = kr.archsurvey.pg.diag.Msg.STORAGE_LOW
            kotlinx.coroutines.delay(2_000)
        }
    }

    @Suppress("UNUSED_EXPRESSION") tick
    val pr = engine.progress
    val fit = engine.fit
    val done = pr.done && !keepShooting
    LaunchedEffect(pr.done) { if (pr.done && !lastDone) TapHaptics.ok(view); lastDone = pr.done }

    val firstTip = remember { !prefs.getBoolean("tip_double_tap", false) }
    var tip by remember { mutableStateOf(firstTip) }
    LaunchedEffect(tip) { if (tip) { kotlinx.coroutines.delay(6000); tip = false; prefs.edit().putBoolean("tip_double_tap", true).apply() } }
    val topBand = msg ?: policyMsg
    LaunchedEffect(topBand) { bandSmall = false; if (topBand != null) { kotlinx.coroutines.delay(4000); bandSmall = true } } // S2

    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding()) {
        // 맨 위: 뒤로 · 제목 · 좌표 상태 · ⋯
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clickable { if (engine.session.stills.isNotEmpty()) leave("top_back", onResult) else leave("top_back", onBack) }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
            Txt(name, bold = true, modifier = Modifier.weight(1f), maxLines = 1)
            val (chipText, chipColor) = when {
                fit != null -> {
                    val cm = (fit.looRmse ?: fit.rmse) * 100
                    "● 오차 ${"%.1f".format(cm)}cm" to (if (cm < 5) P.fix else if (cm < 15) P.float else P.error)
                }
                else -> "사진 ${pr.photos}" to P.sub
            }
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(chipColor).padding(horizontal = 12.dp, vertical = 6.dp)) { Txt(chipText, size = 15.sp, bold = true, color = Color.White, maxLines = 1) }
            Box(Modifier.size(48.dp).releaseTap("더 보기") { Minimal.moreOpen = !Minimal.moreOpen }, contentAlignment = Alignment.Center) { Txt("⋯", size = 24.sp, bold = true) }
        }
        // 가운데: 점군(기본) — 두 번 탭 = 영상/점군 바꾸기(S10)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(P.stage)) {
            if (renderer != null) {
                AndroidView(factory = { c ->
                    GLSurfaceView(c).apply {
                        preserveEGLContextOnPause = true
                        val es = if (((c.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).deviceConfigurationInfo.reqGlEsVersion) >= 0x30000) 3 else 2
                        renderer.glVersion = es
                        setEGLContextClientVersion(es); setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        glView = this
                        @Suppress("DEPRECATION") renderer.rotation = (c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
                    }
                }, modifier = Modifier.fillMaxSize())
                // 0.0.15 손가락 보기(촬영과 무관): 한 손가락 = 돌리기, 두 손가락 = 확대·이동, 두 번 탭/「따라가기」 = 카메라 따라가기
                Box(Modifier.fillMaxSize()
                    .pointerInput(renderer) { detectTapGestures(onDoubleTap = { renderer.touch.doubleTap(); renderer.followCamera(); freeView = false; tip = false; prefs.edit().putBoolean("tip_double_tap", true).apply() }) }
                    .pointerInput(renderer) {
                        awaitEachGesture {
                            val d = awaitFirstDown(requireUnconsumed = false)
                            renderer.touch.down(d.position.x, d.position.y)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pts = ev.changes.filter { it.pressed }.map { it.position.x to it.position.y }
                                if (pts.isEmpty()) break
                                renderer.touch.move(pts, size.height)
                                if (renderer.freeView && !freeView) { freeView = true; tip = false }
                            }
                            renderer.touch.up()
                        }
                    })
                if (freeView) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.9f))
                    .releaseTap("따라가기") { renderer.touch.doubleTap(); renderer.followCamera(); freeView = false }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Txt("따라가기", size = 15.sp, bold = true, color = FieldIvory.ink)
                }
            } else Txt("실시간 3D 를 열 수 없어요", color = Color.White, modifier = Modifier.align(Alignment.Center))

            val o = overlay
            val dens = LocalDensity.current
            o?.tags?.forEach { (no, x, y) ->
                val n = engine.tagCounts[no] ?: 0
                Box(Modifier.offset { IntOffset(x.toInt() - with(dens) { 28.dp.roundToPx() }, y.toInt() - with(dens) { 14.dp.roundToPx() }) }
                    .clip(RoundedCornerShape(8.dp)).background(Color.White).border(2.dp, if (n >= 3) FieldIvory.fix else FieldIvory.ink, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)) { Txt("#$no ${minOf(n, 3)}/3", size = 14.sp, bold = true, color = FieldIvory.ink) }
            }
            val tg = o?.target
            if (!done && tg != null && pr.hint != null) {
                val cx = with(dens) { maxWidth.toPx() } / 2; val cy = with(dens) { maxHeight.toPx() } / 2
                Canvas(Modifier.fillMaxSize()) {
                    val dx = tg[0] - cx; val dy = tg[1] - cy
                    val len = kotlin.math.hypot(dx, dy)
                    if (len > 40f) {
                        val end = Offset(cx + dx * 0.85f, cy + dy * 0.85f); val a = atan2(dy, dx)
                        drawLine(Color.White, Offset(cx, cy), end, strokeWidth = 14f, cap = StrokeCap.Round)
                        for (s in listOf(2.6f, -2.6f)) drawLine(Color.White, end, Offset(end.x - 48f * cos(a + s / 3), end.y - 48f * sin(a + s / 3)), strokeWidth = 14f, cap = StrokeCap.Round)
                    }
                }
            }
            // 위 띠들(가운데 상자 없음, S2)
            Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (o != null && (o.slow || !o.tracking)) Band(if (o.slow) "천천히" else kr.archsurvey.pg.diag.Msg.tracking(o.failure), FieldIvory.error, big = true)
                if (leaving && savingLeft > 0) Band("저장 중 ${savingLeft}장", FieldIvory.ink, big = true) // 0.0.16
                if (paused) Band("⏸ 일시정지", FieldIvory.ink)
                if (!autoShot && !paused) Band("자동 촬영 꺼짐 · 셔터 길게 눌러 켜기", FieldIvory.ink)
                if (CaptureRules.noTargetWarn(pr.photos, engine.tagCounts.size)) Band(CaptureRules.NO_TARGET, Color(0xFFF2C200), ink = FieldIvory.ink) // M14
                topBand?.let { m -> Band(if (bandSmall) m.take(14) + if (m.length > 14) "…" else "" else m, FieldIvory.float, small = bandSmall) { msg = null } }
                if (tip) Band("끌어 돌리기 · 두 손가락 확대/이동 · 두 번 탭 = 따라가기", FieldIvory.ink)
            }
        }
        // 아래: 충분 % + 화살표 한 줄(S3) · 오른쪽 아래 [⏸][끝][셔터](M13)
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (done) Txt("✓ 충분", size = 26.sp, bold = true, color = P.fix) else Txt("충분 ${pr.pct}%", size = 26.sp, bold = true)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(P.line)) {
                    Box(Modifier.fillMaxWidth(pr.pct / 100f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(if (pr.done) P.fix else P.primary))
                }
                val tg = overlay?.target
                if (!done && tg != null && pr.hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt(Gates.arrowText(tg[0].toDouble(), tg[1].toDouble(), lower = true).let { if (overlay?.targetOff == true) "$it (화면 밖)" else it }, size = 16.sp, bold = true, color = P.sub, maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.dp))
            Choice(if (paused) "▶" else "⏸", paused) { paused = !paused }
            Spacer(Modifier.width(8.dp))
            if (done) PrimaryBtn("끝", Modifier.width(76.dp), color = P.fix) { leave("finish", onResult) } else OutlineBtn("끝", Modifier.width(76.dp)) { leave("finish", onResult) }
            Spacer(Modifier.width(8.dp))
            Shutter(if (paused) "멈춤" else if (autoShot) "자동" else "촬영", onLong = { autoShot = !autoShot; TapHaptics.ok(view) }) { renderer?.requestShot() } // 0.0.13: 일시정지 중에도 수동은 찍힘
        }
    }

    // ⋯ 메뉴 3개(M13): 좌표 입력 · 결과 · 로그
    if (Minimal.moreOpen) MoreSheet(onClose = { Minimal.moreOpen = false }) {
        OutlineBtn("좌표 입력", Modifier.fillMaxWidth()) { Minimal.moreOpen = false; leave("menu_gcp", onGcp) }
        OutlineBtn("결과", Modifier.fillMaxWidth()) { Minimal.moreOpen = false; leave("menu_result", onResult) }
        OutlineBtn("R10 (선택)", Modifier.fillMaxWidth()) { Minimal.moreOpen = false; kr.archsurvey.pg.r10.R10Hub.sheetOpen = true }
        OutlineBtn("로그 공유", Modifier.fillMaxWidth()) { Minimal.moreOpen = false; runCatching { kr.archsurvey.pg.diag.PgLog.share(ctx) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("log share failed", it) } }
    }
}

/** 위 띠 하나 */
@Composable
private fun Band(text: String, bg: Color, ink: Color = Color.White, big: Boolean = false, small: Boolean = false, onTap: (() -> Unit)? = null) {
    val m = Modifier.clip(RoundedCornerShape(16.dp)).background(bg)
    Box((if (onTap != null) m.releaseTap("닫기") { onTap() } else m).padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 4.dp else 8.dp)) {
        Txt(text, size = if (big) 20.sp else if (small) 13.sp else 16.sp, bold = true, color = ink, maxLines = 2)
    }
}

/** 촬영 화면 규칙(시험용) */
object CaptureRules {
    const val NO_TARGET = "표적이 안 보여요 · 표적이 사진에 들어오게 찍어 주세요"
    /** M14: 사진 10장 넘고(11장째부터) 표적 0개 */
    fun noTargetWarn(photos: Int, targets: Int) = photos > 10 && targets == 0
    /** M13: 아래 버튼·⋯ 항목 */
    val BOTTOM = listOf("⏸", "끝", "셔터")
    val MENU = listOf("좌표 입력", "결과", "로그 공유")
}

/** 아래에서 올라오는 ⋯ 메뉴(선택만) */
@Composable
fun MoreSheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)).releaseTap("닫기", guardMs = 200) { onClose() }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(P.bg)
            .releaseTap(null, guardMs = 0) { }.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Suppress("unused") private fun View.noop() = Unit
