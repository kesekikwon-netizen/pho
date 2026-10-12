package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SurveyStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 측정 직후 「점 확인」을 띄울 때(측량 화면 위, 다른 창이 없을 때) */
internal fun pointConfirmVisible(c: SurveyController): Boolean =
    !c.homeOpen && c.lastPoint != null && ((c.phase == Phase.RESULT && c.resultStripShown) || c.phase == Phase.RESULT_EXPANDED || c.phase == Phase.RECORDING) &&
        c.dupPrompt == null && c.tiltPrompt == null && c.resurveyTarget == null && c.hub.screen == null && c.connection?.open != true && c.exporter?.open != true

/**
 * 0.1.43 시안 ③ 점 저장 · 확인: 측정 직후 자동. 좌표 · 해발 · 정확도 · 안테나 · 기울기 · 좌표계 · n회 평균 표 ·
 * [사진 n/5][음성 메모][글 메모(고르는 문구)] · 이 점 지우기 · 다음 점 재기(한 번 = 바로 다음 측정). 결과 띠처럼 8초 뒤 저절로 지도로
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PointConfirmScreen(c: SurveyController) {
    val p = c.lastPoint ?: return
    var memoOpen by remember(p.name) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = c.ui.confirm == null && c.ui.choice == null) { if (memoOpen) memoOpen = false else c.dismissResult() }
    val idx = c.points.indexOfFirst { it.name == p.name }.let { if (it < 0) c.points.size else it + 1 }
    val st = PointCaution.displayStatus(p)
    Column(Modifier.fillMaxSize().background(D.bg).testTag("point_confirm")) {
        DTopBar("점 확인", onBack = { c.dismissResult() }, backTag = "point_confirm_back", right = {
            Text("$idx / ${c.points.size}", style = DTy.monoS, color = D.sub, modifier = Modifier.testTag("point_confirm_index"))
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.LOCATE, D.ink, 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(p.name, style = DTy.monoBig.copy(fontSize = 30.sp, lineHeight = 36.sp), color = D.ink, modifier = Modifier.weight(1f).testTag("result_name"))
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (st == SurveyStatus.FIXED) D.fix else D.caution))
                Text(" " + when (st) { SurveyStatus.FIXED -> "FIX로 저장"; SurveyStatus.CAUTION -> "주의로 저장"; else -> "저장" }, style = DTy.small, color = if (st == SurveyStatus.FIXED) D.fix else D.caution, modifier = Modifier.testTag("result_chip"))
            }
            Text(c.dig.whatLine(p) + (if (p.measuredAtMs > 0) " · " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(p.measuredAtMs)) else ""), style = DTy.small, color = D.sub, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).testTag("result_h"))
            DRule()
            val h = p.groundMslM ?: p.groundHeightM
            for ((k, v, u) in listOf(Triple("X (동)", grpC(p.easting), ""), Triple("Y (북)", grpC(p.northing), ""), Triple("해발", h?.let { String.format(Locale.US, "%.3f", it) } ?: "—", "m"))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("confirm_${k.take(1)}"), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = DTy.small, color = D.sub, modifier = Modifier.weight(1f))
                    Text(v, style = DTy.monoBig.copy(fontSize = 24.sp), color = D.ink)
                    if (u.isNotEmpty()) Text(" $u", style = DTy.small, color = D.sub)
                }
                DRule(false)
            }
            Spacer(Modifier.height(10.dp))
            fun cm(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it * 100) } ?: "—"
            val tiltLim = c.tilt?.limitDeg
            val rows = listOf(
                "정확도" to "수평 ${cm(p.sigmaHM)} · 수직 ${cm(p.sigmaU)} cm",
                "안테나" to "폴 ${String.format(Locale.US, "%.3f", p.antenna.inputM)} + ARP ${String.format(Locale.US, "%.3f", p.antenna.apcHeightM - p.antenna.inputM)} = ${String.format(Locale.US, "%.3f", p.antenna.apcHeightM)} m",
                "기울기" to (p.tiltDeg?.let { a -> String.format(Locale.US, "%.1f°", a) + (tiltLim?.let { l -> " · 허용 ${String.format(Locale.US, "%.1f", l)}° " + if ((p.tiltMaxDeg ?: a) <= l) "안" else "넘음" } ?: "") } ?: "—"),
                "좌표계" to "EPSG:${p.crsEpsg} · ${kr.archsurvey.core.GeoidModel.fromId(p.heightModel.ifEmpty { null }).shortKo}" + (c.connection?.profile?.displayName?.let { " · " + HeaderModel.shortModel(it) } ?: "") + (if (c.connection?.form?.enabled == true) " · VRS" else ""),
            )
            rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("confirm_row_$i")) {
                    Text(k, style = DTy.small, color = D.sub, modifier = Modifier.width(82.dp))
                    Text(v, style = DTy.mono.copy(fontSize = 13.5.sp), color = D.ink)
                }
            }
            if (p.samples.isNotEmpty()) {
                val n0 = p.samples.map { it.n }.average(); val e0 = p.samples.map { it.e }.average()
                val hs = p.samples.mapNotNull { it.h }; val h0 = if (hs.isEmpty()) null else hs.average()
                val spread = p.samples.maxOf { kotlin.math.hypot(it.n - n0, it.e - e0) }
                Spacer(Modifier.height(12.dp))
                DSectionLabel("${p.samples.size}회 평균 · 흩어짐 ${String.format(Locale.US, "%.1f", spread * 100)} cm")
                Spacer(Modifier.height(6.dp))
                DTableHead(listOf("회" to 0.6f, "평균에서 수평" to 1.6f, "높이" to 1.2f, "상태" to 0.8f))
                p.samples.forEachIndexed { i, s ->
                    val ok = st == SurveyStatus.FIXED || i < p.fixedEpochs
                    DTableRow(listOf("${i + 1}" to 0.6f, String.format(Locale.US, "%.1f cm", kotlin.math.hypot(s.n - n0, s.e - e0) * 100) to 1.6f,
                        (if (s.h != null && h0 != null) String.format(Locale.US, "%.1f cm", kotlin.math.abs(s.h - h0) * 100) else "—") to 1.2f, (if (ok) "FIX" else "FLOAT") to 0.8f),
                        "sample_$i", firstStyle = DTy.mono, colors = mapOf(3 to if (ok) D.fix else D.caution))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DSecondary("사진 ${c.photos.size}/${MediaLimits.PHOTOS}", "result_photo", Modifier.weight(1f).height(50.dp)) { c.addPhotoCamera() }
                DSecondary(if (c.voiceSeconds > 0) "녹음 끝 ${c.voiceSeconds}초" else "음성 메모", "result_voice", Modifier.weight(1f).height(50.dp)) { c.toggleVoice() }
                DSecondary(if (p.memo.isBlank()) "글 메모" else "메모 ✓", "result_memo", Modifier.weight(1f).height(50.dp)) { memoOpen = !memoOpen }
            }
            if (memoOpen) {
                Spacer(Modifier.height(10.dp))
                Text("고르면 바로 붙어요 · 다시 누르면 빠져요", style = DTy.tiny, color = D.sub)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("memo_chips")) {
                    for (n in QuickNotes.ITEMS) DChip(n, QuickNotes.has(p.memo, n), "memo_$n") { c.updatePointMeta(p.name, p.type, QuickNotes.toggle(p.memo, n)) }
                }
            }
            if (p.memo.isNotBlank()) Text("메모 · ${p.memo}", style = DTy.small, color = D.ink, modifier = Modifier.padding(top = 8.dp).testTag("result_memo_text"))
            Spacer(Modifier.height(16.dp))
        }
        DBottomBar {
            Text("이 점 지우기", style = DTy.body.copy(fontWeight = FontWeight.Medium), color = D.bad, textAlign = TextAlign.Center,
                modifier = Modifier.width(112.dp).heightIn(min = 56.dp).hClickable { c.askUndo() }.wrapContentHeight().testTag("undo_button"))
            DPrimary("다음 점 재기", "next_point", Modifier.weight(1f)) { c.nextPoint() }
        }
    }
}

/** 147991.214 → "147,991.214" */
internal fun grpC(v: Double): String = String.format(Locale.US, "%,.3f", v)
