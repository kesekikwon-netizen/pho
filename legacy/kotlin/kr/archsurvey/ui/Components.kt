package kr.archsurvey.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.archsurvey.core.FixQuality

/** 큰 버튼 (높이 64dp: 장갑 터치) */
@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    container: Color = T.primary, content: Color = T.onPrimary, height: Dp = 64.dp, sub: String? = null,
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = T.surfaceVar, disabledContentColor = T.inkSub),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = Ty.title)
            if (sub != null) Text(sub, style = Ty.small)
        }
    }
}

@Composable
fun OutlineBigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 56.dp) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, T.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = T.ink),
    ) { Text(text, style = Ty.bodyB) }
}

/** 작은 정사각 +/- 버튼 */
@Composable
fun StepButton(label: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(T.surfaceVar).clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ty.title, color = T.ink) }
}

@Composable
fun Card(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)) {
        if (accent != null) Box(Modifier.width(8.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(16.dp).weight(1f), content = content)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) T.primary else T.surfaceVar
    val fg = if (selected) T.onPrimary else T.ink
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) { Text(text, style = Ty.bodyB, color = fg) }
}

/** Fix 상태 배지: 색 + 글자 + 기호 (색만으로 구분하지 않음) */
@Composable
fun FixBadge(q: FixQuality, modifier: Modifier = Modifier) {
    val (bg, fg, sym) = when (q) {
        FixQuality.RTK_FIXED -> Triple(T.fixed, T.onFixed, "✓")
        FixQuality.RTK_FLOAT -> Triple(T.float, T.onFloat, "△")
        FixQuality.DGPS, FixQuality.SINGLE, FixQuality.PPS -> Triple(T.single, T.onSingle, "!")
        else -> Triple(T.none, Color.White, "–")
    }
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(sym, style = Ty.title, color = fg); Spacer(Modifier.width(8.dp))
        Text(q.short, style = Ty.title, color = fg)
    }
}

@Composable
fun StatusBanner(text: String, kind: Kind, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (bg, fg) = when (kind) {
        Kind.OK -> T.okCont to T.okText
        Kind.WARN -> T.floatCont to T.floatText
        Kind.ERROR -> T.singleCont to T.singleText
        Kind.INFO -> T.surfaceVar to T.inkSub
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp)) {
        Text(text, style = Ty.body, color = fg)
    }
}

enum class Kind { OK, WARN, ERROR, INFO }

@Composable
fun SectionTitle(text: String) { Text(text, style = Ty.title, color = T.ink, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.body, modifier = Modifier.weight(1f), color = T.ink)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun BigDialog(title: String, onDismiss: (() -> Unit)?, confirmText: String, onConfirm: () -> Unit,
              dismissText: String? = null, onDismissBtn: (() -> Unit)? = null, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(title, style = Ty.title) },
        text = { Column { body() } },
        confirmButton = { BigButton(confirmText, onConfirm, height = 56.dp) },
        dismissButton = if (dismissText != null) { { OutlineBigButton(dismissText, { onDismissBtn?.invoke() ?: onDismiss?.invoke() }) } } else null,
        containerColor = T.surface,
    )
}

fun fmt(v: Double?, d: Int = 3): String = if (v == null) "—" else String.format(java.util.Locale.US, "%,.${d}f", v)
fun fmtPlain(v: Double?, d: Int): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.${d}f", v)

@Composable
fun CenterText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Ty.body, color = T.inkSub, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(24.dp))
}
