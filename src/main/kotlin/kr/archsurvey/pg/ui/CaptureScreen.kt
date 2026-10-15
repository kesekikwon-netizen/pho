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
import kr.archsurvey.pg.data.GcpTxt
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
    val engine = remember { LiveEngine(loaded.session) }
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
    val save: (kr.archsurvey.pg.data.Session) -> Unit = { store.save(it) }
    var lastDone by remember { mutableStateOf(false) }

    var paused by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("pg_prefs", android.content.Context.MODE_PRIVATE) }
    var showVideo by remember { mutableStateOf(prefs.getBoolean("show_video", false)) }
    var showThumb by remember { mutableStateOf(prefs.getBoolean("show_thumb", true)) }
    // 나가기: GL 스레드를 먼저 멈추고(onPause 는 GL 스레드가 끝날 때까지 기다림) 저장 → 세션 닫기는 다른 스레드에서(메인 스레드 멈춤 방지)
    fun leave(reason: String, then: () -> Unit) {
        if (leaving) return
        leaving = true
        kr.archsurvey.pg.diag.PgLog.i("capture leave reason=$reason photos=${engine.session.stills.size}")
        runCatching { store.save(engine.session) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("save on leave failed", it) }
        then()
    }
    DisposableEffect(Unit) {
        engine.onChange = { view.post { tick++ } }
        onDispose {
            engine.onChange = null
            runCatching { glView?.onPause() }
            val s = opened?.session
            engine.close()
            Thread({ runCatching { s?.pause(); s?.close() }.onFailure { kr.archsurvey.pg.diag.PgLog.e("session close failed", it) }; kr.archsurvey.pg.diag.PgLog.i("arcore session closed") }, "pg-close").start()
        }
    }
    BackHandler(enabled = true) { leave("system_back", onBack) }
    val pickGcp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s -> GcpTxt.report(s.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8)) } }
                .getOrElse { kr.archsurvey.pg.diag.PgLog.e("gcp read failed", it); GcpTxt.Report(emptyList(), 0, 0, "파일을 열지 못했어요") }
            kr.archsurvey.pg.diag.PgLog.i("gcp import n=${r.gcps.size} skipped=${r.skipped} dup=${r.duplicates}")
            msg = r.message
            if (r.gcps.isNotEmpty()) engine.setGcps(r.gcps, save)
        }
    }

    val renderer = remember(opened) {
        opened?.let { op ->
            ArRenderer(op.session, engine, op.depth, onOverlay = { o -> view.post { overlay = o } }, onShot = { shot ->
                engine.post {
                    val id = (engine.session.stills.maxOfOrNull { it.id } ?: 0) + 1
                    val file = String.format(java.util.Locale.US, "IMG_%04d.jpg", id)
                    val q = kr.archsurvey.pg.diag.Quality.check(shot.gray, shot.w, shot.h)
                    if (!q.ok) {
                        kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=${q.reason} sharp=%.1f mean=${q.mean} clipped=%.2f".format(q.sharpness, q.clipped))
                        view.post { policyMsg = q.message }
                        return@post
                    }
                    if (!store.files.writePhoto(name, file, shot.jpeg)) {
                        kr.archsurvey.pg.diag.PgLog.e("photo write failed $file free=${store.files.free()}")
                        view.post { msg = kr.archsurvey.pg.diag.Msg.SHOT_SAVE_FAIL }
                        return@post
                    }
                    val f = File(store.photos(name), file)
                    runCatching {
                        ExifInterface(f).apply {
                            setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER); setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                            setAttribute(ExifInterface.TAG_SOFTWARE, "kr.archsurvey.pg")
                            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString()) // 화소 그대로가 바른 방향(센서 방향)
                            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, Math.round((shot.fx + shot.fy) / 2 / maxOf(shot.w, shot.h) * 36).toString())
                            setAttribute(ExifInterface.TAG_IMAGE_WIDTH, shot.w.toString()); setAttribute(ExifInterface.TAG_IMAGE_LENGTH, shot.h.toString())
                            saveAttributes()
                        }
                    }
                    val td0 = System.nanoTime()
                    // P0.1 #5: 저장 사진은 원 해상도 검출(4 MP 이하 decimate 1, 넘으면 2), 오류 비트 0, 35 px 미만 버림
                    val all = TagDetector.detect(shot.gray, shot.w, shot.h, decimate = if (shot.w * shot.h > 4_000_000) 2f else 1f, maxHamming = 0)
                    val (tags, small) = kr.archsurvey.pg.diag.Quality.filterTags(all)
                    small.forEach { kr.archsurvey.pg.diag.PgLog.i("tag DROP photo=$file Target#${it.target} size=%.0fpx < 35".format(kr.archsurvey.pg.diag.Quality.tagPx(it))) }
                    tags.filter { kr.archsurvey.pg.diag.Quality.tagPx(it) < kr.archsurvey.pg.diag.Quality.TAG_GOOD_PX }.forEach { kr.archsurvey.pg.diag.PgLog.d("tag SMALL photo=$file Target#${it.target} size=%.0fpx (goal 60)".format(kr.archsurvey.pg.diag.Quality.tagPx(it))) }
                    kr.archsurvey.pg.diag.PgLog.i("photo $file ${shot.w}x${shot.h} jpeg=${shot.jpeg.size}B tags=${tags.size} detect=%.0fms".format((System.nanoTime() - td0) / 1e6))
                    tags.forEach { t -> kr.archsurvey.pg.diag.PgLog.d("  tag photo=$file Target#${t.target} id=${t.id} ham=${t.hamming} c=(%.1f,%.1f) corners=${t.corners.joinToString(",") { "%.1f".format(it) }}".format(t.cx, t.cy)) }
                    engine.addStill(Still(id, file, shot.w, shot.h, shot.fx, shot.fy, shot.cx, shot.cy, shot.pose, System.currentTimeMillis(), tags), save)
                    view.post { TapHaptics.tap(view) }
                }
            })
        }
    }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life, opened) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> runCatching { opened?.session?.resume(); glView?.onResume(); kr.archsurvey.pg.diag.PgLog.i("session resumed") }.onFailure { msg = if (it is com.google.ar.core.exceptions.CameraNotAvailableException) kr.archsurvey.pg.diag.Msg.CAM_BUSY else kr.archsurvey.pg.diag.Msg.AR_OPEN_FAIL; kr.archsurvey.pg.diag.PgLog.e("session resume failed", it) }
                Lifecycle.Event.ON_PAUSE -> runCatching { glView?.onPause(); opened?.session?.pause(); kr.archsurvey.pg.diag.PgLog.i("session paused") }
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
                renderer?.auto = autoShot && tp.autoShot
                policyMsg = tp.message
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

    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding()) {
        // 맨 위: 뒤로 · 제목 · 좌표 상태 · ⋯
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clickable { leave("top_back", onBack) }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
            Txt("$name · 3D 촬영", bold = true, modifier = Modifier.weight(1f), maxLines = 1)
            val (chipText, chipColor) = when {
                fit != null -> {
                    val cm = (fit.looRmse ?: fit.rmse) * 100
                    "● 오차 ${"%.1f".format(cm)}cm" to (if (cm < 5) P.fix else if (cm < 15) P.float else P.error)
                }
                engine.scale != null -> "○ 로컬 · 축척 ${engine.scale!!.scatterPct?.let { "±%.0f%%".format(it) } ?: "있음"}" to P.sub
                else -> "○ 로컬 좌표" to P.sub
            }
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(chipColor).padding(horizontal = 12.dp, vertical = 6.dp)) { Txt(chipText, size = 15.sp, bold = true, color = Color.White, maxLines = 1) }
            Box(Modifier.size(48.dp).releaseTap("더 보기") { Minimal.moreOpen = !Minimal.moreOpen }, contentAlignment = Alignment.Center) { Txt("⋯", size = 24.sp, bold = true) }
        }
        // 가운데: 카메라 + 쌓이는 3D
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(P.stage)) {
            if (renderer != null) {
                AndroidView(factory = { c ->
                    GLSurfaceView(c).apply {
                        preserveEGLContextOnPause = true
                        // GLES 3 되면 3, 아니면 2 (셰이더는 둘 다 되는 GLSL ES 1.00)
                        val es = if (((c.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).deviceConfigurationInfo.reqGlEsVersion) >= 0x30000) 3 else 2
                        renderer.glVersion = es
                        setEGLContextClientVersion(es); setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        glView = this
                        @Suppress("DEPRECATION") renderer.rotation = (c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
                    }
                }, modifier = Modifier.fillMaxSize())
            } else Txt("실시간 3D 를 열 수 없어요", color = Color.White, modifier = Modifier.align(Alignment.Center))

            if (!Minimal.on) Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendChip(FieldIvory.fix, "충분"); LegendChip(FieldIvory.float, "조금 더"); LegendChip(FieldIvory.error, "부족")
            }
            val o = overlay
            val dens = LocalDensity.current
            // 표적 칩 「#N n/3」
            o?.tags?.forEach { (no, x, y) ->
                val n = engine.tagCounts[no] ?: 0
                Box(Modifier.offset { IntOffset(x.toInt() - with(dens) { 28.dp.roundToPx() }, y.toInt() - with(dens) { 14.dp.roundToPx() }) }
                    .clip(RoundedCornerShape(8.dp)).background(Color.White).border(2.dp, if (n >= 3) FieldIvory.fix else FieldIvory.ink, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)) { Txt("#$no ${minOf(n, 3)}/3", size = 14.sp, bold = true, color = FieldIvory.ink) }
            }
            // 다음 촬영 화살표 + 한 줄 안내
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
                val dir = Gates.arrowText((tg[0] - cx).toDouble(), (tg[1] - cy).toDouble(), lower = true)
                Column(Modifier.align(Alignment.BottomEnd).padding(12.dp).widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Txt(if (o.targetOff) "$dir (화면 밖)" else dir, bold = true, color = FieldIvory.ink)
                    Txt(pr.hint, size = 15.sp, color = FieldIvory.inkSub)
                }
            }
            if (o != null && (o.slow || !o.tracking)) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(16.dp)).background(FieldIvory.error).padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Txt(if (o.slow) "천천히" else kr.archsurvey.pg.diag.Msg.tracking(o.failure), size = 20.sp, bold = true, color = Color.White)
                }
            }
            if (paused || !autoShot) Box(Modifier.align(Alignment.TopCenter).padding(top = if (o != null && (o.slow || !o.tracking)) 110.dp else 56.dp).clip(RoundedCornerShape(16.dp)).background(FieldIvory.ink).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Txt(if (paused) "⏸ 일시정지 · 저장·쌓기 멈춤" else "자동 촬영 꺼짐 · 셔터로만 찍어요", bold = true, color = Color.White)
            }
            policyMsg?.let { m -> Box(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).clip(RoundedCornerShape(16.dp)).background(FieldIvory.float).padding(horizontal = 16.dp, vertical = 8.dp)) { Txt(m, bold = true, color = Color.White) } }
            msg?.let { m ->
                Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color.White).releaseTap("닫기") { msg = null }.padding(16.dp)) { Txt(m, color = FieldIvory.ink) }
            }
        }
        // 아래: 충분 % · 막대 · 한 줄 · [되돌리기] [자동] [셔터/내보내기]
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (done) Txt("✓ 충분 · 촬영 끝", size = 26.sp, bold = true, color = P.fix) else Txt("충분 ${pr.pct}%", size = 26.sp, bold = true)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(P.line)) {
                Box(Modifier.fillMaxWidth(pr.pct / 100f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(if (pr.done) P.fix else P.primary))
            }
            if (!Minimal.on) {
                Spacer(Modifier.height(6.dp))
                Txt("사진 ${pr.photos}장 · 겹침 ${pr.overlapPct}% · 표적 ${pr.tagsOk}/${pr.tagsTotal} · 구멍 ${pr.holesPct}%" + (overlay?.depthMode?.let { " · $it" } ?: ""), size = 15.sp, color = P.sub)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlineBtn("되돌리기", enabled = pr.photos > 0) { engine.undo(save) }
                Spacer(Modifier.width(10.dp))
                if (!Minimal.on && !done) Choice(if (autoShot) "자동 2초 켬" else "자동 꺼짐", autoShot) { autoShot = !autoShot }
                Spacer(Modifier.weight(1f))
                // 오른손 엄지: 위 = 일시정지/계속 · 촬영 끝, 아래 = 셔터
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice("점군", !showVideo) { showVideo = false }
                        Choice("영상+점", showVideo) { showVideo = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(if (paused) "▶ 계속" else "⏸ 일시정지", paused) { paused = !paused }
                        OutlineBtn("■ 촬영 끝") { leave("finish", onResult) }
                    }
                    if (done) PrimaryBtn("3D 저장 · iTwin 내보내기", Modifier.width(230.dp), color = P.fix) { leave("done", onResult) }
                    else Shutter(if (paused) "멈춤" else "촬영") { if (!paused) renderer?.requestShot() }
                }
            }
        }
    }

    if (Minimal.moreOpen) MoreSheet(onClose = { Minimal.moreOpen = false }) {
        Txt("좌표", bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineBtn("좌표 TXT 불러오기") { Minimal.moreOpen = false; pickGcp.launch(arrayOf("text/*", "application/octet-stream")) }
            if (engine.session.gcps.isNotEmpty()) OutlineBtn("좌표 빼기") { engine.setGcps(emptyList(), save) }
        }
        Txt("사진 기준점 앱의 「Target #N,E,N,H」 TXT 를 고르세요", size = 14.sp, color = P.sub)
        Spacer(Modifier.height(12.dp))
        Txt("축척 (좌표 없을 때) · 표적 검은 네모 한 변", bold = true)
        val s = engine.session
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((l, v) in listOf("없음" to null, "10cm" to 0.10, "15cm" to 0.15, "20cm" to 0.20, "30cm" to 0.30))
                Choice(l, s.tagSizeM == v) { engine.setScale(v, s.barM, save) }
        }
        Spacer(Modifier.height(6.dp))
        Txt("축척 막대(양 끝 표적 사이)", bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((l, v) in listOf("없음" to null, "0.5m" to 0.5, "1m" to 1.0, "2m" to 2.0))
                Choice(l, s.barM == v) { engine.setScale(s.tagSizeM, v, save) }
        }
        engine.scale?.let { Txt("축척 ${"%.4f".format(it.s)} · ${it.source}" + (it.scatterPct?.let { p -> " · ±%.1f%%".format(p) } ?: ""), size = 14.sp, color = P.sub) }
            ?: Txt("축척 추정(검증 안 됨) · ARCore 값 그대로", size = 14.sp, color = P.sub)
        OutlineBtn("좌표 입력") { Minimal.moreOpen = false; onGcp() }
        Spacer(Modifier.height(12.dp))
        Txt("화면", bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("점군", !showVideo) { showVideo = false }; Choice("영상+점", showVideo) { showVideo = true }
            Choice(if (showThumb) "작은 영상 켬" else "작은 영상 끔", showThumb) { showThumb = !showThumb }
        }
        Spacer(Modifier.height(12.dp))
        Txt("실시간 3D 보기", bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in LiveEngine.View3D.values()) Choice(v.label, view3d == v) { view3d = v; engine.view3d = v; engine.refreshCloud(); kr.archsurvey.pg.diag.PgLog.i("view3d -> $v") }
        }
        Spacer(Modifier.height(12.dp))
        NameStyleRow()
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(if (autoShot) "자동 촬영 켬" else "자동 촬영 끔", autoShot) { autoShot = !autoShot }
            if (pr.done) Choice("계속 촬영", keepShooting) { keepShooting = !keepShooting }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice("☀ 햇빛", Sunlight.on) { Sunlight.set(ctx, !Sunlight.on) }
        }
        Spacer(Modifier.height(12.dp))
        OutlineBtn("로그 공유") { Minimal.moreOpen = false; runCatching { kr.archsurvey.pg.diag.PgLog.share(ctx) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("log share failed", it) } }
        Spacer(Modifier.height(12.dp))
        OutlineBtn("결과 · iTwin 내보내기") { Minimal.moreOpen = false; leave("menu_result", onResult) }
    }
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

/** 기준점 이름 형식(선택만, 폰에 저장) */
@Composable
fun NameStyleRow() {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(kr.archsurvey.pg.tag.TargetNaming.style) }
    Txt("기준점 이름 형식", bold = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (st in kr.archsurvey.pg.tag.TargetNaming.Style.values()) Choice(st.label, cur == st) {
            kr.archsurvey.pg.tag.TargetNaming.style = st; cur = st
            ctx.getSharedPreferences("pg_prefs", android.content.Context.MODE_PRIVATE).edit().putString("name_style", st.name).apply()
            kr.archsurvey.pg.diag.PgLog.i("name style -> ${st.name}")
        }
    }
    Txt("iTwin 이 붙이는 이름과 같아야 해요 · 기본 Target #N (Flex 확인 전)", size = 14.sp, color = P.sub)
}

@Suppress("unused") private fun View.noop() = Unit
