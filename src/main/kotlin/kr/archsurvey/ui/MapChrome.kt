package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.*

/** 목업(v2/simple) 버튼 3종: 일반(#FDFCF9 + 회색 테두리), 선택(#F7E4DB + 테라코타 2.5dp 테두리), 주(#D97757). 모두 은은한 글로시와 그림자. */
internal enum class MKind { PLAIN, SELECTED, PRIMARY }

@Composable
internal fun MBtn(
    modifier: Modifier, kind: MKind = MKind.PLAIN, radius: Dp = 16.dp, alpha: Float = 1f,
    onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val t = T; val shape = RoundedCornerShape(radius)
    val bgc = when (kind) { MKind.PRIMARY -> t.primary; MKind.SELECTED -> t.primaryCont; else -> t.raised }
    val bm = when (kind) {
        MKind.PRIMARY -> Modifier
        MKind.SELECTED -> Modifier.border(2.5.dp, t.accent, shape)
        else -> Modifier.border(1.5.dp, t.outline, shape)
    }
    Column(
        modifier.alpha(alpha).shadow(3.dp, shape).clip(shape).glossy(bgc).then(bm).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
    )
}

/** 1234567.891 -> "1 234 567.891" (목업의 좌표 표기) */
internal fun grp3(x: Double): String {
    val s = String.format(java.util.Locale.US, "%.3f", Math.abs(x))
    val (i, f) = s.split(".").let { it[0] to it[1] }
    val g = i.reversed().chunked(3).joinToString(" ").reversed()
    return (if (x < 0) "-" else "") + g + "." + f
}

private val MonoLight = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Light)

@Composable
internal fun StatusBand(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val st = c.gv.status
    val (bg, fg) = statusColors(t, st.status)
    val notConnected = st.reason == StatusReason.NOT_CONNECTED
    val main = if (notConnected) "불가 · 수신기 연결 안 됨" else when (st.status) {
        SurveyStatus.FIXED -> "측량 가능"
        SurveyStatus.CAUTION -> "주의 · 저장 시 표시 기록"
        SurveyStatus.UNAVAILABLE -> "불가 · 측점 막힘"
    }
    val sub = if (notConnected) "블루투스로 R10 / SP60 을 연결하세요" else st.reason.textKo
    val detail = if (notConnected) null else bandDetail(c)
    val sigma = if (notConnected) null else st.sigmaHM
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().height(68.dp).shadow(4.dp, shape).clip(shape).glossy(bg).testTag("status_band"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp).fillMaxHeight().clickable { c.drawerOpen = true }.testTag("gear"), contentAlignment = Alignment.Center) { GearIcon(fg, 22.dp) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(fg.copy(alpha = 0.45f)))
        Spacer(Modifier.width(10.dp))
        StatusIcon(st.status, fg, 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(main, style = Ty.bandMain, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_main"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(sub, style = Ty.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("status_sub"))
                if (sigma != null) Text("±" + f3(sigma) + " m", style = MonoLight.copy(fontSize = 13.sp), color = fg, maxLines = 1, modifier = Modifier.padding(start = 6.dp).testTag("status_sigma"))
            }
            if (detail != null) Text(detail, style = Ty.small.copy(fontSize = 11.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_detail"))
        }
    }
}

@Composable
internal fun TabsRow(c: SurveyController) {
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MBtn(Modifier.weight(1.3f).fillMaxHeight().testTag("tab_survey"), MKind.SELECTED, 20.dp, onClick = {}) { Text("측량+지도", style = Ty.label, color = T.ink) }
        MBtn(Modifier.weight(1f).fillMaxHeight().testTag("tab_points"), MKind.PLAIN, 20.dp, onClick = { c.hub.open(ToolScreen.POINTS) }) { Text("점 목록", style = Ty.label, color = T.ink) }
    }
}

@Composable
internal fun CoordBox(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val lat = c.gv.lat; val lon = c.gv.lon
    val p = if (lat != null && lon != null) TmProjection.forward(lat, lon) else null
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.widthIn(max = 226.dp).fillMaxWidth().clip(shape).background(t.raised.copy(alpha = 0.86f)).border(1.dp, t.outline, shape).testTag("coord_line")) {
        val rows = listOf("N" to p?.northing?.let { grp3(it) }, "E" to p?.easting?.let { grp3(it) }, "H" to c.gv.altM?.let { grp3(it) })
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = Ty.label.copy(fontSize = 17.6.sp), color = t.ink, modifier = Modifier.width(18.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                    Text(v ?: "—", style = MonoLight.copy(fontSize = 22.sp, lineHeight = 26.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("coord_$k"))
                    Text(" m", style = Ty.small.copy(fontSize = 11.4.sp), color = t.inkSub, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }
    }
}

@Composable
internal fun ToolBtn(g: Glyph, desc: String, tag: String, kind: MKind = MKind.PLAIN, onClick: () -> Unit) {
    MBtn(Modifier.size(48.dp).testTag(tag), kind, 16.dp, onClick = onClick) { GlyphIcon(g, if (kind == MKind.PRIMARY) T.ink else T.accent, 22.dp) }
}

@Composable
internal fun ControlArea(c: SurveyController) {
    val t = T
    val busy = c.phase == Phase.ACQUIRING
    val blocked = c.gv.status.status == SurveyStatus.UNAVAILABLE
    val notConn = c.gv.status.reason == StatusReason.NOT_CONNECTED
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        Modifier.fillMaxWidth().height(SurveyDims.CONTROL_AREA_DP.dp).shadow(6.dp, shape).clip(shape).glossy(t.card, strength = 0.30f)
            .border(1.5.dp, t.outline.copy(alpha = 0.9f), shape).padding(start = 12.dp, end = 12.dp, top = SurveyDims.CTRL_PAD_TOP_DP.dp, bottom = SurveyDims.CTRL_PAD_BOTTOM_DP.dp)
            .testTag("control_area"),
    ) {
        // 위치 종류 알약 두 개 + 더보기
        val sel = c.typeIndex.coerceIn(0, (c.types.size - 1).coerceAtLeast(0))
        val base = listOf(0, 1).filter { it < c.types.size }
        val shown = if (sel in base || base.isEmpty()) base else listOf(base.first(), sel)
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEachIndexed { k, i ->
                val on = i == sel
                MBtn(Modifier.weight(if (k == 0) 1.45f else 1.5f).fillMaxHeight().testTag(if (on) "type_chip" else "type_chip_$i"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { c.typeIndex = i }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (on) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
                        Text(c.types[i], style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            MBtn(Modifier.weight(0.8f).fillMaxHeight().testTag("type_more"), MKind.PLAIN, 16.dp, onClick = { c.quickSheetOpen = true }) { Text("더보기", style = Ty.label, color = t.ink, maxLines = 1) }
        }
        Spacer(Modifier.height(6.dp))
        // 측점 72dp + 사진
        val measure: @Composable RowScope.() -> Unit = {
            MBtn(
                Modifier.weight(1f, fill = false).widthIn(max = 224.dp).fillMaxWidth().height(SurveyDims.MEASURE_BUTTON_DP.dp).testTag("measure_button"),
                MKind.PRIMARY, 22.dp, alpha = if (busy || blocked) 0.5f else 1f, onClick = { c.onMeasure() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlyphIcon(Glyph.PIN, t.ink, 28.dp)
                    Column {
                        if (notConn) {
                            Text("측점 불가", style = Ty.button.copy(fontSize = 24.sp), color = t.onPrimary)
                            Text("수신기 연결 안 됨", style = Ty.small.copy(fontSize = 13.sp), color = t.onPrimary, modifier = Modifier.testTag("measure_disabled_reason"))
                        } else {
                            Text("측점", style = Ty.button.copy(fontSize = 35.sp, letterSpacing = 3.sp, lineHeight = 38.sp), color = t.onPrimary)
                            Text(c.nextName + " 취득", style = Ty.small.copy(fontSize = 14.sp), color = t.onPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
        val photo: @Composable RowScope.() -> Unit = {
            MBtn(Modifier.width(52.dp).height(72.dp).testTag("photo_button"), MKind.SELECTED, 18.dp, alpha = if (c.lastPoint == null) 0.5f else 1f, onClick = { c.addPhotoCamera() }) {
                GlyphIcon(Glyph.CAMERA, t.accentText, 22.dp); Spacer(Modifier.height(3.dp)); Text("사진", style = Ty.label, color = t.ink)
            }
        }
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.poleOnRight) { measure(); photo() } else { photo(); measure() }
        }
        Spacer(Modifier.height(8.dp))
        val small: @Composable (Glyph, String, String, Float, () -> Unit) -> Unit = { g, label, tag, a, f ->
            MBtn(Modifier.width(90.dp).height(52.dp).testTag(tag), MKind.PLAIN, 16.dp, alpha = a, onClick = f) {
                GlyphIcon(g, t.accent, 20.dp); Spacer(Modifier.height(2.dp)); Text(label, style = Ty.label, color = t.ink, maxLines = 1)
            }
        }
        val voice = @Composable { small(Glyph.MIC, "음성", "voice_button", if (c.lastPoint == null) 0.5f else 1f) { c.toggleVoice() } }
        val link = @Composable { small(Glyph.BT, "수신기", "receiver_button", 1f) { c.connection?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } } }
        val undo = @Composable { small(Glyph.UNDO, "되돌리기", "undo_button", if (c.points.isEmpty() && !busy) 0.45f else 1f) { c.undo() } }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = if (c.poleOnRight) Arrangement.spacedBy(6.dp) else Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (c.poleOnRight) { voice(); link(); undo() } else { undo(); link(); voice() }
        }
    }
}

/** 오른쪽(또는 왼쪽) 56dp 표시 전용 띠. 터치 요소 0. */
@Composable
internal fun PoleStrip(c: SurveyController, batteryPct: Int?) {
    val t = T
    val st = c.gv.status.status
    val (bg, fg) = statusColors(t, st)
    Column(
        Modifier.width(SurveyDims.POLE_STRIP_DP.dp).fillMaxHeight().background(bg).drawBehind {
            drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), endX = size.width * 0.34f), size = Size(size.width * 0.34f, size.height))
        }.padding(top = 14.dp, bottom = 22.dp).testTag("pole_strip"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusIcon(st, fg, 26.dp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.testTag("pole_label").clearAndSetSemantics { text = AnnotatedString(st.labelKo) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { st.labelKo.forEach { ch -> Text(ch.toString(), style = Ty.label.copy(fontSize = 19.sp, lineHeight = 26.sp), color = fg) } }
        }
        GlyphIcon(Glyph.SAT, fg, 16.dp)
        Text("${c.gv.satellites}", style = MonoLight.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal), color = fg, modifier = Modifier.testTag("pole_sats"))
        Spacer(Modifier.height(10.dp))
        GlyphIcon(Glyph.BATT, fg, 16.dp)
        Text(batteryPct?.let { "$it%" } ?: "—", style = MonoLight.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal), color = fg)
        Spacer(Modifier.height(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) { "표시전용".forEach { ch -> Text(ch.toString(), style = Ty.small.copy(fontSize = 12.sp, lineHeight = 14.sp), color = fg) } }
    }
}

/** 더보기 시트: 도구 바로가기 + 위치 종류 전체 목록 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun QuickSheet(c: SurveyController) {
    val t = T
    val close = { c.quickSheetOpen = false }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.3f)).clickable { close() })
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().shadow(8.dp, shape).clip(shape).glossy(t.card).border(1.5.dp, t.outline, shape)
                .padding(16.dp).testTag("quick_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("위치 종류", style = Ty.small, color = t.accentText)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.types.forEachIndexed { i, name ->
                    val on = i == c.typeIndex
                    MBtn(Modifier.height(44.dp).testTag("quick_type_$i"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { c.typeIndex = i; close() }) {
                        Text(name, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 14.dp), maxLines = 1)
                    }
                }
            }
            val rows = listOf(
                Triple<String, String, () -> Unit>("점 목록", "quick_points", { c.hub.open(ToolScreen.POINTS) }),
                Triple<String, String, () -> Unit>("점 찾아가기", "quick_goto", { c.hub.open(ToolScreen.GOTO) }),
                Triple<String, String, () -> Unit>("계산기", "quick_calc", { c.hub.open(ToolScreen.CALC) }),
                Triple<String, String, () -> Unit>("안테나 높이", "quick_antenna", { c.antennaSheetOpen = true }),
                Triple<String, String, () -> Unit>("내보내기", "quick_export", { c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }),
                Triple<String, String, () -> Unit>("발굴 구역 · 그리드", "quick_areas", { c.hub.open(ToolScreen.AREAS) }),
                Triple<String, String, () -> Unit>(if (c.arch.outlineMode) "외곽 취득 끄기" else "외곽 취득 켜기", "quick_outline", { c.toggleOutlineMode() }),
                Triple<String, String, () -> Unit>("위치 종류 편집", "quick_types", { c.hub.open(ToolScreen.TYPES) }),
                Triple<String, String, () -> Unit>("전체 메뉴", "quick_menu", { c.drawerOpen = true }),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) {
                for ((label, tag, act) in rows) MBtn(Modifier.weight(1f).height(48.dp).testTag(tag), MKind.PLAIN, 16.dp, onClick = { close(); act() }) { Text(label, style = Ty.label, color = t.ink) }
            }
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("quick_close"), MKind.PRIMARY, 18.dp, onClick = close) { Text("닫기", style = Ty.label, color = t.onPrimary) }
        }
    }
}
