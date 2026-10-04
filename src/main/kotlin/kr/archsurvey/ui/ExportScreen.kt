package kr.archsurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ExportScreen(ec: ExportController) {
    val t = T
    val s = ec.summary
    val out = ec.output
    ToolScaffold("작업 내보내기", "export_screen", onBack = { ec.close() }, bottom = {
        if (out == null) PrimaryBar(if (ec.running) "만드는 중…" else "ZIP 파일 만들기", "export_run", enabled = !ec.running && (s?.points ?: 0) > 0) { ec.run() }
    }) {
        Card {
            Text("내보낼 내용", style = Ty.label, color = t.ink)
            if (s == null) Text("불러오는 중…", style = Ty.body, color = t.inkSub)
            else {
                Text("작업 ${s.project} · 점 ${s.points}개 · 사진 ${s.photos}장 · 음성 ${s.voices}개", style = Ty.body, color = t.ink, modifier = Modifier.testTag("export_summary"))
                if (s.fakePoints > 0) {
                    Text("가짜 신호로 만든 점이 ${s.fakePoints}개 들어 있어요. 현장 데이터와 섞이지 않게 확인하세요.", style = Ty.small, color = t.bad, modifier = Modifier.testTag("export_fake_warning"))
                }
            }
            CheckRow("사진 포함 (photos/)", ec.includePhotos, "export_photos") { ec.includePhotos = it }
            CheckRow("음성 메모 포함 (voices/)", ec.includeVoices, "export_voices") { ec.includeVoices = it }
        }
        Card {
            Text("ZIP 안의 파일", style = Ty.label, color = t.ink)
            Text("points.csv — 모든 항목 (UTF-8, 엑셀에서 한글 가능)", style = Ty.small, color = t.inkSub)
            Text("points.dxf — CAD용 점·번호·표고 (단위 m, X=동 Y=북)", style = Ty.small, color = t.inkSub)
            Text("points.geojson — 경위도 + 속성", style = Ty.small, color = t.inkSub)
            Text("photos.csv, photos/ · voice.csv, voices/ — 선택한 경우", style = Ty.small, color = t.inkSub)
            Text("README.txt — 파일 설명", style = Ty.small, color = t.inkSub)
        }
        ec.error?.let { ErrorCardView(ErrorCard("-", it, "다시 시도하거나, 계속 안 되면 화면을 캡처해 알려 주세요.", false), "export_error") }
        if (out != null) {
            Card {
                Text("만들었어요", style = Ty.label, color = t.ink)
                Text("${out.fileName}", style = Ty.body, color = t.ink, modifier = Modifier.testTag("export_filename"))
                Text("${ExportController.sizeText(out.sizeBytes)} · 점 ${out.points}개 · 사진 ${out.photos}장 · 음성 ${out.voices}개", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_result_counts"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Btn("공유하기", Modifier.fillMaxWidth().testTag("export_share")) { ec.share() } }
                    Box(Modifier.weight(1f)) { Btn("파일로 저장", Modifier.fillMaxWidth().testTag("export_save")) { ec.saveAs() } }
                }
                Btn("다시 만들기", Modifier.fillMaxWidth().testTag("export_again")) { ec.run() }
                ec.notice?.let { Text(it, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("export_notice")) }
                Text("공유하기: 카카오톡·메일·드라이브 등으로 보내요. 파일로 저장: 폰의 원하는 폴더에 저장해요.", style = Ty.small, color = t.inkSub)
            }
        }
    }
}
