package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.1.43 사용자 디자인 시안(10-12) 부품. 크림 바탕·흰 카드·검은 굵은 선(표)·테라코타 주 단추·모노 숫자.
 * 색 값은 시안 design.html 에서 뽑은 것(plan.md 「디자인 토큰」).
 */
object D {
    val bg = Color(0xFFFAF9F5)
    val card = Color(0xFFFFFFFF)
    val card2 = Color(0xFFF5F4EF)
    val chip = Color(0xFFF0EEE6)
    val hair = Color(0xFFDEDCD1)
    val line = Color(0xFFC2C0B6)
    val ink = Color(0xFF141413)
    val sub = Color(0xFF5E5D59)
    val weak = Color(0xFF73726C)
    val primary = Color(0xFFB5573A)
    val accent = Color(0xFF9C4A2F)
    val accentLight = Color(0xFFD97757)
    val sel = Color(0xFFE3DACC)
    val fix = Color(0xFF3F6B31)
    val caution = Color(0xFF7A5A00)
    val cautionBg = Color(0xFFF7EFD6)
    val cautionLine = Color(0xFFE2CF94)
    val bad = Color(0xFFA33B3B)
    val dark = Color(0xFF30302E)
}

object DTy {
    val h1 = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
    val h2 = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp)
    val serif = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp)
    val serifM = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyB = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val tiny = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 15.sp)
    val mono = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val monoS = mono.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val monoBig = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    val button = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)
}

/** 굵은 검은 선(표 머리) / 가는 선 */
@Composable fun DRule(thick: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(if (thick) 1.5.dp else 1.dp).background(if (thick) D.ink else D.hair))
}

/** 위 머리줄: ‹ 제목(굵게) … 오른쪽 글자 · 아래 굵은 선 */
@Composable
fun DTopBar(
    title: String, onBack: (() -> Unit)?, backTag: String = "tool_back", close: Boolean = false,
    right: (@Composable RowScope.() -> Unit)? = null, sub: String? = null, titleTag: String = "header_title",
) {
    Column(Modifier.fillMaxWidth().background(D.bg).testTag("app_header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Box(Modifier.size(48.dp).clip(CircleShape).hClickable(onClick = onBack).semantics { contentDescription = if (close) "닫기" else "뒤로" }.testTag(backTag), contentAlignment = Alignment.Center) {
                GlyphIcon(if (close) Glyph.CLOSE else Glyph.BACK, D.ink, 22.dp)
            } else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = DTy.h1, color = D.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(titleTag))
                if (sub != null) Text(sub, style = DTy.tiny, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            right?.invoke(this)
        }
        Box(Modifier.padding(horizontal = 16.dp)) { DRule() }
    }
}

/** 작은 회색 머리 + 오른쪽으로 가는 선(「유적 · 상위 폴더 ────」) */
@Composable fun DSectionLabel(text: String, modifier: Modifier = Modifier, right: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = DTy.small, color = D.sub, maxLines = 1)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).background(D.hair))
        if (right != null) Text(right, style = DTy.tiny, color = D.sub)
    }
}

/** 큰 숫자 칸들(마지막 점 · 오늘 · 폴 / 해발 · 수평·수직 · 폴) */
@Composable
fun DMetrics(cells: List<Triple<String, String, String>>, tagPrefix: String, topRule: Boolean = false, big: Boolean = true) {
    Column {
        if (topRule) DRule(false)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 8.dp)) {
            cells.forEachIndexed { i, (k, v, u) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(D.hair))
                Column(Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 12.dp, end = 6.dp).testTag("${tagPrefix}_$i")) {
                    Text(k, style = DTy.tiny, color = D.sub, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(v, style = if (big) DTy.monoBig else DTy.monoBig.copy(fontSize = 20.sp), color = D.ink, maxLines = 1)
                        if (u.isNotEmpty()) Text(" $u", style = DTy.small, color = D.sub, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
        DRule(false)
    }
}

/** 주 단추(테라코타, 흰 굵은 글자) */
@Composable
fun DPrimary(label: String, tag: String, modifier: Modifier = Modifier, height: Dp = 58.dp, enabled: Boolean = true, color: Color = D.primary, icon: Glyph? = null, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(10.dp)).background(if (enabled) color else D.line).hClickable(enabled = enabled, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { GlyphIcon(icon, Color.White, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(label, style = DTy.button, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 보조 단추(흰 바탕 · 회색 테두리) */
@Composable
fun DSecondary(label: String, tag: String, modifier: Modifier = Modifier, height: Dp = 58.dp, color: Color = D.ink, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp))
            .hCombinedClickable(onLongClick = onLongClick, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = DTy.body.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) }
}

/** 나눔 단추(유구별|날짜별|종류별 · 시굴|표본|정밀발굴 · 5185…) */
@Composable
fun DSeg(options: List<String>, selected: Int, tag: String, height: Dp = 50.dp, subs: List<String>? = null, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(height).clip(shape).background(D.card).border(1.dp, D.line, shape).testTag(tag)) {
        options.forEachIndexed { i, o ->
            if (i > 0 && i != selected && i - 1 != selected) Box(Modifier.width(1.dp).fillMaxHeight().background(D.hair))
            val on = i == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().then(if (on) Modifier.clip(RoundedCornerShape(8.dp)).background(D.sel).border(1.8.dp, D.ink, RoundedCornerShape(8.dp)) else Modifier)
                    .hClickable { onPick(i) }.testTag("${tag}_$i"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(o, style = DTy.body.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), color = D.ink, maxLines = 1)
                subs?.getOrNull(i)?.let { Text(it, style = DTy.tiny.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), color = if (on) D.ink else D.sub, maxLines = 1) }
            }
        }
    }
}

/** 칩(유적 · 유구 고르기). [dashed] = 「+ 새 …」 */
@Composable
fun DChip(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, dashed: Boolean = false, serif: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val m = when {
        selected -> Modifier.clip(shape).background(D.sel).border(1.8.dp, D.ink, shape)
        dashed -> Modifier.clip(shape).background(D.chip.copy(alpha = 0.6f)).drawBehind {
            drawRoundRect(D.line, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
        }
        else -> Modifier.clip(shape).background(D.card).border(1.dp, D.line, shape)
    }
    Row(modifier.heightIn(min = 52.dp).then(m).hClickable(onClick = onClick).padding(horizontal = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (serif) DTy.serifM.copy(fontSize = 18.sp) else DTy.body, color = D.ink, maxLines = 1)
        if (sub != null) Text(" $sub", style = DTy.tiny, color = D.sub, maxLines = 1)
    }
}

/** 아래 고정 단추 줄(위 가는 선) */
@Composable
fun DBottomBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(D.bg)) {
        DRule(false)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 표 머리(작은 회색 글자, 아래 가는 선) */
@Composable
fun DTableHead(cols: List<Pair<String, Float>>, alignEndFrom: Int = 1) {
    Column {
        DRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
            cols.forEachIndexed { i, (s, w) -> Text(s, style = DTy.tiny, color = D.sub, textAlign = if (i >= alignEndFrom) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(w)) }
        }
        DRule(false)
    }
}

/** 표 한 줄: [cells] = (글자, 무게, 모노?) / 첫 칸 아래 작은 글자 [sub] */
@Composable
fun DTableRow(
    cells: List<Pair<String, Float>>, tag: String, selected: Boolean = false, sub: String? = null, subColor: Color = D.sub,
    firstStyle: TextStyle = DTy.body, monoFrom: Int = 1, alignEndFrom: Int = 1, colors: Map<Int, Color> = emptyMap(),
    onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (selected) D.sel else Color.Transparent)
                .then(if (onClick != null || onLongClick != null) Modifier.hCombinedClickable(onLongClick = onLongClick) { onClick?.invoke() } else Modifier)
                .padding(horizontal = 4.dp, vertical = 8.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { i, (s, w) ->
                if (i == 0) Column(Modifier.weight(w)) {
                    Text(s, style = firstStyle, color = D.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub != null) Text(sub, style = DTy.tiny, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Text(s, style = if (i >= monoFrom) DTy.mono else DTy.body, color = colors[i] ?: D.ink, maxLines = 1, textAlign = if (i >= alignEndFrom) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(w))
            }
        }
        DRule(false)
    }
}

/** 설정 한 줄: 이름 ···· 값 › (점선 이음) 또는 켬/끔 알약 */
@Composable
fun DLeaderRow(label: String, value: String?, tag: String, toggle: Boolean? = null, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable(onClick = onClick).padding(vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = DTy.body, color = D.ink, maxLines = 1)
            Canvas(Modifier.weight(1f).height(2.dp).padding(horizontal = 8.dp)) {
                drawLine(D.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
            }
            if (toggle != null) DTogglePill(toggle, "${tag}_switch")
            else {
                Text(value ?: "", style = if (value?.any { it.isDigit() } == true) DTy.mono else DTy.body, color = D.ink, maxLines = 1, modifier = Modifier.testTag("${tag}_value"))
                Spacer(Modifier.width(6.dp)); GlyphIcon(Glyph.CHEVRON_RIGHT, D.sub, 12.dp)
            }
        }
        DRule(false)
    }
}

/** 켬/끔 알약(시안: 「켬」 = 베이지 바탕, 「끔」 = 흰 바탕) */
@Composable fun DTogglePill(on: Boolean, tag: String) {
    Box(
        Modifier.height(36.dp).widthIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(if (on) D.sel else D.card).border(1.dp, D.line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(if (on) "켬" else "끔", style = DTy.small.copy(fontWeight = FontWeight.Medium), color = D.ink) }
}

/** 상태 알약(시안 머리줄 오른쪽): ● FIX 1.4 cm / ○ 연결 안 됨 */
@Composable
fun DStatusChip(text: String, sub: String?, dot: Color?, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp)).hClickable(onClick = onClick)
            .padding(horizontal = 12.dp).semantics { contentDescription = text + (sub?.let { " $it" } ?: "") }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        else Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, D.ink, CircleShape))
        Text(text, style = DTy.small.copy(fontWeight = FontWeight.Medium), color = D.ink, maxLines = 1, modifier = Modifier.testTag("pill_fix"))
        if (sub != null) Text(sub, style = DTy.mono, color = D.ink, maxLines = 1, modifier = Modifier.testTag("pill_sigma"))
    }
}

/** 작은 네모 아이콘 + 아래 글자(홈 바로 가기 · 측량 화면 되돌리기/면 닫기/사진) */
@Composable
fun DIconTile(g: Glyph, label: String, tag: String, modifier: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(10.dp)).hClickable(onClickLabel = label, onClick = onClick).padding(vertical = 4.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) D.sel else D.chip).then(if (on) Modifier.border(1.5.dp, D.ink, RoundedCornerShape(10.dp)) else Modifier), contentAlignment = Alignment.Center) { GlyphIcon(g, D.ink, 20.dp) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = DTy.tiny.copy(fontWeight = FontWeight.Medium), color = D.ink, maxLines = 1)
    }
}

/** 화면 맨 아래 상태줄(작은 글자, 세로 칸막이) */
@Composable
fun DFootLine(parts: List<String>, tag: String, weights: List<Float>? = null) {
    Column(Modifier.fillMaxWidth().background(D.bg)) {
        DRule(false)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 7.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            parts.forEachIndexed { i, s ->
                if (i > 0) Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(16.dp).background(D.line))
                Text(s, style = if (i == 0) DTy.tiny else DTy.monoS, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (weights != null) Modifier.weight(weights[i]) else if (i == 0) Modifier.weight(1f) else Modifier)
            }
        }
    }
}
