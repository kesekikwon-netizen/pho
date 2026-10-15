package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.export.ExportFormat

/** 시안 ⑨ 내보내기 표의 한 줄: 양식 · 이름 · 확장자 · 받는 곳 */
private data class ExportLine(val f: ExportFormat?, val name: String, val ext: String, val to: String)

private val MAIN_LINES = listOf(
    ExportLine(ExportFormat.DRAWING_DXF, "도면 · 레이어별", ".dxf", "Trowel · CAD"),
    ExportLine(ExportFormat.SHP, "유구 · 점 Shape", ".shp .dbf .prj", "Strata (hgis)"),
    ExportLine(ExportFormat.CSV, "점 목록", ".csv", "보고서 · 엑셀"),
    ExportLine(ExportFormat.FIELD_CSV, "야장", ".csv", "현장 기록"),
    ExportLine(null, "사진 · 음성 메모", "photos/ voices/", "함께 묶음"),
)
private val MORE = listOf(ExportFormat.GEOJSON, ExportFormat.KML, ExportFormat.HGIS_CSV, ExportFormat.DXF, ExportFormat.REPORT)

/** 만들 파일 개수(사진·음성 묶음은 1개로 셈) */
internal fun exportFileCount(ec: ExportController): Int = ec.formats.size + if (ec.includePhotos || ec.includeVoices) 1 else 0

/**
 * 0.1.43 시안 ⑨ 내보내기: 작업 요약 · 도면 축척(1:20/1:100/1:200) · 오늘 잰 것만 · 들어가는 파일 체크 표 · 다른 형식 접기 ·
 * 저장 위치 · 「파일 N개 만들기」 → 공유 / 파일로 저장. (iTwin XML 은 이 앱에 없음)
 */
@Composable
fun ExportScreen(ec: ExportController) {
    val s = ec.summary
    val out = ec.output
    val sc = LocalSurvey.current
    LaunchedEffect(Unit) {
        sc?.dig?.drawScale?.let { ec.scale = it }
        if (ec.formats == ExportFormat.DEFAULT) ec.formats = ExportFormat.EVENING // 시안 기본: 도면·Shape·점 목록·야장
    }
    ToolScaffold(kr.archsurvey.job.SendFolder.BUTTON, "export_screen", onBack = { ec.close() }, bottom = {
        DBottomBar {
            // 0.1.56 M5·M8: 단추 이름 「PC로 보내기」(세 앱 「…로 보내기」), 잰 점 0개면 꺼짐 + 「잰 점이 없어요」
            if (out == null) DPrimary(when {
                ec.running -> "만드는 중…"
                s != null && s.points == 0 -> kr.archsurvey.job.SendFolder.EMPTY
                else -> kr.archsurvey.job.SendFolder.BUTTON
            }, "export_run", Modifier.weight(1f), enabled = ec.canSend) { ec.runAndShare() }
            else {
                DSecondary("파일로 저장", "export_save", Modifier.weight(1f)) { ec.saveAs() }
                DPrimary("다시 공유", "export_share", Modifier.weight(1.4f)) { ec.share() }
            }
        }
    }) {
        Text(s?.project ?: sc?.projectName ?: "", style = DTy.serifM.copy(fontSize = 22.sp), color = D.ink, modifier = Modifier.testTag("export_title"))
        if (s == null) Text("불러오는 중…", style = DTy.small, color = D.sub)
        else Text("점 ${s.points} · 유구 ${sc?.dig?.featureOutlines()?.size ?: 0} · 사진 ${s.photos} · 음성 ${s.voices} · EPSG:${sc?.zone?.epsg ?: ""}", style = DTy.monoS, color = D.sub, modifier = Modifier.testTag("export_summary"))
        if ((s?.fakePoints ?: 0) > 0) Text("이전 시험 버전에서 수신기 없이 만든 점이 ${s!!.fakePoints}개 들어 있어요. 실측 데이터와 섞이지 않게 확인하세요.", style = DTy.small, color = D.bad, modifier = Modifier.testTag("export_fake_warning"))
        DRule()
        if (out == null) {
            if (s != null && s.points == 0) Text(kr.archsurvey.job.SendFolder.EMPTY, style = DTy.bodyB, color = D.sub, modifier = Modifier.testTag("export_empty"))
            // 0.1.56 S4: 옵션은 접어 두기 — 첫 화면 조작 = 보내기 단추 + 이 한 줄
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable { ec.optionsOpen = !ec.optionsOpen }.testTag("export_options"), verticalAlignment = Alignment.CenterVertically) {
                Text("들어가는 것: " + includedSummary(ec), style = DTy.bodyB, color = D.ink, modifier = Modifier.weight(1f).testTag("export_options_text"))
                GlyphIcon(if (ec.optionsOpen) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, D.sub, 14.dp)
            }
        }
        if (out == null && ec.optionsOpen) {
            DSectionLabel("도면 축척 · 글자 크기가 맞춰집니다")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(230.dp)) {
                    DSeg(kr.archsurvey.dig.DrawingScale.CHOICES.map { "1:$it" }, kr.archsurvey.dig.DrawingScale.CHOICES.indexOf(ec.scale), "export_scale", height = 52.dp, itemTags = kr.archsurvey.dig.DrawingScale.CHOICES.map { "export_scale_$it" }) { i ->
                        val k = kr.archsurvey.dig.DrawingScale.CHOICES[i]; ec.scale = k; sc?.dig?.changeScale(k)
                    }
                }
                Row(Modifier.weight(1f).heightIn(min = 52.dp).hClickable { ec.todayOnly = !ec.todayOnly }.padding(start = 14.dp).testTag("export_today_only"), verticalAlignment = Alignment.CenterVertically) {
                    DCheck(ec.todayOnly)
                    Text("  오늘 잰 것만", style = DTy.body, color = D.ink)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text("들어가는 파일", style = DTy.h2, color = D.ink, modifier = Modifier.weight(1f))
                Text("눌러서 빼기", style = DTy.tiny, color = D.sub)
            }
            DTableHead(listOf("" to 0.5f, "파일" to 2.4f, "받는 곳" to 2f), alignEndFrom = 99)
            for (l in MAIN_LINES) {
                val on = if (l.f != null) l.f in ec.formats else (ec.includePhotos || ec.includeVoices)
                ExportCheckRow(on, l.name, l.ext, l.to, "export_fmt_${l.f?.id ?: "media"}") {
                    if (l.f != null) ec.toggleFormat(l.f) else { val v = !on; ec.includePhotos = v; ec.includeVoices = v }
                }
            }
            var more by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable { more = !more }.testTag("export_more"), verticalAlignment = Alignment.CenterVertically) {
                Text("다른 형식", style = DTy.bodyB, color = D.ink)
                Text("  GeoJSON · KML · hgis 기준점 CSV", style = DTy.small, color = D.sub, modifier = Modifier.weight(1f))
                GlyphIcon(if (more) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, D.sub, 14.dp)
            }
            if (more) for (f in MORE) ExportCheckRow(f in ec.formats, f.labelKo, f.ext, "", "export_fmt_${f.id}") { ec.toggleFormat(f) }
        }
        // 0.1.56 M5: 경로 표기 한 가지 — 보내기 앞·완료 같은 글
        if (out == null) ec.where?.let { Text(it, style = DTy.small, color = D.sub, modifier = Modifier.testTag("export_where")) }
        ec.error?.let { ErrorCardView(ErrorCard("-", it, "다시 시도하거나, 계속 안 되면 화면을 캡처해 알려 주세요.", false), "export_error") }
        if (out != null) {
            Text("파일을 만들었어요", style = DTy.h2, color = D.ink, modifier = Modifier.testTag("export_done"))
            ec.savedText?.let { Text(it, style = DTy.bodyB, color = D.ink, modifier = Modifier.testTag("export_saved_path")) }
            Text("${ec.formats.size}개 양식 · ${ExportController.sizeText(out.sizeBytes)} · 점 ${out.points}개 · 사진 ${out.photos}장 · 음성 ${out.voices}개", style = DTy.small, color = D.sub, modifier = Modifier.testTag("export_result_counts"))
            DSecondary("양식 바꿔 다시 만들기", "export_again", Modifier.fillMaxWidth().height(52.dp)) { ec.openScreen(); ec.optionsOpen = true }
            ec.notice?.let { Text(it, style = DTy.small, color = D.sub, modifier = Modifier.testTag("export_notice")) }
        }
    }
}

/** 0.1.56 S4: 접힌 줄 「DXF·Shape·CSV·… N개」 */
internal fun includedSummary(ec: ExportController): String {
    val names = MAIN_LINES.filter { l -> if (l.f != null) l.f in ec.formats else (ec.includePhotos || ec.includeVoices) }.map { it.f?.let { f -> when (f) {
        ExportFormat.DRAWING_DXF, ExportFormat.DXF -> "DXF"; ExportFormat.SHP -> "Shape"; ExportFormat.CSV -> "CSV"; ExportFormat.FIELD_CSV -> "야장"; else -> f.labelKo } } ?: "사진·음성" } +
        MORE.filter { it in ec.formats }.map { it.labelKo }
    return names.distinct().joinToString("·") + " ${exportFileCount(ec)}개 ›"
}

@Composable
internal fun DCheck(on: Boolean) {
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(if (on) D.ink else D.card).border(1.5.dp, D.ink, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        if (on) GlyphIcon(Glyph.CHECK, Color.White, 18.dp)
    }
}

@Composable
private fun ExportCheckRow(on: Boolean, name: String, ext: String, to: String, tag: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 4.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.5f)) { DCheck(on) }
            Column(Modifier.weight(2.4f)) {
                Text(name, style = DTy.body.copy(fontWeight = FontWeight.Medium), color = D.ink)
                Text(ext, style = DTy.monoS, color = D.sub)
            }
            Text(to, style = DTy.body, color = D.ink, modifier = Modifier.weight(2f))
        }
        DRule(false)
    }
}
