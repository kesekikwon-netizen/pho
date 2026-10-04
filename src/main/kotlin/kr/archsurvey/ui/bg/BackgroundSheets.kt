package kr.archsurvey.ui.bg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.georef.*
import kr.archsurvey.ui.Glyph
import kr.archsurvey.ui.GlyphIcon
import kr.archsurvey.ui.SurveyDims
import kr.archsurvey.ui.T
import kr.archsurvey.ui.Ty
import java.util.Locale

/** 지도 위에 올라오는 배경 관련 시트 전체. 단계(screen)에 맞는 시트 하나만 보인다. 시트는 화면 높이의 절반 이하라 지도가 항상 일부 보인다. */
@Composable
fun BackgroundSheets(bg: BackgroundController) {
    when (bg.screen) {
        BgScreen.NONE -> {}
        BgScreen.OPACITY -> OpacitySheet(bg)
        BgScreen.MANAGE -> ManageSheet(bg)
        BgScreen.PICK_FILE -> PickFileSheet(bg)
        BgScreen.RESULT -> ResultSheet(bg)
        BgScreen.WARNING -> WarningSheet(bg)
        BgScreen.CRS_PICK -> CrsPickSheet(bg)
        BgScreen.MANUAL -> ManualSheet(bg)
    }
}

const val SHEET_MAX_FRACTION = 0.5f

@Composable
private fun SheetFrame(tag: String, scrim: Boolean, onDismiss: () -> Unit, fixedBottom: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = T
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (scrim) Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.2f)).clickable(onClick = onDismiss).testTag("${tag}_scrim"))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * SHEET_MAX_FRACTION)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(t.bg).clickable(enabled = false) {}.testTag(tag),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (fixedBottom == null) 14.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            if (fixedBottom != null) Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = fixedBottom)
        }
    }
}

private enum class BtnKind { PRIMARY, NORMAL, DANGER }

@Composable
private fun BigBtn(label: String, tag: String, kind: BtnKind = BtnKind.NORMAL, sub: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier, minHeight: Dp = 56.dp, onClick: () -> Unit) {
    val t = T
    val bgc = when (kind) { BtnKind.PRIMARY -> t.primary; else -> t.card }
    val fg = when (kind) { BtnKind.PRIMARY -> t.onPrimary; BtnKind.DANGER -> t.bad; else -> t.ink }
    Column(
        modifier.fillMaxWidth().heightIn(min = minHeight.coerceAtLeast(SurveyDims.MIN_TOUCH_DP.dp)).clip(RoundedCornerShape(14.dp)).background(bgc)
            .then(if (kind == BtnKind.NORMAL) Modifier.border(1.dp, t.outline.copy(alpha = 0.35f), RoundedCornerShape(14.dp)) else Modifier)
            .alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = Ty.label.copy(fontSize = 16.sp), color = fg)
        if (sub != null) Text(sub, style = Ty.small, color = if (kind == BtnKind.PRIMARY) t.onPrimary else t.inkSub)
    }
}

@Composable
private fun HeaderRow(title: String, tag: String, right: @Composable RowScope.() -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = SurveyDims.MIN_TOUCH_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Ty.title, color = t.ink, modifier = Modifier.weight(1f).testTag("${tag}_title"))
        right()
    }
}

@Composable
private fun SmallPillBtn(label: String, tag: String, onClick: () -> Unit) {
    val t = T
    Row(
        Modifier.height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(24.dp)).background(t.card).clickable(onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, style = Ty.label, color = t.ink) }
}

@Composable
private fun CloseX(tag: String, onClick: () -> Unit) {
    val t = T
    Box(Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "닫기", onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        GlyphIcon(Glyph.CLOSE, t.ink)
    }
}

@Composable
private fun Notice(bg: BackgroundController) {
    val t = T
    bg.notice?.let {
        Text(it, style = Ty.small, color = t.accentText, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.card).padding(8.dp).testTag("bg_notice"))
    }
}

// ---------------- s36 빠른 투명도 시트 ----------------

@Composable
private fun OpacitySheet(bg: BackgroundController) {
    val t = T
    SheetFrame("bg_sheet_opacity", scrim = false, onDismiss = { bg.close() }) {
        HeaderRow("레이어 투명도", "opacity") {
            SmallPillBtn("관리", "btn_manage") { bg.notice = null; bg.openManage() }
            Spacer(Modifier.width(4.dp))
            CloseX("btn_close_sheet") { bg.notice = null; bg.close() }
        }
        Notice(bg)
        for (r in bg.layers) LayerSliderRow(bg, r)
        Text("아래에 있는 레이어일수록 위에 겹쳐 그려져요", style = Ty.small, color = t.inkSub)
    }
}

@Composable
private fun LayerSliderRow(bg: BackgroundController, r: LayerRow) {
    val t = T
    Column(Modifier.fillMaxWidth().testTag("row_${r.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = SurveyDims.MIN_TOUCH_DP.dp)) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = Ty.label.copy(fontSize = 15.sp), color = t.ink)
                r.badge?.let { Text(it, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, modifier = Modifier.testTag("badge_${r.id}")) }
            }
            Text("${(r.opacity * 100).toInt()}%", style = Ty.coord, color = t.ink, textAlign = TextAlign.End, modifier = Modifier.width(48.dp).testTag("pct_${r.id}"))
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = r.enabled, onCheckedChange = { bg.setEnabled(r.id, it) }, modifier = Modifier.testTag("toggle_${r.id}"),
                colors = SwitchDefaults.colors(checkedTrackColor = t.primary, checkedThumbColor = t.bg, uncheckedTrackColor = t.card, uncheckedThumbColor = t.outline, uncheckedBorderColor = t.outline),
            )
        }
        Slider(
            value = r.opacity.toFloat(), onValueChange = { bg.setOpacity(r.id, it.toDouble()) }, valueRange = 0f..1f, enabled = r.enabled,
            colors = SliderDefaults.colors(thumbColor = t.primary, activeTrackColor = t.primary, inactiveTrackColor = t.line),
            modifier = Modifier.fillMaxWidth().testTag("opacity_${r.id}"),
        )
    }
}

// ---------------- s35 배경 레이어 관리 시트 ----------------

@Composable
private fun ManageSheet(bg: BackgroundController) {
    val t = T
    SheetFrame("bg_sheet_manage", scrim = true, onDismiss = { bg.close() }, fixedBottom = {
        BigBtn("파일 올리기", "btn_upload", BtnKind.PRIMARY, sub = "도면(GeoTIFF · DXF)을 지도에 맞춰 올려요") { bg.openPicker() }
    }) {
        HeaderRow("배경 레이어", "manage") {
            SmallPillBtn("투명도", "btn_opacity") { bg.openOpacity() }
            Spacer(Modifier.width(4.dp))
            CloseX("btn_close_sheet") { bg.close() }
        }
        Notice(bg)
        Text("위성 → 지적도 → 업로드 순서로 겹쳐요", style = Ty.small, color = t.inkSub)
        for (r in bg.layers) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(t.card).padding(start = 12.dp, end = 4.dp).testTag("manage_row_${r.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(r.name, style = Ty.label.copy(fontSize = 15.sp), color = t.ink)
                    r.badge?.let { Text(it, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, modifier = Modifier.testTag("manage_badge_${r.id}")) }
                }
                if (r.deletable) {
                    val armed = bg.deleteArmedId == r.id
                    Box(
                        Modifier.heightIn(min = SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(12.dp)).clickable { bg.requestDelete(r.id) }.padding(horizontal = 10.dp).testTag("delete_${r.id}"),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (armed) "정말 지울까요?" else "삭제", style = Ty.label, color = t.bad) }
                }
                Switch(
                    checked = r.enabled, onCheckedChange = { bg.setEnabled(r.id, it) }, modifier = Modifier.testTag("manage_toggle_${r.id}"),
                    colors = SwitchDefaults.colors(checkedTrackColor = t.primary, checkedThumbColor = t.bg, uncheckedTrackColor = t.card, uncheckedThumbColor = t.outline, uncheckedBorderColor = t.outline),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        if (bg.uploads.isEmpty()) Text("아직 올린 도면이 없어요", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("no_uploads"))
    }
}

// ---------------- 파일 고르기 (가짜) ----------------

@Composable
private fun PickFileSheet(bg: BackgroundController) {
    val t = T
    SheetFrame("bg_sheet_pick", scrim = true, onDismiss = { bg.openManage() }) {
        HeaderRow("파일 올리기", "pick") { CloseX("btn_close_sheet") { bg.openManage() } }
        Text("예시 파일 고르기 — 실제 파일 선택 화면은 다음 라운드에 연결해요. 지금은 예시 파일로 흐름만 확인해요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("pick_note"))
        for (s in FakeSample.entries) BigBtn(s.fileName, "pick_${s.name}", sub = s.summary) { bg.pick(s) }
    }
}

// ---------------- s34c 좌표 읽기 결과 ----------------

private fun grp(x: Double) = String.format(Locale.US, "%,.0f", x).replace(',', ' ')

@Composable
private fun FileChip(p: PendingUpload) {
    val t = T
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p.sample.fileName, style = Ty.label, color = t.ink, modifier = Modifier.weight(1f, fill = false).testTag("file_name"))
        Text(if (p.sample.kind == BgKind.DXF) "DXF" else "GeoTIFF", style = Ty.small, color = t.inkSub,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(t.card).padding(horizontal = 8.dp, vertical = 2.dp).testTag("file_kind"))
    }
}

@Composable
private fun ResultSheet(bg: BackgroundController) {
    val t = T
    val p = bg.pending ?: return
    val o = p.outcome
    SheetFrame("bg_sheet_result", scrim = false, onDismiss = {}, fixedBottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigBtn("불러오기", "btn_import", BtnKind.PRIMARY, modifier = Modifier.weight(2f)) { bg.importPending() }
            BigBtn("취소", "btn_cancel", BtnKind.DANGER, modifier = Modifier.weight(1f)) { bg.cancelUpload() }
        }
    }) {
        HeaderRow("좌표 읽기 결과", "result") {}
        FileChip(p)
        InfoLine("좌표계", o.crs?.let { "EPSG:${it.epsg} ${it.name}" + if (o.crsSource == CrsSource.USER) " (직접 지정)" else "" } ?: "-", "info_crs")
        o.extent?.let { InfoLine("범위", "N ${grp(it.minY)} ~ ${grp(it.maxY)}\nE ${grp(it.minX)} ~ ${grp(it.maxX)}", "info_extent") }
        val aff = o.fileToCrs
        if (o.rasterWidth != null && aff != null) {
            val px = Math.hypot(aff.a, aff.d)
            InfoLine("해상도", String.format(Locale.US, "%.2f m/화소 · %,d × %,d", px, o.rasterWidth, o.rasterHeight ?: 0), "info_res")
        } else InfoLine("종류", "선·도형 도면 (화소 없음)", "info_res")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "✓ 지도 위치에 맞게 배치됨", style = Ty.label, color = t.onFixed,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(t.fixed).padding(horizontal = 12.dp, vertical = 6.dp).testTag("placed_pill"),
            )
            val extra = buildList {
                o.distanceToWorkKm?.let { add(String.format(Locale.US, "작업 위치에서 %.1f km", it)) }
                o.approxErrorPx?.let { if (it > 0.05) add(String.format(Locale.US, "배치 오차 약 %.1f화소", it)) }
            }
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f))
        }
        for (m in o.messages) Text("· $m", style = Ty.small, color = t.inkSub)
        Text("불러오면 투명도 50%로 지도에 겹쳐 보여요", style = Ty.small, color = t.inkSub)
    }
}

@Composable
private fun InfoLine(k: String, v: String, tag: String) {
    val t = T
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = Ty.small, color = t.inkSub, modifier = Modifier.width(56.dp))
        Text(v, style = Ty.coord.copy(fontSize = 12.sp), color = t.ink, modifier = Modifier.weight(1f).testTag(tag))
    }
}

// ---------------- s34d 경고 ----------------

@Composable
private fun WarningSheet(bg: BackgroundController) {
    val t = T
    val p = bg.pending ?: return
    val o = p.outcome
    val head = when {
        o.status == AlignStatus.NEEDS_MANUAL && o.fileToCrs == null -> "이 파일에 좌표 정보가 없습니다"
        o.status == AlignStatus.NEEDS_MANUAL -> "도면 좌표를 지도에 맞출 수 없어요"
        o.status == AlignStatus.NEEDS_CRS -> "좌표계를 알 수 없어요"
        else -> "도면 위치가 작업 위치와 맞지 않아요"
    }
    SheetFrame("bg_sheet_warning", scrim = false, onDismiss = {}, fixedBottom = {
        BigBtn("취소", "btn_cancel", BtnKind.DANGER, minHeight = 48.dp) { bg.cancelUpload() }
    }) {
        HeaderRow("좌표 확인", "warning") {}
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.caution.copy(alpha = 0.18f)).border(1.dp, t.caution, RoundedCornerShape(14.dp)).padding(10.dp)) {
            FileChip(p)
            Text("⚠ $head", style = Ty.label.copy(fontSize = 15.sp), color = t.ink, modifier = Modifier.testTag("warning_head"))
            for (m in o.messages.take(3)) Text(m, style = Ty.small, color = t.inkSub)
            if (o.status == AlignStatus.NEEDS_MANUAL && o.fileToCrs == null) Text("위치와 크기를 알 수 없어 지도에 맞출 수 없어요.", style = Ty.small, color = t.inkSub)
        }
        Text("방법 고르기", style = Ty.small, color = t.accentText)
        BigBtn("좌표계 직접 지정", "btn_pick_crs", sub = if (bg.canPickCrs) "파일의 좌표계를 목록에서 골라요" else "이 파일은 좌표가 없어서 쓸 수 없어요", enabled = bg.canPickCrs) { bg.openCrsPick() }
        BigBtn("기준점 2~3개로 수동 정렬", "btn_manual", sub = if (bg.canManual) "도면 위 점과 지도 위 점을 짝지어요" else "DXF 수동 정렬은 다음 라운드에 연결해요", enabled = bg.canManual) { bg.startManual() }
    }
}

// ---------------- 좌표계 고르기 ----------------

@Composable
private fun CrsPickSheet(bg: BackgroundController) {
    val t = T
    val p = bg.pending ?: return
    SheetFrame("bg_sheet_crs", scrim = false, onDismiss = {}, fixedBottom = {
        BigBtn("뒤로", "btn_back", minHeight = 48.dp) { bg.screen = BgScreen.WARNING }
    }) {
        HeaderRow("좌표계 고르기", "crs") {}
        Text("도면 좌표가 들어맞는 후보예요. 도면을 만든 곳에 물어보면 가장 정확해요.", style = Ty.small, color = t.inkSub)
        bg.notice?.let { Text(it, style = Ty.small, color = t.bad) }
        for (c in p.outcome.candidates) {
            val sub = buildList {
                if (c.inKorea == true) add("한국 안") else if (c.inKorea == false) add("한국 밖")
                c.distanceKm?.let { add(String.format(Locale.US, "작업 위치에서 %.1f km", it)) }
                c.note?.let { add(it) }
                if (!c.convertible) add("아직 변환할 수 없어요")
            }.joinToString(" · ")
            BigBtn("EPSG:${c.crs.epsg} ${c.crs.name}", "crs_${c.crs.epsg}", sub = sub, enabled = c.convertible) { bg.chooseCrs(c.crs) }
        }
    }
}

// ---------------- s34e 수동 정렬 ----------------

@Composable
private fun ManualSheet(bg: BackgroundController) {
    val t = T
    val m = bg.manual
    val n = m.pairs.size
    SheetFrame("bg_sheet_manual", scrim = false, onDismiss = {}, fixedBottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigBtn("✓ 정렬 적용", "btn_apply_manual", BtnKind.PRIMARY, enabled = bg.manualCanApply, modifier = Modifier.weight(2f)) { bg.applyManual() }
            BigBtn("취소", "btn_cancel", BtnKind.DANGER, modifier = Modifier.weight(1f)) { bg.cancelUpload() }
        }
    }) {
        HeaderRow("수동 정렬", "manual") { Text("쌍 $n / ${bg.manualMaxPairs}", style = Ty.label, color = t.inkSub, modifier = Modifier.testTag("pair_counter")) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until bg.manualMaxPairs) {
                val done = i < n; val cur = i == n
                Box(
                    Modifier.weight(1f).height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (done) t.fixed else if (cur) t.primary else t.card).testTag("pair_tab_$i"),
                    contentAlignment = Alignment.Center,
                ) { Text((if (done) "✓ " else "") + "쌍 ${i + 1}" + if (i >= 2) " (선택)" else "", style = Ty.small.copy(fontSize = 12.sp), color = if (done) t.onFixed else t.ink) }
            }
        }
        Text(
            if (bg.manualCanAddPair) "◇ 도면 ${n + 1}번 위치와 같은 곳을 지도에서 눌러요" else "점을 다 찍었어요. 아래 오차를 확인하고 적용하세요",
            style = Ty.body, color = t.ink, modifier = Modifier.testTag("pair_instruction"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigBtn("예시 점 찍기", "btn_fake_pair", enabled = bg.manualCanAddPair, sub = "(가짜) 정해진 위치를 대신 찍어요", modifier = Modifier.weight(1f)) { bg.addFakePair() }
            BigBtn("마지막 점 지우기", "btn_undo_pair", enabled = n > 0, modifier = Modifier.weight(1f)) { bg.removeLastPair() }
        }
        val f = bg.manualFit
        Text(
            when {
                f != null -> "오차  " + f.residuals.mapIndexed { i, r -> "쌍${i + 1} ${String.format(Locale.US, "%.2f", r)} m" }.joinToString(" · ") +
                    "  |  평균 ${String.format(Locale.US, "%.2f", f.rms)} m"
                bg.manualRejected != null -> bg.manualRejected!!
                n == 1 -> "쌍 1 지정 · 쌍 2를 지정하면 오차를 계산해요"
                else -> "점을 2개 이상 찍으면 오차를 계산해요"
            },
            style = Ty.small.copy(fontSize = 12.sp), color = t.ink, modifier = Modifier.testTag("pair_residuals"),
        )
        for (h in bg.manualHints()) Text("· $h", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("pair_hint"))
    }
}
