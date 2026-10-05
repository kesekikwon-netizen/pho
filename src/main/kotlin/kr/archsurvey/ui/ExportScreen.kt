package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.export.ExportFormat

/**
 * 0.1.14 작업 전송(목업 11 양식 선택 → 12 전송 방식).
 * 1) 양식 체크(여러 개) + 사진/음성 → 「적용 (n)」 = ZIP 만들기
 * 2) 전송 방식: 무선 전송(공유 시트) / 장치 내 저장(문서 선택기) → 「전송」
 */
@Composable
fun ExportScreen(ec: ExportController) {
    val t = T
    val s = ec.summary
    val out = ec.output
    ToolScaffold("작업 전송", "export_screen", onBack = { ec.close() }, bottom = {
        if (out == null) DualBar("취소", "export_cancel", { ec.close() },
            if (ec.running) "만드는 중…" else "적용 (${ec.formats.size})", "export_run", !ec.running && (s?.points ?: 0) > 0 && ec.formats.isNotEmpty()) { ec.run() }
        else DualBar("취소", "export_method_cancel", { ec.close() }, "전송", "export_send", true) { ec.send() }
    }) {
        if (s == null) Text("불러오는 중…", style = Ty.body, color = t.inkSub)
        else {
            Text("작업 ${s.project} · 점 ${s.points}개 · 사진 ${s.photos}장 · 음성 ${s.voices}개", style = Ty.body, color = t.ink, modifier = Modifier.testTag("export_summary"))
            if (s.fakePoints > 0) {
                Text("이전 시험 버전에서 수신기 없이 만든 점이 ${s.fakePoints}개 들어 있어요. 실측 데이터와 섞이지 않게 확인하세요.", style = Ty.small, color = t.bad, modifier = Modifier.testTag("export_fake_warning"))
            }
        }
        if (out == null) {
            // hgis(Strata)에 끌어 놓을 Shape 한 벌을 한 번에(양식 고르기 없이)
            Btn(if (ec.running) "만드는 중…" else "hgis(Strata)용 SHP 바로 만들기", Modifier.fillMaxWidth().testTag("export_hgis_quick")) { ec.runHgis() }
            Text("작업 좌표계 그대로 .shp .shx .dbf .prj .cpg 한 벌 + 기준점 CSV · 폰 문서 폴더(Documents/ExcavationSurvey/hgis)에도 둬요",
                style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_hgis_note"))
            Text("추출할 양식을 고르세요 · 여러 개 선택 가능", style = Ty.small, color = t.inkSub)
            Column {
                for (f in ExportFormat.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable { ec.toggleFormat(f) }.testTag("export_fmt_${f.id}"), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(f in ec.formats, { ec.toggleFormat(f) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = t.primary, checkmarkColor = t.onPrimary))
                        Text(f.labelKo, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
                        Text(f.ext, style = Ty.coord.copy(fontSize = 12.sp), color = t.inkSub)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.2f)))
                }
            }
            CheckRow("사진 포함 (photos/)", ec.includePhotos, "export_photos") { ec.includePhotos = it }
            CheckRow("음성 메모 포함 (voices/)", ec.includeVoices, "export_voices") { ec.includeVoices = it }
            Text("공사·지적용 양식(목업의 공공측량·물량산출 등)과 캘리브레이션 파일은 발굴 기록 범위 밖이라 넣지 않았어요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_scope_note"))
            var filesOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            FoldRow("파일 설명", filesOpen, "export_files_toggle") { filesOpen = !filesOpen }
            if (filesOpen) Card {
                Text("기본 양식 points.csv — 모든 항목 (UTF-8, 엑셀 한글 가능)", style = Ty.small, color = t.inkSub)
                Text("캐드 points.dxf — 점·번호·표고 (단위 m, X=동 Y=북)", style = Ty.small, color = t.inkSub)
                Text("점 보고서 point_report.csv — 번호·종류·좌표·표고·정밀도·시각", style = Ty.small, color = t.inkSub)
                Text("구글어스 points.kml — 경위도 + 지면 높이", style = Ty.small, color = t.inkSub)
                Text("Shape points_EPSG.shp/.shx/.dbf/.prj/.cpg — hgis·QGIS용 점 (작업 좌표계 5186/5187 등, 표고 Z, 한글 UTF-8)", style = Ty.small, color = t.inkSub)
                Text("hgis 기준점 hgis_control_points_EPSG.csv — hgis 「CSV 기준점」 양식", style = Ty.small, color = t.inkSub)
                Text("GeoJSON points.geojson — 경위도 + 속성", style = Ty.small, color = t.inkSub)
                Text("README.txt — 파일 설명", style = Ty.small, color = t.inkSub)
            }
        }
        ec.error?.let { ErrorCardView(ErrorCard("-", it, "다시 시도하거나, 계속 안 되면 화면을 캡처해 알려 주세요.", false), "export_error") }
        if (out != null) {
            Text("전송 방식", style = Ty.title, color = t.ink)
            MethodCard(Glyph.SHARE, "무선 전송", "이메일 · 메신저 · 주변 기기 공유", ec.methodShare, "export_method_share") { ec.methodShare = true }
            MethodCard(Glyph.FOLDER, "장치 내 저장", "저장할 폴더를 직접 골라요", !ec.methodShare, "export_method_save") { ec.methodShare = false }
            Text(out.fileName, style = Ty.body, color = t.ink, modifier = Modifier.testTag("export_filename"))
            out.publicFolder?.let { folder ->
                Card {
                    Text("hgis용 폴더: 내장 저장공간/$folder", style = Ty.body, color = t.ink, modifier = Modifier.testTag("export_public_folder"))
                    Text("USB로 PC에 연결해 이 폴더를 PC에 복사한 뒤, 안의 points_EPSG.shp 를 hgis 지도에 끌어 놓으세요. ZIP으로 보냈다면 압축을 먼저 푸세요.",
                        style = Ty.small, color = t.inkSub)
                }
            }
            Text("${ec.formats.size}개 양식 · ${ExportController.sizeText(out.sizeBytes)} · 점 ${out.points}개 · 사진 ${out.photos}장 · 음성 ${out.voices}개", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_result_counts"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Btn("공유하기", Modifier.fillMaxWidth().testTag("export_share")) { ec.share() } }
                Box(Modifier.weight(1f)) { Btn("파일로 저장", Modifier.fillMaxWidth().testTag("export_save")) { ec.saveAs() } }
            }
            Btn("양식 다시 고르기", Modifier.fillMaxWidth().testTag("export_again")) { ec.openScreen() }
            ec.notice?.let { Text(it, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_notice")) }
        }
    }
}

@Composable
private fun MethodCard(g: Glyph, title: String, sub: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(t.raised).border(if (on) 1.5.dp else 1.dp, if (on) t.ink else t.line.copy(alpha = 0.45f), shape)
            .hClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlyphIcon(g, t.ink, 20.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
            Text(sub, style = Ty.small, color = t.inkSub)
        }
        if (on) GlyphIcon(Glyph.CHECK, t.ink, 20.dp)
    }
}
