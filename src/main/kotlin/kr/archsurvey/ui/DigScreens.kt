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

internal fun prepAction(c: SurveyController, id: String) {
    when (id) {
        "receiver" -> c.connection?.openScreen()
        "vrs" -> c.connection?.openVrs()
        "pole" -> c.dig.confirmPole()
        "crs" -> c.hub.open(ToolScreen.JOB_INFO)
        "bm" -> { c.dig.select(DigKind.BM); c.goToSurvey() }
    }
}

/** 0.1.43 시안 ⑤ 측량한 것: 요약 · 유구별/날짜별/종류별 · 확인 필요 띠 · 유구 표(점·FIX·면적) · 고른 유구의 점 표 */
@Composable
fun FeaturesScreen(c: SurveyController) {
    val d = c.dig
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val cards = d.featureCards()
    var selId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableLongStateOf(cards.firstOrNull { it.open }?.id ?: cards.lastOrNull()?.id ?: -1L) }
    ToolScaffold("측량한 것", "features_screen", onBack = { c.hub.back() }, bottom = {
        DBottomBar {
            DSecondary("점 목록 전체", "features_all_points", Modifier.weight(1f)) { c.hub.open(ToolScreen.POINTS) }
            DPrimary("내보내기", "features_export", Modifier.weight(1.4f)) { c.exporter?.openScreen() }
        }
    }) {
        Text("점 ${c.points.size}   유구 ${cards.size}   유물 ${c.points.count { it.artifactNo != null }}   오늘 ${d.todayCount()}", style = DTy.monoS, color = D.sub, modifier = Modifier.testTag("features_summary"))
        DSeg(listOf("유구별", "날짜별", "종류별"), mode, "features_mode") { mode = it }
        val flagged = c.points.filter { PointCaution.displayStatus(it) != SurveyStatus.FIXED }
        if (flagged.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(D.cautionBg).border(1.dp, D.caution.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .hClickable { flagged.firstOrNull()?.let { c.hub.openDetail(it) } }.padding(horizontal = 14.dp, vertical = 14.dp).testTag("features_flagged"), verticalAlignment = Alignment.CenterVertically) {
                Text("▲  확인 필요 · FIX 아닌 점 ${flagged.size}개 " + flagged.take(3).joinToString(" ") { it.name }, style = DTy.body, color = D.caution, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("보기", style = DTy.bodyB, color = D.caution)
            }
        }
        when (mode) {
            0 -> {
                DTableHead(listOf("유구" to 3f, "점" to 0.8f, "FIX" to 1.2f, "면적" to 1.6f))
                if (cards.isEmpty()) Text("아직 유구가 없어요 · 측량 화면의 「재는 것」을 유구 경계로 두고 재세요", style = DTy.small, color = D.sub, modifier = Modifier.testTag("features_empty"))
                for (fc in cards) DTableRow(listOf(fc.name to 3f, "${fc.total}" to 0.8f, "${fc.fixedCount}/${fc.total}" to 1.2f, (fc.areaSqm?.let { f2(it) + " ㎡" } ?: "—") to 1.6f),
                    "feature_card_${fc.id}", selected = fc.id == selId, sub = if (fc.open) "재는 중" else if (fc.closed) "경계 닫힘" else "선",
                    subColor = if (fc.open) D.accent else D.sub, firstStyle = DTy.serif.copy(fontSize = 20.sp),
                    colors = if (fc.fixedCount < fc.total) mapOf(2 to D.caution) else emptyMap(), onLongClick = { d.renameFor = fc.id }) { selId = fc.id }
                val others = c.points.filter { p -> p.featureId == null && c.arch.outlines.none { p.rowId in it.pointRowIds } }
                if (others.isNotEmpty()) {
                    val fx = others.count { PointCaution.displayStatus(it) == SurveyStatus.FIXED }
                    DTableRow(listOf("유구 밖" to 3f, "${others.size}" to 0.8f, "$fx/${others.size}" to 1.2f, "—" to 1.6f), "features_others",
                        sub = others.groupBy { DigKind.resolve(it.digKind, it.type)?.labelKo ?: "기타" }.map { (k, v) -> "$k ${v.size}" }.joinToString(" · "),
                        firstStyle = DTy.bodyB, colors = if (fx < others.size) mapOf(2 to D.caution) else emptyMap())
                }
                DRule()
                cards.firstOrNull { it.id == selId }?.let { fc ->
                    val pts = c.points.filter { p -> p.featureId == fc.id || c.arch.outlines.any { it.id == fc.id && p.rowId in it.pointRowIds } }
                    DSectionLabel("${fc.name}의 점", Modifier.padding(top = 8.dp))
                    DTableHead(listOf("번호" to 2f, "해발 m" to 1.6f, "수평 cm" to 1.4f, "시각" to 1.2f))
                    for (p in pts) DTableRow(listOf(p.name to 2f, ((p.groundMslM ?: p.groundHeightM)?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—") to 1.6f,
                        (p.sigmaHM?.let { String.format(java.util.Locale.US, "%.1f", it * 100) } ?: "—") to 1.4f,
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(p.measuredAtMs)) to 1.2f), "features_pt_${p.name}",
                        firstStyle = DTy.monoS.copy(fontSize = 15.sp), monoFrom = 0, colors = mapOf(3 to D.sub), compact = true) { c.hub.openDetail(p) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DSecondary("이름 바꾸기", "feature_rename_${fc.id}", Modifier.weight(1f), height = 50.dp) { d.renameFor = fc.id }
                        DSecondary(if (fc.open) "이어서 재기" else "다시 열어 재기", "feature_continue_${fc.id}", Modifier.weight(1f), height = 50.dp) {
                            if (!fc.open && fc.closed) c.arch.reopenOutline(fc.id)
                            d.select(DigKind.FEATURE, fc.id); c.hub.closeAll(); c.goToSurvey()
                        }
                    }
                }
                val bms = c.points.filter { DigKind.resolve(it.digKind, it.type) == DigKind.BM }
                if (bms.isNotEmpty()) {
                    val tod = d.bmToday
                    Card("기준점 · 확인", (bms.groupBy { it.digTag.ifBlank { it.name } }.map { (n, l) -> "$n ${l.size}회" }.joinToString(" · ")) +
                        " · 오늘 " + when (tod) { true -> "통과"; false -> "차이 큼"; null -> "아직" }, "features_bm") {}
                }
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
    Column(Modifier.fillMaxWidth().hClickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp).testTag(tag)) {
        Text(title, style = DTy.bodyB, color = D.ink)
        Text(sub, style = DTy.small, color = D.sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    DRule(false)
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
