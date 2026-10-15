package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.dig.StepState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 0.1.43 머리줄 상태 알약 글자(시안: 「○ 연결 안 됨」 / 「● FIX 1.4 cm」 / 「● FLOAT 5.2 cm」) */
data class D43Pill(val text: String, val sub: String?, val dot: Color?, val mark: StatusMark = if (dot == null) StatusMark.NONE else StatusMark.FIX)

internal fun d43Pill(c: SurveyController): D43Pill {
    val g = c.gv
    if (g.noReceiver) return D43Pill("연결 안 됨", null, null)
    val cm = g.status.sigmaHM?.let { String.format(Locale.US, "%.1f cm", it * 100) }
    if (g.sigmaMissingOnFix) return if (g.strictMode) D43Pill("FIX · 정밀도 정보 없음", "저장 잠김", D.bad) else D43Pill("FIX", "정밀도 정보 없음", D.fix)
    return when (g.displayStatus) {
        SurveyStatus.FIXED -> D43Pill("FIX", cm, D.fix)
        SurveyStatus.CAUTION -> D43Pill(digPill(c).text.let { if (it.startsWith("FLOAT")) "FLOAT" else it }, cm, D.floatDot, StatusMark.FLOAT)
        SurveyStatus.UNAVAILABLE -> D43Pill("측량 불가", null, D.bad, StatusMark.NONE)
    }
}

@Composable
internal fun D43StatusPill(c: SurveyController, modifier: Modifier = Modifier) {
    // 0.1.52 #4: 위치가 바뀔 때마다가 아니라 알약 글자·색이 바뀔 때만 다시 그림
    val p by remember(c) { derivedStateOf { d43Pill(c) } }
    DStatusChip(p.text, p.sub, p.dot, "status_pill", modifier, p.mark) { if (c.gv.noReceiver) c.connection?.openScreen() ?: run { c.dig.statusOpen = true } else c.dig.statusOpen = true }
}

/** 앱 표시(어두운 네모 + 폴) — 아이콘과 같은 어둡고 굵은 모양 */
@Composable
internal fun D43Logo(size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(D.dark).testTag("home_logo"), contentAlignment = Alignment.Center) {
        GlyphIcon(Glyph.POLE, D.logoInk, (size * 0.62f).dp)
    }
}

/** 0.1.46 시안 s01 홈: 지금 작업 카드(상태 칩 + 큰 「측량 계속하기」) · 다른 작업 · 새 작업 · 바로가기 3 · 측량 전 준비 */
@Composable
fun HomeToday(c: SurveyController) {
    rememberTick()
    val d = c.dig
    val steps = d.prepSteps(ntripNow())
    val done = kr.archsurvey.dig.TodayPrep.doneCount(steps)
    val last = c.points.lastOrNull()
    val site = c.jobs?.currentSite
    Column(Modifier.fillMaxSize().background(D.bg).testTag("home_screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("발굴 측량", style = DTy.h1, color = D.ink, maxLines = 1, modifier = Modifier.testTag("home_title"))
                Text(site ?: "현장 GNSS", style = DTy.small, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            D43StatusPill(c)
            // 0.1.53: 미니멀이면 ⋯ (숨긴 기능 전부), 일반이면 예전 ≡ 서랍
            if (Minimal.on) MoreIcon("home_more") else HeaderIcon(Glyph.MENU, "메뉴", "home_settings") { c.drawerOpen = true }
        }
        // 0.1.53: [미니멀 | 일반] + ☀ 햇빛 — 머리줄에 둘 자리가 없어 바로 아랫줄(선택만, 폰에 저장)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).testTag("home_mode_row"), verticalAlignment = Alignment.CenterVertically) {
            ModeChip(D.ink, D.card, D.line, Modifier.padding(end = 8.dp))
            SunToggle()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.hair))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(10.dp))
            // 지금 작업 카드
            val cardShape = RoundedCornerShape(20.dp)
            Column(Modifier.fillMaxWidth().clip(cardShape).background(D.card).border(1.dp, D.hair, cardShape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("지금 작업", style = DTy.small, color = D.sub)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.projectName, style = DTy.h1.copy(fontSize = 24.sp, lineHeight = 30.sp), color = D.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("home_job"))
                    Box(Modifier.size(48.dp).hClickable { c.jobs?.openInfo() ?: c.hub.open(ToolScreen.JOB_INFO) }.semantics { contentDescription = "작업 정보" }.testTag("home_job_btn"), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.CHEVRON_RIGHT, D.sub, 16.dp)
                    }
                }
                Text(listOfNotNull(c.zone.displayKo, "오늘 ${d.todayCount()}점", last?.let { "마지막 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it.measuredAtMs)) }, "해발 ${c.geoid.shortKo}").joinToString(" · "),
                    style = DTy.small, color = D.sub, maxLines = 2, modifier = Modifier.testTag("home_job_info"))
                last?.let { Text(timeLabel(it.measuredAtMs, c.nowMs()) + " 저장 · " + it.name, style = DTy.small, color = D.sub, maxLines = 1, modifier = Modifier.testTag("home_saved_at")) }
                c.jobs?.currentFolder?.let { Text("폴더 · $it", style = DTy.small, color = D.weak, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("home_job_folder")) }
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val conn = c.connection
                    val rx = if (c.gv.noReceiver) "수신기 없음" else (conn?.profile?.displayName?.let { HeaderModel.shortModel(it) } ?: "수신기") + " 연결"
                    HomeChip(rx, if (c.gv.noReceiver) D.bad else D.fix, "home_chip_rx")
                    if (conn?.form?.enabled == true && conn.form.mount.isNotBlank()) HomeChip(conn.form.mount, D.fix, "home_chip_vrs")
                    HomeChip("폴 " + String.format(Locale.US, "%.2f", c.antenna.inputValueM) + " m", D.accent, "home_chip_pole")
                }
                // 0.1.50 오른손 #7: 폴 확인 띠(아래 한 줄)는 지금 작업 카드 안 배지로
                Text(homeHint(c, steps), style = DTy.small.copy(fontWeight = FontWeight.Medium), color = D.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(D.chip).padding(horizontal = 10.dp, vertical = 6.dp).testTag("home_foot"))
            }
            if (!Minimal.on) {
            Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("측량 전 준비", style = DTy.h2, color = D.ink)
                Text("  ${steps.size}개 중 ${done}개 끝남", style = DTy.small, color = D.sub, modifier = Modifier.testTag("prep_count"))
            }
            steps.forEachIndexed { i, s -> D43PrepRow(c, i, s, i == steps.lastIndex) }
            BatteryGuideCard(Modifier.padding(top = 8.dp)) // 0.1.48
            Text(backupLabel(c), style = DTy.small, color = D.sub, modifier = Modifier.padding(start = 4.dp).testTag("home_backup"))
            } else {
                // 0.1.53 미니멀: 자동으로 끝난 준비 줄(수신기·VRS 연결 등)은 접고, 안 끝났거나 실패한 줄만(폴 「맞아요」 포함)
                val open = steps.withIndex().filter { it.value.state != kr.archsurvey.dig.StepState.DONE }
                open.forEachIndexed { k, (i, s) -> D43PrepRow(c, i, s, k == open.lastIndex) }
                if (open.isEmpty()) Text("준비 끝 · 수신기·VRS 자동 연결됨", style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.fix, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag("home_prep_done"))
                // 위험 4: 자동 백업이 꺼져 있으면 작은 「백업 안 됨」(기존 백업 표시 재사용)
                if (c.drive?.autoBackup != true) Text("백업 안 됨" + backupLabel(c).let { if (it.endsWith("—")) "" else " · 마지막 $it" } + " · ⋯ 내보내기", style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.bad, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag("home_backup_off"))
                Text("나머지 기능은 ⋯ 안에 있어요", style = DTy.small, color = D.sub, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag("home_more_hint"))
            }
            Spacer(Modifier.height(8.dp))
        }
        // 0.1.50 오른손 #7: 자리만 바꿈 — 드문 것(구역·찾아가기·설정 / 새 작업·다른 작업·유구별)은 위, 자주 쓰는 것(점 목록·내보내기·수신기)은 아래,
        // 「측량 계속하기」는 화면 맨 아래 고정(104dp, 엄지 쉬움)
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.hair))
        Column(Modifier.fillMaxWidth().background(D.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).testTag("home_dock"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Minimal.on) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 0.1.53 미니멀: 매일 쓰는 「+ 새 작업」만(오른쪽 엄지 쪽). 나머지는 ⋯
                Spacer(Modifier.weight(1f))
                HomeTileBox(Glyph.PLUS, "+ 새 작업", "home_new_job", Modifier.weight(1f), small = true, accent = true) { c.jobs?.openNew() ?: c.ui.toast("작업 관리를 쓸 수 없어요") }
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTileBox(Glyph.GRID, "구역·그리드", "home_areas", Modifier.weight(1f), small = true) { c.homeOpen = false; c.hub.open(ToolScreen.AREAS) }
                HomeTileBox(Glyph.LOCATE, "점 찾아가기", "home_goto", Modifier.weight(1f), small = true) { c.hub.open(ToolScreen.GOTO) }
                HomeTileBox(Glyph.SLIDERS, "설정", "home_settings2", Modifier.weight(1f), small = true) { c.hub.open(ToolScreen.SETTINGS) }
            }
            // 0.1.51 새 작업 쉽게: 「+ 새 작업」은 오른쪽(엄지 쪽)·테라코타 테두리로 눈에 띄게 → 다음 화면 「만들고 측량 시작」 한 번
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTileBox(Glyph.FOLDER, "다른 작업", "home_card_open", Modifier.weight(1f), small = true) { c.jobs?.openList() ?: c.hub.open(ToolScreen.JOB_OPEN) }
                HomeTileBox(Glyph.FACE, "유구별", "home_features", Modifier.weight(1f), small = true) { c.homeOpen = false; c.hub.open(ToolScreen.FEATURES) }
                HomeTileBox(Glyph.PLUS, "+ 새 작업", "home_new_job", Modifier.weight(1f), small = true, accent = true) { c.jobs?.openNew() ?: c.ui.toast("작업 관리를 쓸 수 없어요") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTileBox(Glyph.LIST, "점 목록", "home_points", Modifier.weight(1f)) { c.homeOpen = false; c.hub.open(ToolScreen.POINTS) }
                HomeTileBox(Glyph.EXPORT, "내보내기", "home_export", Modifier.weight(1f)) { c.exporter?.openScreen() }
                HomeTileBox(Glyph.BT, "수신기", "home_receiver", Modifier.weight(1f)) { c.connection?.openScreen() ?: c.ui.toast("연결 화면을 열 수 없어요") }
            }
            }
            Box(
                Modifier.fillMaxWidth().height(MEASURE_RH).clip(RoundedCornerShape(18.dp)).background(D.primary)
                    .hClickable { c.goToSurvey() }.semantics { contentDescription = "측량 계속하기" }.testTag("home_survey"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("측량 계속하기", style = DTy.h1.copy(fontSize = 24.sp), color = FieldIvory.onPrimary)
                    Text("지도에서 바로 측량", style = DTy.small, color = FieldIvory.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun HomeChip(text: String, color: Color, tag: String) {
    Text(text, style = DTy.small.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp).testTag(tag))
}

@Composable
private fun HomeListRow(g: Glyph, title: String, sub: String, tag: String, plain: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).hClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (plain) D.chip else FieldIvory.primarySoft), contentAlignment = Alignment.Center) { GlyphIcon(g, if (plain) D.ink else D.accent, 22.dp) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = DTy.bodyB, color = D.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = DTy.small, color = D.sub, maxLines = 1)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, D.sub, 16.dp)
    }
}

@Composable
private fun HomeTileBox(g: Glyph, label: String, tag: String, modifier: Modifier, small: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val sh = RoundedCornerShape(16.dp)
    Column(modifier.heightIn(min = if (small) FieldIvory.minTouch else 76.dp).clip(sh).background(if (accent) FieldIvory.primarySoft else D.card).border(if (accent) 2.dp else 1.5.dp, if (accent) FieldIvory.primary else D.line, sh).hClickable(onClickLabel = label, onClick = onClick).padding(vertical = 8.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (!small) { GlyphIcon(g, D.ink, 24.dp); Spacer(Modifier.height(6.dp)) }
        Text(label, style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.ink, maxLines = 1)
    }
}

internal fun timeLabel(ms: Long, nowMs: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    val same = day.format(Date(ms)) == day.format(Date(nowMs))
    return (if (same) "오늘 " else SimpleDateFormat("MM-dd ", Locale.KOREA).format(Date(ms))) + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
}

private fun homeHint(c: SurveyController, steps: List<kr.archsurvey.dig.PrepStep>): String = when {
    c.gv.noReceiver -> "수신기를 켜고 「연결」을 누르세요."
    c.gv.displayStatus != SurveyStatus.FIXED -> c.dig.causeLine(ntripNow()) ?: "FIX를 기다리는 중이에요."
    steps.any { it.state == StepState.NEXT } -> steps.first { it.state == StepState.NEXT }.title + " — 확인해 주세요."
    else -> "준비됐어요 · 「이어서 측량」을 누르세요."
}

internal fun backupLabel(c: SurveyController): String {
    val b = c.backup?.items?.maxByOrNull { it.createdAtMs }
    return if (b != null) "백업 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(b.createdAtMs)) else "백업 —"
}

@Composable
private fun D43PrepRow(c: SurveyController, i: Int, s: kr.archsurvey.dig.PrepStep, lastRow: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("prep_${s.id}")) {
        Box(Modifier.width(30.dp).fillMaxHeight()) {
            if (!lastRow) Box(Modifier.align(Alignment.TopCenter).padding(top = 30.dp).width(1.dp).fillMaxHeight().background(D.line))
            Box(
                Modifier.padding(top = 6.dp).align(Alignment.TopCenter).size(24.dp).clip(CircleShape)
                    .background(D.bg).border(if (s.state == StepState.DONE) 1.5.dp else 1.dp, if (s.state == StepState.DONE) D.ink else D.line, CircleShape)
                    .then(if (s.state == StepState.NEXT) Modifier.border(0.dp, Color.Transparent, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when (s.state) {
                    StepState.DONE -> GlyphIcon(Glyph.CHECK, D.ink, 14.dp)
                    StepState.NEXT -> Box(Modifier.size(24.dp).background(D.bg), contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).clip(CircleShape).background(D.accentLight)) }
                    StepState.TODO -> Text("${i + 1}", style = DTy.monoS, color = D.sub)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 4.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = DTy.body.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = D.ink)
                if (s.state == StepState.NEXT) Text("  다음", style = DTy.small.copy(fontWeight = FontWeight.Medium), color = D.accentLight, modifier = Modifier.testTag("prep_${s.id}_next"))
            }
            Text(s.sub, style = DTy.small, color = D.sub, maxLines = 2, modifier = Modifier.testTag("prep_${s.id}_sub"))
        }
        s.action?.let { a ->
            DSecondary(a, "prep_${s.id}_act", Modifier.padding(top = 2.dp).height(48.dp).widthIn(min = 70.dp)) { prepAction(c, s.id) }
        }
    }
}

/** 0.1.52 #9 햇빛 고대비: 홈 머리줄에서 한 번 눌러 켬/끔(선택만, 저장됨) */
@Composable
internal fun SunToggle() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val on = Sunlight.on
    Box(Modifier.padding(end = 6.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) D.ink else D.card).border(1.5.dp, D.line, RoundedCornerShape(24.dp))
        .hClickable(onClickLabel = "햇빛 고대비") { Sunlight.set(ctx, !on) }.padding(horizontal = 10.dp)
        .semantics { contentDescription = "햇빛 고대비 " + if (on) "켬" else "끔" }.testTag("home_sun"), contentAlignment = Alignment.Center) {
        Text(if (on) "☀ 고대비" else "☀ 햇빛", style = DTy.small.copy(fontWeight = FontWeight.Bold), color = if (on) D.bg else D.ink, maxLines = 1)
    }
}

/** 0.1.53 ⋯ 단추(미니멀): 숨긴 기능 한 곳 */
@Composable
internal fun MoreIcon(tag: String) {
    Box(Modifier.size(48.dp).clip(CircleShape).hClickable(onClickLabel = "더보기") { Minimal.moreOpen = true }.semantics { contentDescription = "더보기 메뉴" }.testTag(tag), contentAlignment = Alignment.Center) {
        Text("⋯", style = DTy.h1.copy(fontSize = 26.sp), color = D.ink)
    }
}
