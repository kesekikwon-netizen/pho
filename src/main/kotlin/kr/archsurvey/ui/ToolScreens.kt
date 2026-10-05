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
        null -> {}
    }
}

// ---------------- 점 목록 ----------------

@Composable
fun PointsScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val list = h.filtered()
    ToolScaffold("점 목록", "points_screen", onBack = { h.back() }) {
        Field("검색", h.query, "points_search") { h.query = it }
        Text("${list.size}개" + if (h.query.isNotBlank()) " (전체 ${c.points.size}개)" else "", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("points_count"))
        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (c.points.isEmpty()) "아직 측점한 점이 없어요" else "검색 결과가 없어요", style = Ty.title, color = t.ink, modifier = Modifier.testTag("points_empty"))
                if (c.points.isEmpty()) {
                    Text("지도 화면의 ‘측점’ 버튼으로 첫 점을 찍으면 여기에 쌓여요.", style = Ty.body, color = t.inkSub)
                    Btn("지도로 돌아가기", Modifier.fillMaxWidth().height(56.dp).testTag("points_to_map")) { h.closeAll() }
                }
            }
        }
        for (p in list) PointRow(p) { h.openDetail(p) }
    }
}

@Composable
private fun PointRow(p: SavedPoint, onClick: () -> Unit) {
    val t = T
    val (sbg, sfg) = statusColors(t, p.status)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(t.card).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("point_row_${p.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.name, style = Ty.coord.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = t.ink, maxLines = 1)
            Text(p.type + if (p.memo.isNotBlank()) " · ${p.memo}" else "", style = Ty.small, color = t.inkSub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("N ${f3(p.northing)}  E ${f3(p.easting)}", style = Ty.coord.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1)
        }
        // 상태는 색 + 아이콘 + 글자 (목업 규칙)
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(sbg).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) { StatusIcon(p.status, sfg, 16.dp); Text(if (p.status == SurveyStatus.FIXED) "고정" else "주의", style = Ty.label.copy(fontSize = 12.sp), color = sfg) }
    }
}

// ---------------- 점 상세 · 편집 ----------------

@Composable
fun PointDetailScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val p = h.selected
    if (p == null) { h.back(); return }
    ToolScaffold(p.name, "point_detail", onBack = { h.back() }, bottom = {
        PrimaryBar(if (h.saved && !h.dirty) "저장했어요" else "수정 저장", "point_save", enabled = h.dirty) { h.saveEdit() }
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
        Field("비고", h.memo(), "edit_memo") { h.editMemo = it }
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
                        Text(" m", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            }
        }
        Text("좌표·높이는 바꿀 수 없어요. 잘못 쟀다면 다시 측점하세요.", style = Ty.small, color = t.inkSub)
        Text(
            "${if (p.status == SurveyStatus.FIXED) "고정" else "주의"}${p.sigmaHM?.let { " ±${f3(it)} m" } ?: ""} · 위성 ${p.satellites} · ${p.epochs}회 평균 · EPSG:${p.crsEpsg}" +
                (p.groundMslM?.let { " · 표고 ${f3(it)} m" } ?: "") + if (p.measuredAtMs > 0) "\n" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(p.measuredAtMs)) else "",
            style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("detail_info"),
        )
        PointHistoryCards(c, p)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Btn("여기로 찾아가기", Modifier.fillMaxWidth().testTag("detail_goto")) { h.startGoto(p) } }
            Box(Modifier.weight(1f)) { Btn("지도에서 보기", Modifier.fillMaxWidth().testTag("detail_map")) { c.focusOn(p); h.closeAll() } }
        }
    }
}

private fun ToolsHub.memo() = editMemo

/** 목업의 읽기 전용 줄: 왼쪽 작은 이름 + 오른쪽 값 */
@Composable
private fun ReadRow(label: String, content: @Composable RowScope.() -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape).background(t.raised).border(1.5.dp, t.outline, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.widthIn(min = 72.dp))
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
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(p) }.padding(horizontal = 12.dp).testTag("${tag}_${p.name}"), verticalAlignment = Alignment.CenterVertically) {
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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, t.outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(56.dp))
        Text(value ?: "고르기", style = Ty.body, color = if (value == null) t.accentText else t.ink)
    }
}

// ---------------- 설정 ----------------

@Composable
fun SettingsScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val st = c.settingsState
    ToolScaffold("설정", "settings_screen", onBack = { h.back() }) {
        if (st == null) { Text("설정을 불러올 수 없어요", style = Ty.body, color = t.inkSub); return@ToolScaffold }
        var zoneOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        SelectRow("좌표 원점", c.zone.labelKo, zoneOpen, "zone_toggle") { zoneOpen = !zoneOpen }
        if (zoneOpen) Card {
            for (z in kr.archsurvey.core.KoreanTmZone.entries) Choice(z.labelKo, z == c.zone, Modifier.fillMaxWidth().testTag("zone_${z.epsg}")) { st.setZone(z); zoneOpen = false }
            Text("새로 측점하는 점부터 적용돼요. 대부분의 중부 지역은 중부원점(5186)이에요.", style = Ty.small, color = t.inkSub)
        }
        Card {
            Text("측점 버튼 위치 (폴 쪽)", style = Ty.label, color = t.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("오른쪽", c.poleOnRight, Modifier.weight(1f).testTag("pole_right")) { st.setPoleRight(true) }
                Choice("왼쪽", !c.poleOnRight, Modifier.weight(1f).testTag("pole_left")) { st.setPoleRight(false) }
            }
        }
        Card {
            ToggleRow("소리", st.sound, "set_sound") { st.changeSound(it) }
            ToggleRow("진동", st.vibration, "set_vibration") { st.changeVibration(it) }
            ToggleRow("화면 항상 켜두기", st.keepScreenOn, "set_keepscreen") { st.changeKeepScreenOn(it) }
        }
        Card {
            Text(if (c.gv.status.reason == kr.archsurvey.core.StatusReason.NOT_CONNECTED) "수신기 연결 안 됨" else "수신기 연결됨 · ${c.gv.quality.labelKo}", style = Ty.label, color = t.ink, modifier = Modifier.testTag("set_fake_state"))
        }
        Text(rememberAppVersionLabel() + " · 발굴 측량 · 실기기 시험 전 버전", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("app_version").padding(vertical = 8.dp))
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(value, onChange, modifier = Modifier.testTag(tag))
    }
}

// ---------------- 작업 관리 ----------------

@Composable
fun JobScreen(c: SurveyController) {
    val t = T; val h = c.hub
    val jc = c.jobs
    if (jc == null) { Box(Modifier.fillMaxSize().background(t.bg)) { Text("작업 관리를 쓸 수 없어요", style = Ty.body, color = t.inkSub, modifier = Modifier.padding(24.dp)) }; return }
    val title = when (h.screen) { ToolScreen.JOB_NEW -> "새 작업 만들기"; ToolScreen.JOB_OPEN -> "작업 열기 · 삭제"; else -> "작업 정보" }
    ToolScaffold(title, "job_screen", onBack = { h.back() }, bottom = {
        if (h.screen == ToolScreen.JOB_NEW) PrimaryBar(if (jc.busy) "만드는 중…" else "만들고 열기", "job_create", enabled = !jc.busy) { jc.create() }
    }) {
        jc.notice?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.testTag("job_notice")) }
        jc.error?.let { ErrorCardView(ErrorCard("-", it, "이름을 바꾸거나 다시 시도하세요.", false), "job_error") }
        when (h.screen) {
            ToolScreen.JOB_NEW -> {
                Card {
                    Text("작업 이름", style = Ty.label, color = t.ink)
                    OutlinedTextField(jc.newName, { jc.newName = it }, singleLine = true, label = { Text("예) 3지구-A트렌치") }, modifier = Modifier.fillMaxWidth().testTag("job_name"))
                    Text("새 작업은 점 번호가 P-0001부터 다시 시작해요. 이전 작업의 점은 그대로 있어요.", style = Ty.small, color = t.inkSub)
                }
            }
            ToolScreen.JOB_OPEN -> {
                Text("열려 있는 작업: ${jc.current}", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("job_current"))
                for ((i, j) in jc.list.withIndex()) {
                    Card {
                        Text(j.name + if (j.name == jc.current) "  (열려 있음)" else "", style = Ty.label, color = t.ink, modifier = Modifier.testTag("job_item_$i"))
                        Text("점 ${j.points} · 사진 ${j.photos} · 음성 ${j.voices}" + if (j.fakePoints > 0) " · 이전 시험 버전 점 ${j.fakePoints}" else "", style = Ty.small, color = t.inkSub)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Btn("열기", Modifier.fillMaxWidth().testTag("job_open_$i")) { jc.open(j.name) } }
                            Box(Modifier.weight(1f)) { Btn("삭제", Modifier.fillMaxWidth().testTag("job_delete_$i")) { jc.askDelete(j) } }
                        }
                    }
                }
                Btn("+ 새 작업 만들기", Modifier.fillMaxWidth().testTag("job_new")) { jc.openNew() }
            }
            else -> {
                val i = jc.info
                Card {
                    Text("열려 있는 작업", style = Ty.label, color = t.ink)
                    Text(jc.current, style = Ty.title, color = t.ink, modifier = Modifier.testTag("job_info_name"))
                    if (i != null) {
                        Text("점 ${i.points}개 · 사진 ${i.photos}장 · 음성 ${i.voices}개", style = Ty.body, color = t.ink, modifier = Modifier.testTag("job_info_counts"))
                        i.firstAtMs?.let { Text("첫 측점 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)), style = Ty.small, color = t.inkSub) }
                        i.lastAtMs?.let { Text("마지막 측점 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)), style = Ty.small, color = t.inkSub) }
                        if (i.fakePoints > 0) Text("이전 시험 버전(0.1.1)에서 수신기 없이 만든 점이 ${i.fakePoints}개 있어요. 실측 데이터가 아니에요.", style = Ty.small, color = t.bad)
                    }
                    Text("좌표계 EPSG:${c.zone.epsg} (새 점 기준)", style = Ty.small, color = t.inkSub)
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
