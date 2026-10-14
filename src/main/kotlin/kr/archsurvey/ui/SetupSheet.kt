package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var newLink by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).hClickable { c.setupOpen = false }.testTag("setup_scrim"))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp).shadow(1.dp, shape).clip(shape).glossy(t.card).border(1.5.dp, t.outline, shape)
                .hClickable(enabled = false) {}.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).testTag("setup_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("측량 설정", style = Ty.title, color = t.ink, modifier = Modifier.height(32.dp).testTag("setup_title"))
            FieldRow("점 이름") { Text(c.nextName, style = Ty.coord.copy(fontSize = 18.sp), color = t.ink, modifier = Modifier.testTag("setup_name")) }
            // 목업 16 「코드 / 심볼」 = 이 앱의 위치 종류(발굴 용어)
            PickField("코드 / 심볼", c.types.getOrElse(typeIdx) { "없음" }, "setup_code") {
                c.ui.choose(ChoiceRequest("코드 / 심볼 (위치 종류)", c.types, typeIdx, "setup_type") { typeIdx = it })
            }
            QuickNoteChips(memo, "setup_note") { if (it.length <= 60) memo = it } // 0.1.39 한 손가락 비고
            FieldRow("비고") {
                BasicTextField(
                    memo, { if (it.length <= 60) memo = it.replace("\n", " ") }, singleLine = true, textStyle = Ty.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.accent), modifier = Modifier.fillMaxWidth().testTag("setup_memo"),
                    decorationBox = { inner -> if (memo.isEmpty()) Text("메모 입력 (다음 점 하나에만)", style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub); inner() },
                )
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, t.outline, RoundedCornerShape(16.dp)).background(t.bg)
                    .hClickable { c.setupOpen = false; c.antennaSheetOpen = true }.padding(horizontal = 14.dp).testTag("setup_antenna"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("안테나 높이", style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
                Text(f3(c.antenna.inputValueM), style = Ty.coord.copy(fontSize = 18.sp), color = t.ink)
                Text("m", style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f))
                Box(Modifier.rotate(-90f)) { GlyphIcon(Glyph.CHEVRON_DOWN, t.outline, 17.dp) }
            }
            // 목업 16 「높이 지점」 = 안테나 측정 기준점
            val model = runCatching { c.antennaModel }.getOrNull()
            val refs = model?.visibleRefs.orEmpty()
            PickField("높이 지점", model?.ref(c.antenna.refPointId)?.labelKo ?: "—", "setup_ref") {
                if (refs.isEmpty()) c.ui.fail("이 안테나는 높이 지점을 고를 수 없어요")
                else c.ui.choose(ChoiceRequest("높이 지점", refs.map { it.labelKo }, refs.indexOfFirst { it.id == c.antenna.refPointId }.coerceAtLeast(0), "setup_refpick") { k ->
                    c.setRefPoint(refs[k].id); c.ui.ok("높이 지점 · ${refs[k].labelKo}")
                })
            }
            // 목업 16 「자동 결선」: 켜면 다음 점부터 선으로 잇기(외곽 취득)
            Row(Modifier.fillMaxWidth().height(48.dp).hClickable { link = if (link == Link.POINT) Link.LINE else Link.POINT }.padding(horizontal = 4.dp).testTag("setup_autolink"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("자동 결선", style = Ty.body, color = t.ink, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(link != Link.POINT, { on -> link = if (on) (if (link == Link.POINT) Link.LINE else link) else Link.POINT }, colors = blackSwitch())
            }
            @Composable fun LinkBtn(label: String, tag: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
                MBtn(modifier.fillMaxHeight().testTag(tag), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = onClick) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (on) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
                        Text(label, style = Ty.label, color = t.ink, maxLines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkBtn("선으로 연결", "setup_link_line", link == Link.LINE, Modifier.weight(1f)) { link = Link.LINE }
                LinkBtn("면으로 연결", "setup_link_area", link == Link.AREA, Modifier.weight(1f)) { link = Link.AREA }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkBtn("점만", "setup_link_point", link == Link.POINT, Modifier.weight(1f)) { link = Link.POINT; newLink = false }
                LinkBtn("새 연결 시작", "setup_new_link", newLink, Modifier.weight(1f)) { newLink = !newLink; if (newLink && link == Link.POINT) link = Link.LINE }
            }
            if (link != Link.POINT) Text("선·면은 외곽 취득을 켜고 위치 종류를 ‘유구 경계점’으로 맞춰요" + if (newLink) " · 앞 외곽은 마무리해요" else "", style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.testTag("setup_link_note"))
            // 목업 16 「경사센서 사용」: 이 앱은 폰 기포(참고용, 좌표 미보정). 수신기 IMU 아님
            val tc = c.tilt
            val b = tc?.bubble
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).hClickable { c.setupOpen = false; c.hub.open(ToolScreen.IMU) }.padding(horizontal = 4.dp).testTag("setup_tilt"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("폰 기포 (참고)", style = Ty.small, color = t.inkSub, modifier = Modifier.weight(1f))
                val txt = when {
                    tc == null || !tc.available -> "센서 없음"
                    !tc.enabled -> "꺼짐 · 눌러서 설정"
                    b == null -> "읽는 중…"
                    else -> "X " + signedDms(Math.toDegrees(kotlin.math.asin(b.x.coerceIn(-1.0, 1.0)))) + " · Y " + signedDms(Math.toDegrees(kotlin.math.asin(b.y.coerceIn(-1.0, 1.0))))
                }
                Text(txt, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, maxLines = 1)
            }
          }
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MBtn(Modifier.weight(1f).fillMaxHeight().testTag("setup_cancel"), MKind.PLAIN, 28.dp, onClick = { c.setupOpen = false }) {
                    Text("취소", style = Ty.label, color = t.ink, maxLines = 1)
                }
                MBtn(Modifier.weight(1f).fillMaxHeight().testTag("setup_apply"), MKind.PRIMARY, 28.dp, onClick = {
                    c.pendingMemo = memo.trim()
                    c.typeIndex = typeIdx
                    val wantOutline = link != Link.POINT
                    c.arch.preferClosed = link == Link.AREA
                    if (wantOutline != c.arch.outlineMode) c.toggleOutlineMode()
                    else if (wantOutline && newLink) c.startNewLink()
                    c.setupOpen = false
                    c.ui.ok("측량 설정을 적용했어요 · 측점 버튼을 누르면 측량해요")
                }) {
                    Text("▷ 시작", style = Ty.button, color = t.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun PickField(label: String, value: String, tag: String, onClick: () -> Unit) {
    val t = T
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, t.outline, RoundedCornerShape(16.dp)).background(t.bg)
            .hClickable(onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
        Text(value, style = Ty.body, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        GlyphIcon(Glyph.CHEVRON_DOWN, t.outline, 17.dp)
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    val t = T
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, t.outline, RoundedCornerShape(16.dp)).background(t.bg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}
