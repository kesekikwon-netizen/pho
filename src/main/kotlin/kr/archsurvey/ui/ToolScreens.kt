package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.Calc
import kr.archsurvey.core.SurveyStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** 도구 화면 하나를 그린다. 지도 화면 위에 덮인다. */
@Composable
fun ToolHost(c: SurveyController) {
    val h = c.hub
    when (h.screen) {
        ToolScreen.POINTS -> PointsScreen(c)
        ToolScreen.POINT_DETAIL -> PointDetailScreen(c)
        ToolScreen.GOTO -> GotoScreen(c)
        ToolScreen.CALC -> CalcScreen(c)
        ToolScreen.SETTINGS -> SettingsScreen(c)
        ToolScreen.JOB_INFO, ToolScreen.JOB_OPEN, ToolScreen.JOB_NEW -> JobScreen(c)
        ToolScreen.AREAS -> AreasScreen(c)
        ToolScreen.GRID_WIZ -> GridWizardScreen(c)
        ToolScreen.TYPES -> TypesScreen(c)
        ToolScreen.VWORLD -> VWorldScreen(c)
        ToolScreen.SURVEY_HUB -> SurveyHubScreen(c)
        ToolScreen.JOB_HUB -> JobHubScreen(c)
        ToolScreen.RTK -> RtkScreen(c)
        ToolScreen.IMU -> ImuScreen(c)
        ToolScreen.FEATURES -> FeaturesScreen(c)
        null -> {}
    }
}

// ---------------- 점 목록 ----------------

/** 0.1.46 시안 s04 점 목록: 종류 칸(전체·많이 쓴 종류 3) · 흰 카드 줄(핀 · 이름·종류 · N E H · ±cm). 누르면 점 시트 */
@Composable
fun PointsScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val all = c.points.asReversed()
    val topTypes = all.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val list = if (tab == null) all else all.filter { it.type == tab }
    ToolScaffold("점 목록", "points_screen", onBack = { h.back() }, sub = "${c.projectName} · ${all.size}점") {
        Text("${list.size}개", style = DTy.small, color = D.sub, modifier = Modifier.testTag("points_count"))
        if (all.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("아직 측점한 점이 없어요", style = DTy.h2, color = D.ink, modifier = Modifier.testTag("points_empty"))
                Text("지도 화면의 「측량」 단추로 첫 점을 찍으면 여기에 쌓여요.", style = DTy.body, color = D.sub)
                DPrimary("지도로 돌아가기", "points_to_map", Modifier.fillMaxWidth()) { c.goToSurvey() }
            }
            return@ToolScaffold
        }
        val sh = RoundedCornerShape(16.dp)
        Row(Modifier.fillMaxWidth().clip(sh).background(D.chip).padding(4.dp).testTag("points_tabs")) {
            (listOf<String?>(null) + topTypes).forEachIndexed { i, ty ->
                val n = if (ty == null) all.size else all.count { it.type == ty }
                val sel = tab == ty
                Box(Modifier.weight(1f).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(12.dp)).background(if (sel) D.card else androidx.compose.ui.graphics.Color.Transparent)
                    .hClickable { tab = ty }.testTag("points_tab_$i"), contentAlignment = Alignment.Center) {
                    Text((ty?.let { shortType(it) } ?: "전체") + " $n", style = DTy.bodyB, color = if (sel) D.ink else D.sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        WhiteCard(tag = "points_list") {
            list.forEachIndexed { i, p ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(D.hair))
                PointRow(p, onLong = { c.pointMenuFor = p }) { c.pointMenuFor = p }
            }
        }
        Text("점을 누르면 종류 바꾸기 · 다시 측량 · 지도에서 보기", style = DTy.small, color = D.sub)
    }
}

internal fun shortType(t: String): String = t.removePrefix("유구 ").take(4)

@Composable
private fun PointRow(p: SavedPoint, onLong: () -> Unit, onClick: () -> Unit) {
    val shown = PointCaution.displayStatus(p)
    val ok = shown == kr.archsurvey.core.SurveyStatus.FIXED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).hCombinedClickable(onLongClick = onLong, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("point_row_${p.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, D.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.PIN, FieldIvory.primary, 22.dp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(p.name, style = DTy.bodyB.copy(fontSize = 18.sp), color = D.ink, maxLines = 1, softWrap = false)
                Text(" · " + p.type.ifBlank { "종류 없음" }, style = DTy.small, color = D.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("N ${grp3(p.northing)} E ${grp3(p.easting)} H ${p.groundHeightM?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}", style = DTy.small, color = D.sub, maxLines = 2)
            PointCaution.rowReason(p)?.let { Text(it, style = DTy.small, color = D.ink, maxLines = 1, modifier = Modifier.testTag("point_reason_${p.name}")) }
            Text(PointCaution.hv(p), style = DTy.small, color = D.weak, maxLines = 1, modifier = Modifier.testTag("point_hv_${p.name}"))
        }
        Text(p.sigmaHM?.let { "±" + String.format(java.util.Locale.US, "%.1f", it * 100) } ?: "±—", style = DTy.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = if (ok) FieldIvory.fix else FieldIvory.float,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background((if (ok) FieldIvory.fix else FieldIvory.float).copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("point_sigma_${p.name}"))
    }
}

// ---------------- 점 상세 · 편집 ----------------

@Composable
fun PointDetailScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val p = h.selected
    if (p == null) { h.back(); return }
    ToolScaffold(p.name, "point_detail", onBack = { h.back() }, bottom = {
        // 0.1.14 목업 21 하단: 지도에서 보기 / 편집 저장
        DualBar("지도에서 보기", "detail_map_bar", { c.focusOn(p); c.goToSurvey() }, if (h.saved && !h.dirty) "저장했어요" else "편집 저장", "point_save", h.dirty) { h.saveEdit() }
    }) {
        ReadRow("점 이름") { Text(p.name, style = Ty.coord.copy(fontSize = 18.sp), color = t.ink, modifier = Modifier.testTag("detail_name")) }
        var typeOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        SelectRow("위치 종류", h.editType.trim().ifBlank { p.type }, typeOpen, "edit_type_toggle") { typeOpen = !typeOpen }
        if (typeOpen) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in c.types.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ty in row) Choice(ty, ty == h.editType.trim(), Modifier.weight(1f).testTag("edit_type_$ty")) { h.editType = ty; typeOpen = false }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        // 0.1.39 비고: 자주 쓰는 말은 칩으로(키보드 없이), 그 밖은 아래 칸
        QuickNoteChips(h.memo(), "edit_note") { h.editMemo = it }
        // 0.1.46: 키보드는 「직접 입력」을 눌렀을 때만
        var memoTyping by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        TypeFallbackRow(memoTyping, "edit_memo_manual") { memoTyping = !memoTyping }
        if (memoTyping) Field("비고", h.memo(), "edit_memo") { h.editMemo = it }
        ReadRow("안테나 위상중심") {
            Text(f3(p.antenna.apcHeightM), style = Ty.coord.copy(fontSize = 18.sp), color = t.ink)
            Text(" m · ${p.antenna.refPointLabel}", style = Ty.small, color = t.inkSub, maxLines = 1)
        }
        // N / E / H 카드 (목업 s31)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.raised).border(1.dp, t.line, RoundedCornerShape(18.dp)).testTag("detail_neh")) {
            val rows = listOf("N" to p.northing, "E" to p.easting, "H" to p.groundHeightM)
            rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = Ty.label.copy(fontSize = 18.sp), color = t.ink, modifier = Modifier.width(22.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                        Text(v?.let { grp3(it) } ?: "—", style = Ty.coord.copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light), color = t.ink, maxLines = 1, modifier = Modifier.testTag("detail_$k"))
                        Text(" m", style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            }
        }
        // 0.1.31: 이 점의 오차(수평 H · 수직 V, 1σ). 수신기가 GST 를 안 보낸 점은 —
        Text(PointCaution.hvLine(p), style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("detail_hv"))
        Text("좌표·높이는 바꿀 수 없어요. 잘못 쟀다면 다시 측점하세요.", style = Ty.small, color = t.inkSub)
        Text(
            "${PointCaution.label(p)}${p.sigmaHM?.let { " ±${f3(it)} m" } ?: ""} · 위성 ${p.satellites} · ${p.epochs}회 평균 · EPSG:${p.crsEpsg}" +
                (p.groundMslM?.let { " · 표고 ${f3(it)} m (" + kr.archsurvey.core.GeoidModel.fromId(p.heightModel.ifEmpty { null }).shortKo + ")" } ?: "") + (if (p.measuredAtMs > 0) "\n" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(p.measuredAtMs)) else "") + averagingLine(p),
            style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("detail_info"),
        )
        MeasureTable(c, p)
        DetailPhotos(c, p)
        if (p.samples.isNotEmpty()) SamplesCard(p)
        PointHistoryCards(c, p)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Btn("여기로 찾아가기", Modifier.fillMaxWidth().testTag("detail_goto")) { h.startGoto(p) } }
            Box(Modifier.weight(1f)) { Btn("지도에서 보기", Modifier.fillMaxWidth().testTag("detail_map")) { c.focusOn(p); c.goToSurvey() } }
        }
    }
}

private fun ToolsHub.memo() = editMemo

/** 0.1.14 목업 21 측량 정보 표. 저장된 값만(PDOP 은 점에 저장하지 않아 HDOP 만) */
@Composable
private fun MeasureTable(c: SurveyController, p: SavedPoint) {
    val t = T
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    val idx = c.points.indexOfFirst { it.rowId == p.rowId && it.name == p.name }
    val prev = if (idx > 0) c.points[idx - 1] else null
    fun d(v: Double?) = v?.let { String.format(Locale.US, "%+.3f m", it) } ?: "—"
    val rows = listOf(
        "HDOP" to (p.hdop?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
        "시작 시간" to (p.avgStartedAtMs?.let { fmt.format(Date(it)) } ?: "—"),
        "종료 시간" to (p.avgEndedAtMs?.let { fmt.format(Date(it)) } ?: (if (p.measuredAtMs > 0) fmt.format(Date(p.measuredAtMs)) else "—")),
        "Epoch" to "${p.epochs}",
        "위성" to "${p.satellites}",
        "높이 지점" to p.antenna.refPointLabel,
        "수평 / 수직 정밀도" to "${p.sigmaHM?.let { f3(it) } ?: "—"} / ${p.sigmaU?.let { f3(it) } ?: "—"} m",
        "ΔX(N) 앞 점 대비" to (prev?.let { d(p.northing - it.northing) } ?: "— (첫 점)"),
        "ΔY(E) 앞 점 대비" to (prev?.let { d(p.easting - it.easting) } ?: "—"),
        "ΔZ(H) 앞 점 대비" to (prev?.let { a -> if (a.groundHeightM != null && p.groundHeightM != null) d(p.groundHeightM - a.groundHeightM) else "—" } ?: "—"),
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.raised).border(1.dp, t.line, RoundedCornerShape(18.dp)).padding(vertical = 4.dp).testTag("detail_measure")) {
        Text("측량 정보" + (prev?.let { " · 앞 점 ${it.name}" } ?: ""), style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.weight(1f))
                Text(v, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("detail_m_$i"))
            }
        }
    }
}

/** 0.1.14 목업 21 현장 사진(이 점에 붙은 사진, 최대 4장) */
@Composable
private fun DetailPhotos(c: SurveyController, p: SavedPoint) {
    val t = T
    var photos by androidx.compose.runtime.remember(p.rowId) { androidx.compose.runtime.mutableStateOf<List<PhotoItem>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(p.rowId) { photos = runCatching { c.media?.photos(p.rowId) }.getOrNull().orEmpty() }
    Card {
        Text("현장 사진 ${photos.size}/${MediaLimits.PHOTOS}", style = Ty.label, color = t.ink, modifier = Modifier.testTag("detail_photos_title"))
        if (photos.isEmpty()) Text("이 점에 붙은 사진이 없어요. 잰 직후 장부 카드의 「사진」으로 찍어요.", style = Ty.small, color = t.inkSub)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ph in photos.take(MediaLimits.PHOTOS)) Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(t.bg).testTag("detail_photo_${ph.id}"), contentAlignment = Alignment.Center) { PhotoThumb(c, ph) }
        }
    }
}

/** 목업의 읽기 전용 줄: 왼쪽 작은 이름 + 오른쪽 값 */
@Composable
private fun ReadRow(label: String, content: @Composable RowScope.() -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape).background(t.raised).border(1.5.dp, t.outline, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.widthIn(min = 72.dp))
        content()
    }
}

// ---------------- 점 찾아가기 ----------------

@Composable
fun GotoScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val g = h.guidance()
    ToolScaffold("점 찾아가기", "goto_screen", onBack = { h.back() }) {
        Card {
            Text("목표", style = Ty.label, color = t.ink)
            Text(h.gotoTarget?.label ?: "아직 고르지 않았어요", style = Ty.body, color = t.ink, modifier = Modifier.testTag("goto_target"))
            Btn(if (h.gotoPickerOpen) "목록 닫기" else "측점한 점에서 고르기", Modifier.fillMaxWidth().testTag("goto_pick")) { h.gotoPickerOpen = !h.gotoPickerOpen }
            if (h.gotoPickerOpen) {
                PointChooser(c.points.asReversed(), "goto_list") { h.startGoto(it); h.gotoPickerOpen = false }
            }
            Text("또는 좌표를 직접 입력 (현재 좌표계 EPSG:${c.zone.epsg})", style = Ty.small, color = t.inkSub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("N (북)", h.gotoN, "goto_n", Modifier.weight(1f)) { h.gotoN = it }
                NumField("E (동)", h.gotoE, "goto_e", Modifier.weight(1f)) { h.gotoE = it }
            }
            Btn("이 좌표로 찾아가기", Modifier.fillMaxWidth().testTag("goto_manual")) { if (!h.setManualGoto()) c.message = "N, E 숫자를 입력하세요" }
        }
        if (h.gotoTarget != null) {
            Card {
                Text("안내", style = Ty.label, color = t.ink)
                if (g == null) Text("현재 위치를 아직 받지 못했어요. 수신기 연결과 위치 수신을 확인하세요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("goto_nopos"))
                else {
                    Text(if (g.arrived) "도착했어요" else "%.3f m".format(Locale.US, g.distance), style = Ty.bandMain.copy(fontSize = 32.sp), color = if (g.arrived) t.accentText else t.ink, modifier = Modifier.testTag("goto_distance"))
                    if (!g.arrived) {
                        Text("${g.compass}쪽 · 방위각 ${Calc.dms(g.bearingDeg)} (북 기준 시계방향)", style = Ty.body, color = t.ink, modifier = Modifier.testTag("goto_bearing"))
                        Text(g.northText, style = Ty.body, color = t.ink, modifier = Modifier.testTag("goto_north"))
                        Text(g.eastText, style = Ty.body, color = t.ink, modifier = Modifier.testTag("goto_east"))
                    }
                }
                Text("방위는 지도의 북쪽이 기준이에요(수신기 방향 센서는 쓰지 않아요). 거리가 줄어드는 쪽으로 걸으세요.", style = Ty.small, color = t.inkSub)
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier.testTag(tag),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** 높이가 제한된 점 목록 (번호 · 종류). 눌러서 고른다. */
@Composable
fun PointChooser(points: List<SavedPoint>, tag: String, onPick: (SavedPoint) -> Unit) {
    val t = T
    if (points.isEmpty()) { Text("측점한 점이 없어요.", style = Ty.small, color = t.inkSub); return }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, t.line, RoundedCornerShape(12.dp)).testTag(tag)) {
        items(points, key = { it.name }) { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).hClickable { onPick(p) }.padding(horizontal = 12.dp).testTag("${tag}_${p.name}"), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = Ty.label, color = t.ink, modifier = Modifier.weight(1f))
                Text(p.type, style = Ty.small, color = t.inkSub)
            }
        }
    }
}

// ---------------- 계산기 ----------------

@Composable
fun CalcScreen(c: SurveyController) {
    val t = T; val h = c.hub
    ToolScaffold("계산기", "calc_screen", onBack = { h.back() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in CalcMode.entries) Choice(m.label, m == h.calcMode, Modifier.weight(1f).testTag("calc_mode_${m.name}")) { h.calcMode = m; h.calcPicking = null }
        }
        when (h.calcMode) {
            CalcMode.DISTANCE -> {
                Card {
                    Text("두 점 사이", style = Ty.label, color = t.ink)
                    PickRow("출발", if (h.calcUseCurrentA) "현재 위치" else h.calcA?.name, "calc_pick_a") { h.calcPicking = if (h.calcPicking == "A") null else "A" }
                    Btn("출발을 현재 위치로", Modifier.fillMaxWidth().testTag("calc_a_current")) { h.calcUseCurrentA = true; h.calcA = null; h.calcPicking = null }
                    PickRow("도착", h.calcB?.name, "calc_pick_b") { h.calcPicking = if (h.calcPicking == "B") null else "B" }
                    if (h.calcPicking == "A" || h.calcPicking == "B") PointChooser(c.points.asReversed(), "calc_list") { h.pick(it) }
                }
                val r = h.distResult()
                Card {
                    Text("결과", style = Ty.label, color = t.ink)
                    if (r == null) Text("출발과 도착을 고르세요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("calc_dist_empty"))
                    else {
                        Text("거리 ${f3(r.dist)} m", style = Ty.body, color = t.ink, modifier = Modifier.testTag("calc_dist"))
                        Text("방위각 ${Calc.dms(r.bearing)} (${"%.4f".format(Locale.US, r.bearing)}°) · ${Calc.compass8(r.bearing)}쪽", style = Ty.body, color = t.ink, modifier = Modifier.testTag("calc_bearing"))
                        Text("ΔN ${f3(r.dN)} m · ΔE ${f3(r.dE)} m", style = Ty.small, color = t.inkSub)
                    }
                }
            }
            CalcMode.AREA -> {
                Card {
                    Text("점을 이어지는 순서대로 고르세요 (3개 이상)", style = Ty.label, color = t.ink)
                    Text(if (h.areaPicks.isEmpty()) "아직 고른 점이 없어요" else h.areaPicks.joinToString(" → ") { it.name }, style = Ty.body, color = t.ink, modifier = Modifier.testTag("calc_area_picks"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Btn(if (h.calcPicking == "AREA") "목록 닫기" else "점 추가", Modifier.fillMaxWidth().testTag("calc_area_add")) { h.calcPicking = if (h.calcPicking == "AREA") null else "AREA" } }
                        Box(Modifier.weight(1f)) { Btn("마지막 빼기", Modifier.fillMaxWidth().testTag("calc_area_undo")) { if (h.areaPicks.isNotEmpty()) h.areaPicks.removeAt(h.areaPicks.lastIndex) } }
                        Box(Modifier.weight(1f)) { Btn("모두 지우기", Modifier.fillMaxWidth().testTag("calc_area_clear")) { h.areaPicks.clear() } }
                    }
                    if (h.calcPicking == "AREA") PointChooser(c.points.asReversed(), "calc_list") { h.pick(it) }
                }
                val r = h.areaResult()
                Card {
                    Text("결과", style = Ty.label, color = t.ink)
                    if (r == null) Text("점을 3개 이상 고르면 계산해요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("calc_area_empty"))
                    else {
                        Text("면적 ${"%.3f".format(Locale.US, r.area)} ㎡ (${"%.2f".format(Locale.US, Calc.toPyeong(r.area))} 평)", style = Ty.body, color = t.ink, modifier = Modifier.testTag("calc_area"))
                        Text("둘레 ${f3(r.perimeter)} m (마지막 점에서 첫 점으로 닫아 계산)", style = Ty.body, color = t.ink, modifier = Modifier.testTag("calc_perimeter"))
                        if (r.crossing) Text("선이 서로 엇갈려요. 점 순서를 확인하세요. 지금 값은 믿지 마세요.", style = Ty.small, color = t.bad, modifier = Modifier.testTag("calc_crossing"))
                    }
                }
            }
            CalcMode.OFFSET -> {
                Card {
                    Text("기준점에서 방위각·거리만큼 떨어진 좌표", style = Ty.label, color = t.ink)
                    PickRow("기준점", h.calcA?.name, "calc_pick_base") { h.calcPicking = if (h.calcPicking == "BASE") null else "BASE" }
                    if (h.calcPicking == "BASE") PointChooser(c.points.asReversed(), "calc_list") { h.pick(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("방위각 (°, 북 기준)", h.offBearing, "calc_bearing_in", Modifier.weight(1f)) { h.offBearing = it }
                        NumField("거리 (m)", h.offDist, "calc_dist_in", Modifier.weight(1f)) { h.offDist = it }
                    }
                }
                val r = h.offsetResult()
                Card {
                    Text("결과", style = Ty.label, color = t.ink)
                    if (r == null) Text("기준점, 방위각, 거리를 입력하세요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("calc_off_empty"))
                    else {
                        Text("N ${f3(r.n)}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("calc_off_n"))
                        Text("E ${f3(r.e)}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("calc_off_e"))
                        Text("좌표계 EPSG:${c.zone.epsg} · 저장하지 않는 계산이에요", style = Ty.small, color = t.inkSub)
                    }
                }
            }
        }
        Text("모든 계산은 평면(TM) 좌표 기준이에요. 높이 차는 계산하지 않아요.", style = Ty.small, color = t.inkSub)
    }
}

@Composable
private fun PickRow(label: String, value: String?, tag: String, onClick: () -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, t.outline, RoundedCornerShape(14.dp)).hClickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(56.dp))
        Text(value ?: "고르기", style = Ty.body, color = if (value == null) t.accentText else t.ink)
    }
}

// ---------------- 설정 ----------------

/** 0.1.43 시안 ⑩ 설정: 꼭 필요한 것 · 측정 · 발굴 · 지도 · 보관 · 버전. 모든 값은 줄 누르기 → 고르기 창(숫자 입력 없음) */
@Composable
fun SettingsScreen(c: SurveyController) {
    val h = c.hub
    val st = c.settingsState
    val d = c.dig
    val cc = c.connection
    var ftypesOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var driveOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    @Composable fun group(title: String, right: String? = null, first: Boolean = false) {
        Column(Modifier.padding(top = 4.dp)) {
            if (!first) DRule()
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = DTy.bodyB, color = D.ink, modifier = Modifier.weight(1f)); right?.let { Text(it, style = DTy.tiny, color = D.sub) }
            }
        }
    }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(c.settingsTab) }
    ToolScaffold("설정", "settings_screen", onBack = { h.back() }, right = "이 작업에 저장") {
        // 0.1.46 시안 s07: 일반 · 수신기 · 고급(위험·시험) 세 칸
        SettingsTabs(tab) { tab = it; c.settingsTab = it }
        if (tab == 1) {
        group("수신기 · 보정", "측량 전에 한 번", first = true)
        val model = cc?.profile?.displayName?.let { HeaderModel.shortModel(it) } ?: "수신기"
        DLeaderRow("수신기", model + " · " + if (c.gv.noReceiver) "연결 안 됨" else c.gv.quality.labelKo, "set_receiver") { cc?.openScreen() ?: c.ui.toast("연결 화면을 열 수 없어요") }
        DLeaderRow("VRS 보정", if (cc?.form?.enabled == true) (cc.form.host.substringBefore('.').ifBlank { "VRS" } + " · 켬") else "끔", "set_vrs") { cc?.openVrs() ?: c.ui.toast("VRS 설정을 열 수 없어요") }
        DLeaderRow("폴 높이", String.format(java.util.Locale.US, "%.2f m", c.antenna.inputValueM), "set_pole") { c.antennaSheetOpen = true }
        // 좌표계·높이는 작업 정보에서만 편집. 점이 있으면 잠김
        DLeaderRow("좌표계 · 높이", "${c.zone.epsg} · ${c.geoid.shortKo}" + if (d.crsLocked) " · 잠김" else "", "zone_toggle") { c.jobs?.openInfo() ?: c.ui.toast("작업 관리를 쓸 수 없어요") }
        c.tilt?.let { TiltSettings(it) }
        }
        if (st == null) { Text("나머지 설정을 불러올 수 없어요", style = DTy.body, color = D.sub); return@ToolScaffold }
        if (tab == 0) {
        group("측정", first = true)
        val counts = listOf(1, 3, 5, 10)
        DLeaderRow("한 점 측정 횟수", "${c.epochs}회 평균", "epochs_toggle") {
            c.ui.choose(ChoiceRequest("한 점 측정 횟수", counts.map { "${it}회" + if (it == 3) " (기본)" else "" }, counts.indexOf(c.epochs), "epochs",
                "측정 단추를 누르면 이 횟수만큼 위치를 받아 평균해 저장해요. 각 측정값과 편차는 점 확인에서 볼 수 있어요.") { st.setEpochs(counts[it]) })
        }
        DLeaderRow("FIX일 때만 저장", null, "set_strict_quality", toggle = st.requireFixed) { st.changeRequireFixed(!st.requireFixed) }
        DLeaderRow("측정 단추 자리", if (c.poleOnRight) "오른손" else "왼손", "pole_side") {
            c.ui.choose(ChoiceRequest("측정 단추 자리 (폴 쪽)", listOf("오른손", "왼손"), if (c.poleOnRight) 0 else 1, "pole", null) { st.setPoleRight(it == 0) })
        }
        DLeaderRow("볼륨 아래 키로 측정", null, "set_volume_key", toggle = d.volumeKey) { d.changeVolumeKey(!d.volumeKey) }
        val tols = listOf(2, 3, 5)
        DLeaderRow("기준점 허용 차이", "${Math.round(d.bmTolM * 100)} cm", "set_bm_tol") {
            c.ui.choose(ChoiceRequest("기준점 확인 허용 차이", tols.map { "$it cm" + if (it == 3) " (기본)" else "" }, tols.indexOfFirst { kotlin.math.abs(d.bmTolM - it / 100.0) < 1e-6 }, "set_bm_tol", null) { d.changeBmTol(tols[it] / 100.0) })
        }
        group("발굴")
        val arts = kr.archsurvey.dig.ArtifactMode.entries
        DLeaderRow("유물 번호", d.artifactMode.labelKo, "set_art") {
            c.ui.choose(ChoiceRequest("유물 번호", arts.map { it.labelKo }, arts.indexOf(d.artifactMode), "set_art", null) { d.changeArtifactMode(arts[it]) })
        }
        DLeaderRow("새 유구는 가번호로", null, "set_provisional", toggle = d.provisional) { d.changeProvisional(!d.provisional) }
        DLeaderRow("쓰는 유구 종류", "${d.featureTypes.size}가지", "set_ftypes") { ftypesOpen = !ftypesOpen }
        if (ftypesOpen) {
            var typesText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(d.featureTypes.joinToString(", ")) }
            Text("눌러서 켜기/끄기 · 바로 저장", style = DTy.small, color = D.sub)
            ChipFlow {
                for (ty in OneFingerTypes.catalog(d.featureTypes)) DChip(ty, ty in d.featureTypes, "set_ftype_$ty") {
                    d.setFeatureTypes(OneFingerTypes.toggle(d.featureTypes, ty)); typesText = d.featureTypes.joinToString(", ")
                }
            }
            var typing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            TypeFallbackRow(typing, "set_feature_types_manual") { typing = !typing }
            if (typing) {
                Field("수혈, 주거지, 주혈 …", typesText, "set_feature_types") { typesText = it }
                Btn("유구 종류 저장", Modifier.fillMaxWidth().testTag("set_feature_types_save")) { d.setFeatureTypes(typesText.split(',', '\n')); typesText = d.featureTypes.joinToString(", "); c.ui.ok("유구 종류 ${d.featureTypes.size}개") }
            }
        }
        val scales = kr.archsurvey.dig.DrawingScale.CHOICES
        DLeaderRow("도면 축척", "1:${d.drawScale}", "set_scale") {
            c.ui.choose(ChoiceRequest("도면 축척 (내보내기 글자 크기)", scales.map { "1:$it" }, scales.indexOf(d.drawScale), "set_scale", null) { d.changeScale(scales[it]); c.exporter?.scale = scales[it] })
        }
        group("지도")
        DLeaderRow("배경지도", c.mapBase.labelKo, "set_mapbase") { chooseMapBase(c) }
        DLeaderRow("위치 표시", "점 · 이름표", "set_marker") { c.markerStyleOpen = true }
        DLeaderRow("화면 켜 두기", null, "set_keepscreen", toggle = st.keepScreenOn) { st.changeKeepScreenOn(!st.keepScreenOn) }
        group("보관", "점은 잴 때마다 폰에 저장")
        val dr = c.drive
        DLeaderRow("구글 드라이브", if (dr?.email.isNullOrBlank()) "연결 안 함" else if (dr?.autoBackup == true) "자동 백업 켬" else "연결됨", "set_drive") { driveOpen = !driveOpen }
        if (driveOpen) DriveConnectCard(c.drive, c.jobs?.currentFolder?.removePrefix("Documents/"))
        DLeaderRow("백업 · 복구", "${c.backup?.items?.size ?: 0}개 · " + backupLabel(c).removePrefix("백업 "), "set_backup") { c.backupQuickOpen = true }
        DLeaderRow("소리 · 진동", null, "set_sound", toggle = st.sound || st.vibration) { val v = !(st.sound || st.vibration); st.changeSound(v); st.changeVibration(v) }
        }
        if (tab == 2) {
        group("고급 (현장에서는 바꾸지 마세요)", first = true)
        DLeaderRow("V월드 키 · 지적 스타일", if (c.vworld.hasKey) "키 있음" else "키 없음", "mapbase_toggle") { c.vworld.load(); c.hub.open(ToolScreen.VWORLD) }
        c.voice?.let { VoiceSettingsCard(it) }
        }
        DRule()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rememberAppVersionLabel(), style = DTy.small, color = D.sub, modifier = Modifier.weight(1f).testTag("app_version"))
            Text("오류 기록 보내기", style = DTy.small.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = D.accent,
                modifier = Modifier.heightIn(min = 48.dp).hClickable { if (st.showCrash) st.showCrash = false else st.openCrash() }.padding(vertical = 12.dp).testTag("crash_toggle"))
        }
        if (st.showCrash) Card {
            val txt = st.crashText
            Text(if (txt.isBlank()) "기록된 오류가 없어요." else "최근 오류 기록 (문의할 때 ‘공유’로 보내 주세요)", style = Ty.label, color = D.ink)
            if (txt.isNotBlank()) Text(txt.takeLast(4000), style = DTy.monoS.copy(fontSize = 16.sp, lineHeight = 21.sp), color = D.ink, modifier = Modifier.testTag("crash_text"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Btn("공유", Modifier.fillMaxWidth().testTag("crash_share")) { st.shareCrash() } }
                Box(Modifier.weight(1f)) { Btn("지우기", Modifier.fillMaxWidth().testTag("crash_clear")) {
                    c.ui.ask(ConfirmRequest("오류 기록을 지울까요?", "지운 기록은 되살릴 수 없어요.", "지우기") { st.clearCrash() })
                } }
            }
        }
    }
}

@Composable
private fun SettingsTabs(sel: Int, onSel: (Int) -> Unit) {
    val sh = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(sh).background(D.chip).padding(4.dp).testTag("settings_tabs")) {
        listOf("일반", "수신기", "고급").forEachIndexed { i, l ->
            Box(Modifier.weight(1f).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(12.dp)).background(if (sel == i) D.card else androidx.compose.ui.graphics.Color.Transparent)
                .hClickable { onSel(i) }.testTag("settings_tab_$i"), contentAlignment = Alignment.Center) {
                Text(l, style = DTy.bodyB, color = if (sel == i) D.ink else D.sub)
            }
        }
    }
}

/** 전자 기포 설정: 평소엔 한 줄로 접혀 있고, 펼치면 켜기·허용 각도·넘을 때 동작·영점 잡기 */
@Composable
private fun TiltSettings(tc: TiltController) {
    val t = T
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!tc.available) {
        Card { Text("전자 기포: 이 폰에는 기울기(가속도) 센서가 없어 쓸 수 없어요", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("tilt_no_sensor")) }
        return
    }
    val summary = if (tc.enabled) "켜짐 · 허용 ${tc.f1(tc.limitDeg)}° · ${tc.policy.labelKo}" else "꺼짐"
    DLeaderRow("전자 기포", if (tc.enabled) "${tc.f1(tc.limitDeg)}° · ${tc.policy.labelKo}" else "끔", "tilt_toggle") { open = !open }
    if (!open) return
    Card {
        Text(TiltCopy.NOTE, style = Ty.small, color = t.ink, modifier = Modifier.testTag("tilt_copy"))
        ToggleRow("폴 기울기 보기 (폰 센서)", tc.enabled, "tilt_enabled") { tc.changeEnabled(it) }
        if (tc.enabled) {
            Text("허용 각도", style = Ty.label, color = t.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MBtn(Modifier.size(56.dp).testTag("tilt_minus"), MKind.PLAIN, 16.dp, onClick = { tc.stepLimit(false) }) { GlyphIcon(Glyph.MINUS, t.accent, 22.dp) }
                Text("${tc.f1(tc.limitDeg)}°", style = Ty.coord.copy(fontSize = 24.sp), color = t.ink, modifier = Modifier.testTag("tilt_limit"))
                MBtn(Modifier.size(56.dp).testTag("tilt_plus"), MKind.PLAIN, 16.dp, onClick = { tc.stepLimit(true) }) { GlyphIcon(Glyph.PLUS, t.accent, 22.dp) }
            }
            Text(TiltCopy.offsetLine(tc.limitDeg), style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("tilt_offset"))
            Text("넘으면", style = Ty.label, color = t.ink)
            for (pol in kr.archsurvey.core.TiltPolicy.entries) Choice(pol.labelKo, tc.policy == pol, Modifier.fillMaxWidth().testTag("tilt_policy_${pol.id}")) { tc.changePolicy(pol) }
            Btn("폴 세우고 영점 잡기", Modifier.fillMaxWidth().testTag("tilt_calibrate")) { tc.calibrate() }
            tc.calibrationNote?.let { Text(it, style = Ty.small, color = t.ink, modifier = Modifier.testTag("tilt_note")) }
            Text("폰을 폴에 세로로 고정하고, 폴을 기포·수준기로 똑바로 세운 뒤 누르세요. 각도는 경고·기록용이고 좌표를 보정하지는 않아요.", style = Ty.small, color = t.inkSub)
            if (tc.calibrated) Btn("영점 기본값으로", Modifier.fillMaxWidth().testTag("tilt_reset")) { tc.resetCalibration() }
        }
    }
}

@Composable
internal fun ToggleRow(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
        val view = androidx.compose.ui.platform.LocalView.current; val hap = LocalTapHaptic.current
        androidx.compose.material3.Switch(value, { if (hap) TapHaptics.tap(view); onChange(it) }, modifier = Modifier.testTag(tag))
    }
}

// ---------------- 작업 관리 ----------------

/**
 * 0.1.12 작업 정보 확인 / 새 작업: SmartTopo 2024 「작업 정보 확인」과 같은 배치(구조만) —
 * 위에서부터 작업명 · 좌표계 · 지역 · 배경맵 · Geoid, 각 줄 오른쪽 ›, 아래 고정 「나가기 | 적용」.
 * 색·글꼴·아이콘은 이 앱 것(아이보리·검정). 작업 열기·삭제 목록은 그대로.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun JobScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val jc = c.jobs
    if (jc == null) { Box(Modifier.fillMaxSize().background(t.bg)) { Text("작업 관리를 쓸 수 없어요", style = Ty.body, color = t.inkSub, modifier = Modifier.padding(24.dp)) }; return }
    val title = when (h.screen) { ToolScreen.JOB_NEW -> "새 작업"; ToolScreen.JOB_OPEN -> "작업"; else -> "작업 정보 확인" }
    ToolScaffold(title, "job_screen", onBack = { h.back() }, close = h.screen == ToolScreen.JOB_NEW,
        right = if (h.screen == ToolScreen.JOB_OPEN) "폰 문서 › ExcavationSurvey" else null, bottom = {
        when (h.screen) {
            // 0.1.39: 큰 단추 하나 — 작업+폴더를 만들고 바로 측량 지도로
            ToolScreen.JOB_NEW -> DBottomBar {
                DSecondary("취소", "job_cancel", Modifier.weight(1f)) { h.back() }
                DPrimary(if (jc.busy) "만드는 중…" else "만들고 측량 시작", "job_create", Modifier.weight(1.9f), enabled = !jc.busy) { jc.create() }
            }
            ToolScreen.JOB_OPEN -> DBottomBar {
                DSecondary("백업 · 복구", "job_backup", Modifier.weight(1f)) { c.backupQuickOpen = true }
                DPrimary("+ 새 작업", "job_new", Modifier.weight(1.9f)) { jc.openNew() }
            }
            ToolScreen.JOB_INFO -> DualBar("나가기", "job_exit", { h.back() }, "적용", "job_apply", !jc.busy) { jc.applyInfo() }
            else -> {}
        }
    }) {
        jc.notice?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.testTag("job_notice")) }
        jc.error?.let { ErrorCardView(ErrorCard("-", it, "이름이나 값을 바꾸거나 다시 시도하세요.", false), "job_error") }
        when (h.screen) {
            ToolScreen.JOB_NEW -> {
                // 0.1.46 시안 s02: 이름(자동) 카드 → 유적 · 조사 단계 · 지구 · 좌표계 원점 · 배경 지도 — 모두 칸 선택
                SectionHead("이름 (자동)")
                WhiteCard(tag = "job_preview") {
                    Column(Modifier.padding(16.dp)) {
                        Text(jc.effectiveName.ifBlank { "이름을 고르세요" }, style = DTy.h1.copy(fontSize = 20.sp), color = D.ink, modifier = Modifier.testTag("job_built_name"))
                        Text("유적 + 지구 + 날짜로 자동 · 지난 작업 설정 그대로", style = DTy.small, color = D.sub)
                        Text(jc.newFolderPreview, style = DTy.small, color = D.weak, maxLines = 2, modifier = Modifier.testTag("job_folder_preview"))
                    }
                }
                // 0.1.51 새 작업 쉽게: 기본값(자동 이름·지난 작업 설정)이 이미 골라져 있어 「만들고 측량 시작」 한 번. 바꾸기는 아래 「바꾸기 ›」 안에
                DRule(false)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable { jc.newDetailsOpen = !jc.newDetailsOpen }.testTag("job_details"), verticalAlignment = Alignment.CenterVertically) {
                    Text("${jc.formZone.shortKo} ${jc.formZone.epsg} · 높이 ${jc.formGeoid.shortKo} · ${jc.formMap.labelKo}", style = DTy.body, color = D.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (jc.newDetailsOpen) "접기 ⌃" else "바꾸기 ›", style = DTy.small, color = D.accent)
                }
                DRule(false)
                if (jc.newDetailsOpen) {
                    SectionHead("유적")
                    ChoiceGrid(jc.siteChips.map { g -> GridItem(g, "job_site_$g", !jc.newGroupTyping && jc.newSite == g) { jc.pickSite(g) } } +
                        GridItem("없음", "job_site_none", !jc.newGroupTyping && jc.newSite.isBlank()) { jc.pickSite(null) } +
                        GridItem("새 유적…", "job_site_new", jc.newGroupTyping) { jc.startNewGroup() })
                    // 새 유적 이름만은 글자가 필요해 키보드 대체 경로로 남김(시안 「새 유적…」)
                    if (jc.newGroupTyping) OutlinedTextField(jc.newSite, { jc.newSite = it }, singleLine = true, label = { Text("새 유적 이름 · 예) 강릉 교동") }, modifier = Modifier.fillMaxWidth().testTag("job_group"))
                    SectionHead("조사 단계")
                    val stages = kr.archsurvey.job.SurveyStage.entries
                    ChoiceGrid(stages.map { st -> GridItem(st.labelKo, "job_stage_${st.name}", jc.newStage == st) { jc.pickStage(st) } }, modifier = Modifier.testTag("job_stage"))
                    SectionHead("지구 · 날짜 ${jc.mmdd.let { if (it.length == 4) it.take(2) + "-" + it.drop(2) else it }}는 저절로")
                    val maxNo = kr.archsurvey.job.NewJobName.MAX_NO
                    val nos = (1..5).toMutableList().apply { if (jc.newNo > 5) add(jc.newNo) }
                    ChoiceGrid(nos.map { n -> GridItem("${n}지구", "job_no_$n", jc.newNo == n) { jc.setNo(n) } } +
                        GridItem("−", "job_no_m1") { jc.setNo((jc.newNo - 1).coerceAtLeast(1)) } +
                        GridItem("+", "job_no_p1") { jc.setNo((jc.newNo + 1).coerceAtMost(maxNo)) }, modifier = Modifier.testTag("job_no"))
                    SectionHead("좌표계 원점")
                    val zones = kr.archsurvey.core.KoreanTmZone.entries
                    ChoiceGrid(zones.mapIndexed { i, z -> GridItem(z.shortKo + " " + z.epsg + if (jc.recommendedZone == z) " · 추천" else "", "job_zone_${z.epsg}", jc.formZone == z, span = if (i == zones.lastIndex) 3 else 1) { jc.formZone = z } }, modifier = Modifier.testTag("job_zone_seg"))
                    Text(if (jc.recommendedZone != null) "지금 위치(경도)로 추천 원점을 표시했어요. 첫 점을 재면 잠깁니다." else "아직 위치가 없어 고른 원점으로 둡니다. 첫 점을 재면 잠깁니다.", style = DTy.small, color = D.sub, modifier = Modifier.testTag("job_zone_note"))
                    SectionHead("배경 지도")
                    val maps = listOf(kr.archsurvey.map.MapBase.SATELLITE_CADASTRAL to "위성 + 지적", kr.archsurvey.map.MapBase.SATELLITE to "위성만", kr.archsurvey.map.MapBase.CADASTRAL to "일반 + 지적")
                    ChoiceGrid(maps.map { (m, l) -> GridItem(l, "job_newmap_${m.id}", jc.formMap == m) { jc.formMap = m } })
                    JobConfigRows(c, jc, editableZone = true)
                    // 0.1.46: 새 작업은 선택만 — 작업명 직접 입력 제거(이름은 유적·지구·날짜 선택으로 자동)
                }
            }
            ToolScreen.JOB_OPEN -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("최근 작업", style = DTy.bodyB, color = D.ink); Text(" ${jc.list.size}개", style = DTy.tiny, color = D.sub, modifier = Modifier.weight(1f))
                    Text("길게 누르면 지우기", style = DTy.tiny, color = D.sub)
                }
                Text("열려 있는 작업: ${jc.current}", style = DTy.tiny.copy(fontSize = 1.sp), color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.testTag("job_current"))
                Column {
                    DTableHead(listOf("작업" to 3f, "점" to 0.8f, "마지막" to 1.6f))
                    val today = kr.archsurvey.dig.DayKey.of(System.currentTimeMillis())
                    for ((i, j) in jc.list.withIndex()) {
                        val site = j.folder?.removePrefix("Documents/ExcavationSurvey/")?.split("/")?.takeIf { it.size >= 2 }?.first()
                        val sub = listOfNotNull(site ?: "유적 없음", if (j.name == jc.current) "열려 있음" else null, if (j.points == 0) "빈 작업" else "사진 ${j.photos}" + if (j.voices > 0) " · 음성 ${j.voices}" else "",
                            if (j.fakePoints > 0) "시험 점 ${j.fakePoints}" else null).joinToString(" · ")
                        val last = j.lastAtMs?.let { if (kr.archsurvey.dig.DayKey.of(it) == today) "오늘 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it)) else SimpleDateFormat("MM-dd", Locale.KOREA).format(Date(it)) } ?: "—"
                        DTableRow(listOf(j.name to 3f, "${j.points}" to 0.8f, last to 1.6f), "job_item_$i", selected = j.name == jc.current, sub = sub,
                            firstStyle = DTy.serif.copy(fontSize = 19.sp), colors = mapOf(2 to D.sub), onLongClick = { jc.askDelete(j) }) { jc.open(j.name) }
                    }
                }
                val dr = c.drive
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(IntrinsicSize.Min).testTag("job_status")) {
                    for ((k, v) in listOf("자동 저장" to "잴 때마다", "백업" to "${c.backup?.items?.size ?: 0}개 · " + backupLabel(c).removePrefix("백업 "),
                            "구글 드라이브" to (if (dr?.email.isNullOrBlank()) "연결 안 함" else if (dr?.autoBackup == true) "자동 백업 켬" else "연결됨"))) {
                        if (k != "자동 저장") Box(Modifier.width(1.dp).fillMaxHeight().background(D.line))
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(k, style = DTy.bodyB.copy(fontSize = 16.sp), color = D.ink); Text(v, style = DTy.tiny, color = D.sub, maxLines = 1) }
                    }
                }
                Text("작업을 지우기 전에는 언제나 백업 파일을 먼저 만들어 둡니다.", style = DTy.small, color = D.sub)
            }
            else -> {
                val i = jc.info
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("작업명", style = Ty.small, color = t.inkSub)
                    Text(jc.current, style = Ty.title, color = t.inkSub, modifier = Modifier.testTag("job_info_name"))
                    (i?.folder ?: jc.currentFolder)?.let { Text("폴더 · $it", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("job_info_folder")) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                }
                // 0.1.35 X11: 점이 있는 작업은 좌표계·높이 잠금(실수로 바꾸면 도면이 어긋남). 풀기 버튼으로만
                if (c.dig.crsLocked) Card {
                    Text("측량 중 잠김 · 점 ${c.points.size}개가 이 좌표계·높이로 저장돼 있어요", style = Ty.label, color = t.ink, modifier = Modifier.testTag("crs_locked"))
                    Btn("잠금 풀고 바꾸기", Modifier.fillMaxWidth().testTag("crs_unlock")) {
                        c.ui.ask(ConfirmRequest("좌표계·높이 잠금을 풀까요?", "이미 저장된 점은 바뀌지 않고, 바꾼 뒤 찍는 점부터 적용돼요. 같은 도면에 섞이면 위치가 어긋날 수 있어요.", "풀기") { c.dig.crsUnlocked = true })
                    }
                }
                JobConfigRows(c, jc, editableZone = !c.dig.crsLocked)
                Card {
                    if (i != null) {
                        Text("점 ${i.points}개 · 사진 ${i.photos}장 · 음성 ${i.voices}개", style = Ty.body, color = t.ink, modifier = Modifier.testTag("job_info_counts"))
                        i.firstAtMs?.let { Text("첫 측점 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)), style = Ty.small, color = t.inkSub) }
                        i.lastAtMs?.let { Text("마지막 측점 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)), style = Ty.small, color = t.inkSub) }
                        if (i.fakePoints > 0) Text("이전 시험 버전(0.1.1)에서 수신기 없이 만든 점이 ${i.fakePoints}개 있어요. 실측 데이터가 아니에요.", style = Ty.small, color = t.bad)
                    }
                    // 0.1.13 P0: 같은 화면에서 form(초안)과 라이브가 어긋나지 않게 — 적용 전 미리보기도 form*
                    Text("적용 예정 EPSG:${jc.formZone.epsg} · Geoid ${jc.formGeoid.shortKo} · 배경맵 ${jc.formMap.labelKo}", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("job_info_preview"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Btn("작업 열기 · 삭제", Modifier.fillMaxWidth().testTag("job_to_list")) { jc.openList() } }
                    Box(Modifier.weight(1f)) { Btn("새 작업", Modifier.fillMaxWidth().testTag("job_to_new")) { jc.openNew() } }
                }
            }
        }
        jc.confirmDelete?.let { j ->
            ConfirmDialog(
                "‘${j.name}’ 작업을 지울까요?",
                "점 ${j.points}개, 사진 ${j.photos}장, 음성 ${j.voices}개가 폰에서 지워져요. 지우기 직전에 전체 백업을 자동으로 하나 만들어 두지만, 중요한 자료라면 먼저 ‘작업 내보내기’로 보내 두세요.",
                "지우기", { jc.delete() }, { jc.confirmDelete = null },
            )
        }
    }
}

/** 좌표계 · 지역 · 배경맵 · Geoid 네 줄(SmartTopo 순서). 값은 [JobController] 의 form 에 담겼다가 「만들기/적용」에서 반영 */
@Composable
private fun JobConfigRows(c: SurveyController, jc: JobController, editableZone: Boolean) {
    val zones = kr.archsurvey.core.KoreanTmZone.entries
    FormRow("좌표계", "GRS80 (TM)", "job_crs") {
        c.ui.choose(ChoiceRequest("좌표계", listOf("GRS80 (TM) · 한국 평면직각"), 0, "jobcrs",
            "지금은 GRS80 TM(EPSG:5185~5188)만 써요. Bessel·UTM·경위도 좌표계는 이 앱에서 지원하지 않아요.") {})
    }
    FormRow("지역", jc.formZone.labelKo, "job_zone") {
        if (!editableZone) { c.ui.toast("측량 중 잠김 · 위의 「잠금 풀고 바꾸기」를 누르세요"); return@FormRow }
        val opts = zones.map { it.labelKo } + "자동 (현재 위치로 고르기)"
        c.ui.choose(ChoiceRequest("지역 (투영 원점)", opts, zones.indexOf(jc.formZone), "jobzone",
            "서부 125°E · 중부 127°E · 동부 129°E(EPSG:5187, 경북·경남 동부) · 동해 131°E. 점이 있는 작업은 다른 좌표계로 바꿀 수 없어요.") { i ->
            if (i < zones.size) jc.formZone = zones[i] else jc.autoZone()
        })
    }
    val bases = kr.archsurvey.map.MapBase.entries
    FormRow("배경맵", jc.formMap.labelKo, "job_map") {
        c.ui.choose(ChoiceRequest("배경맵", bases.map { it.labelKo }, bases.indexOf(jc.formMap), "jobmap",
            if (c.vworld.hasKey) "V월드 지적도는 인터넷으로 받아요(참고용)." else "V월드 키가 아직 없어요. 환경설정 → V월드 지적도에서 키를 넣어야 보여요(지금은 점만).") { jc.formMap = bases[it] })
    }
    val geoids = kr.archsurvey.core.GeoidModel.entries
    FormRow("Geoid", jc.formGeoid.labelKo, "job_geoid") {
        if (!editableZone) { c.ui.toast("측량 중 잠김 · 위의 「잠금 풀고 바꾸기」를 누르세요"); return@FormRow }
        c.ui.choose(ChoiceRequest("Geoid (표고 모델)", geoids.map { it.labelKo }, geoids.indexOf(jc.formGeoid), "jobgeoid",
            "새 점부터 적용돼요. 수신기 출력 = 수신기가 보낸 해발고 그대로. EGM96 = 앱이 타원체고에서 EGM96 지오이드고를 빼요(수신기가 지오이드 분리를 보내야 함). KNGeoid18은 격자 파일이 없어 아직 지원하지 않아요.") { jc.formGeoid = geoids[it] })
    }
}

/** SmartTopo 식 입력 줄: 작은 이름 위, 값 + › 아래, 밑줄 (48dp 이상) */
@Composable
internal fun FormRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    val t = T
    Column(Modifier.fillMaxWidth().hClickable(onClick = onClick).testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Ty.small, color = t.inkSub)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = Ty.body.copy(fontSize = 17.sp), color = t.ink, modifier = Modifier.weight(1f).testTag(tag + "_value"), maxLines = 2)
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(t.chip), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CHEVRON_RIGHT, t.ink, 16.dp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** 아래 고정 두 칸 막대: 왼쪽 나가기 · 오른쪽 적용(주 동작) */
@Composable
internal fun DualBar(left: String, leftTag: String, onLeft: () -> Unit, right: String, rightTag: String, enabled: Boolean = true, onRight: () -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MBtn(Modifier.weight(1f).height(56.dp).testTag(leftTag), MKind.PLAIN, 28.dp, onClick = onLeft) { Text(left, style = Ty.button, color = t.ink) }
        Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).glossy(if (enabled) t.primary else t.chip, enabled).hClickable(enabled = enabled, onClick = onRight).testTag(rightTag),
            contentAlignment = Alignment.Center) { Text(right, style = Ty.button, color = if (enabled) t.onPrimary else t.inkSub) }
    }
}

/** 평균 측정 메타 한 줄: 평균 시각·표본 수(고정/Float)·보정 연령. 기록 없는 옛 점은 빈 문자열 */
internal fun averagingLine(p: SavedPoint): String {
    val s = p.avgStartedAtMs; val e = p.avgEndedAtMs
    val parts = ArrayList<String>()
    if (s != null && e != null) {
        val f = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        parts += "평균 ${f.format(Date(s))}–${f.format(Date(e))}"
    }
    if (p.fixedEpochs + p.floatEpochs > 0) parts += "표본 ${p.epochs} (고정 ${p.fixedEpochs}·Float ${p.floatEpochs})"
    p.correctionAgeSec?.let { last ->
        val mx = p.maxCorrectionAgeSec
        parts += "보정 연령 ${String.format(Locale.US, "%.1f", last)}초" + (if (mx != null && mx > last) " (최대 ${String.format(Locale.US, "%.1f", mx)}초)" else "")
    }
    p.tiltDeg?.let { a -> parts += "폴 기울기 ${String.format(Locale.US, "%.1f", a)}°" + (p.tiltMaxDeg?.let { m -> " (최대 ${String.format(Locale.US, "%.1f", m)}°)" } ?: "") }
    return if (parts.isEmpty()) "" else "\n" + parts.joinToString(" · ")
}


/** 점 상세: 평균에 쓴 각 측정값과 평균에서의 편차(mm) */
@Composable
private fun SamplesCard(p: SavedPoint) {
    val t = T
    val n = p.samples.map { it.n }.average(); val e = p.samples.map { it.e }.average()
    val hs = p.samples.mapNotNull { it.h }; val h = if (hs.isEmpty()) null else hs.average()
    fun mm(v: Double) = (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.0f", kotlin.math.abs(v) * 1000)
    Card {
        Text("측정값 ${p.samples.size}회 · 평균에서의 편차(mm)", style = Ty.label, color = t.ink)
        for ((i, s) in p.samples.withIndex()) {
            Column(Modifier.fillMaxWidth().testTag("sample_$i")) {
                Text("${i + 1}회  N ${String.format(Locale.US, "%.3f", s.n)}  E ${String.format(Locale.US, "%.3f", s.e)}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, maxLines = 1)
                Text((s.h?.let { "H ${String.format(Locale.US, "%.3f", it)} · " } ?: "") + "ΔN ${mm(s.n - n)} ΔE ${mm(s.e - e)}" + (if (s.h != null && h != null) " ΔH ${mm(s.h - h)}" else "") + " mm", style = Ty.coord.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1)
            }
        }
        val spread = p.samples.maxOf { kotlin.math.hypot(it.n - n, it.e - e) }
        Text("수평 최대 편차 ${String.format(Locale.US, "%.0f", spread * 1000)} mm", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("samples_spread"))
    }
}
