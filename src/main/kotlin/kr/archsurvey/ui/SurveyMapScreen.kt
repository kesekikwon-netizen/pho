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
import kr.archsurvey.ui.bg.*

private fun statusColors(t: Tokens, s: SurveyStatus): Pair<Color, Color> = when (s) {
    SurveyStatus.FIXED -> t.fixed to t.onFixed
    SurveyStatus.CAUTION -> t.caution to t.onCaution
    SurveyStatus.UNAVAILABLE -> t.bad to t.onBad
}

private fun f3(x: Double) = String.format(java.util.Locale.US, "%.3f", x)

/** 측량 + 지도 상시 화면. 상태 띠 / 지도 / 정보 / 컨트롤 네 영역이 겹치지 않는다. */
@Composable
fun SurveyMapScreen(
    c: SurveyController, batteryPct: Int?, map: MapSurface = rememberMapSurface(c),
    bg: BackgroundController = rememberBackgroundController(c),
) {
    val t = T
    Box(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.systemBars).testTag("screen")) {
        Row(Modifier.fillMaxSize()) {
            if (!c.poleOnRight) PoleStrip(c, batteryPct)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                StatusBand(c)
                MapArea(c, map, Modifier.weight(1f), bg)
                InfoZone(c)
                ControlArea(c)
            }
            if (c.poleOnRight) PoleStrip(c, batteryPct)
        }
        DrawerOverlay(c)
        if (c.antennaSheetOpen) AntennaSheet(c)
        BackgroundSheets(bg)
        c.exporter?.let { if (it.open) ExportScreen(it) }
        c.connection?.let { ConnectionHost(c, it) }
    }
}

// ---------------- 상태 띠 ----------------

@Composable
fun StatusBand(c: SurveyController) {
    val t = T
    val st = c.gv.status
    val (bg, fg) = statusColors(t, st.status)
    val main = when (st.status) {
        SurveyStatus.FIXED -> "고정 · 측량 가능"
        SurveyStatus.CAUTION -> "주의 · 저장 시 표시 기록"
        SurveyStatus.UNAVAILABLE -> "불가 · 측점 막힘"
    }
    val sub = st.reason.textKo + (st.sigmaHM?.let { " · ±${f3(it)} m" } ?: "") + (if (c.fakeMode) " · 가짜 신호" else "")
    Row(
        Modifier.fillMaxWidth().height(SurveyDims.STATUS_BAND_DP.dp).background(bg).testTag("status_band"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.padding(start = 8.dp).size(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
                .background(fg.copy(alpha = 0.16f)).clickable { c.drawerOpen = true }.testTag("gear"),
            contentAlignment = Alignment.Center,
        ) { GearIcon(fg) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(main, style = Ty.bandMain, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_main"))
            Text(sub, style = Ty.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_sub"))
        }
        StatusIcon(st.status, fg, 30.dp)
        Spacer(Modifier.width(12.dp))
    }
}

// ---------------- 지도 영역 ----------------

@Composable
fun MapArea(c: SurveyController, map: MapSurface, modifier: Modifier, bg: BackgroundController? = null) {
    val t = T
    val ms = c.mapState().let { if (bg == null) it else it.copy(backgrounds = bg.mapLayers()) }
    Box(
        modifier.fillMaxWidth().testTag("map_area").pointerInput(Unit) {
            detectDragGestures { _, drag ->
                val ppm = c.dpPerM * density
                c.panBy(-drag.x / ppm, drag.y / ppm, c.mapState().center)
            }
        },
    ) {
        map.Draw(ms, Modifier.fillMaxSize())
        // 왼쪽 지도 도구
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(SurveyDims.MAP_TOOL_GAP_DP.dp)) {
            ToolButton(Glyph.LOCATE, "내 위치", "tool_locate", active = c.follow) { c.recenter() }
            ToolButton(Glyph.PLUS, "확대", "tool_zoom_in") { c.zoomIn() }
            ToolButton(Glyph.MINUS, "축소", "tool_zoom_out") { c.zoomOut() }
        }
        // 오른쪽 위: 안테나 높이
        val h = c.antennaResult?.totalToApcM
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp).height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
                .background(t.bg.copy(alpha = 0.92f)).clickable { c.antennaSheetOpen = true }.padding(horizontal = 10.dp).testTag("antenna_chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.RULER, t.accent, 18.dp)
            Spacer(Modifier.width(4.dp))
            Column {
                Text("안테나 높이", style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub)
                Text(c.antenna.inputValueM.let { f3(it) } + " m", style = Ty.coord, color = t.ink, modifier = Modifier.testTag("antenna_input"))
            }
        }
        if (bg != null) {
            // 오른쪽 위, 안테나 높이 칩 아래: 배경 레이어
            Box(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 8.dp)) { ToolButton(Glyph.LAYERS, "배경 레이어", "layer_btn", active = bg.screen != BgScreen.NONE) { if (bg.screen == BgScreen.NONE) bg.openOpacity() else bg.close() } }
            bg.mapCaption()?.let {
                Text(it, style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 30.dp).background(t.bg.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map_caption"))
            }
        }
        Text(
            ms.backdropLabel, style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(t.bg.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (h != null) Text(
            "위상중심 ${f3(h)} m", style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(t.bg.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("apc_label"),
        )
    }
}

@Composable
private fun ToolButton(g: Glyph, desc: String, tag: String, active: Boolean = false, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(t.bg.copy(alpha = 0.92f))
            .then(if (active) Modifier.background(t.primary.copy(alpha = 0.18f)) else Modifier)
            .clickable(onClickLabel = desc, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(g, if (active) t.accent else t.ink) }
}

// ---------------- 정보 영역 (좌표 / 취득 / 결과 띠 / 녹음) ----------------

@Composable
fun InfoZone(c: SurveyController) {
    val t = T
    val h = SurveyLayout.infoHeightDp(c.phase)
    Box(Modifier.fillMaxWidth().height(h.dp).background(t.card).testTag("info_zone")) {
        when (c.phase) {
            Phase.IDLE -> CoordLine(c)
            Phase.ACQUIRING -> AcquireStrip(c)
            Phase.RESULT -> ResultStrip(c)
            Phase.RESULT_EXPANDED -> Column { Box(Modifier.height(SurveyDims.INFO_STRIP_DP.dp)) { ResultStrip(c) }; ExpandedExtra(c) }
            Phase.RECORDING -> RecordStrip(c)
        }
    }
}

@Composable
private fun CoordLine(c: SurveyController) {
    val t = T
    val msg = c.message
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (msg != null) {
            Text(msg, style = Ty.body, color = t.accentText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("message"))
        } else {
            val lat = c.gv.lat; val lon = c.gv.lon
            val txt = if (lat != null && lon != null) {
                val p = TmProjection.forward(lat, lon)
                "N ${f3(p.northing)}  E ${f3(p.easting)}"
            } else "N —  E —"
            Text(txt, style = Ty.coord, color = t.ink, maxLines = 1, modifier = Modifier.testTag("coord_line"))
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

@Composable
private fun ResultStrip(c: SurveyController) {
    val t = T
    val p = c.lastPoint ?: return
    val (bg, fg) = statusColors(t, p.status)
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} · ${p.type}", style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (p.status == SurveyStatus.FIXED) "고정" else "주의") + (p.sigmaHM?.let { " ±${f3(it)}" } ?: ""),
                    style = Ty.small.copy(fontSize = 11.sp), color = fg, maxLines = 1,
                    modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("result_chip"),
                )
            }
            Text("N ${f3(p.northing)}  E ${f3(p.easting)}", style = Ty.coord.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1, modifier = Modifier.testTag("result_coord"))
        }
        IconBtn(Glyph.MIC, "voice_btn") { c.toggleVoice() }
        IconBtn(if (c.phase == Phase.RESULT_EXPANDED) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_UP, "expand_btn") { c.toggleExpand() }
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
                Modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(t.primary).clickable { c.toggleVoice() }.padding(horizontal = 16.dp).testTag("voice_big"),
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
private fun ConfirmDialog(title: String, body: String, okLabel: String, onOk: () -> Unit, onCancel: () -> Unit) {
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

@Composable
fun ControlArea(c: SurveyController) {
    val t = T
    val busy = c.phase == Phase.ACQUIRING
    val blocked = c.gv.status.status == SurveyStatus.UNAVAILABLE
    Column(
        Modifier.fillMaxWidth().height(SurveyDims.CONTROL_AREA_DP.dp).background(t.bg)
            .padding(top = SurveyDims.CTRL_PAD_TOP_DP.dp, bottom = SurveyDims.CTRL_PAD_BOTTOM_DP.dp, start = 12.dp, end = 12.dp)
            .testTag("control_area"),
    ) {
        Box(
            Modifier.fillMaxWidth().height(SurveyDims.MEASURE_BUTTON_DP.dp).clip(RoundedCornerShape(22.dp))
                .background(t.primary).alpha(if (busy || blocked) 0.5f else 1f)
                .clickable(onClickLabel = "측점") { c.onMeasure() }.testTag("measure_button"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (c.points.isEmpty()) "측점" else "다음 점", style = Ty.button, color = t.onPrimary)
                Text(c.nextName + " · " + c.typeName, style = Ty.small, color = t.onPrimary)
            }
        }
        Spacer(Modifier.height(SurveyDims.CTRL_GAP_DP.dp))
        val chip: @Composable () -> Unit = {
            Row(
                Modifier.height(SurveyDims.SECONDARY_ROW_DP.dp).widthIn(min = 140.dp).clip(RoundedCornerShape(14.dp)).background(t.card)
                    .border1(t).clickable { c.cycleType() }.padding(horizontal = 14.dp).testTag("type_chip"),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("위치 종류: ${c.typeName}", style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val undo: @Composable () -> Unit = {
            Row(
                Modifier.height(SurveyDims.SECONDARY_ROW_DP.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).background(t.card)
                    .border1(t).alpha(if (c.points.isEmpty() && !busy) 0.45f else 1f).clickable { c.undo() }.padding(horizontal = 14.dp).testTag("undo_button"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) { GlyphIcon(Glyph.UNDO, t.accent, 20.dp); Spacer(Modifier.width(6.dp)); Text("되돌리기", style = Ty.label, color = t.ink) }
        }
        Row(Modifier.fillMaxWidth().height(SurveyDims.SECONDARY_ROW_DP.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // 되돌리기는 폴 쪽 끝
            if (c.poleOnRight) { chip(); undo() } else { undo(); chip() }
        }
    }
}

private fun Modifier.border1(t: Tokens): Modifier = this.border(1.dp, t.outline.copy(alpha = 0.45f), RoundedCornerShape(14.dp))

// ---------------- 폴 쪽 56dp 표시 전용 띠 ----------------

/** 터치 요소 0: clickable/pointerInput 없음. 시험에서 확인한다. */
@Composable
fun PoleStrip(c: SurveyController, batteryPct: Int?) {
    val t = T
    val st = c.gv.status.status
    val (bg, fg) = statusColors(t, st)
    Column(
        Modifier.width(SurveyDims.POLE_STRIP_DP.dp).fillMaxHeight().background(bg).testTag("pole_strip"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        StatusIcon(st, fg, 28.dp)
        Spacer(Modifier.height(30.dp))
        Text(st.labelKo, style = Ty.label.copy(fontSize = 16.sp), color = fg, modifier = Modifier.rotate(90f).wrapContentWidth(unbounded = true).testTag("pole_label"), maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text("위성", style = Ty.small.copy(fontSize = 10.sp), color = fg)
        Text("${c.gv.satellites}", style = Ty.label, color = fg, modifier = Modifier.testTag("pole_sats"))
        Spacer(Modifier.height(10.dp))
        Text("배터리", style = Ty.small.copy(fontSize = 10.sp), color = fg)
        Text(batteryPct?.let { "$it%" } ?: "—", style = Ty.label, color = fg)
        Spacer(Modifier.height(14.dp))
        Text("표시 전용", style = Ty.small.copy(fontSize = 10.sp), color = fg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
    }
}

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
            Column(
                Modifier.width(maxWidth * 0.8f).fillMaxHeight().background(t.bg).verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("drawer"),
            ) {
                val st = c.gv.status
                val (bg, fg) = statusColors(t, st.status)
                Column(Modifier.padding(16.dp)) {
                    // WP-11: 서랍 상단 로고 마크 (B안, 벡터)
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(kr.archsurvey.R.drawable.ic_logo_mark_terracotta),
                        contentDescription = "발굴 측량 로고", modifier = Modifier.size(44.dp).testTag("drawer_logo"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (c.fakeMode) "샘플 작업 (가짜 데이터)" else "측량 작업", style = Ty.title, color = t.ink)
                    Text((if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60") + if (c.fakeMode) " (가짜 신호)" else " (실제 수신기)", style = Ty.body, color = t.inkSub)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${st.status.labelKo}${st.sigmaHM?.let { " ±${f3(it)} m" } ?: ""}", style = Ty.small, color = fg,
                        modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("drawer_status"),
                    )
                }
                for (sec in DrawerMenu.sections) {
                    Text(sec.title, style = Ty.small, color = t.accentText, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    for (it in sec.items) DrawerRow(c, it)
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(c: SurveyController, item: MenuItem) {
    val t = T
    val selected = item.action == MenuAction.GO_SURVEY
    Row(
        Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) t.primary.copy(alpha = 0.16f) else Color.Transparent)
            .alpha(if (item.enabled) 1f else 0.5f)
            .clickable(enabled = item.enabled) {
                when (item.action) {
                    MenuAction.GO_SURVEY -> c.drawerOpen = false
                    MenuAction.OPEN_ANTENNA -> { c.drawerOpen = false; c.antennaSheetOpen = true }
                    MenuAction.OPEN_EXPORT -> { c.drawerOpen = false; c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }
                    MenuAction.OPEN_CONNECTION -> { c.drawerOpen = false; c.connection?.openScreen() ?: run { c.message = "연결 화면은 이 빌드에 없어요" } }
                    MenuAction.NOT_YET -> { c.drawerOpen = false; c.message = "‘${item.label}’은 다음 라운드에 연결돼요" }
                    MenuAction.DEV_SKIP_SEGMENT -> c.fakeSource?.skipToNextSegment()
                    MenuAction.TOGGLE_POLE_SIDE -> c.poleOnRight = !c.poleOnRight
                }
            }.padding(horizontal = 10.dp).testTag("menu_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.label, style = Ty.body.copy(fontSize = 15.sp), color = t.ink, modifier = Modifier.weight(1f))
        item.note?.let { Text(it, style = Ty.small, color = t.inkSub) }
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
                Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(t.primary).clickable { c.antennaSheetOpen = false }.testTag("sheet_close"),
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

@Composable
fun rememberBackgroundController(c: SurveyController): BackgroundController {
    val dir = java.io.File(androidx.compose.ui.platform.LocalContext.current.cacheDir, "fake_uploads")
    return remember(c) { BackgroundController(c.projection, dir) }
}

/** 기본은 자체 Canvas 지도. `-PwithMapLibre` 로 빌드해 클래스가 있을 때만 MapLibre 지도를 쓴다(실기기 확인 전이라 기본값 아님). */
@Composable
fun rememberMapSurface(c: SurveyController): MapSurface = remember(c) {
    if (kr.archsurvey.BuildConfig.USE_MAPLIBRE) {
        runCatching { Class.forName("kr.archsurvey.ui.MapLibreMapSurface").getConstructor(kr.archsurvey.map.LocalProjection::class.java).newInstance(c.projection) as MapSurface }
            .getOrElse { CanvasMapSurface() }
    } else CanvasMapSurface()
}
