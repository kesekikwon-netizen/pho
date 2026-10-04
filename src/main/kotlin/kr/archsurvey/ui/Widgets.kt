package kr.archsurvey.ui

import androidx.compose.foundation.background
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
            Text(title, style = Ty.title.copy(fontSize = 21.sp), color = t.ink, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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
