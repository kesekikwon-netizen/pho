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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
data class D43Pill(val text: String, val sub: String?, val dot: Color?)

internal fun d43Pill(c: SurveyController): D43Pill {
    val g = c.gv
    if (g.noReceiver) return D43Pill("연결 안 됨", null, null)
    val cm = g.status.sigmaHM?.let { String.format(Locale.US, "%.1f cm", it * 100) }
    if (g.sigmaMissingOnFix) return if (g.strictMode) D43Pill("FIX · σ 없음", "저장 잠김", D.bad) else D43Pill("FIX", "σ 없음", D.fix)
    return when (g.displayStatus) {
        SurveyStatus.FIXED -> D43Pill("FIX", cm, D.fix)
        SurveyStatus.CAUTION -> D43Pill(digPill(c).text.let { if (it.startsWith("FLOAT")) "FLOAT" else it }, cm, Color(0xFFD9A400))
        SurveyStatus.UNAVAILABLE -> D43Pill("측량 불가", null, D.bad)
    }
}

@Composable
internal fun D43StatusPill(c: SurveyController, modifier: Modifier = Modifier) {
    rememberTick()
    val p = d43Pill(c)
    DStatusChip(p.text, p.sub, p.dot, "status_pill", modifier) { if (c.gv.noReceiver) c.connection?.openScreen() ?: run { c.dig.statusOpen = true } else c.dig.statusOpen = true }
}

/** 앱 표시(어두운 네모 + 폴) — 아이콘과 같은 어둡고 굵은 모양 */
@Composable
internal fun D43Logo(size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(D.dark).testTag("home_logo"), contentAlignment = Alignment.Center) {
        GlyphIcon(Glyph.POLE, Color(0xFFF2E6DA), (size * 0.62f).dp)
    }
}

/** 시안 ① 홈 · 이어서 측량: 작업 카드 · 3칸 지표 · [이어서 측량][다른 작업] · 측량 전 준비 5단계 · 바로 가기 5 · 상태줄 */
@Composable
fun HomeToday(c: SurveyController) {
    rememberTick()
    val d = c.dig
    val steps = d.prepSteps(ntripNow())
    val done = kr.archsurvey.dig.TodayPrep.doneCount(steps)
    val last = c.points.lastOrNull()
    Column(Modifier.fillMaxSize().background(D.bg).testTag("home_screen")) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            D43Logo()
            Spacer(Modifier.width(10.dp))
            Text("발굴 측량", style = DTy.serifM.copy(fontSize = 22.sp), color = D.ink, maxLines = 1, modifier = Modifier.testTag("home_title"))
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(D.line))
            Text("현장 GNSS", style = DTy.small, color = D.sub, maxLines = 1, modifier = Modifier.weight(1f))
            D43StatusPill(c)
            HeaderIcon(Glyph.MENU, "메뉴", "home_settings") { c.drawerOpen = true }
        }
        Box(Modifier.padding(horizontal = 16.dp)) { DRule() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("이어서 측량", style = DTy.tiny, color = D.sub, modifier = Modifier.weight(1f))
                last?.let { Text(timeLabel(it.measuredAtMs, c.nowMs()) + " 저장", style = DTy.monoS, color = D.sub, modifier = Modifier.testTag("home_saved_at")) }
            }
            Spacer(Modifier.height(6.dp))
            Text(c.projectName, style = DTy.serif, color = D.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("home_job"))
            val site = c.jobs?.currentSite
            Text(listOfNotNull(site, "EPSG:${c.zone.epsg}", "해발 ${c.geoid.shortKo}").joinToString(" · "), style = DTy.small, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).testTag("home_job_info"))
            c.jobs?.currentFolder?.let { Text("폴더 · $it", style = DTy.tiny, color = D.weak, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).testTag("home_job_folder")) }
            Spacer(Modifier.height(10.dp))
            DMetrics(listOf(
                Triple("마지막 점", last?.name ?: "—", ""),
                Triple("오늘", "${d.todayCount()}", "점"),
                Triple("폴", String.format(Locale.US, "%.2f", c.antenna.inputValueM), "m"),
            ), "home_ledger", topRule = true)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DPrimary("이어서 측량", "home_survey", Modifier.weight(1f).height(62.dp)) { c.goToSurvey() }
                DSecondary("다른 작업", "home_card_open", Modifier.width(112.dp).height(62.dp)) { c.jobs?.openList() ?: c.hub.open(ToolScreen.JOB_OPEN) }
            }
            // 0.1.39 「새 작업」은 홈에서 한 번(사용자 요청 유지) — 시안 자리를 해치지 않게 가는 점선 단추
            DChip("+ 새 작업 · 폴더 만들기", false, "home_new_job", Modifier.fillMaxWidth().padding(top = 10.dp), dashed = true) { c.jobs?.openNew() ?: c.ui.toast("작업 관리를 쓸 수 없어요") }
            Spacer(Modifier.height(16.dp))
            DRule()
            Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text("측량 전 준비", style = DTy.h2, color = D.ink)
                Text("  ${steps.size}개 중 ${done}개 끝남", style = DTy.small, color = D.sub, modifier = Modifier.testTag("prep_count"))
            }
            steps.forEachIndexed { i, s -> D43PrepRow(c, i, s, i == steps.lastIndex) }
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth().background(D.bg)) {
            Box(Modifier.padding(horizontal = 16.dp)) { DRule(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                DIconTile(Glyph.LIST, "측량한 것", "home_points", Modifier.weight(1f)) { c.homeOpen = false; c.hub.open(ToolScreen.FEATURES) }
                DIconTile(Glyph.GRID, "구역·그리드", "home_areas", Modifier.weight(1f)) { c.homeOpen = false; c.hub.open(ToolScreen.AREAS) }
                DIconTile(Glyph.LOCATE, "점 찾아가기", "home_goto", Modifier.weight(1f)) { c.hub.open(ToolScreen.GOTO) }
                DIconTile(Glyph.EXPORT, "내보내기", "home_export", Modifier.weight(1f)) { c.exporter?.openScreen() }
                DIconTile(Glyph.SLIDERS, "설정", "home_settings2", Modifier.weight(1f)) { c.hub.open(ToolScreen.SETTINGS) }
            }
            DFootLine(listOf(homeHint(c, steps), backupLabel(c)), "home_foot")
        }
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

private fun backupLabel(c: SurveyController): String {
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
            DSecondary(a, "prep_${s.id}_act", Modifier.padding(top = 2.dp).height(46.dp).widthIn(min = 70.dp)) { prepAction(c, s.id) }
        }
    }
}
