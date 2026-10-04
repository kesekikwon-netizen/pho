package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Link { POINT, LINE, AREA }

/**
 * 측량 설정 시트 (목업 s15). 측점 버튼을 길게 누르면 열린다.
 * 점 이름은 번호가 자동으로 이어지므로 보기만 한다. 비고는 다음 측점 하나에만 붙는다.
 * 연결 방식: 점만 / 선(외곽을 열린 선으로) / 면(외곽을 닫아 면으로). 선·면은 외곽 취득을 켜고 위치 종류를 ‘유구 경계점’으로 맞춘다.
 */
@Composable
fun SetupSheet(c: SurveyController) {
    val t = T
    var memo by remember { mutableStateOf(c.pendingMemo) }
    var typeIdx by remember { mutableStateOf(c.typeIndex) }
    var link by remember { mutableStateOf(if (!c.arch.outlineMode) Link.POINT else if (c.arch.preferClosed) Link.AREA else Link.LINE) }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).clickable { c.setupOpen = false }.testTag("setup_scrim"))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().shadow(8.dp, shape).clip(shape).glossy(t.card).border(1.5.dp, t.outline, shape)
                .clickable(enabled = false) {}.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).testTag("setup_sheet"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("측량 설정", style = Ty.title, color = t.ink, modifier = Modifier.height(32.dp).testTag("setup_title"))
            FieldRow("점 이름") { Text(c.nextName, style = Ty.coord.copy(fontSize = 17.6.sp), color = t.ink, modifier = Modifier.testTag("setup_name")) }
            // 위치 종류: 앞의 두 개 + 더보기(= 하나씩 넘기기)
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = (0 until c.types.size).toList().let { all ->
                    val first = all.take(2).toMutableList(); if (typeIdx !in first && typeIdx in all) { first[1] = typeIdx }; first
                }
                for ((k, i) in shown.withIndex()) {
                    val on = i == typeIdx
                    MBtn(Modifier.weight(if (k == 0) 1.45f else 1.5f).fillMaxHeight().testTag("setup_type_$i"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { typeIdx = i }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (on) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
                            Text(c.types[i], style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                MBtn(Modifier.weight(0.8f).fillMaxHeight().testTag("setup_type_next"), MKind.PLAIN, 16.dp, onClick = { if (c.types.isNotEmpty()) typeIdx = (typeIdx + 1) % c.types.size }) {
                    Text("더보기", style = Ty.label, color = t.ink, maxLines = 1)
                }
            }
            FieldRow("비고") {
                BasicTextField(
                    memo, { if (it.length <= 60) memo = it.replace("\n", " ") }, singleLine = true, textStyle = Ty.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.accent), modifier = Modifier.fillMaxWidth().testTag("setup_memo"),
                    decorationBox = { inner -> if (memo.isEmpty()) Text("한 줄 입력", style = Ty.small.copy(fontSize = 14.1.sp), color = t.inkSub); inner() },
                )
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, t.outline, RoundedCornerShape(16.dp)).background(t.bg)
                    .clickable { c.setupOpen = false; c.antennaSheetOpen = true }.padding(horizontal = 14.dp).testTag("setup_antenna"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("안테나 높이", style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
                Text(f3(c.antenna.inputValueM), style = Ty.coord.copy(fontSize = 17.6.sp), color = t.ink)
                Text("m", style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f))
                Box(Modifier.rotate(-90f)) { GlyphIcon(Glyph.CHEVRON_DOWN, t.outline, 17.dp) }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((l, label) in listOf(Link.POINT to "점만", Link.LINE to "선 연결", Link.AREA to "면 연결")) {
                    val on = link == l
                    MBtn(Modifier.weight(1f).fillMaxHeight().testTag("setup_link_${l.name.lowercase()}"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { link = l }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (on) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
                            Text(label, style = Ty.label, color = t.ink, maxLines = 1)
                        }
                    }
                }
            }
            if (link != Link.POINT) Text("선·면은 외곽 취득을 켜고 위치 종류를 ‘유구 경계점’으로 맞춰요", style = Ty.small.copy(fontSize = 11.4.sp), color = t.inkSub, modifier = Modifier.testTag("setup_link_note"))
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MBtn(Modifier.weight(1f).widthIn(max = 224.dp).fillMaxHeight().testTag("setup_apply"), MKind.PRIMARY, 18.dp, onClick = {
                    c.pendingMemo = memo.trim()
                    c.typeIndex = typeIdx
                    val wantOutline = link != Link.POINT
                    c.arch.preferClosed = link == Link.AREA
                    if (wantOutline != c.arch.outlineMode) c.toggleOutlineMode()
                    c.setupOpen = false
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlyphIcon(Glyph.CHECK, t.ink, 22.dp); Text("적용", style = Ty.button, color = t.onPrimary)
                    }
                }
                MBtn(Modifier.width(64.dp).height(48.dp).testTag("setup_cancel"), MKind.PLAIN, 14.dp, onClick = { c.setupOpen = false }) {
                    GlyphIcon(Glyph.CLOSE, t.accent, 16.dp); Text("취소", style = Ty.label, color = t.ink, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    val t = T
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, t.outline, RoundedCornerShape(16.dp)).background(t.bg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}
