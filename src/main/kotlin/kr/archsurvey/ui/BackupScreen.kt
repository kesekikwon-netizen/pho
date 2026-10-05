package kr.archsurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(ms))

@Composable
fun BackupScreen(bc: BackupController) {
    val t = T
    ToolScaffold("데이터 백업 · 복구", "backup_screen", onBack = { bc.close() }, bottom = {
        PrimaryBar(if (bc.running) "처리 중…" else "지금 백업 만들기", "backup_create", enabled = !bc.running && !bc.restarting) { bc.create() }
    }) {
        Card {
            Text("점은 측량하는 즉시 폰에 저장돼요(자동 저장). 이 화면의 백업은 폰을 바꾸거나 앱을 지우기 전에 한 번 더 안전하게 보관하는 용도예요.", style = Ty.small, color = t.inkSub)
            Text("백업 파일(ZIP)에는 점·사진·음성이 모두 들어가요. 공유하기/파일로 저장으로 폰 밖에도 꼭 보관하세요.", style = Ty.small, color = t.inkSub)
        }
        bc.notice?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.testTag("backup_notice")) }
        bc.error?.let { ErrorCardView(ErrorCard("-", it, "파일을 다시 확인하거나, 계속 안 되면 화면을 캡처해 알려 주세요.", false), "backup_error") }
        Card {
            Text("보관된 백업", style = Ty.label, color = t.ink)
            if (bc.items.isEmpty()) Text("아직 백업이 없어요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("backup_empty"))
            for ((i, b) in bc.items.withIndex()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("backup_item_$i"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.name, style = Ty.body, color = t.ink)
                    Text("${whenText(b.createdAtMs)} · 점 ${b.points} · 사진 ${b.photos} · 음성 ${b.voices} · ${ExportController.sizeText(b.sizeBytes)}", style = Ty.small, color = t.inkSub)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Btn("복구", Modifier.fillMaxWidth().testTag("backup_restore_$i")) { bc.askRestore(b) } }
                        Box(Modifier.weight(1f)) { Btn("공유", Modifier.fillMaxWidth().testTag("backup_share_$i")) { bc.share(b) } }
                        Box(Modifier.weight(1f)) { Btn("저장", Modifier.fillMaxWidth().testTag("backup_save_$i")) { bc.saveAs(b) } }
                        Box(Modifier.weight(1f)) { Btn("삭제", Modifier.fillMaxWidth().testTag("backup_delete_$i")) { bc.askDelete(b) } }
                    }
                }
            }
        }
        Card {
            Text("다른 곳의 백업으로 복구", style = Ty.label, color = t.ink)
            Text("폰에 저장해 둔 백업 ZIP을 골라 복구해요.", style = Ty.small, color = t.inkSub)
            Btn("백업 파일 고르기", Modifier.fillMaxWidth().testTag("backup_pick")) { bc.pickFile() }
        }
        bc.confirmRestore?.let { b ->
            ConfirmDialog(
                "이 백업으로 복구할까요?",
                "지금 폰의 점·사진·음성이 이 백업(${whenText(b.createdAtMs)}, 점 ${b.points}개)으로 바뀌어요. 바꾸기 전에 지금 상태를 ‘복구 직전’ 백업으로 먼저 보관하고, 끝나면 앱이 다시 시작돼요.",
                "복구하기", { bc.restore() }, { bc.confirmRestore = null },
            )
        }
        bc.confirmDelete?.let { b ->
            ConfirmDialog("백업 파일을 지울까요?", "${b.name} 을(를) 폰에서 지워요. 되돌릴 수 없어요.", "지우기", { bc.delete() }, { bc.confirmDelete = null })
        }
    }
}
