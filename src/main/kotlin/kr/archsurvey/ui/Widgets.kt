package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 전체 화면 도구 화면 공통 틀: 상단 제목 + 뒤로 + 스크롤 본문 + (선택) 하단 고정 영역 */
@Composable
fun ToolScaffold(title: String, tag: String, onBack: () -> Unit, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = T
    Column(Modifier.fillMaxSize().background(t.bg).testTag(tag)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MBtn(Modifier.size(48.dp).testTag("tool_back"), MKind.PLAIN, 20.dp, onClick = onBack) { Box(Modifier.rotate(-90f)) { GlyphIcon(Glyph.CHEVRON_UP, t.accent, 20.dp) } }
            Text(title, style = Ty.title.copy(fontSize = 20.sp), color = t.ink, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        bottom?.invoke()
    }
}

/** 하단 주 버튼 (테라코타) */
@Composable
fun PrimaryBar(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = T
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).glossy(if (enabled) t.primary else t.line, enabled)
                .clickable(enabled = enabled, onClick = onClick).testTag(tag),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = Ty.button.copy(fontSize = 18.sp), color = if (enabled) t.onPrimary else t.inkSub) }
    }
}

/** 체크박스 한 줄 (높이 48dp 이상) */
@Composable
fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked, { onChange(it) })
        Text(label, style = Ty.body, color = t.ink)
    }
}

/** 간격 척도(dp): 모든 새 화면은 4의 배수만 쓴다 */
object Sp { const val X1 = 4; const val X2 = 8; const val X3 = 12; const val X4 = 16; const val X6 = 24 }

/** 접힌 선택 줄: 왼쪽 작은 이름 + 현재 값 + 펼침 화살표(48dp 이상). 눌러서 아래 목록을 펼친다 */
@Composable
fun SelectRow(label: String, value: String, open: Boolean, tag: String, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(t.raised).border(1.dp, t.outline, shape).clickable(onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.widthIn(min = 72.dp))
        Text(value, style = Ty.label, color = t.ink, modifier = Modifier.weight(1f), maxLines = 1)
        Box(Modifier.rotate(if (open) 0f else 180f)) { GlyphIcon(Glyph.CHEVRON_UP, t.accent, 20.dp) }
    }
}

/** 접는 글자 줄(도움말·자세히): 48dp 이상 */
@Composable
fun FoldRow(text: String, open: Boolean, tag: String, onClick: () -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = Ty.label, color = t.accentText, modifier = Modifier.weight(1f))
        Box(Modifier.rotate(if (open) 0f else 180f)) { GlyphIcon(Glyph.CHEVRON_UP, t.accent, 16.dp) }
    }
}
