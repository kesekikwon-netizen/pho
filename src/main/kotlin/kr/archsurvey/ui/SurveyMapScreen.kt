package kr.archsurvey.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.*
import kr.archsurvey.map.MapSurface

internal fun statusColors(t: Tokens, s: SurveyStatus): Pair<Color, Color> = when (s) {
    SurveyStatus.FIXED -> t.fixed to t.onFixed
    SurveyStatus.CAUTION -> t.caution to t.onCaution
    SurveyStatus.UNAVAILABLE -> t.bad to t.onBad
}

internal fun f3(x: Double) = String.format(java.util.Locale.US, "%.3f", x)

/** 측량 + 지도 상시 화면. 상태 띠 / 지도 / 정보 / 컨트롤 네 영역이 겹치지 않는다. */
@Composable
fun SurveyMapScreen(
    c: SurveyController, batteryPct: Int?, map: MapSurface = rememberMapSurface(c),
) {
    val t = T
    Box(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.systemBars).testTag("screen")) {
        Column(Modifier.fillMaxSize()) {
            MapArea(c, map, Modifier.weight(1f))
            if (SurveyLayout.isResult(c.phase)) ResultPanel(c) else { InfoZone(c); ControlArea(c) }
        }
        DrawerOverlay(c)
        if (c.quickSheetOpen) QuickSheet(c)
        if (c.dupPrompt != null) DupSheet(c)
        c.bg?.let { b ->
            androidx.activity.compose.BackHandler(enabled = b.screen != kr.archsurvey.ui.bg.BgScreen.NONE) { b.close() }
            kr.archsurvey.ui.bg.BackgroundSheets(b)
        }
        if (c.setupOpen) SetupSheet(c)
        if (c.antennaSheetOpen) AntennaSheet(c)
        if (c.hub.screen != null) ToolHost(c)
        c.exporter?.let { if (it.open) ExportScreen(it) }
        c.backup?.let { if (it.open) BackupScreen(it) }
        c.connection?.let { ConnectionHost(c, it) }
    }
}

// ---------------- 상태 띠 ----------------

private fun cm1(m: Double) = String.format(java.util.Locale.US, "%.1f", m * 100.0)

/** 상태 띠 셋째 줄: Fix 종류 · 위성 수 · 수평/수직 정확도(cm). 값이 없으면 "—" */
internal fun bandDetail(c: SurveyController): String {
    val g = c.gv
    return "${g.quality.labelKo} · 위성 ${g.satellites}" + (g.satsInView?.let { "/$it" } ?: "") + " · 수평 " + (g.status.sigmaHM?.let { "±${cm1(it)}cm" } ?: "—") + " · 수직 " + (g.sigmaVM?.let { "±${cm1(it)}cm" } ?: "—")
}

/** 수신기가 연결되지 않았을 때 지도 가운데에 뜨는 안내 카드 */
@Composable
fun NotConnectedCard(c: SurveyController, modifier: Modifier) {
    if (c.gv.status.reason != StatusReason.NOT_CONNECTED) return
    val t = T
    Column(
        modifier.padding(horizontal = 40.dp).clip(RoundedCornerShape(18.dp)).background(t.bg.copy(alpha = 0.96f))
            .border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(16.dp).testTag("not_connected_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("수신기 연결 안 됨", style = Ty.title, color = t.ink)
        Text("위치를 받으려면 블루투스로 수신기(R10 / SP60)를 연결하세요. 연결 전에는 위치를 표시하지 않고 측점도 할 수 없어요.", style = Ty.small, color = t.inkSub, textAlign = TextAlign.Center)
        Box(
            Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).glossy(t.primary)
                .clickable { c.connection?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } }.testTag("not_connected_open"),
            contentAlignment = Alignment.Center,
        ) { Text("수신기 연결하기", style = Ty.label, color = t.onPrimary) }
    }
}

// ---------------- 지도 영역 ----------------

@Composable
fun MapArea(c: SurveyController, map: MapSurface, modifier: Modifier) {
    val t = T
    val ms = c.mapState()
    Box(
        modifier.fillMaxWidth().testTag("map_area").pointerInput(Unit) {
            detectDragGestures { _, drag ->
                val ppm = c.dpPerM * density
                c.panBy(-drag.x / ppm, drag.y / ppm, c.mapState().center)
            }
        },
    ) {
        map.Draw(ms, Modifier.fillMaxSize())
        // 위: 상태 카드 + 탭 (목업 s1)
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusBand(c)
        }
        // 왼쪽: 현재 좌표 + 지도 도구
        Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 84.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!SurveyLayout.isResult(c.phase)) CoordBox(c)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolBtn(Glyph.LOCATE, "내 위치 고정/해제", "tool_locate", if (c.follow) MKind.PRIMARY else MKind.PLAIN) { if (c.follow) c.follow = false else c.recenter() }
                ToolBtn(Glyph.EXTENT, "전체 보기", "tool_fit") { c.fitAll() }
                ToolBtn(Glyph.PLUS, "확대", "tool_zoom_in") { c.zoomIn() }
                ToolBtn(Glyph.MINUS, "축소", "tool_zoom_out") { c.zoomOut() }
            }
        }
        // 오른쪽: 점 목록 · 안테나 높이
        Column(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 84.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolBtn(Glyph.CHEVRON_UP, "퀵 메뉴", "tool_quick") { c.quickSheetOpen = true }
            ToolBtn(Glyph.LIST, "점 목록", "tool_points") { c.hub.open(ToolScreen.POINTS) }
            c.bg?.let { b -> ToolBtn(Glyph.LAYERS, "배경 레이어", "layer_btn", if (b.screen != kr.archsurvey.ui.bg.BgScreen.NONE) MKind.PRIMARY else MKind.PLAIN) { b.toggle() } }
            MBtn(Modifier.size(48.dp).testTag("antenna_chip"), MKind.PLAIN, 16.dp, onClick = { c.antennaSheetOpen = true }) {
                GlyphIcon(Glyph.RULER, t.accent, 18.dp)
                Text(f3(c.antenna.inputValueM), style = Ty.coord.copy(fontSize = 11.4.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("antenna_input"))
            }
        }
        c.bg?.mapCaption()?.let {
            Text(it, style = Ty.small, color = t.ink, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp).background(t.raised.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map_caption"))
        }
        NotConnectedCard(c, Modifier.align(Alignment.Center))
        val h = c.antennaResult?.totalToApcM
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArchOverlays(c)
            c.message?.let { msg ->
                Text(msg, style = Ty.body, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(t.raised.copy(alpha = 0.95f), RoundedCornerShape(10.dp)).border(1.dp, t.accent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp).testTag("message"))
            }
            if (h != null) Text(
                "위상중심 ${f3(h)} m", style = Ty.small.copy(fontSize = 11.4.sp), color = t.inkSub,
                modifier = Modifier.background(t.bg.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("apc_label"),
            )
        }
    }
}

// ---------------- 정보 영역 (좌표 / 취득 / 결과 띠 / 녹음) ----------------

@Composable
fun InfoZone(c: SurveyController) {
    val t = T
    if (c.phase == Phase.RESULT && c.resultStripShown) {
        androidx.compose.runtime.LaunchedEffect(c.resultStripSeq) { kotlinx.coroutines.delay(c.resultStripMs); c.hideResultStrip() }
    }
    val h = if (c.phase == Phase.RESULT && !c.resultStripShown) 0 else SurveyLayout.infoHeightDp(c.phase)
    Box(Modifier.fillMaxWidth().height(h.dp).background(t.card).testTag("info_zone")) {
        when (c.phase) {
            Phase.IDLE -> Unit
            Phase.ACQUIRING -> AcquireStrip(c)
            Phase.RESULT -> if (c.resultStripShown) ResultStrip(c)
            Phase.RESULT_EXPANDED -> Unit
            Phase.RECORDING -> RecordStrip(c)
        }
    }
}

@Composable
private fun AcquireStrip(c: SurveyController) {
    val t = T
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("취득 중 · ${c.gv.status.reason.textKo}", style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("acquire_text"))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.line)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(c.progress.coerceIn(0f, 1f)).background(t.primary))
            }
        }
        SmallButton("취소", "acquire_cancel") { c.cancelAcquire() }
    }
}

/** 측점 직후 잠깐 보이는 한 줄 결과 띠. 측점 버튼을 막지 않는다. 누르면 사진·음성이 있는 패널로 펼쳐진다 */
@Composable
private fun ResultStrip(c: SurveyController) {
    val t = T
    val p = c.lastPoint ?: return
    val (bg, fg) = statusColors(t, p.status)
    Row(
        Modifier.fillMaxSize().clickable { c.toggleExpand() }.padding(horizontal = 12.dp).testTag("result_strip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(p.name, style = Ty.coord.copy(fontSize = 19.4.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_name"))
        Text("${p.type} · 저장됨 ±${p.sigmaHM?.let { f3(it) } ?: "—"} m", style = Ty.small, color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("result_h"))
        Row(
            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).glossy(bg).padding(start = 6.dp, end = 9.dp).testTag("result_chip"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            StatusIcon(p.status, fg, 14.dp)
            Text(if (p.status == SurveyStatus.FIXED) "FIXED 고정" else "주의", style = Ty.label.copy(fontSize = 12.3.sp), color = fg, maxLines = 1)
        }
        GlyphIcon(Glyph.CHEVRON_UP, t.accent, 20.dp)
    }
}

/** 펼친 결과 패널 (목업 s6b): 점 이름·종류·상태, N/E/H·±, 사진·음성·펼치기, 다음 점 · 되돌리기 */
@Composable
private fun ResultPanel(c: SurveyController) {
    val t = T
    val p = c.lastPoint
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    val expanded = c.phase == Phase.RESULT_EXPANDED
    Column(
        Modifier.fillMaxWidth().height((SurveyDims.RESULT_PANEL_DP + if (expanded) SurveyDims.EXPANDED_EXTRA_DP else 0).dp)
            .shadow(6.dp, shape).clip(shape).glossy(t.card, strength = 0.30f).border(1.5.dp, t.outline.copy(alpha = 0.9f), shape)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp).testTag("info_zone"),
    ) {
        if (expanded) Box(Modifier.height(SurveyDims.EXPANDED_EXTRA_DP.dp)) { ExpandedExtra(c) }
        if (p != null) {
            val (bg, fg) = statusColors(t, p.status)
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, style = Ty.coord.copy(fontSize = 19.4.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_name"))
                Text(p.type, style = Ty.small, color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).glossy(bg).padding(start = 6.dp, end = 9.dp).testTag("result_chip"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    StatusIcon(p.status, fg, 14.dp)
                    Text(if (p.status == SurveyStatus.FIXED) "FIXED 고정" else "주의", style = Ty.label.copy(fontSize = 12.3.sp), color = fg, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            Column(Modifier.fillMaxWidth().height(40.dp), verticalArrangement = Arrangement.Center) {
                val m = Ty.coord.copy(fontSize = 12.3.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
                Text("N ${grp3(p.northing)}  E ${grp3(p.easting)}", style = m, color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_coord"))
                Text("H ${p.groundHeightM?.let { grp3(it) } ?: "—"}  ±${p.sigmaHM?.let { f3(it) } ?: "—"} m", style = m, color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_h"))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.height(44.dp).testTag("photo_count_btn"), MKind.PLAIN, 16.dp, onClick = { if (c.photos.size < MediaLimits.PHOTOS) c.addPhotoCamera() else c.toggleExpand() }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlyphIcon(Glyph.CAMERA, t.accent, 18.dp)
                    Text("사진", style = Ty.label, color = t.ink)
                    Text("${c.photos.size}/${MediaLimits.PHOTOS}", style = Ty.coord.copy(fontSize = 12.3.sp), color = t.ink, modifier = Modifier.testTag("photo_count"))
                }
            }
            Spacer(Modifier.weight(1f))
            MBtn(Modifier.size(52.dp).testTag("voice_btn"), MKind.PRIMARY, 26.dp, onClick = { c.toggleVoice() }) { GlyphIcon(Glyph.MIC, t.ink, 22.dp) }
            MBtn(Modifier.size(48.dp).testTag("expand_btn"), MKind.PLAIN, 16.dp, onClick = { c.toggleExpand() }) {
                GlyphIcon(if (expanded) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_UP, t.accent, 22.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MBtn(Modifier.weight(1f, fill = false).widthIn(max = 224.dp).fillMaxWidth().height(72.dp).testTag("next_point"), MKind.PRIMARY, 22.dp, onClick = { c.nextPoint() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.rotate(90f)) { GlyphIcon(Glyph.CHEVRON_UP, t.ink, 30.dp) }
                    Column {
                        Text("다음 점", style = Ty.button.copy(fontSize = 35.sp, letterSpacing = 3.sp, lineHeight = 38.sp), color = t.onPrimary, maxLines = 1)
                        Text(c.nextName, style = Ty.small.copy(fontSize = 14.sp), color = t.onPrimary, maxLines = 1)
                    }
                }
            }
            MBtn(Modifier.width(52.dp).height(44.dp).testTag("undo_button"), MKind.PLAIN, 14.dp, onClick = { c.undo() }) { GlyphIcon(Glyph.UNDO, t.accent, 20.dp) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ExpandedExtra(c: SurveyController) {
    val t = T
    val p = c.lastPoint ?: return
    Column(Modifier.fillMaxWidth().height(SurveyDims.EXPANDED_EXTRA_DP.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "H ${p.groundHeightM?.let { f3(it) } ?: "—"} m (지면, 타원체고) · 안테나 위상중심 ${f3(p.antenna.apcHeightM)} m · 기준점 ${p.antenna.refPointLabel}",
            style = Ty.small, color = t.inkSub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("expanded_info"),
        )
        var confirmPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var confirmVoice by remember { mutableStateOf<VoiceItem?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(MediaLimits.PHOTOS) { i ->
                val ph = c.photos.getOrNull(i)
                Box(
                    Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(12.dp)).background(t.bg)
                        .clickable { if (ph != null) confirmPhoto = ph else c.addPhotoCamera() }.testTag("photo_slot_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ph != null) PhotoThumb(c, ph) else GlyphIcon(Glyph.CAMERA, t.inkSub, 20.dp)
                }
            }
            Text("사진 ${c.photos.size}/${MediaLimits.PHOTOS}", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("photo_count"))
            SmallButton("앨범", "photo_pick") { c.addPhotoPicker() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).glossy(t.primary).clickable { c.toggleVoice() }.padding(horizontal = 16.dp).testTag("voice_big"),
                contentAlignment = Alignment.Center,
            ) { Row(verticalAlignment = Alignment.CenterVertically) { GlyphIcon(Glyph.MIC, t.onPrimary, 20.dp); Spacer(Modifier.width(8.dp)); Text("음성 남기기", style = Ty.label, color = t.onPrimary) } }
            for (v in c.voices) {
                val playing = c.playingVoiceId == v.id
                Box(
                    Modifier.height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(if (playing) t.primary.copy(alpha = 0.25f) else t.bg)
                        .combinedClickable(onClick = { c.playVoice(v) }, onLongClick = { confirmVoice = v }).padding(horizontal = 10.dp).testTag("voice_item_${v.id}"),
                    contentAlignment = Alignment.Center,
                ) { Text((if (playing) "■ " else "▶ ") + "0:%02d".format((v.durationMs / 1000).toInt()), style = Ty.label, color = t.ink) }
            }
        }
        confirmPhoto?.let { ph ->
            ConfirmDialog("이 사진을 지울까요?", "앱 폴더의 사진만 지워요. 앨범의 원본은 그대로예요.", "지우기", { c.deletePhoto(ph); confirmPhoto = null }, { confirmPhoto = null })
        }
        confirmVoice?.let { v ->
            ConfirmDialog("이 음성 메모를 지울까요?", "지우면 되돌릴 수 없어요.", "지우기", { c.deleteVoice(v); confirmVoice = null }, { confirmVoice = null })
        }
    }
}

@Composable
private fun PhotoThumb(c: SurveyController, ph: PhotoItem) {
    var b by remember(ph.rel) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(ph.rel) {
        b = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.media?.thumb(ph.rel) }.getOrNull() }
    }
    val bm = b
    if (bm != null) androidx.compose.foundation.Image(bm, "사진", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else GlyphIcon(Glyph.CAMERA, T.accent, 20.dp)
}

@Composable
internal fun ConfirmDialog(title: String, body: String, okLabel: String, onOk: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOk, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_ok")) { Text(okLabel) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_cancel")) { Text("취소") } },
    )
}

@Composable
private fun RecordStrip(c: SurveyController) {
    val t = T
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(t.bad))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("녹음 중 0:%02d / 0:%02d".format(c.voiceSeconds, MediaLimits.VOICE_SECONDS), style = Ty.label, color = t.ink, modifier = Modifier.testTag("record_text"))
            Text(if (c.media != null) "마이크로 녹음하고 있어요 · 끝내기를 누르면 저장" else "화면 시안 — 실제 녹음은 아직 연결 전", style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub)
        }
        SmallButton("끝내기", "record_stop") { c.toggleVoice() }
    }
}

@Composable
private fun SmallButton(label: String, tag: String, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.padding(end = 8.dp).height(SurveyDims.MIN_TOUCH_DP.dp).widthIn(min = SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
            .background(t.bg).clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ty.label, color = t.ink) }
}

@Composable
private fun IconBtn(g: Glyph, tag: String, onClick: () -> Unit) {
    val t = T
    Box(Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) { GlyphIcon(g, t.accent) }
}

// ---------------- 컨트롤 영역 ----------------

// ---------------- 왼쪽 서랍 ----------------

@Composable
fun DrawerOverlay(c: SurveyController) {
    val t = T
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AnimatedVisibility(c.drawerOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(t.scrim).clickable { c.drawerOpen = false }.testTag("drawer_scrim"))
        }
        AnimatedVisibility(
            c.drawerOpen,
            enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it },
        ) {
            val dshape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
            Column(
                Modifier.width(maxWidth * 0.8f).fillMaxHeight().clip(dshape).glossy(t.card).border(1.5.dp, t.outline, dshape)
                    .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 16.dp).testTag("drawer"),
            ) {
                val st = c.gv.status
                val (bg, fg) = statusColors(t, st.status)
                Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(kr.archsurvey.R.drawable.ic_logo_mark_terracotta),
                        contentDescription = "발굴 측량 로고", modifier = Modifier.size(30.dp).testTag("drawer_logo"),
                    )
                    Text("발굴 측량", style = Ty.small, color = t.accentText)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(c.projectName, style = Ty.title.copy(fontSize = 17.6.sp), color = t.ink, maxLines = 1)
                        Text((if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60") + if (st.reason == StatusReason.NOT_CONNECTED) " · 연결 안 됨" else "", style = Ty.small.copy(fontSize = 11.4.sp), color = t.inkSub, modifier = Modifier.testTag("drawer_device"))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${st.status.labelKo}${st.sigmaHM?.let { " ±${f3(it)} m" } ?: ""}", style = Ty.small.copy(fontSize = 11.sp), color = fg, maxLines = 1,
                            modifier = Modifier.background(bg, RoundedCornerShape(11.dp)).padding(horizontal = 9.dp, vertical = 3.dp).testTag("drawer_status"),
                        )
                    }
                    MBtn(Modifier.size(48.dp).testTag("drawer_settings"), MKind.PLAIN, 16.dp, onClick = { c.drawerOpen = false; c.hub.open(ToolScreen.SETTINGS) }) { GearIcon(t.accent, 22.dp) }
                }
                for (sec in DrawerMenu.sections) {
                    Text(sec.title, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, modifier = Modifier.height(26.dp).padding(horizontal = 6.dp).wrapContentHeight(Alignment.Bottom))
                    if (sec.title == "즐겨찾기") {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { for (it in sec.items) FavCard(c, it, Modifier.weight(1f)) }
                    } else for (it in sec.items) DrawerRow(c, it)
                }
                Spacer(Modifier.height(16.dp))
                Text(rememberAppVersionLabel(), style = Ty.small, color = t.inkSub, modifier = Modifier.padding(start = 6.dp).testTag("drawer_version"))
            }
        }
    }
}

private fun runMenu(c: SurveyController, item: MenuItem) {
    when (item.action) {
            MenuAction.OPEN_AREAS -> { c.drawerOpen = false; c.hub.open(ToolScreen.AREAS) }
            MenuAction.OPEN_TYPES -> { c.drawerOpen = false; c.hub.open(ToolScreen.TYPES) }
            MenuAction.GO_SURVEY -> { c.drawerOpen = false; c.hub.closeAll() }
            MenuAction.OPEN_BG -> { c.drawerOpen = false; c.bg?.openManage() ?: run { c.message = "배경 레이어는 이 빌드에 없어요" } }
            MenuAction.OPEN_ANTENNA -> { c.drawerOpen = false; c.antennaSheetOpen = true }
            MenuAction.OPEN_TOOL_POINTS -> { c.drawerOpen = false; c.hub.open(ToolScreen.POINTS) }
            MenuAction.OPEN_TOOL_GOTO -> { c.drawerOpen = false; c.hub.open(ToolScreen.GOTO) }
            MenuAction.OPEN_TOOL_CALC -> { c.drawerOpen = false; c.hub.open(ToolScreen.CALC) }
            MenuAction.OPEN_TOOL_SETTINGS -> { c.drawerOpen = false; c.hub.open(ToolScreen.SETTINGS) }
            MenuAction.OPEN_JOB_NEW -> { c.drawerOpen = false; c.jobs?.openNew() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_JOB_LIST -> { c.drawerOpen = false; c.jobs?.openList() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_JOB_INFO -> { c.drawerOpen = false; c.jobs?.openInfo() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_BACKUP -> { c.drawerOpen = false; c.backup?.openScreen() ?: run { c.message = "백업은 이 빌드에 없어요" } }
            MenuAction.OPEN_EXPORT -> { c.drawerOpen = false; c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }
            MenuAction.OPEN_CONNECTION -> { c.drawerOpen = false; c.connection?.openScreen() ?: run { c.message = "연결 화면은 이 빌드에 없어요" } }
        }
}

private fun menuGlyph(id: String): Glyph? = when (id) {
    "fav_points", "job_points" -> Glyph.LIST
    "fav_goto", "goto" -> Glyph.LOCATE
    "fav_calc", "calc" -> Glyph.CALC
    "job_new" -> Glyph.PLUS
    "job_open" -> Glyph.FOLDER
    "job_export" -> Glyph.SHARE
    "job_info" -> Glyph.INFO
    "job_backup" -> Glyph.LAYERS
    "bg" -> Glyph.LAYERS
    "settings" -> Glyph.INFO
    "areas" -> Glyph.LAYERS
    "types" -> Glyph.LIST
    "survey" -> Glyph.PIN
    "connection" -> Glyph.BT
    "antenna" -> Glyph.RULER
    "pole_side" -> Glyph.EXTENT
    else -> null
}

@Composable
private fun DrawerRow(c: SurveyController, item: MenuItem) {
    val t = T
    val selected = item.action == MenuAction.GO_SURVEY && c.hub.screen == null
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(shape)
            .background(if (selected) t.primaryCont else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, t.accent, shape) else Modifier)
            .alpha(if (item.enabled) 1f else 0.5f)
            .clickable(enabled = item.enabled) { runMenu(c, item) }.padding(horizontal = 12.dp).testTag("menu_${item.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val g = menuGlyph(item.id)
        if (g != null) GlyphIcon(g, t.accent, 20.dp) else Spacer(Modifier.width(20.dp))
        Text(item.label, style = Ty.body.copy(fontSize = 15.sp, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Light), color = t.ink, modifier = Modifier.weight(1f))
        item.note?.let { Text(it, style = Ty.small, color = t.inkSub) }
    }
}

/** 즐겨찾기 카드: 아이콘 위, 글자 아래 (목업 s17) */
@Composable
private fun FavCard(c: SurveyController, item: MenuItem, modifier: Modifier) {
    val t = T
    MBtn(modifier.height(48.dp).testTag("menu_${item.id}"), MKind.PLAIN, 14.dp, onClick = { runMenu(c, item) }) {
        menuGlyph(item.id)?.let { GlyphIcon(it, t.accent, 18.dp) }
        Text(item.label, style = Ty.small.copy(fontSize = 11.4.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = t.ink, maxLines = 1)
    }
}

// ---------------- 안테나 높이 시트 (s4 최소판) ----------------

@Composable
fun AntennaSheet(c: SurveyController) {
    val t = T
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.25f)).clickable { c.antennaSheetOpen = false })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.55f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(t.bg).verticalScroll(rememberScrollState())
                .padding(16.dp).testTag("antenna_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("안테나 높이", style = Ty.title, color = t.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in listOf("TRIMBLE_R10" to "Trimble R10", "SPECTRA_SP60" to "Spectra SP60")) {
                    val sel = c.antennaModelId == id
                    Box(
                        Modifier.weight(1f).height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) t.primary.copy(alpha = 0.2f) else t.card).clickable { c.setAntennaModel(id) }.testTag("model_$id"),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Ty.label, color = t.ink) }
                }
            }
            Text("어디부터 쟀나요", style = Ty.small, color = t.accentText)
            for (r in c.antennaModel.visibleRefs) {
                val sel = c.antenna.refPointId == r.id
                Row(
                    Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) t.primary.copy(alpha = 0.2f) else t.card).clickable { c.setRefPoint(r.id) }.padding(horizontal = 12.dp).testTag("ref_${r.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.labelKo, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
                    Text("+${f3(r.pcoV)} m", style = Ty.coord, color = t.inkSub)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepBtn("−1 cm", "step_minus") { c.stepAntennaCm(-1) }
                Text(f3(c.antenna.inputValueM) + " m", style = Ty.title, color = t.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("sheet_input"))
                StepBtn("+1 cm", "step_plus") { c.stepAntennaCm(1) }
            }
            val r = c.antennaResult
            Text(r?.let { AntennaMath.summary(it) } ?: "계산할 수 없어요", style = Ty.body, color = t.ink, modifier = Modifier.testTag("sheet_summary"))
            Text(if (c.antennaModelId == "TRIMBLE_R10") (if (c.antenna.refPointId == "bottom_of_antenna_mount") AntennaMath.ADAPTER_NOTE_R10_MOUNT else AntennaMath.ADAPTER_NOTE_R10) else AntennaMath.ADAPTER_NOTE_SP60, style = Ty.small, color = t.inkSub)
            AntennaMath.mismatchWarning(c.antenna, c.antennaModel)?.let { Text(it, style = Ty.small, color = t.bad) }
            Text("실기기 실측 전 값 — 현장에서 확인하세요", style = Ty.small, color = t.inkSub)
            Box(
                Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).glossy(t.primary).clickable { c.antennaSheetOpen = false }.testTag("sheet_close"),
                contentAlignment = Alignment.Center,
            ) { Text("닫기", style = Ty.label, color = t.onPrimary) }
        }
    }
}

@Composable
private fun StepBtn(label: String, tag: String, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.size(width = 84.dp, height = SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(t.card).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ty.label, color = t.ink) }
}

/** 기본은 자체 Canvas 지도. `-PwithMapLibre` 로 빌드해 클래스가 있을 때만 MapLibre 지도를 쓴다(실기기 확인 전이라 기본값 아님). */
@Composable
fun rememberMapSurface(c: SurveyController): MapSurface = remember(c) {
    if (kr.archsurvey.BuildConfig.USE_MAPLIBRE) {
        runCatching { Class.forName("kr.archsurvey.ui.MapLibreMapSurface").getConstructor(kr.archsurvey.map.LocalProjection::class.java).newInstance(c.projection) as MapSurface }
            .getOrElse { CanvasMapSurface() }
    } else CanvasMapSurface()
}
