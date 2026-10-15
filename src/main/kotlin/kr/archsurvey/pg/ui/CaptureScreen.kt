package kr.archsurvey.pg.ui

import android.opengl.GLSurfaceView
import android.view.View
import androidx.activity.compose.BackHandler
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
fun CaptureScreen(store: Store, name: String, onBack: () -> Unit, onResult: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val engine = remember { LiveEngine(store.load(name)) }
    var tick by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var autoShot by remember { mutableStateOf(true) }
    var keepShooting by remember { mutableStateOf(false) }
    val opened = remember { ArSupport.open(ctx) }
    val save: (kr.archsurvey.pg.data.Session) -> Unit = { store.save(it) }
    var lastDone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        engine.onChange = { view.post { tick++ } }
        onDispose { engine.onChange = null; engine.close(); opened?.session?.close() }
    }
    BackHandler { onBack() }
    val pickGcp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val g = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { GcpTxt.parse(it.readBytes().toString(Charsets.UTF_8)) } }.getOrDefault(emptyList())
            msg = if (g.isEmpty()) "좌표를 읽지 못했어요 (Target #N,E,N,H 형식)" else "좌표 ${g.size}점 불러왔어요"
            if (g.isNotEmpty()) engine.setGcps(g, save)
        }
    }

    val renderer = remember(opened) {
        opened?.let { op ->
            ArRenderer(op.session, engine, op.depth, onOverlay = { o -> view.post { overlay = o } }, onShot = { shot ->
                engine.post {
                    val id = (engine.session.stills.maxOfOrNull { it.id } ?: 0) + 1
                    val file = String.format(java.util.Locale.US, "IMG_%04d.jpg", id)
                    val f = File(store.photos(name), file)
                    f.writeBytes(shot.jpeg)
                    runCatching {
                        ExifInterface(f).apply {
                            setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER); setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                            setAttribute(ExifInterface.TAG_SOFTWARE, "kr.archsurvey.pg"); saveAttributes()
                        }
                    }
                    val tags = TagDetector.detect(shot.gray, shot.w, shot.h, decimate = 1.5f)
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
                Lifecycle.Event.ON_RESUME -> runCatching { opened?.session?.resume() }.onFailure { msg = "카메라를 열지 못했어요" }
                Lifecycle.Event.ON_PAUSE -> runCatching { opened?.session?.pause() }
                else -> {}
            }
        }
        life.lifecycle.addObserver(obs)
        if (life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { opened?.session?.resume() }
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(autoShot) { renderer?.auto = autoShot }

    @Suppress("UNUSED_EXPRESSION") tick
    val pr = engine.progress
    val fit = engine.fit
    val done = pr.done && !keepShooting
    LaunchedEffect(pr.done) { if (pr.done && !lastDone) TapHaptics.ok(view); lastDone = pr.done }

    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding()) {
        // 맨 위: 뒤로 · 제목 · 좌표 상태 · ⋯
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).releaseTap("뒤로") { onBack() }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
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
                        preserveEGLContextOnPause = true; setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
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
                    Txt(if (o.slow) "천천히" else "폰을 천천히 움직여 주세요", size = 20.sp, bold = true, color = Color.White)
                }
            }
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
                if (!Minimal.on && !done) Choice(if (autoShot) "자동 2초" else "자동 끔", autoShot) { autoShot = !autoShot }
                Spacer(Modifier.weight(1f))
                if (done) PrimaryBtn("3D 저장 · iTwin 내보내기", Modifier.width(230.dp), color = P.fix) { onResult() }
                else Shutter("촬영") { renderer?.requestShot() }
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(if (autoShot) "자동 촬영 켬" else "자동 촬영 끔", autoShot) { autoShot = !autoShot }
            if (pr.done) Choice("계속 촬영", keepShooting) { keepShooting = !keepShooting }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChip(P.ink, P.bg, P.ink); Choice("☀ 햇빛", Sunlight.on) { Sunlight.set(ctx, !Sunlight.on) }
        }
        Spacer(Modifier.height(12.dp))
        OutlineBtn("결과 · iTwin 내보내기") { Minimal.moreOpen = false; onResult() }
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

@Suppress("unused") private fun View.noop() = Unit
