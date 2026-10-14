package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 0.1.46 시안(mockups-v2) 공용 부품: 선택 칸 격자 · 섹션 머리 · 흰 카드 */
data class GridItem(val label: String, val tag: String, val selected: Boolean = false, val span: Int = 1, val onClick: () -> Unit)

/** 한 손가락 선택 칸: 56dp 이상, 선택 = 연테라코타 바탕 + 테라코타 테두리·글자 */
@Composable
fun ChoiceTile(label: String, tag: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sh = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = FieldIvory.minTouch).clip(sh).background(if (selected) FieldIvory.primarySoft else D.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) FieldIvory.primary else D.line, sh)
            .hClickable(onClick = onClick).semantics { this.selected = selected; contentDescription = label }.padding(horizontal = 6.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = DTy.bodyB, color = if (selected) FieldIvory.primary else D.ink, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
}

/** 칸 격자(기본 3열). span 으로 넓은 칸 */
@Composable
fun ChoiceGrid(items: List<GridItem>, columns: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldIvory.gap)) {
        var row = mutableListOf<GridItem>(); var used = 0
        val rows = mutableListOf<List<GridItem>>()
        for (it in items) { if (used + it.span > columns) { rows += row; row = mutableListOf(); used = 0 }; row += it; used += it.span }
        if (row.isNotEmpty()) rows += row
        for (r in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FieldIvory.gap)) {
            for (it in r) ChoiceTile(it.label, it.tag, it.selected, Modifier.weight(it.span.toFloat())) { it.onClick() }
            val rest = columns - r.sumOf { it.span }
            if (rest > 0 && rows.size > 1) Spacer(Modifier.weight(rest.toFloat()))
        }
    }
}

@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.sub, modifier = modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val sh = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(sh).background(D.card).border(1.dp, D.hair, sh).then(if (tag != null) Modifier.testTag(tag) else Modifier), content = content)
}
