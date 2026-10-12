package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.dig.DigKind
import kr.archsurvey.dig.StepState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 0.1.35 화면: ① 오늘(홈 「오늘 준비」 5단계) · ③ 측량한 것(유구별/날짜별/종류별) */

private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f3s(v: Double) = String.format(Locale.US, "%.3f", v)

@Composable
private fun LedgerRow(cells: List<Triple<String, String, String>>, tagPrefix: String) {
    val t = T
    Column {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(t.ink))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 6.dp)) {
            cells.forEachIndexed { i, (k, v, u) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(t.line.copy(alpha = 0.5f)))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp).testTag("${tagPrefix}_$i")) {
                    Text(k, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(v, style = Ty.ledger.copy(fontSize = 20.sp), color = t.ink, maxLines = 1)
                        if (u.isNotEmpty()) Text(" $u", style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.5f)))
    }
}

/** ① 오늘 준비: 작업명(명조) · 장부(마지막 점/폴/정밀도) · 측량 시작 · 작업 · 5단계 · 바로 가기 4 */
@Composable
fun HomeToday(c: SurveyController) {
    val t = T
    rememberTick()
    val d = c.dig
    val steps = d.prepSteps(ntripNow())
    val done = kr.archsurvey.dig.TodayPrep.doneCount(steps)
    val last = c.points.lastOrNull()
    Column(Modifier.fillMaxSize().background(t.bg).testTag("home_screen")) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("발굴 측량", style = Ty.serifHead.copy(fontSize = 20.sp), color = t.ink, modifier = Modifier.weight(1f).testTag("home_title"))
            DigStatusPill(c, Modifier.padding(horizontal = 4.dp))
            HeaderIcon(Glyph.MENU, "메뉴", "home_settings") { c.drawerOpen = true }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 0.1.39 「새 작업」 한 번에: 홈 맨 위 큰 단추(예전: ≡ → 메뉴 → 새 작업 → … 여러 번)
            MBtn(Modifier.fillMaxWidth().height(64.dp).testTag("home_new_job"), MKind.PRIMARY, 18.dp, onClick = { c.jobs?.openNew() ?: c.ui.toast("작업 관리를 쓸 수 없어요") }) {
                Text("+ 새 작업", style = Ty.button.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = t.onPrimary)
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(t.ink))
            Row {
                Text("이어서 측량", style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f))
                last?.let { Text(SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(it.measuredAtMs)) + " 저장", style = Ty.small, color = t.inkSub) }
            }
            Text(c.projectName, style = Ty.serifTitle, color = t.ink, maxLines = 2, modifier = Modifier.testTag("home_job"))
            Text("EPSG:${c.zone.epsg} · 해발 ${c.geoid.shortKo} · ${c.points.size}점 · 유구 ${d.featureOutlines().size}", style = Ty.coord.copy(fontSize = 13.sp), color = t.inkSub, modifier = Modifier.testTag("home_job_info"))
            // 0.1.38 새 작업 = 새 폴더: 이 작업 자료가 모이는 곳
            c.jobs?.currentFolder?.let { Text("폴더 · $it", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 2, modifier = Modifier.testTag("home_job_folder")) }
            LedgerRow(listOf(
                Triple("마지막 점", last?.name ?: "—", ""),
                Triple("폴", String.format(Locale.US, "%.1f", c.antenna.inputValueM), "m"),
                Triple("수평 정밀도", c.gv.status.sigmaHM?.let { String.format(Locale.US, "%.1f", it * 100) } ?: "—", "cm"),
            ), "home_ledger")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MBtn(Modifier.weight(1f).height(64.dp).testTag("home_survey"), MKind.PRIMARY, 18.dp, onClick = { c.goToSurvey() }) {
                    Text("측량 시작", style = Ty.button.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = t.onPrimary)
                }
                MBtn(Modifier.width(110.dp).height(64.dp).testTag("home_job_btn"), MKind.PLAIN, 18.dp, onClick = { c.jobs?.openInfo() ?: c.hub.open(ToolScreen.JOB_INFO) }) { Text("작업", style = Ty.label.copy(fontSize = 16.sp), color = t.ink) }
            }
            // 0.1.39 작업 카드: 다른 작업 열기(새 작업은 바로 위 큰 단추 — 같은 단추를 두 번 두지 않음)
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("home_card_open"), MKind.PLAIN, 16.dp, onClick = { c.jobs?.openList() }) { Text("다른 작업 열기", style = Ty.label, color = t.ink) }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(t.ink))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘 준비", style = Ty.serifHead, color = t.ink, modifier = Modifier.weight(1f))
                Text("$done / ${steps.size}", style = Ty.coord.copy(fontSize = 14.sp), color = t.inkSub, modifier = Modifier.testTag("prep_count"))
            }
            steps.forEachIndexed { i, s -> PrepRow(c, i, s) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.5f)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut(Glyph.LIST, "측량한 것", "home_points", Modifier.weight(1f)) { c.homeOpen = false; c.hub.open(ToolScreen.FEATURES) }
                Shortcut(Glyph.GRID, "구역·그리드", "home_areas", Modifier.weight(1f)) { c.homeOpen = false; c.hub.open(ToolScreen.AREAS) }
                Shortcut(Glyph.SHARE, "내보내기", "home_export", Modifier.weight(1f)) { c.exporter?.openScreen() }
                Shortcut(Glyph.INFO, "설정", "home_settings2", Modifier.weight(1f)) { c.hub.open(ToolScreen.SETTINGS) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PrepRow(c: SurveyController, i: Int, s: kr.archsurvey.dig.PrepStep) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("prep_${s.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (s.state == StepState.DONE) t.raised else t.bg)
                .border(if (s.state == StepState.TODO) 1.dp else 1.8.dp, when (s.state) { StepState.DONE -> t.ink; StepState.NEXT -> t.primary; StepState.TODO -> t.outline }, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (s.state) {
                StepState.DONE -> GlyphIcon(Glyph.CHECK, t.ink, 16.dp)
                StepState.NEXT -> Box(Modifier.size(10.dp).clip(CircleShape).background(t.primary))
                StepState.TODO -> Text("${i + 1}", style = Ty.small, color = t.inkSub)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.title, style = Ty.label.copy(fontSize = 16.sp, fontWeight = if (s.state == StepState.NEXT) FontWeight.Medium else FontWeight.Normal), color = if (s.state == StepState.NEXT) t.accentText else t.ink)
            Text(s.sub, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 2, modifier = Modifier.testTag("prep_${s.id}_sub"))
        }
        s.action?.let { a ->
            MBtn(Modifier.height(48.dp).widthIn(min = 72.dp).testTag("prep_${s.id}_act"), MKind.PLAIN, 14.dp, onClick = { prepAction(c, s.id) }) {
                Text(a, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

private fun prepAction(c: SurveyController, id: String) {
    when (id) {
        "receiver" -> c.connection?.openScreen()
        "vrs" -> c.connection?.openVrs()
        "pole" -> c.dig.confirmPole()
        "crs" -> c.hub.open(ToolScreen.JOB_INFO)
        "bm" -> { c.dig.select(DigKind.BM); c.goToSurvey() }
    }
}

@Composable
private fun Shortcut(g: Glyph, label: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    Column(modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(14.dp)).hClickable(onClick = onClick).padding(6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(t.chip), contentAlignment = Alignment.Center) { GlyphIcon(g, t.ink, 22.dp) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Ty.small.copy(fontSize = 12.sp), color = t.ink, maxLines = 1)
    }
}

/** ③ 측량한 것: 유구별 · 날짜별 · 종류별 / 기준점 확인 카드 / 확인 필요(고정 아님) / 이름 바꾸기 / 내보내기 */
@Composable
fun FeaturesScreen(c: SurveyController) {
    val t = T
    val d = c.dig
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    ToolScaffold("측량한 것", "features_screen", onBack = { c.hub.back() }, bottom = {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.weight(1f).height(56.dp).testTag("features_all_points"), MKind.PLAIN, 16.dp, onClick = { c.hub.open(ToolScreen.POINTS) }) { Text("점 목록 전체", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(56.dp).testTag("features_export"), MKind.PRIMARY, 16.dp, onClick = { c.exporter?.openScreen() }) { Text("내보내기", style = Ty.label, color = t.onPrimary) }
        }
    }) {
        Text("점 ${c.points.size} · 유구 ${d.featureOutlines().size} · 유물 ${c.points.count { it.artifactNo != null }} · 오늘 ${d.todayCount()}", style = Ty.coord.copy(fontSize = 13.sp), color = t.inkSub, modifier = Modifier.testTag("features_summary"))
        Seg(listOf("유구별", "날짜별", "종류별"), mode, "features_mode") { mode = it }
        val flagged = c.points.filter { PointCaution.displayStatus(it) != SurveyStatus.FIXED }
        if (flagged.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.caution.copy(alpha = 0.18f)).border(1.dp, t.cautionInk, RoundedCornerShape(14.dp)).padding(12.dp).testTag("features_flagged")) {
                Text("확인 필요 · 고정(FIX) 아님 ${flagged.size}점", style = Ty.label.copy(fontWeight = FontWeight.Medium), color = t.ink)
                Text(flagged.takeLast(6).joinToString(" · ") { it.name }, style = Ty.coord.copy(fontSize = 13.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when (mode) {
            0 -> {
                val bms = c.points.filter { DigKind.resolve(it.digKind, it.type) == DigKind.BM }
                if (bms.isNotEmpty()) {
                    val tod = d.bmToday
                    Card("기준점 · 확인", (bms.groupBy { it.digTag.ifBlank { it.name } }.map { (n, l) -> "$n ${l.size}회" }.joinToString(" · ")) +
                        " · 오늘 " + when (tod) { true -> "통과"; false -> "차이 큼"; null -> "아직" }, "features_bm") {}
                }
                val cards = d.featureCards()
                if (cards.isEmpty()) Text("아직 유구가 없어요 · 지도에서 「지금 재는 것」을 유구 경계로 두고 재세요", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("features_empty"))
                for (fc in cards) FeatureCardView(c, fc)
                val others = c.points.filter { p -> p.featureId == null && c.arch.outlines.none { p.rowId in it.pointRowIds } && DigKind.resolve(p.digKind, p.type) != DigKind.BM }
                if (others.isNotEmpty()) Card("유구 밖 · 그 밖의 점 ${others.size}", others.takeLast(8).joinToString(" · ") { it.name + (it.artifactNo?.let { n -> "(유물 $n)" } ?: "") }, "features_others") {}
            }
            1 -> {
                val byDay = c.points.groupBy { kr.archsurvey.dig.DayKey.of(it.measuredAtMs) }.toSortedMap(compareByDescending { it })
                for ((day, l) in byDay) Card(day, "${l.size}점 · " + l.groupBy { DigKind.resolve(it.digKind, it.type)?.labelKo ?: it.type }.map { (k, v) -> "$k ${v.size}" }.joinToString(" · "), "features_day_$day") {}
            }
            else -> {
                val byKind = c.points.groupBy { DigKind.resolve(it.digKind, it.type)?.labelKo ?: it.type }
                for ((k, l) in byKind) Card(k, "${l.size}점 · " + l.takeLast(6).joinToString(" · ") { it.name }, "features_kind_$k") { l.lastOrNull()?.let { c.hub.openDetail(it) } }
            }
        }
    }
}

@Composable
private fun Card(title: String, sub: String, tag: String, onClick: () -> Unit) {
    val t = T
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(14.dp)).hClickable(onClick = onClick).padding(12.dp).testTag(tag)) {
        Text(title, style = Ty.serifHead.copy(fontSize = 17.sp), color = t.ink)
        Text(sub, style = Ty.small.copy(fontSize = 13.sp), color = t.inkSub, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeatureCardView(c: SurveyController, fc: kr.archsurvey.dig.FeatureCard) {
    val t = T
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.raised).border(if (fc.open) 2.dp else 1.dp, if (fc.open) t.primary else t.outline, RoundedCornerShape(14.dp)).padding(12.dp).testTag("feature_card_${fc.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fc.name, style = Ty.serifHead, color = if (fc.open) t.accentText else t.ink, modifier = Modifier.weight(1f).testTag("feature_name_${fc.id}"))
            Text(if (fc.open) "재는 중" else if (fc.closed) "면 닫힘" else "선", style = Ty.label.copy(fontSize = 12.sp), color = t.inkSub)
        }
        Text("경계 ${fc.boundary} · 유물 ${if (fc.artifacts.isEmpty()) "없음" else fc.artifacts.joinToString(",")} · 레벨 ${fc.levels} · 고정 ${fc.fixedCount}/${fc.total}", style = Ty.small.copy(fontSize = 13.sp), color = t.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            fc.areaSqm?.let { Text("면적 ${f2(it)}㎡", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink) }
            fc.perimeterM?.let { Text("둘레 ${f2(it)} m", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink) }
            fc.depthM?.let { Text("깊이 ${f2(it)} m", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink) }
            fc.floorMeanM?.let { Text("바닥 ${f3s(it)}", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink) }
        }
        if (fc.history.size > 1) Text("이전 이름: " + fc.history.dropLast(1).joinToString(" → "), style = Ty.small, color = t.inkSub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.weight(1f).height(48.dp).testTag("feature_rename_${fc.id}"), MKind.PLAIN, 12.dp, onClick = { c.dig.renameFor = fc.id }) { Text("이름 바꾸기", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(48.dp).testTag("feature_continue_${fc.id}"), MKind.PLAIN, 12.dp, onClick = {
                if (!fc.open && fc.closed) c.arch.reopenOutline(fc.id)
                c.dig.select(DigKind.FEATURE, fc.id); c.hub.closeAll(); c.goToSurvey()
            }) { Text(if (fc.open) "이어서 재기" else "다시 열어 재기", style = Ty.label, color = t.ink) }
        }
    }
}
