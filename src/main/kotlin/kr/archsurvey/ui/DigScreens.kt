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
