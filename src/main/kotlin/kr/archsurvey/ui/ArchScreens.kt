package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.arch.*
import kr.archsurvey.core.NE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 발굴 구역 · 그리드 · 외곽 관리 화면 */
@Composable
fun AreasScreen(c: SurveyController) {
    val t = T; val a = c.arch; val h = c.hub
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Area?>(null) }
    ToolScaffold("발굴 구역 · 그리드", "areas_screen", onBack = { h.back() }) {
        if (!a.enabled) { Text("이 빌드에서는 구역을 저장할 수 없어요", style = Ty.body, color = t.inkSub); return@ToolScaffold }
        a.error?.let { Text(it, style = Ty.body, color = t.bad, modifier = Modifier.testTag("arch_error")) }
        Card {
            Text("발굴 구역", style = Ty.label, color = t.ink)
            if (a.areas.isEmpty()) Text("아직 구역이 없어요. 아래에서 이름을 적어 만드세요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("areas_empty"))
            for (ar in a.areas) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Choice("${ar.name} · 그리드 ${a.gridsOf(ar.id).size}개", ar.id == a.activeAreaId, Modifier.weight(1f).testTag("area_${ar.id}")) { a.selectArea(ar.id) }
                    Btn("지우기", Modifier.width(80.dp).testTag("area_del_${ar.id}")) { confirmDelete = ar }
                }
            }
            // 0.1.39 한 손가락: 안 쓴 이름 칩을 누르면 바로 만들어짐. 그 밖은 「직접 입력」
            val taken = a.areas.map { it.name }.toSet()
            ChipChoices(OneFingerNames.areaChoices(taken), null, "area_pick", { it }) { nm -> a.addArea(nm) { newName = "" } }
            var typing by remember { mutableStateOf(false) }
            TypeFallbackRow(typing, "area_manual") { typing = !typing }
            if (typing) {
                Field("새 구역 이름 (예: A, 3지구)", newName, "area_name") { newName = it }
                Btn("구역 만들기", Modifier.fillMaxWidth().testTag("area_add")) { a.addArea(newName) { newName = "" } }
            }
        }
        val ar = a.activeArea
        if (ar != null) Card {
            Text("그리드 — 발굴 구역 ${ar.name}", style = Ty.label, color = t.ink)
            val gs = a.gridsOf(ar.id)
            if (gs.isEmpty()) Text("이 구역에 그리드가 없어요. 점 2개로 격자를 만들 수 있어요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("grids_empty"))
            for (g in gs) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Choice("${g.name} · ${g.cols}×${g.rows}칸 · 간격 ${"%.2f".format(g.spacingM)} m · 방위 ${"%.1f".format(g.azimuthDeg)}°", g.id == a.activeGridId, Modifier.weight(1f).testTag("grid_${g.id}")) { a.selectGrid(g) }
                    Btn("지우기", Modifier.width(80.dp).testTag("grid_del_${g.id}")) { a.deleteGrid(g.id) }
                }
            }
            if (a.gridMismatch) Text("선택한 그리드는 지금과 다른 좌표 원점으로 만들어져 쓰지 않아요.", style = Ty.small, color = t.bad, modifier = Modifier.testTag("grid_mismatch"))
            Btn("그리드 만들기", Modifier.fillMaxWidth().testTag("grid_new")) { h.open(ToolScreen.GRID_WIZ) }
        }
        Card {
            Text("유구 외곽 (선·면)", style = Ty.label, color = t.ink)
            Text("‘유구 경계점’을 순서대로 측점하면 꼭짓점으로 이어져요. 끝낼 때 선 또는 면(닫힘)을 고릅니다.", style = Ty.small, color = t.inkSub)
            Btn(if (a.outlineMode) "외곽 이어서 취득 끄기" else "외곽 이어서 취득 켜기", Modifier.fillMaxWidth().testTag("outline_toggle")) { c.toggleOutlineMode() }
            for (o in a.outlineViews()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.outline.label + " · " + (if (!o.outline.finished) "취득 중" else a.summaryText(o.outline.label, o.summary).substringAfter(" ")), style = Ty.small, color = t.ink, modifier = Modifier.weight(1f).testTag("outline_${o.outline.id}"))
                    Btn("지우기", Modifier.width(80.dp).testTag("outline_del_${o.outline.id}")) { a.deleteOutline(o.outline.id) }
                }
            }
        }
    }
    confirmDelete?.let { ar ->
        ConfirmDialog("구역 ‘${ar.name}’을 지울까요?", "이 구역의 그리드도 함께 지워져요. 측점한 점은 지워지지 않고 구역 소속만 비워져요.", "지우기", { a.deleteArea(ar.id); confirmDelete = null }, { confirmDelete = null })
    }
}

/** 그리드 만들기: 점 2개(원점 + 방향점) 또는 원점 좌표 + 방위각 */
@Composable
fun GridWizardScreen(c: SurveyController) {
    val t = T; val a = c.arch; val h = c.hub
    var name by remember { mutableStateOf("G${a.gridsOf(a.activeAreaId ?: 0).size + 1}") }
    var byPoints by remember { mutableStateOf(true) }
    var spacing by remember { mutableStateOf("1.0") }
    var cols by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf("10") }
    var pO by remember { mutableStateOf<SavedPoint?>(null) }
    var pD by remember { mutableStateOf<SavedPoint?>(null) }
    var n by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var az by remember { mutableStateOf("") }
    var colsAz by remember { mutableStateOf("10") }
    var problem by remember { mutableStateOf<String?>(null) }
    var warn by remember { mutableStateOf<String?>(null) }
    val areaId = a.activeAreaId
    ToolScaffold("그리드 만들기", "grid_wizard", onBack = { h.back() }, bottom = {
        PrimaryBar("그리드 저장", "grid_save", enabled = areaId != null) {
            problem = null; warn = null
            val sp = spacing.toDoubleOrNull(); val rw = rows.toIntOrNull()
            if (sp == null || rw == null) { problem = "간격과 줄 수를 숫자로 적으세요"; return@PrimaryBar }
            if (name.isBlank()) { problem = "그리드 이름을 적으세요"; return@PrimaryBar }
            if (byPoints) {
                val o = pO; val d = pD
                if (o == null || d == null) { problem = "원점 점과 방향 점을 고르세요"; return@PrimaryBar }
                val s = GridMath.fromTwoPoints(name.trim(), areaId!!, c.pointNE(o), c.pointNE(d), sp, cols.toIntOrNull(), rw)
                warn = s.warning
                val g = s.grid ?: run { problem = s.warning; return@PrimaryBar }
                a.addGrid(g) { h.back() }
            } else {
                val nn = n.toDoubleOrNull(); val ee = e.toDoubleOrNull(); val aa = az.toDoubleOrNull(); val cc = colsAz.toIntOrNull()
                if (nn == null || ee == null || aa == null || cc == null) { problem = "N, E, 방위각, 칸 수를 숫자로 적으세요"; return@PrimaryBar }
                if (sp <= 0 || rw < 1 || cc < 1) { problem = "간격·칸 수는 0보다 커야 해요"; return@PrimaryBar }
                a.addGrid(GridMath.fromOriginAzimuth(name.trim(), areaId!!, NE(nn, ee), aa, sp, cc, rw)) { h.back() }
            }
        }
    }) {
        if (areaId == null) Text("먼저 발굴 구역을 고르세요.", style = Ty.body, color = t.bad, modifier = Modifier.testTag("grid_need_area"))
        Card {
            Text("그리드는 가로(열) 방향이 원점 → 방향 점이에요. 칸 이름은 A-1, B-1 … 처럼 열 글자 + 줄 번호예요.", style = Ty.small, color = t.inkSub)
            Text("그리드 이름 · $name", style = Ty.label, color = t.ink, modifier = Modifier.testTag("grid_name_label"))
            ChipChoices(OneFingerNames.gridChoices(a.gridsOf(a.activeAreaId ?: 0).map { it.name }.toSet()), name, "grid_pick", { it }) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("점 2개로", byPoints, Modifier.weight(1f).testTag("grid_mode_points")) { byPoints = true }
                Choice("좌표 + 방위각", !byPoints, Modifier.weight(1f).testTag("grid_mode_az")) { byPoints = false }
            }
            // 0.1.39 간격 = 칩, 줄 수 = 큰 숫자 고르기(키보드 없이)
            Text("칸 간격", style = Ty.label, color = t.ink)
            ChipChoices(OneFingerNames.SPACINGS, spacing.toDoubleOrNull(), "grid_sp", { OneFingerNames.m(it) }) { spacing = it.toString() }
            Text("줄 수 (세로 칸)", style = Ty.label, color = t.ink)
            BigNumberPicker(rows.toIntOrNull() ?: 10, 1..200, "grid_rows_pick", "줄") { rows = it.toString() }
            var spTyping by remember { mutableStateOf(false) }
            TypeFallbackRow(spTyping, "grid_manual") { spTyping = !spTyping }
            if (spTyping) Field("칸 간격 (m)", spacing, "grid_spacing", KeyboardType.Decimal) { spacing = it }
        }
        if (byPoints) {
            Card {
                Text("원점 점: ${pO?.name ?: "고르세요"}", style = Ty.label, color = t.ink, modifier = Modifier.testTag("grid_o_label"))
                PointChooser(c.points.toList().asReversed(), "grid_o") { pO = it }
            }
            Card {
                Text("방향 점: ${pD?.name ?: "고르세요"}", style = Ty.label, color = t.ink, modifier = Modifier.testTag("grid_d_label"))
                PointChooser(c.points.toList().asReversed(), "grid_d") { pD = it }
                Text("열 수 · " + (cols.ifBlank { "자동(두 점 거리 ÷ 간격)" }), style = Ty.label, color = t.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Choice("자동", cols.isBlank(), Modifier.testTag("grid_cols_auto")) { cols = "" } }
                BigNumberPicker(cols.toIntOrNull() ?: 10, 1..200, "grid_cols_pick", "열") { cols = it.toString() }
            }
        } else {
            Card {
                Field("원점 N", n, "grid_n", KeyboardType.Decimal) { n = it }
                Field("원점 E", e, "grid_e", KeyboardType.Decimal) { e = it }
                Field("방위각 (° 북 기준, 시계방향)", az, "grid_az", KeyboardType.Decimal) { az = it }
                BigNumberPicker(colsAz.toIntOrNull() ?: 10, 1..200, "grid_cols_az_pick", "열") { colsAz = it.toString() }
            }
        }
        problem?.let { Text(it, style = Ty.body, color = t.bad, modifier = Modifier.testTag("grid_problem")) }
        warn?.let { Text(it, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("grid_warn")) }
        a.error?.let { Text(it, style = Ty.body, color = t.bad) }
    }
}

/** 위치 종류 편집: 추가 · 이름 바꾸기 · 지우기 · 순서. 종류는 이름 하나뿐(수량·재질 같은 칸은 없음) */
@Composable
fun TypesScreen(c: SurveyController) {
    val t = T; val h = c.hub
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    ToolScaffold("위치 종류 편집", "types_screen", onBack = { h.back() }) {
        Text("종류 이름은 ${ArchTypes.MAX_LEN}자까지예요. 이미 측점한 점의 종류는 바뀌지 않아요.", style = Ty.small, color = t.inkSub)
        msg?.let { Text(it, style = Ty.body, color = t.bad, modifier = Modifier.testTag("types_msg")) }
        Card {
            for ((i, name) in c.types.toList().withIndex()) {
                if (editing == name) {
                    Field("이름 바꾸기", editText, "type_edit_field") { editText = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Btn("저장", Modifier.fillMaxWidth().testTag("type_edit_ok")) { msg = c.renameType(name, editText); if (msg == null) editing = null } }
                        Box(Modifier.weight(1f)) { Btn("취소", Modifier.fillMaxWidth().testTag("type_edit_cancel")) { editing = null; msg = null } }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = Ty.label, color = t.ink, modifier = Modifier.weight(1f).testTag("type_row_$i"))
                        Btn("▲", Modifier.width(48.dp).testTag("type_up_$i")) { c.moveType(name, -1) }
                        Btn("▼", Modifier.width(48.dp).testTag("type_down_$i")) { c.moveType(name, 1) }
                        Btn("이름", Modifier.width(56.dp).testTag("type_rename_$i")) { editing = name; editText = name; msg = null }
                        Btn("지우기", Modifier.width(68.dp).testTag("type_del_$i")) { msg = c.removeType(name) }
                    }
                }
            }
        }
        Card {
            // 0.1.46: 자주 쓰는 발굴 위치 종류를 눌러 추가, 그 밖은 「직접 입력」
            Text("눌러서 추가", style = Ty.small, color = t.inkSub)
            ChoiceGrid(ArchTypes.seed.plus(listOf("토층", "기타", "시료 채취", "사진 기준")).filter { it !in c.types }.distinct().take(9).mapIndexed { i, nm ->
                GridItem(nm, "type_pick_$i") { msg = c.addType(nm) } })
            var typing by remember { mutableStateOf(false) }
            TypeFallbackRow(typing, "type_manual") { typing = !typing }
            if (typing) {
                Field("새 종류 이름", newName, "type_new_field") { newName = it }
                Btn("종류 추가", Modifier.fillMaxWidth().testTag("type_add")) { msg = c.addType(newName); if (msg == null) newName = "" }
            }
        }
    }
}

/**
 * 0.1.46 같은 자리를 다시 찍었을 때(시안 s03): 딤·시트 없이 측정 패널 위 결정 바.
 * 지도는 그대로 보이고 [덮어쓰기(재측량·이력 보관)] [새 점] [취소] 56dp 세 칸.
 */
@Composable
fun DupDecisionBar(c: SurveyController) {
    val d = c.dupPrompt ?: return
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(D.card).border(2.dp, FieldIvory.float, shape).padding(12.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("dup_sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.INFO, FieldIvory.float, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("같은 자리(${"%.2f".format(java.util.Locale.US, d.distM)} m)에 ${d.existing.name} 가 있어요", style = DTy.bodyB, color = D.ink, maxLines = 2, modifier = Modifier.testTag("dup_text"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DupBtn("덮어쓰기", "${d.existing.name} 재측량으로 덮어쓰기, 이력 보관", "dup_resurvey", Modifier.weight(1f)) { c.resolveDup(DupChoice.RESURVEY) }
            DupBtn("새 점", "새 점으로 따로 저장", "dup_new", Modifier.weight(1f)) { c.resolveDup(DupChoice.NEW_POINT) }
            DupBtn("취소", "저장 안 함", "dup_discard", Modifier.weight(1f)) { c.resolveDup(DupChoice.DISCARD) }
        }
    }
}

@Composable
private fun DupBtn(label: String, desc: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = FieldIvory.minTouch).clip(shape).background(D.card).border(1.5.dp, D.line, shape)
            .hClickable(onClick = onClick).semantics { contentDescription = desc }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = DTy.button, color = D.ink, maxLines = 1) }
}

/** 지도 아래쪽에 겹치는 고고학 안내: 구역·그리드·칸 한 줄, 재측량 중, 외곽 취득 중 */
@Composable
fun ArchOverlays(c: SurveyController) {
    val t = T; val a = c.arch
    val pill = Modifier.background(t.raised.copy(alpha = 0.95f), RoundedCornerShape(10.dp)).border(1.dp, t.accent, RoundedCornerShape(10.dp))
    c.resurveyTarget?.let { r ->
        Row(pill.padding(start = 10.dp).testTag("resurvey_banner"), verticalAlignment = Alignment.CenterVertically) {
            Text("재측량 중 · ${r.name} — 측점을 누르세요", style = Ty.small.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.heightIn(min = 48.dp).hClickable { c.cancelResurvey() }.padding(horizontal = 12.dp).testTag("resurvey_cancel"), contentAlignment = Alignment.Center) { Text("취소", style = Ty.label, color = t.accentText) }
        }
    }
    if (a.outlineMode) {
        val o = a.activeOutline
        val n = o?.pointRowIds?.size ?: 0
        Column(pill.padding(horizontal = 10.dp, vertical = 4.dp).testTag("outline_bar")) {
            Text("외곽 ${o?.label ?: "(새로 시작)"} · 꼭짓점 ${n}개", style = Ty.small.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("outline_count"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (a.preferClosed) "선으로 끝내기" else "✓ 선으로 끝내기", Modifier.testTag("outline_finish_line")) { a.finishOutline(false) }
                Btn(if (a.preferClosed) "✓ 면으로 닫기" else "면으로 닫기", Modifier.testTag("outline_finish_area")) { a.finishOutline(true) }
                Btn("끄기", Modifier.testTag("outline_off")) { c.toggleOutlineMode() }
            }
        }
    }
    a.lastSummary?.let { Text(it, style = Ty.small.copy(fontSize = 16.sp), color = t.ink, modifier = pill.padding(horizontal = 10.dp, vertical = 4.dp).testTag("outline_summary")) }
    a.contextLine()?.let { Text(it, style = Ty.small.copy(fontSize = 16.sp), color = t.ink, maxLines = 2, modifier = pill.padding(horizontal = 10.dp, vertical = 4.dp).testTag("context_line")) }
}

/** 점 상세의 재측량 · 이력 카드 */
@Composable
fun PointHistoryCards(c: SurveyController, p: SavedPoint) {
    val t = T; val a = c.arch
    LaunchedEffect(p.rowId, a.revisionCounts[p.rowId]) { if (p.rowId > 0) a.loadRevisions(p.rowId) }
    Card {
        Text("발굴 구역", style = Ty.label, color = t.ink)
        val ar = a.areas.firstOrNull { it.id == p.areaId }
        Text((ar?.let { "구역 ${it.name}" } ?: "구역 없음") + (if (p.cellName.isNotBlank()) " · 칸 ${p.cellName}" else ""), style = Ty.body, color = t.ink, modifier = Modifier.testTag("detail_cell"))
        a.outlineOf(p.rowId)?.let { (o, k) -> Text("외곽 ${o.label} · ${k}번째 꼭짓점", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("detail_outline")) }
        Btn("이 자리 다시 측량 (재측량)", Modifier.fillMaxWidth().testTag("detail_resurvey")) { c.startResurvey(p) }
    }
    val revs = a.revisions
    if (revs.isNotEmpty()) Card {
        Text("재측량 이력 ${revs.size}건", style = Ty.label, color = t.ink, modifier = Modifier.testTag("revision_title"))
        for (r in revs) Text(
            "${r.revNo}회 · ${SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(r.measuredAtMs))} · N ${f3(r.northing)} E ${f3(r.easting)}" + (r.sigmaHM?.let { " · ±${"%.1f".format(it * 100)}cm" } ?: "") + (if (r.cell.isNotBlank()) " · ${r.cell}" else ""),
            style = Ty.coord, color = t.inkSub, modifier = Modifier.testTag("revision_${r.revNo}"),
        )
    }
}
