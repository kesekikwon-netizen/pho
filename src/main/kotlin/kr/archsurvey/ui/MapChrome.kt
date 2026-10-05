package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun MBtn(
    modifier: Modifier, kind: MKind = MKind.PLAIN, radius: Dp = 16.dp, alpha: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val t = T; val shape = RoundedCornerShape(radius)
    val bgc = when (kind) { MKind.PRIMARY -> t.primary; MKind.SELECTED -> t.primaryCont; else -> t.raised }
    val bm = when (kind) {
        MKind.PRIMARY -> Modifier
        MKind.SELECTED -> Modifier.border(1.5.dp, t.accent, shape)
        else -> Modifier.border(1.dp, t.outline, shape)
    }
    Column(
        modifier.alpha(alpha).clip(shape).glossy(bgc).then(bm).combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        SurveyStatus.CAUTION -> "주의"
        SurveyStatus.UNAVAILABLE -> "불가"
    }
    val sub = if (notConnected) "블루투스로 R10 / SP60 을 연결하세요" else when (st.status) {
        SurveyStatus.CAUTION -> "저장 시 표시 기록 · ${st.reason.textKo}"
        SurveyStatus.UNAVAILABLE -> "측점 막힘 · ${st.reason.textKo}"
        SurveyStatus.FIXED -> st.reason.textKo
    }
    val detail = if (notConnected) null else bandDetail(c)
    val sigma = if (notConnected) null else st.sigmaHM
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().height(68.dp).shadow(1.dp, shape).clip(shape).glossy(bg).testTag("status_band"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp).fillMaxHeight().clickable { c.drawerOpen = true }.testTag("gear"), contentAlignment = Alignment.Center) { GearIcon(fg, 22.dp) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(fg.copy(alpha = 0.45f)))
        Spacer(Modifier.width(10.dp))
        StatusIcon(st.status, fg, 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.Center) {
            // 1줄: 상태(가장 크게) + 오른쪽에 수평 정밀도. 2줄: 판정 이유(고정이 아닐 때만). 3줄: 위성·정밀도 요약
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(main, style = Ty.bandMain, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("status_main"))
                if (sigma != null) Text("±" + f3(sigma) + " m", style = MonoLight.copy(fontSize = 14.sp), color = fg, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("status_sigma"))
            }
            if (st.status != SurveyStatus.FIXED || notConnected) Text(sub, style = Ty.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_sub"))
            if (detail != null) Text(detail, style = Ty.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_detail"))
        }
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
                Text(k, style = Ty.label.copy(fontSize = 18.sp), color = t.ink, modifier = Modifier.width(18.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                    Text(v ?: "—", style = MonoLight.copy(fontSize = 22.sp, lineHeight = 26.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("coord_$k"))
                    Text(" m", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.padding(bottom = 3.dp))
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

/** 하단 측점 영역(SmartTopo 구조): 위치 종류 버튼 하나 + 큰 측점 버튼. 나머지 도구는 더보기·서랍으로 접었다. */
@Composable
internal fun ControlArea(c: SurveyController) {
    val t = T
    val busy = c.phase == Phase.ACQUIRING
    val blocked = c.gv.status.status == SurveyStatus.UNAVAILABLE
    val notConn = c.gv.status.reason == StatusReason.NOT_CONNECTED
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    val sel = c.typeIndex.coerceIn(0, (c.types.size - 1).coerceAtLeast(0))
    Row(
        Modifier.fillMaxWidth().height(SurveyDims.CONTROL_AREA_DP.dp).shadow(1.dp, shape).clip(shape).glossy(t.card, strength = 0.30f)
            .border(1.dp, t.line, shape).padding(start = 12.dp, end = 12.dp, top = SurveyDims.CTRL_PAD_TOP_DP.dp, bottom = SurveyDims.CTRL_PAD_BOTTOM_DP.dp)
            .testTag("control_area"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val typeBtn: @Composable RowScope.() -> Unit = {
            MBtn(Modifier.weight(1f).fillMaxHeight().testTag("type_chip"), MKind.SELECTED, 18.dp, onClick = { c.quickSheetOpen = true }) {
                Text("위치 종류", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.types.getOrElse(sel) { "—" }, style = Ty.label.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp))
                    GlyphIcon(Glyph.CHEVRON_UP, t.accent, 16.dp)
                }
            }
        }
        val measure: @Composable RowScope.() -> Unit = {
            MBtn(
                Modifier.weight(1.2f).fillMaxHeight().testTag("measure_button"),
                MKind.PRIMARY, 22.dp, alpha = if (busy || blocked) 0.5f else 1f, onLongClick = { if (!busy) c.setupOpen = true }, onClick = { c.onMeasure() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlyphIcon(Glyph.PIN, t.ink, 28.dp)
                    Column {
                        if (notConn) {
                            Text("측점 불가", style = Ty.button.copy(fontSize = 24.sp), color = t.onPrimary)
                            Text("수신기 연결 안 됨", style = Ty.small.copy(fontSize = 14.sp), color = t.onPrimary, modifier = Modifier.testTag("measure_disabled_reason"))
                        } else {
                            Text("측점", style = Ty.button.copy(fontSize = 35.sp, letterSpacing = 3.sp, lineHeight = 38.sp), color = t.onPrimary)
                            Text(c.nextName + " 취득", style = Ty.small.copy(fontSize = 14.sp), color = t.onPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (c.poleOnRight) { typeBtn(); measure() } else { measure(); typeBtn() }
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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().shadow(1.dp, shape).clip(shape).glossy(t.card).border(1.5.dp, t.outline, shape)
                .padding(16.dp).testTag("quick_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("위치 종류", style = Ty.small, color = t.accentText)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.types.forEachIndexed { i, name ->
                    val on = i == c.typeIndex
                    MBtn(Modifier.height(44.dp).testTag("quick_type_$i"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { c.typeIndex = i; close() }) {
                        Text(name, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 14.dp), maxLines = 1)
                    }
                }
            }
            val rows = listOf(
                Triple<String, String, () -> Unit>("되돌리기", "quick_undo", { c.undo() }),
                Triple<String, String, () -> Unit>("사진", "quick_photo", { c.addPhotoCamera() }),
                Triple<String, String, () -> Unit>(if (c.voiceSeconds > 0) "녹음 끝내기" else "음성", "quick_voice", { c.toggleVoice() }),
                Triple<String, String, () -> Unit>("점 목록", "quick_points", { c.hub.open(ToolScreen.POINTS) }),
                Triple<String, String, () -> Unit>("전체 보기", "quick_fit", { c.fitAll() }),
                Triple<String, String, () -> Unit>("배경 레이어", "quick_bg", { c.bg?.toggle() ?: run { c.message = "배경 레이어는 이 빌드에 없어요" } }),
                Triple<String, String, () -> Unit>("수신기 연결", "quick_receiver", { c.connection?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } }),
                Triple<String, String, () -> Unit>(if (c.arch.outlineMode) "외곽 취득 끄기" else "외곽 취득 켜기", "quick_outline", { c.toggleOutlineMode() }),
                Triple<String, String, () -> Unit>("전체 메뉴", "quick_menu", { c.drawerOpen = true }),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                for ((label, tag, act) in rows) MBtn(Modifier.weight(1f).height(56.dp).testTag(tag), MKind.PLAIN, 16.dp, onClick = { close(); act() }) { Text(label, style = Ty.label, color = t.ink, maxLines = 1) }
            }
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("quick_close"), MKind.PRIMARY, 18.dp, onClick = close) { Text("닫기", style = Ty.label, color = t.onPrimary) }
        }
    }
}
