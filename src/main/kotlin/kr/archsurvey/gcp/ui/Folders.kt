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
import androidx.compose.ui.draw.drawBehind
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
fun GTopBar(title: String?, onBack: (() -> Unit)?, c: GcpController, onMenu: (() -> Unit)? = null, brand: Boolean = false, tag: String = "app_header", sub: String? = null, onTitle: (() -> Unit)? = null, titleTag: String = "header_title", subTag: String = "header_sub", titleClickTag: String = "header_title_click") {
    // 0.1.8 Field Ivory: ‹ 제목(굵게) / 보조 한 줄 · 상태 알약 · ≡ · 아래 구분선
    Column(Modifier.fillMaxWidth().background(G.bg).testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(Glyph.BACK, "뒤로", "header_back", onBack) else Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(start = 4.dp).then(if (onTitle != null) Modifier.clip(RoundedCornerShape(8.dp)).hClickable(onClickLabel = "바꾸기") { onTitle() }.testTag(titleClickTag) else Modifier)) {
                Text(title ?: "사진 기준점", style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(titleTag))
                if (sub != null) Text(sub, style = Ty.small, color = G.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(subTag))
            }
            GcpPill(c.source, Modifier.padding(horizontal = 4.dp)) { c.openReceiver() }
            if (onMenu != null) IconBtn(Glyph.MENU, "메뉴", "menu", onMenu) else Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
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

// =============================== g01 폴더 목록(유적 첫 화면) ===============================
/** 목록 배지: 시작 전 · 측량 N점 남음 · 사진 필요 · 보내기 준비 · 끝 */
fun rowBadge(st: Stage, measured: Int): Pair<String, BadgeTone> = when (st) {
    Stage.MEASURE -> if (measured == 0) "시작 전" to BadgeTone.BAD else "측량 ${(Pipeline.MIN_TARGETS - measured).coerceAtLeast(1)}점 남음" to BadgeTone.WARN
    Stage.PHOTO -> "사진 필요" to BadgeTone.NEUTRAL
    Stage.HANDOFF -> "보내기 준비" to BadgeTone.DONE
    Stage.DONE -> "끝" to BadgeTone.DONE
}

@Composable
fun ObjectsScreen(c: GcpController) {
    val b = c.book ?: return
    val rows = c.rows()
    val tabs = listOf(null, Stage.MEASURE, Stage.PHOTO, Stage.HANDOFF)
    Column(Modifier.fillMaxSize().background(G.bg).testTag("objects")) {
        GTopBar("사진 기준점", null, c, onMenu = { c.menuOpen = true }, sub = b.site.name, subTag = "site_name", onTitle = { c.openSitePicker() }, titleClickTag = "site_switch")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            if (b.objs.isNotEmpty()) Segmented(listOf("전체" to "stage_all", "측량 필요" to "stage_measure", "사진 필요" to "stage_photo", "보내기" to "stage_handoff"),
                tabs.indexOf(c.filter).coerceAtLeast(0), Modifier.testTag("stage_row")) { i -> c.filter = tabs[i] }
            Text("폴더 ${b.objs.size}개 · EPSG:${b.site.epsg}", style = Ty.small, color = G.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp).testTag("folder_count"))
            if (rows.isEmpty()) Text(if (c.filter != null) "「${c.filter?.ko}」 폴더가 없어요" else "아직 폴더가 없어요.\n아래 「새 대상」을 누르면 종류·번호를 골라 폴더를 만들어요.",
                style = Ty.label, color = G.sub, modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp).testTag("obj_empty"))
            // 차수 · 면으로 묶기(목업 「1차 · 검출면」)
            for ((group, list) in rows.groupBy { r -> r.session?.let { "${it.no}차 · ${it.label}" } ?: "차수 없음" }.toSortedMap()) {
                GroupLabel(group)
                CardGroup {
                    list.forEachIndexed { i, r ->
                        if (i > 0) CardDivider()
                        val (word, tone) = rowBadge(r.stage, r.measured)
                        val photo = if (r.session?.photoDoneAt != null) " · 사진 완료" else if (r.stage != Stage.MEASURE) " · 사진 전" else ""
                        CardRow(Glyph.FOLDER, r.obj.name, "표적 ${r.measured}/${r.slots}$photo", "obj_${r.obj.code}", onClick = { c.openObj(r.obj.id) }) {
                            Badge(word, tone, "objstage_${r.obj.code}")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        BottomBar {
            val n = c.handoffCount(HandoffScope.PENDING)
            Row(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(20.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(20.dp))
                .hClickable(onClickLabel = "PC로 보내기") { c.handoffOpen = true }.semantics { contentDescription = "PC로 보내기 · 보낼 폴더 $n" }.testTag("to_handoff"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.SHARE, G.ink, 22.dp); Spacer(Modifier.width(10.dp))
                Text("PC로 보내기", style = Ty.label.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 1)
            }
            Row(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(20.dp)).background(G.dark)
                .hClickable(onClickLabel = "새 대상") { c.openNewObj() }.testTag("obj_new"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.PLUS, G.onDark, 22.dp); Spacer(Modifier.width(10.dp))
                Text("새 대상", style = Ty.label.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = G.onDark, maxLines = 1)
            }
        }
    }
}

// =============================== g05 폴더 안(4단계 체크리스트) ===============================
@Composable
fun ObjScreen(c: GcpController) {
    val b = c.book ?: return; val o = c.obj ?: return; val s = c.session ?: return
    val st = b.stage(s); val n = b.active(s).size
    val locked = c.locked()
    val shots = b.active(s)
    val photoDone = s.photoDoneAt != null
    val sent = st == Stage.DONE
    val measuredOk = n >= Pipeline.MIN_TARGETS
    val done = booleanArrayOf(measuredOk, photoDone, photoDone, sent)
    val nowStep = when (st) { Stage.MEASURE -> 0; Stage.PHOTO -> 1; Stage.HANDOFF -> 3; Stage.DONE -> -1 }
    val doneCount = done.count { it }
    Column(Modifier.fillMaxSize().background(G.bg).testTag("obj_screen")) {
        GTopBar(o.name, { c.back() }, c, sub = "${s.no}차 · ${s.label}" + if (locked) " · 앞 차수 보기만" else "", titleTag = "obj_title", onMenu = { c.menuOpen = true }, onTitle = { c.sessionSheetOpen = true }, titleClickTag = "tool_session")
        // 0.1.12 오른손 #8: 단계 카드는 화면 아래쪽에 붙인다(지금 단계·4단계까지 엄지 범위)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
            Row(Modifier.padding(top = 14.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("4단계 중 ${doneCount}단계 완료", style = Ty.small, color = G.sub, modifier = Modifier.weight(1f).testTag("obj_progress"))
                Box(Modifier.width(140.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEFEBE3))) {
                    Box(Modifier.fillMaxWidth(doneCount / 4f).fillMaxHeight().background(G.dark))
                }
            }
            val sigMax = shots.mapNotNull { it.sigmaH }.maxOrNull()?.let { String.format(Locale.US, " · 정밀도 최대 %.1f cm", it * 100) } ?: ""
            StepCard(1, "표적 좌표", if (n > 0) "#${QrPayload.ranges(shots.map { it.tag }.sorted())} ${n}개 측량$sigMax" else "${Pipeline.MIN_TARGETS}개부터 사진",
                done[0], nowStep == 0, "step_targets") { c.openTargets() }
            StepCard(2, "ID 카드", "QR · 대상 이름 · 날짜", done[1], nowStep == 1, "step_idcard") { c.openIdCard() }
            StepCard(3, "사진 찍기", if (photoDone) "다 찍었어요" else "iTwin Capture용 · ID 카드부터", done[2], false, "step_photo", null)
            StepCard(4, "PC로 보내기", if (sent) "보냈어요" else "zip · 좌표 XML·TXT · 목록 CSV", done[3], nowStep == 3, "step_send") { c.handoffOpen = true }
            FolderChip(Names.photoFolder(o, s), "obj_folder")
            Text("XML: ${Names.objFolder(o)}/${Names.xmlName(o, s, b.site.epsg)}", style = Ty.coord.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(start = 4.dp).testTag("obj_xml"))
            Spacer(Modifier.height(4.dp))
        }
        }
        Column(Modifier.fillMaxWidth().background(G.bg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
            // 0.1.12 오른손 #8: 위 ‹ 와 같은 뒤로를 아래 「지도」로도(76dp, 다음 단추 왼쪽). 이전 화면이 지도가 아니면 「목록」
            val backToMap = c.stack.getOrNull(c.stack.size - 2) == Screen.MAP
            val backLabel = if (backToMap) "지도" else "목록"
            Row(Modifier.padding(16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(76.dp).fillMaxHeight().heightIn(min = 80.dp).clip(RoundedCornerShape(20.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(20.dp))
                    .hClickable(onClickLabel = backLabel) { c.back() }.testTag("obj_to_map"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GlyphIcon(if (backToMap) Glyph.PIN else Glyph.LIST, G.ink, 24.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(backLabel, style = Ty.small.copy(fontWeight = FontWeight.Medium), color = G.ink, maxLines = 1)
                }
                Box(Modifier.weight(1f)) {
                when {
                    locked -> BigPrimary("차수 고르기", "앞 차수는 보기만 해요", "obj_session") { c.sessionSheetOpen = true }
                    st == Stage.MEASURE -> BigPrimary("다음: 표적 재기", "1 / 4 단계 · ${n}/${s.slots.size} · FIX 때만 저장", "obj_measure") { c.openTargets() }
                    st == Stage.PHOTO -> BigPrimary("다음: ID 카드 띄우기", "2 / 4 단계 · 그다음 사진 찍기", "obj_idcard") { c.openIdCard() }
                    st == Stage.HANDOFF -> BigPrimary("다음: PC로 보내기", "4 / 4 단계", "obj_send") { c.handoffOpen = true }
                    else -> BigPrimary("다음 차수 만들기", "4단계 모두 끝 · 앞 차수 파일은 그대로", "obj_newsession") { c.openNewSession() }
                }
                }
            }
        }
    }
}

@Composable
private fun StepCard(no: Int, title: String, sub: String, done: Boolean, now: Boolean, tag: String, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(shape).background(G.white).border(if (now) 2.dp else 1.dp, if (now) G.dark else G.line, shape)
        .then(if (onClick != null) Modifier.hClickable(onClickLabel = title) { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(when { done -> FieldIvory.fix; now -> G.dark; else -> Color(0xFFEFEBE3) }), contentAlignment = Alignment.Center) {
            if (done) GlyphIcon(Glyph.CHECK, G.white, 22.dp)
            else Text("$no", style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = if (now) G.onDark else G.sub)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.ink)
            Text(sub, style = Ty.small, color = G.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            now -> Badge("지금", BadgeTone.NOW, "${tag}_now")
            onClick != null -> GlyphIcon(Glyph.CHEVRON_RIGHT, G.sub, 22.dp)
        }
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
            // 0.1.12 오른손 #1: 평균 횟수는 측량 단추 아래가 아니라 폴 옆(위 줄)
            Column(Modifier.widthIn(min = 72.dp).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(16.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(16.dp))
                .hClickable(onClickLabel = "평균 횟수") { val l = GcpController.EPOCH_CHOICES; c.setEpochs(l[(l.indexOf(c.prefs.epochs) + 1).mod(l.size)]) }
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag("epochs_chip"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("평균", style = Ty.small, color = G.sub)
                Text("${c.prefs.epochs}회", style = Ty.coord.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = G.ink, maxLines = 1, modifier = Modifier.testTag("epochs_value"))
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
            // 0.1.12 오른손 #5: 칩 줄을 오른쪽으로 40dp(1번도 엄지 범위) · 다음 표적은 자동이라 칩은 다시 잴 때만
            Row(m.padding(start = 40.dp).testTag("qtag_row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val scroll = s.slots.size > 4
                s.slots.forEachIndexed { i, tag ->
                    val shot = c.shotOf(tag)
                    val now = c.measuringTag == tag
                    val isNext = tag == next && shot == null
                    val hot = now || isNext
                    val cellMod = if (scroll) Modifier.width(72.dp) else Modifier.weight(1f)
                    Column(cellMod.heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
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
                // 0.1.12 오른손 #5: 「+」(표적 더하기)는 길게 눌러야(오터치 방지). 짧게 누르면 안내만
                if (!locked) Box(Modifier.width(FieldIvory.minTouch).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(16.dp))
                    .hCombinedClickable(onLongClick = { c.openAddSlot() }) { c.ui.toast(ADD_SLOT_HINT) }.semantics { contentDescription = "표적 더하기 · 길게 누르기" }.testTag("add_slot"), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.PLUS, G.ink, 24.dp) }
            }
        }
        if (s.slots.size > 4) cells(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) else cells(Modifier.fillMaxWidth())
        // 0.1.12 오른손 #1: 측량 단추 위 16dp 는 누를 것 없음 · 대상 + 104dp 측량(오른쪽)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(88.dp).height(MEASURE_RH).clip(RoundedCornerShape(18.dp)).background(G.white).border(1.dp, G.line, RoundedCornerShape(18.dp))
                .hClickable(onClickLabel = "대상") { c.back() }.testTag("targets_obj"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GlyphIcon(Glyph.FOLDER, G.ink, 26.dp)
                Spacer(Modifier.height(4.dp))
                Text("대상", style = Ty.small.copy(fontWeight = FontWeight.Medium), color = G.ink, maxLines = 1)
            }
            Box(Modifier.weight(1f)) { MeasureBig(c) }
        }
    }
}

/** 0.1.12 오른손 #1: 측량 단추 104dp */
internal val MEASURE_RH = 104.dp
const val ADD_SLOT_HINT = "표적을 더하려면 「+」를 길게 누르세요"

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
    // 0.1.12 오른손 #9: 손을 뗄 때 실행 · 8dp 넘게 밀리면 취소 · 연타 0.8초 막기
    /* 0.1.52 #6 눌림 스프링 */ Box(Modifier.fillMaxWidth().height(MEASURE_RH).pressSpring(20.dp, 34.dp).background(if (dim) FieldIvory.disabled else G.dark)
        .releaseTap(label) { if (!locked || busy) c.onMeasureButton() }.testTag("measure_button"), contentAlignment = Alignment.Center) {
        if (busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).drawBehind { drawRect(G.white.copy(alpha = 0.85f), size = size.copy(width = size.width * c.progress.coerceIn(0f, 1f))) }.testTag("measure_progress")) // 0.1.52 #4 그리기 단계에서만 읽음
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Ty.label.copy(fontSize = if (busy) 30.sp else 24.sp, fontWeight = FontWeight.Bold), color = if (dim) FieldIvory.onDisabled else G.onDark, maxLines = 1, modifier = Modifier.testTag("measure_label"))
            Text(sub, style = Ty.small, color = if (dim) G.sub else G.onDark, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).testTag("measure_sub"))
        }
    }
}

// =============================== g02 새 대상(한 화면 · 고르기만) ===============================
@Composable
fun NewObjScreen(c: GcpController, f: NewObjForm) {
    val epsg = c.book?.site?.epsg
    val taken = f.no in f.used
    val name = ObjNames.label(f.kind, f.no)
    Column(Modifier.fillMaxSize().background(G.bg).hClickable {}.testTag("newobj")) {
        GTopBar("새 대상", { c.newObj = null }, c, sub = "고르기만 하면 돼요", onMenu = { c.menuOpen = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GroupLabel("종류")
            TileGrid(ObjKind.entries, 3, 10.dp) { k -> Tile(k.ko, k == f.kind, "kind_${k.id}", Modifier.weight(1f), height = 60.dp) { c.pickKind(k) } }
            GroupLabel("번호")
            if (f.kind.lettered) {
                TileGrid((1..26).toList(), 6, 8.dp) { n -> Tile(ObjNames.letter(n), n == f.no, "letter_$n", Modifier.weight(1f), enabled = n !in f.used, height = 56.dp, big = true) { c.pickObjNo(n) } }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile("−", false, "objno_minus", Modifier.width(76.dp), height = 76.dp, big = true, enabled = f.no > 1) { c.pickObjNo(f.no - 1) }
                    Column(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(16.dp)).background(G.white).border(1.dp, if (taken) G.bad else G.line, RoundedCornerShape(16.dp))
                        .hClickable(onClickLabel = "번호 고르기") { c.openObjNoPad() }.testTag("objno_display"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("${f.no}호", style = Ty.ledger.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = if (taken) G.bad else G.ink, maxLines = 1)
                    }
                    Tile("+", false, "objno_plus", Modifier.width(76.dp), height = 76.dp, big = true, enabled = f.no < ObjNames.MAX_NO) { c.pickObjNo(f.no + 1) }
                }
                Spacer(Modifier.height(10.dp))
                val nx = ObjNames.next(f.used)
                val quick = (listOf(1, nx, nx + 1, 10, 20)).filter { it <= ObjNames.MAX_NO && it !in f.used }.distinct().take(5)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in quick) Tile("$n", f.no == n, "objno_q_$n", Modifier.weight(1f), height = 56.dp) { c.pickObjNo(n) }
                }
            }
            if (taken) Text("이미 있는 번호예요 · 다음 번호 ${ObjNames.next(f.used)}", style = Ty.label, color = G.bad, modifier = Modifier.padding(top = 6.dp).testTag("objno_taken"))
            f.nearNote?.let { Text(it, style = Ty.label, color = G.warn, modifier = Modifier.padding(top = 4.dp).testTag("near_note")) }
            GroupLabel("차수 · 면")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("1차", true, "newobj_session_1", Modifier.weight(1f), height = 60.dp) {}
                Column(Modifier.weight(2f).heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                    Text("새 대상은 1차로 시작해요 · 2차부터는 폴더 안 차수에서", style = Ty.small, color = G.sub, modifier = Modifier.testTag("newobj_session_note"))
                }
            }
            Spacer(Modifier.height(10.dp))
            TileGrid(SessionLabels.ALL, 3, 10.dp) { l -> Tile(l, l == f.label, "label_$l", Modifier.weight(1f), height = 60.dp) { c.pickLabel(l) } }
            Spacer(Modifier.height(12.dp))
            Text("폴더 ${ObjNames.folder(f.kind, f.no)}_1차 · 표적 칸 #" + QrPayload.ranges(f.slots) + (epsg?.let { " · EPSG:$it" } ?: ""), style = Ty.small, color = G.sub, modifier = Modifier.padding(start = 4.dp).testTag("newobj_folder"))
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth().background(G.bg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
            Box(Modifier.padding(16.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(20.dp)).background(if (!taken) G.dark else FieldIvory.disabled)
                    .hClickable(enabled = !taken, onClickLabel = "$name 만들고 표적 재기") { c.submitNewObj() }.padding(10.dp).testTag("newobj_ok"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = Ty.label.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = if (!taken) G.onDark else FieldIvory.onDisabled, maxLines = 1, modifier = Modifier.testTag("newobj_name"))
                        Text(" 만들고 시작", style = Ty.label.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = if (!taken) G.onDark else FieldIvory.onDisabled, maxLines = 1)
                    }
                    Text("1차 · ${f.label}", style = Ty.small, color = if (!taken) G.onDark else FieldIvory.onDisabled)
                }
            }
        }
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
/** g06 PC로 보내기: 무엇을 · 받는 곳 · 들어가는 파일 · zip 만들어 보내기. 형식(zip 안 목록.csv + XML·TXT)은 그대로 */
@Composable
fun HandoffScreen(c: GcpController) {
    val b = c.book ?: return
    val now = System.currentTimeMillis()
    val sel = Handoff.select(b, c.handoffScope, now, c.zone)
    var dest by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(G.bg).hClickable {}.testTag("handoff_sheet")) {
        GTopBar("PC로 보내기", { c.handoffOpen = false }, c, sub = "iTwin Capture용 zip", onMenu = { c.handoffOpen = false; c.menuOpen = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GroupLabel("무엇을 보낼까요")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sc in HandoffScope.entries) {
                    val n = c.handoffCount(sc)
                    Tile(when (sc) { HandoffScope.PENDING -> "안 보낸 것"; HandoffScope.TODAY -> "오늘"; HandoffScope.ALL -> "유적 전체" }, c.handoffScope == sc, "handoff_${sc.name.lowercase()}", Modifier.weight(1f), sub = "폴더 $n", height = 64.dp) { c.handoffScope = sc }
                }
            }
            GroupLabel("받는 곳")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("공유하기", dest == 0, "handoff_dest_share", Modifier.weight(1f), sub = "Drive · 메일 · 메신저", height = 64.dp) { dest = 0 }
                Tile("USB / PC 케이블", dest == 1, "handoff_dest_usb", Modifier.weight(1f), sub = "Documents 폴더", height = 64.dp) { dest = 1 }
            }
            if (dest == 1) Text("파일은 이미 「${c.whereLabel()}」에 있어요. 케이블로 PC에 꽂아 그 폴더를 열면 돼요.", style = Ty.small, color = G.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp).testTag("handoff_usb_note"))
            GroupLabel("들어가는 파일")
            CardGroup(tag = "handoff_files") {
                CardRow(Glyph.FOLDER, Handoff.zipName(b.site, c.handoffScope, now, c.zone), "폴더 ${sel.size}개", "handoff_zip")
                CardDivider()
                CardRow(Glyph.LIST, Names.listCsvName(b.site), "폴더 · 차수 · 사진 폴더 목록", "handoff_csv") { Badge("포함", BadgeTone.DONE) }
                CardDivider()
                val pts = sel.sumOf { (_, s) -> b.active(s).size }
                CardRow(Glyph.PIN, "표적 좌표 ${pts}점", "XML · TXT · EPSG:${b.site.epsg}", "handoff_xml") { Badge(if (sel.isEmpty()) "없음" else "포함", if (sel.isEmpty()) BadgeTone.NEUTRAL else BadgeTone.DONE) }
                CardDivider()
                val noPhoto = sel.count { it.second.photoDoneAt == null }
                CardRow(Glyph.CAMERA, "사진", "zip에 넣지 않아요 · 사진 폴더 이름으로 PC에서 맞춰요", "handoff_photo") { if (noPhoto > 0) Badge("사진 아직 $noPhoto", BadgeTone.WARN) }
            }
            if (sel.isEmpty()) Text("이 범위에 보낼 폴더가 없어요", style = Ty.label, color = G.sub, modifier = Modifier.padding(top = 10.dp, start = 4.dp).testTag("handoff_empty"))
            if (sel.isNotEmpty()) {
                GroupLabel("폴더")
                for ((o, s) in sel.take(30)) ZipLine(Names.objFolder(o) + "/", "${o.name} · ${s.no}차", 0)
                if (sel.size > 30) Text("… 그 밖에 ${sel.size - 30}개", style = Ty.label, color = G.sub)
            }
            val last = b.sessions.mapNotNull { it.deliveredAt }.maxOrNull()
            if (last != null) Text("마지막으로 보낸 때 · ${c.timeLabel(last)}", style = Ty.small, color = G.sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp).testTag("handoff_last"))
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth().background(G.bg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(G.line))
            Box(Modifier.padding(16.dp)) { BigPrimary("zip 만들어 보내기", "폴더 ${sel.size}개 · 공유 창에서 보낼 곳 고르기", "handoff_send", enabled = sel.isNotEmpty()) { c.sendHandoff() } }
        }
    }
}

@Composable
private fun ZipLine(name: String, note: String, depth: Int, color: Color = G.ink) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 18).dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Ty.coord.copy(fontSize = 16.sp), color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = Ty.small.copy(fontSize = 16.sp), color = G.sub, maxLines = 1)
    }
}
