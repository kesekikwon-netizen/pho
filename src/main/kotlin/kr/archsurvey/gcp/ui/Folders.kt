package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.1.5 사용자 시안(10-12) 화면: 폴더 목록 · 폴더 안 · 표적 재기 · 재는 중 · 새 대상 · 번호 고르기 · PC로 보내기.
 * 시안 모양을 따르되 실제 측량 흐름(FIX 때만 저장 · 차수 · 잠금 · zip 넘기기)은 그대로 둔다.
 */

// ---------------- 공용 조각 ----------------
@Composable
fun GTopBar(title: String?, onBack: (() -> Unit)?, c: GcpController, onMenu: (() -> Unit)? = null, brand: Boolean = false, tag: String = "app_header") {
    Row(Modifier.fillMaxWidth().background(G.bg).heightIn(min = 64.dp).padding(horizontal = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Row(Modifier.clip(RoundedCornerShape(12.dp)).hClickable(onClickLabel = "뒤로") { onBack() }.padding(horizontal = 6.dp, vertical = 10.dp).testTag("header_back"), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.BACK, G.ink, 22.dp)
            if (title != null) Text(title, style = Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp).padding(start = 4.dp).testTag("header_title"))
        } else if (brand) Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppMark()
            Text(title ?: "사진 기준점", style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Black), color = G.ink, modifier = Modifier.padding(start = 10.dp).testTag("header_title"))
        } else if (title != null) Text(title, style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Black), color = G.ink, modifier = Modifier.padding(start = 12.dp).testTag("header_title"))
        Spacer(Modifier.weight(1f))
        GcpPill(c.source, Modifier.padding(horizontal = 4.dp)) { c.openReceiver() }
        if (onMenu != null) IconBtn(Glyph.MENU, "메뉴", "menu", onMenu)
    }
}

/** 시안의 검은 네모 + 과녁 십자(앱 아이콘과 같은 생각) */
@Composable
fun AppMark(size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(G.ink), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.62f)) {
            val r = this.size.minDimension / 2
            drawCircle(Color.White, r)
            drawRect(G.ink, androidx.compose.ui.geometry.Offset(r - r * 0.12f, 0f), androidx.compose.ui.geometry.Size(r * 0.24f, r * 2))
            drawRect(G.ink, androidx.compose.ui.geometry.Offset(0f, r - r * 0.12f), androidx.compose.ui.geometry.Size(r * 2, r * 0.24f))
            drawArc(G.ink, 0f, 90f, true, androidx.compose.ui.geometry.Offset(r * 0.1f, r * 0.1f), androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f))
            drawArc(G.ink, 180f, 90f, true, androidx.compose.ui.geometry.Offset(r * 0.1f, r * 0.1f), androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f))
        }
    }
}

@Composable
fun SectionLabel(text: String, tag: String = "") {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(text, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.sub, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag))
        Spacer(Modifier.height(6.dp)); Box(Modifier.fillMaxWidth().height(2.dp).background(G.ink))
    }
}

@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(G.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun FolderChip(text: String, tag: String) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(G.chip).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.FOLDER, G.ink, 16.dp)
        Text(text, style = Ty.coord.copy(fontSize = 16.sp), color = G.ink, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 상태 → 시안 글자·색: 표적 n/m(점) · 사진 차례(점) · 보낼 차례(점) · 끝(초록) */
fun stageWord(st: Stage, measured: Int, slots: Int): Pair<String, Color> = when (st) {
    Stage.MEASURE -> "표적 $measured/$slots" to G.dot
    Stage.PHOTO -> "사진 차례" to G.dot
    Stage.HANDOFF -> "보낼 차례" to G.dot
    Stage.DONE -> "끝" to G.ok
}

@Composable
fun StageBadge(st: Stage, measured: Int, slots: Int, tag: String) {
    val (w, col) = stageWord(st, measured, slots)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(tag)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
        Text(w, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = if (st == Stage.DONE) G.ok else G.ink, modifier = Modifier.padding(start = 6.dp))
    }
}

// =============================== 폴더 목록(유적 첫 화면) ===============================
@Composable
fun ObjectsScreen(c: GcpController) {
    val b = c.book ?: return
    val counts = c.counts()
    val rows = c.rows()
    Column(Modifier.fillMaxSize().background(G.bg).testTag("objects")) {
        GTopBar("사진 기준점", null, c, onMenu = { c.menuOpen = true }, brand = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).hClickable(onClickLabel = "유적 바꾸기") { c.openSitePicker() }.testTag("site_switch"), verticalAlignment = Alignment.CenterVertically) {
                Text(b.site.name, style = Ty.label.copy(fontSize = 24.sp, fontWeight = FontWeight.Black), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("site_name"))
                GlyphIcon(Glyph.CHEVRON_DOWN, G.ink, 20.dp)
            }
            Text("폴더 ${b.objs.size}개 · ${b.site.epsg}", style = Ty.small.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(bottom = 4.dp).testTag("folder_count"))
        }
        Box(Modifier.padding(horizontal = 18.dp).padding(top = 8.dp).fillMaxWidth().height(2.dp).background(G.ink))
        // 100개가 넘어도 헷갈리지 않게: 할 일 거르기(누르면 그것만, 다시 누르면 전체)
        if (b.objs.size > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("stage_row"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (st in Stage.entries) {
                val on = c.filter == st
                val n = counts[st] ?: 0
                Text("${if (st == Stage.MEASURE) "표적" else if (st == Stage.PHOTO) "사진" else if (st == Stage.HANDOFF) "보낼" else "끝"} $n",
                    style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = if (on) G.onDark else G.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) G.dark else G.chip).hClickable(onClickLabel = st.ko) { c.toggleFilter(st) }.padding(vertical = 10.dp).testTag("stage_${st.name.lowercase()}"))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) Text(if (c.filter != null) "「${c.filter?.ko}」 폴더가 없어요" else "아직 폴더가 없어요.\n아래 「+ 새 대상」을 누르면 종류·번호를 골라 폴더를 만들어요.",
                style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(24.dp).testTag("obj_empty"))
            for (r in rows) {
                val sel = r.obj.id == c.objId
                Row(Modifier.fillMaxWidth().background(if (sel) G.sel else G.bg).hClickable(onClickLabel = r.obj.name) { c.openObj(r.obj.id) }.padding(horizontal = 18.dp, vertical = 14.dp).testTag("obj_${r.obj.code}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.FOLDER, G.ink, 24.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(r.obj.name + (r.session?.let { " · ${it.no}차" } ?: ""), style = Ty.label.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.session?.let { Names.photoFolder(r.obj, it) } ?: r.obj.code, style = Ty.coord.copy(fontSize = 16.sp), color = G.sub, maxLines = 1)
                    }
                    StageBadge(r.stage, r.measured, r.slots, "objstage_${r.obj.code}")
                }
                Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(G.line))
            }
        }
        BottomBar {
            val n = c.handoffCount(HandoffScope.PENDING)
            Btn("PC로 보내기", "to_handoff", Modifier.weight(0.42f), primary = false, height = 68.dp, sub = "보낼 폴더 $n") { c.handoffOpen = true }
            Btn("+ 새 대상", "obj_new", Modifier.weight(0.58f), height = 68.dp) { c.openNewObj() }
        }
    }
}

// =============================== 폴더 안(대상 · 지금 차수) ===============================
@Composable
fun ObjScreen(c: GcpController) {
    val b = c.book ?: return; val o = c.obj ?: return; val s = c.session ?: return
    val st = b.stage(s); val n = b.active(s).size
    val locked = c.locked()
    Column(Modifier.fillMaxSize().background(G.bg).testTag("obj_screen")) {
        GTopBar(b.site.name, { c.back() }, c)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text(o.name, style = Ty.label.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black), color = G.ink, modifier = Modifier.padding(top = 8.dp).testTag("obj_title"))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).hClickable(onClickLabel = "차수 바꾸기") { c.sessionSheetOpen = true }.padding(vertical = 4.dp).testTag("tool_session"), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.no}차 · ${s.label}", style = Ty.label.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = G.ink)
                GlyphIcon(Glyph.CHEVRON_DOWN, G.sub, 18.dp)
                if (locked) Text("  앞 차수 · 보기만", style = Ty.label.copy(fontSize = 16.sp), color = G.warn)
            }
            Spacer(Modifier.height(10.dp))
            FolderChip(Names.photoFolder(o, s), "obj_folder")
            Spacer(Modifier.height(18.dp))
            SectionLabel("이 폴더에 들어갈 것")
            val xmlOk = n > 0 && s.xmlSavedAt != null
            val photoDone = s.photoDoneAt != null
            val sent = st == Stage.DONE
            StepRow(1, "표적 좌표", if (n >= Pipeline.MIN_TARGETS) "${n}개 재었음 · 더 재도 돼요" else "${n}개 재었음 · ${Pipeline.MIN_TARGETS}개부터 사진",
                done = n >= Pipeline.MIN_TARGETS, now = st == Stage.MEASURE, right = if (xmlOk) "XML" else null, rightSub = if (xmlOk) (if (n >= Pipeline.MIN_TARGETS) "준비됨" else "저장됨") else null, tag = "step_targets") { c.openTargets() }
            StepRow(2, "ID 카드", "사진 세트의 첫 장으로 찍어요", done = photoDone, now = st == Stage.PHOTO, tag = "step_idcard") { c.openIdCard() }
            StepRow(3, "사진 찍기", if (photoDone) "다 찍었어요" else "카메라로 한 세트 · 끝나면 ID 카드에서 확인", done = photoDone, now = false, tag = "step_photo", onClick = null)
            StepRow(4, "PC로 보내기", if (sent) "보냈어요" else "zip 하나에 넣어 보내요", done = sent, now = st == Stage.HANDOFF, tag = "step_send") { c.handoffOpen = true }
            Spacer(Modifier.height(8.dp))
            Text("XML: ${Names.objFolder(o)}/${Names.xmlName(o, s, b.site.epsg)}", style = Ty.coord.copy(fontSize = 16.sp), color = G.faint, modifier = Modifier.testTag("obj_xml"))
            Spacer(Modifier.height(12.dp))
        }
        BottomBar {
            when {
                locked -> { Btn("차수 고르기", "obj_session", Modifier.weight(1f), primary = false, height = 68.dp) { c.sessionSheetOpen = true }; Btn("ID 카드", "obj_idcard", Modifier.weight(1f), height = 68.dp) { c.openIdCard() } }
                st == Stage.MEASURE -> { Btn("지도", "obj_map", Modifier.weight(0.4f), primary = false, height = 68.dp) { c.go(Screen.MAP) }; Btn("표적 재기", "obj_measure", Modifier.weight(0.6f), height = 68.dp, sub = "${n}/${s.slots.size} · FIX 때만 저장") { c.openTargets() } }
                st == Stage.PHOTO -> { Btn("표적 더 재기", "obj_measure", Modifier.weight(0.42f), primary = false, height = 68.dp) { c.openTargets() }; Btn("ID 카드 띄우기", "obj_idcard", Modifier.weight(0.58f), height = 68.dp) { c.openIdCard() } }
                st == Stage.HANDOFF -> { Btn("ID 카드", "obj_idcard", Modifier.weight(0.42f), primary = false, height = 68.dp) { c.openIdCard() }; Btn("PC로 보내기", "obj_send", Modifier.weight(0.58f), height = 68.dp) { c.handoffOpen = true } }
                else -> { Btn("다음 차수", "obj_newsession", Modifier.weight(0.5f), primary = false, height = 68.dp) { c.openNewSession() }; Btn("표적 보기", "obj_measure", Modifier.weight(0.5f), height = 68.dp) { c.openTargets() } }
            }
        }
    }
}

@Composable
private fun StepRow(no: Int, title: String, sub: String, done: Boolean, now: Boolean, right: String? = null, rightSub: String? = null, tag: String, onClick: (() -> Unit)?) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).then(if (onClick != null) Modifier.hClickable(onClickLabel = title) { onClick() } else Modifier).padding(vertical = 12.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(if (done) G.ink else G.bg).border(if (done) 0.dp else 1.5.dp, if (now) G.ink else G.lineStrong, CircleShape), contentAlignment = Alignment.Center) {
                when { done -> GlyphIcon(Glyph.CHECK, G.white, 20.dp); now -> Box(Modifier.size(14.dp).clip(CircleShape).background(G.dot)); else -> Text("$no", style = Ty.label.copy(fontSize = 16.sp), color = G.sub) }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = Ty.label.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), color = if (done || now) G.ink else G.ink.copy(alpha = 0.8f))
                Text(sub, style = Ty.small.copy(fontSize = 16.sp), color = G.sub)
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    right != null -> { Text(right, style = Ty.coord.copy(fontSize = 16.sp), color = G.ink); rightSub?.let { Text(it, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.ok) } }
                    now -> Text("다음", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.ink)
                    done -> {}
                    else -> Text("—", style = Ty.label.copy(fontSize = 16.sp), color = G.sub)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
    }
}

// =============================== 표적 재기 (g03 · g04) ===============================
/**
 * 0.1.8 Field Ivory g03: 접힘 = 위 지도 / 아래 판(지금 재는 것 + 폴, 표적 1–4 세 상태, 대상 + 96dp 측량).
 * 펼침(폭 600dp 이상) = 왼쪽 지도 / 오른쪽 340dp 판(기능 같음). 재는 중에도 지도는 그대로(점마다 창 없음).
 */
@Composable
fun TargetsScreen(c: GcpController, env: GcpEnv = GcpEnv()) {
    val o = c.obj ?: return; val s = c.session ?: return
    c.poleTick
    val g by c.source.gnss.collectAsState()
    LaunchedEffect(g.gga) { c.onFix() }
    LaunchedEffect(o.id, s.id) { c.focusObj() }
    Column(Modifier.fillMaxSize().background(G.bg).testTag("targets")) {
        GTopBar("${o.name} · ${s.no}차", { c.back() }, c)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= FieldIvory.unfoldedMinWidth
            val map: @Composable (Modifier) -> Unit = { m ->
                Box(m) {
                    MapArea(c, env, Modifier.fillMaxSize())
                    if (c.measuringTag != null) MeasuringChip(c, Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 76.dp, end = 76.dp))
                }
            }
            if (wide) Row(Modifier.fillMaxSize().testTag("targets_unfolded")) {
                map(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(G.line))
                Column(Modifier.width(FieldIvory.sidePanel).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    TargetsPanel(c, o, s, wide = true)
                }
            } else Column(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
                TargetsPanel(c, o, s, wide = false)
            }
        }
    }
}

@Composable
private fun TargetsPanel(c: GcpController, o: kr.archsurvey.gcp.model.Obj, s: kr.archsurvey.gcp.model.Session, wide: Boolean) {
    val shots = c.activeShots()
    val locked = c.locked()
    val next = if (locked || c.measuringTag != null) null else c.nextTag()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 지금 재는 것 + 폴 카드
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("지금 재는 것", style = Ty.small, color = G.sub, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 1)
                    Text(" · ${s.no}차 ${s.label}", style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.dot, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("표적 ${shots.size}/${s.slots.size}" + (if (shots.size < Pipeline.MIN_TARGETS) " · ${Pipeline.MIN_TARGETS}개부터 사진" else " · 사진 가능") + (if (locked) " · 앞 차수 보기만" else ""),
                    style = Ty.small, color = if (locked) G.warn else G.sub, maxLines = 2, modifier = Modifier.testTag("targets_note"))
            }
            Column(Modifier.widthIn(min = 96.dp).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(16.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(16.dp))
                .hClickable(onClickLabel = "폴 높이") { c.poleOpen = true }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("pole_chip"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("폴", style = Ty.small, color = G.sub)
                Text("${String.format(Locale.US, "%.2f", c.poleM())} m", style = Ty.coord.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = G.ink, maxLines = 1)
            }
        }
        // 표적 칸(세 상태): 완료 = 연테라코타 + 「완료」, 지금/다음 = 테라코타 채움, 대기 = 흰색
        val cells: @Composable (Modifier) -> Unit = { m ->
            Row(m.testTag("qtag_row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val scroll = s.slots.size > 4
                s.slots.forEachIndexed { i, tag ->
                    val shot = c.shotOf(tag)
                    val now = c.measuringTag == tag
                    val isNext = tag == next && shot == null
                    val hot = now || isNext
                    val cellMod = if (scroll) Modifier.width(72.dp) else Modifier.weight(1f)
                    Column(cellMod.heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp))
                        .background(when { hot -> G.dark; shot != null -> G.sel; else -> G.white })
                        .border(if (hot) 0.dp else 1.dp, if (shot != null) G.dark.copy(alpha = 0.35f) else G.line, RoundedCornerShape(16.dp))
                        .hCombinedClickable(onLongClick = { c.onTargetLong(i) }) { c.onTargetTap(tag) }
                        .semantics { contentDescription = "Target $tag" + if (shot != null) " · 잰 것, 다시 재려면 길게" else " · 누르면 바로 측점, 길게 = 번호 바꾸기" }
                        .padding(vertical = 6.dp).testTag("qtag_$tag"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("$tag", style = Ty.ledger.copy(fontSize = if (tag >= 100) 20.sp else 26.sp, fontWeight = FontWeight.Medium), color = when { hot -> G.onDark; shot != null -> G.dot; else -> G.ink }, maxLines = 1)
                        when {
                            now -> Text("재는 중", style = Ty.small, color = G.onDark, maxLines = 1)
                            shot != null -> Text("완료", style = Ty.small.copy(fontWeight = FontWeight.Medium), color = G.dot, maxLines = 1, modifier = Modifier.testTag("qtag_done_$tag"))
                            isNext -> Text("지금", style = Ty.small.copy(fontWeight = FontWeight.Medium), color = G.onDark, maxLines = 1)
                            else -> Text("대기", style = Ty.small, color = G.sub, maxLines = 1)
                        }
                    }
                }
                if (!locked) Box(Modifier.width(FieldIvory.minTouch).heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(16.dp))
                    .hClickable(onClickLabel = "표적 더하기") { c.openAddSlot() }.testTag("add_slot"), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.PLUS, G.ink, 24.dp) }
            }
        }
        if (s.slots.size > 4) cells(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) else cells(Modifier.fillMaxWidth())
        // 평균 횟수(선택만) · 대상 · 96dp 측량
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(88.dp).height(FieldIvory.measureButton).clip(RoundedCornerShape(18.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(18.dp))
                .hClickable(onClickLabel = "대상") { c.back() }.testTag("targets_obj"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GlyphIcon(Glyph.FOLDER, G.ink, 26.dp)
                Spacer(Modifier.height(4.dp))
                Text("대상", style = Ty.small.copy(fontWeight = FontWeight.Medium), color = G.ink, maxLines = 1)
            }
            Box(Modifier.weight(1f)) { MeasureBig(c) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("평균", style = Ty.small, color = G.sub, modifier = Modifier.weight(1f))
            Text("FIX ${c.prefs.epochs}회", style = Ty.coord, color = G.ink,
                modifier = Modifier.heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(12.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(12.dp))
                    .hClickable(onClickLabel = "평균 횟수") { val l = GcpController.EPOCH_CHOICES; c.setEpochs(l[(l.indexOf(c.prefs.epochs) + 1).mod(l.size)]) }
                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp).testTag("epochs_chip"))
        }
    }
}

/** g04 지도 위 작은 진행 띠(화면을 가리지 않음): 표적 N · 7/10 · 막대 */
@Composable
fun MeasuringChip(c: GcpController, modifier: Modifier = Modifier) {
    val total = c.prefs.epochs
    val done = (c.progress.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total)
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(G.ink.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("measuring"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("표적 #${c.measuringTag} · $done / $total · 폴 고정", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.white, maxLines = 1, modifier = Modifier.testTag("measuring_count"))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(180.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(G.white.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(c.progress.coerceIn(0f, 1f)).fillMaxHeight().background(FieldIvory.decor))
        }
    }
}

/** 평균 남은 초(1 Hz 기준) */
fun GcpController.remainingSec(): Int { val total = prefs.epochs; return (total - (progress.coerceIn(0f, 1f) * total).toInt()).coerceIn(0, total) }

/** 96dp 테라코타 「표적 #N 측량」: FIX 아니면 막힘(누르면 이유), 연결 없으면 「수신기 연결」. 재는 중 = 큰 남은 초(g04) */
@Composable
fun MeasureBig(c: GcpController) {
    val p = rememberPill(c.source)
    val busy = c.measuringTag != null
    val locked = c.locked()
    val next = if (locked) null else c.nextTag()
    val notConn = p.tone == PTone.NO_RECEIVER
    val blocked = p.tone != PTone.FIXED
    val label: String; val sub: String
    when {
        busy -> { label = "남은 ${c.remainingSec()}초"; sub = "표적 #${c.measuringTag} 평균 중 · 누르면 멈춤" }
        locked -> { label = "앞 차수 · 보기만"; sub = "새로 재려면 차수에서 마지막 차수" }
        notConn -> { label = "수신기 연결"; sub = "눌러서 연결" }
        blocked -> { label = "FIX 기다리는 중"; sub = "FIX 때만 저장해요" }
        else -> { label = "표적 #$next 측량"; sub = "FIX ${c.prefs.epochs}회 평균" + if (c.session?.slots?.contains(next) == false) " · 새 칸" else "" }
    }
    val dim = !busy && (locked || (blocked && !notConn))
    Box(Modifier.fillMaxWidth().height(FieldIvory.measureButton).clip(RoundedCornerShape(20.dp)).background(if (dim) FieldIvory.disabled else G.dark)
        .hClickable(onClickLabel = label) { if (!locked || busy) c.onMeasureButton() }.testTag("measure_button"), contentAlignment = Alignment.Center) {
        if (busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(c.progress.coerceIn(0f, 1f)).height(6.dp).background(G.white.copy(alpha = 0.85f)).testTag("measure_progress"))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Ty.label.copy(fontSize = if (busy) 30.sp else 24.sp, fontWeight = FontWeight.Bold), color = if (dim) FieldIvory.onDisabled else G.onDark, maxLines = 1, modifier = Modifier.testTag("measure_label"))
            Text(sub, style = Ty.small, color = if (dim) G.sub else G.onDark, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).testTag("measure_sub"))
        }
    }
}

// =============================== 새 대상(한 화면) ===============================
@Composable
fun NewObjScreen(c: GcpController, f: NewObjForm) {
    val epsg = c.book?.site?.epsg
    val taken = f.no in f.used
    Column(Modifier.fillMaxSize().background(G.bg).hClickable {}.testTag("newobj")) {
        GTopBar("새 대상", { c.newObj = null }, c)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            // 미리 보기(시안 맨 위 큰 이름 + 폴더)
            Text(ObjNames.label(f.kind, f.no), style = Ty.label.copy(fontSize = 30.sp, fontWeight = FontWeight.Black), color = if (taken) G.bad else G.ink, modifier = Modifier.testTag("newobj_name"))
            Text("1차 · ${f.label}", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.ink)
            Spacer(Modifier.height(8.dp))
            FolderChip("${ObjNames.folder(f.kind, f.no)}_1차", "newobj_folder")
            if (taken) Text("이미 있는 번호예요 · 다음 번호 ${ObjNames.next(f.used)}", style = Ty.label.copy(fontSize = 16.sp), color = G.bad, modifier = Modifier.padding(top = 6.dp).testTag("objno_taken"))
            SectionLabel("종류")
            Spacer(Modifier.height(8.dp))
            TileGrid(ObjKind.entries, 3, 8.dp) { k -> Tile(k.ko, k == f.kind, "kind_${k.id}", Modifier.weight(1f), height = 52.dp) { c.pickKind(k) } }
            SectionLabel("번호")
            Spacer(Modifier.height(8.dp))
            if (f.kind.lettered) {
                TileGrid((1..26).toList(), 6, 6.dp) { n -> Tile(ObjNames.letter(n), n == f.no, "letter_$n", Modifier.weight(1f), enabled = n !in f.used, height = 48.dp, big = true) { c.pickObjNo(n) } }
            } else {
                val nx = ObjNames.next(f.used)
                val quick = (listOf(nx) + (nx + 1..nx + 2)).filter { it <= ObjNames.MAX_NO }.distinct()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, n) in quick.withIndex()) Tile("${n}호", f.no == n, "objno_q_$n", Modifier.weight(1f), sub = if (i == 0) "다음 번호" else null, height = 60.dp) { c.pickObjNo(n) }
                    val other = f.no !in quick
                    Tile(if (other) "${f.no}호" else "다른…", other, "objno_display", Modifier.weight(1.1f), sub = "1–999호", height = 60.dp) { c.openObjNoPad() }
                }
            }
            SectionLabel("차수")
            Spacer(Modifier.height(8.dp))
            Text("새 대상은 1차로 시작해요. 2차부터는 폴더 안 「차수」에서 더해요(앞 차수 파일은 그대로).", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.testTag("newobj_session_note"))
            SectionLabel("면")
            Spacer(Modifier.height(8.dp))
            TileGrid(SessionLabels.ALL, 4, 6.dp) { l -> Tile(l, l == f.label, "label_$l", Modifier.weight(1f), height = 52.dp) { c.pickLabel(l) } }
            Spacer(Modifier.height(10.dp))
            Text("표적 칸 #" + QrPayload.ranges(f.slots) + " · 번호는 표적 재기에서 길게 눌러 바꿔요", style = Ty.small.copy(fontSize = 16.sp), color = G.sub)
            f.nearNote?.let { Text(it, style = Ty.label.copy(fontSize = 16.sp), color = G.warn, modifier = Modifier.padding(top = 4.dp).testTag("near_note")) }
            epsg?.let { Text("XML: ${ObjNames.folder(f.kind, f.no)}_1차_EPSG$it.xml", style = Ty.coord.copy(fontSize = 16.sp), color = G.faint, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
        }
        BottomBar { Btn("${ObjNames.label(f.kind, f.no)} 만들기", "newobj_ok", Modifier.weight(1f), enabled = !taken, height = 68.dp) { c.submitNewObj() } }
    }
}

// =============================== 번호 고르기(백 · 십 · 일) ===============================
@Composable
fun NumPickScreen(c: GcpController, p: NumPick) {
    val v = p.value.coerceIn(0, 999)
    val digits = intArrayOf(v / 100, v / 10 % 10, v % 10)
    val unit = when (p.forWhat) { PickFor.ObjNo -> "호"; PickFor.District -> "지구"; else -> "" }
    val prefix = if (p.forWhat is PickFor.AddSlot || p.forWhat is PickFor.ChangeSlot) "#" else ""
    Column(Modifier.fillMaxSize().background(G.bg).hClickable {}.testTag("numpad_sheet")) {
        GTopBar(p.title, { c.numPick = null }, c)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (v == 0) "—" else "$prefix$v", style = Ty.ledger.copy(fontSize = 64.sp), color = if (p.ok) G.ink else G.bad, modifier = Modifier.testTag("numpad_value"))
                if (unit.isNotEmpty()) Text(" $unit", style = Ty.label.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = G.ink, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(when { p.taken -> "이미 있어요"; v in p.near -> "근처 대상과 겹쳐요 — 다른 번호를 권해요"; else -> "자리마다 숫자 하나씩 누르세요. 백·십에 0을 고르면 두 자리·한 자리 번호가 돼요." },
                style = Ty.label.copy(fontSize = 16.sp), color = if (p.taken) G.bad else if (v in p.near) G.warn else G.sub, textAlign = TextAlign.Center, modifier = Modifier.testTag("numpad_warn"))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("백", "십", "일").forEachIndexed { col, name ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(name, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.sub)
                        for (d in 0..9) {
                            val on = digits[col] == d
                            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(if (on) G.dark else G.white).border(1.dp, if (on) G.dark else G.line, RoundedCornerShape(10.dp))
                                .hClickable(onClickLabel = "$name $d") { val nd = digits.copyOf(); nd[col] = d; c.numSet(nd[0] * 100 + nd[1] * 10 + nd[2]) }.testTag("col${col}_$d"), contentAlignment = Alignment.Center) {
                                Text("$d", style = Ty.ledger.copy(fontSize = 20.sp), color = if (on) G.onDark else G.ink)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val free = generateSequence(1) { it + 1 }.filter { it !in p.used && it !in p.near }.take(3).toList()
            val start = (p.used.maxOrNull() ?: 0) + 1
            val quick = (listOf(TargetChecks.nextFree(p.used + p.near, start)) + free).distinct().filter { it <= p.max }.take(4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, n) in quick.withIndex()) Tile("$prefix$n", v == n, "numpad_q_$n", Modifier.weight(1f), sub = if (i == 0) "다음" else "빈 번호", height = 54.dp) { c.numSet(n) }
            }
            Spacer(Modifier.height(12.dp))
        }
        BottomBar {
            val okLabel = when { !p.ok -> if (p.taken) "이미 있어요" else "번호를 고르세요"; p.forWhat is PickFor.ObjNo -> "${v}호로 정하기"; p.forWhat is PickFor.District -> "${v}지구로 정하기"; else -> "#$v 로 정하기" }
            Btn(okLabel, "numpad_ok", Modifier.weight(1f), enabled = p.ok, height = 68.dp) { c.submitNum() }
        }
    }
}

// =============================== PC로 보내기 ===============================
@Composable
fun HandoffScreen(c: GcpController) {
    val b = c.book ?: return
    val now = System.currentTimeMillis()
    val sel = Handoff.select(b, c.handoffScope, now, c.zone)
    Column(Modifier.fillMaxSize().background(G.bg).hClickable {}.testTag("handoff_sheet")) {
        GTopBar(b.site.name, { c.handoffOpen = false }, c)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text("PC로 보내기", style = Ty.label.copy(fontSize = 30.sp, fontWeight = FontWeight.Black), color = G.ink)
            Text("iTwin Capture용 표적 파일을 zip 하나로 묶어요.", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sc in HandoffScope.entries) {
                    val n = c.handoffCount(sc); val on = c.handoffScope == sc
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) G.dark else G.white).border(1.dp, if (on) G.dark else G.line, RoundedCornerShape(14.dp))
                        .hClickable(onClickLabel = sc.ko) { c.handoffScope = sc }.padding(vertical = 10.dp).testTag("handoff_${sc.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (sc == HandoffScope.PENDING) "안 보낸 것" else sc.ko, style = Ty.label.copy(fontSize = 16.sp), color = if (on) G.onDark else G.sub)
                        Text("$n", style = Ty.ledger.copy(fontSize = 26.sp), color = if (on) G.onDark else G.ink)
                    }
                }
            }
            SectionLabel("zip 안")
            Text(Handoff.zipName(b.site, c.handoffScope, now, c.zone), style = Ty.coord.copy(fontSize = 16.sp), color = G.ink, modifier = Modifier.padding(top = 10.dp).testTag("handoff_zip"))
            ZipLine("${Names.listCsvName(b.site)}", "폴더 · 차수 · 사진 폴더 목록", 0)
            if (sel.isEmpty()) Text("이 범위에 보낼 폴더가 없어요", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(top = 8.dp).testTag("handoff_empty"))
            for ((o, s) in sel.take(30)) {
                val noPhoto = s.photoDoneAt == null
                ZipLine(Names.objFolder(o) + "/", "${o.name} · ${s.no}차" + if (noPhoto) " · ⚠ 사진 아직" else "", 0, if (noPhoto) G.warn else G.ink)
                ZipLine(Names.xmlName(o, s, b.site.epsg), "iTwin Capture", 1)
                ZipLine(Names.txtName(o, s, b.site.epsg), "같은 좌표 · 글", 1)
            }
            if (sel.size > 30) Text("… 그 밖에 ${sel.size - 30}개", style = Ty.label.copy(fontSize = 16.sp), color = G.sub)
            Spacer(Modifier.height(10.dp))
            Text("사진 파일은 zip에 넣지 않아요. 사진 폴더 이름(${sel.firstOrNull()?.let { Names.photoFolder(it.first, it.second) } ?: "012호_주거지_1차"})으로 PC에서 맞춰요.", style = Ty.label.copy(fontSize = 16.sp), color = G.sub)
            val last = b.sessions.mapNotNull { it.deliveredAt }.maxOrNull()
            if (last != null) Text("마지막으로 보낸 때 · ${c.timeLabel(last)}", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(top = 4.dp).testTag("handoff_last"))
            Spacer(Modifier.height(12.dp))
        }
        BottomBar { Btn("zip 만들어 보내기", "handoff_send", Modifier.weight(1f), enabled = sel.isNotEmpty(), height = 68.dp, sub = "폴더 ${sel.size}개 · 공유 창에서 보낼 곳 고르기") { c.sendHandoff() } }
    }
}

@Composable
private fun ZipLine(name: String, note: String, depth: Int, color: Color = G.ink) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 18).dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Ty.coord.copy(fontSize = 16.sp), color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = Ty.small.copy(fontSize = 16.sp), color = G.sub, maxLines = 1)
    }
}
