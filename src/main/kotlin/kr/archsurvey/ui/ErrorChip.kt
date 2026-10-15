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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 0.1.46 사용자에게 보이는 오류 문구. 예외 이름·메시지·스택은 진단 로그(CrashLog)에만 남기고 화면에는 문장만.
 */
object UserError {
    /** 기술 정보는 로그로, 화면에는 [what] 한 문장 + [next] 한 문장 */
    fun of(where: String, e: Throwable?, what: String, next: String? = null): String {
        if (e != null) runCatching { android.util.Log.w("ArchSurvey", "handled:$where " + kr.archsurvey.diag.CrashLog.redact(android.util.Log.getStackTraceString(e))) } // 크래시 기록(CrashLog)과 섞지 않음
        return if (next.isNullOrBlank()) what else "$what · $next"
    }

    private val techPattern = Regex("""\(?\b[A-Za-z]+(Exception|Error)\b\)?|\bjava\.[\w.]+|\bat [\w.$]+\(""")

    /** 문구 안에 예외 이름이 섞였으면 걷어낸다(이전 문구 호환) */
    fun scrub(text: String): String = text.replace(techPattern, "").replace(Regex("""\(\s*\)"""), "").replace(Regex("\\s{2,}"), " ").trim()
}

/** 오류 칩: [!] 무엇 · 영향 + [다시]. 흰 카드 + 오류색 테두리(지도 위에서도 읽힘) */
@Composable
fun ErrorChip(text: String, modifier: Modifier = Modifier, tag: String = "error_chip", onRetry: (() -> Unit)? = null, maxLines: Int = 3) {
    val shape = RoundedCornerShape(12.dp)
    // 0.1.50: 지도 맨 위에서는 한 줄로 접고, 누르면 펼침
    var open by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    val lines = if (open) 3 else maxLines
    val msg = UserError.scrub(text)
    Row(
        modifier.clip(shape).background(D.card).border(2.dp, FieldIvory.error, shape).padding(start = 10.dp, end = if (onRetry != null) 4.dp else 10.dp)
            .heightIn(min = 48.dp).then(if (maxLines < 3) Modifier.hClickable(onClickLabel = if (open) "접기" else "펼치기") { open = !open } else Modifier).semantics { contentDescription = "오류: $msg" }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(Glyph.INFO, FieldIvory.error, 22.dp)
        Text(msg, style = DTy.small, color = D.ink, maxLines = lines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp).testTag("${tag}_text"))
        if (onRetry != null) Box(Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).hClickable(onClick = onRetry).testTag("${tag}_retry"), contentAlignment = Alignment.Center) {
            Text("다시", style = DTy.small.copy(fontWeight = FontWeight.Bold), color = FieldIvory.primary)
        }
    }
}
