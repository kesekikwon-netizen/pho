package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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

/** 0.1.30 T24: 수신기 없을 때 측점 버튼 글자(UX리뷰) */
internal const val MEASURE_NO_RECEIVER = "수신기 연결 필요"

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun MBtn(
    modifier: Modifier, kind: MKind = MKind.PLAIN, radius: Dp = 16.dp, alpha: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    /** 0.1.30: 바탕을 이 단색으로(없으면 종류별 색) */
    solid: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val t = T; val shape = RoundedCornerShape(radius)
    val bgc = solid ?: when (kind) { MKind.PRIMARY -> t.primary; MKind.SELECTED -> t.primaryCont; else -> t.raised }
    val bm = when (kind) {
        MKind.PRIMARY -> Modifier
        MKind.SELECTED -> Modifier.border(1.5.dp, t.accent, shape)
        else -> Modifier.border(1.dp, t.outline, shape)
    }
    Column(
        modifier.alpha(alpha).clip(shape).glossy(bgc).then(bm).hCombinedClickable(onLongClick = onLongClick, onClick = onClick),
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

/** 지도 왼쪽 위 현재 좌표 카드(목업 15): X(N) / Y(E) / Z(H). 값 없으면 “—” */
@Composable
internal fun CoordBox(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val lat = c.gv.lat; val lon = c.gv.lon
    val p = if (lat != null && lon != null) TmProjection.forward(lat, lon, c.zone) else null
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(t.raised.copy(alpha = 0.94f)).border(1.dp, t.line.copy(alpha = 0.45f), shape).padding(horizontal = 10.dp, vertical = 6.dp).testTag("coord_line")) {
        val rows = listOf(Triple("N", "X(N)", p?.northing?.let { grp3(it) }), Triple("E", "Y(E)", p?.easting?.let { grp3(it) }), Triple("H", "Z(H)", c.gv.altM?.let { grp3(it) }))
        for ((k, label, v) in rows) {
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, modifier = Modifier.width(34.dp))
                Text(v ?: "—", style = MonoLight.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal), color = t.ink, maxLines = 1, modifier = Modifier.testTag("coord_$k"))
            }
        }
    }
}

@Composable
internal fun ToolBtn(g: Glyph, desc: String, tag: String, kind: MKind = MKind.PLAIN, onClick: () -> Unit) {
    MBtn(Modifier.size(48.dp).testTag(tag), kind, 16.dp, onClick = onClick) { GlyphIcon(g, if (kind == MKind.PRIMARY) T.onPrimary else T.accent, 22.dp) }
}

/** 하단 도구 한 칸(목업 15): 아이콘 + 짧은 글자. 폭 ≥ 48dp, 높이 = 측점 버튼 높이 */
@Composable
private fun RowScope.BarItem(g: Glyph, label: String, tag: String, desc: String, selected: Boolean = false, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.weight(1f).widthIn(min = 48.dp).fillMaxHeight().clip(shape).background(if (selected) t.primaryCont else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, t.ink, shape) else Modifier)
            .hClickable(onClick = onClick).semantics { contentDescription = desc }.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(g, t.ink, 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ty.small.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal), color = t.ink, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 0.1.11 하단 측량 영역(목업 15): 왼쪽 도구(위치 종류 · 선으로 연결 · 면으로 연결 · 되돌리기) + 큰 검정 「측량하기」(72dp).
 * 폴 쪽 설정에 따라 측량 버튼이 오른쪽/왼쪽으로 간다. 길게 누르면 측량 설정 시트.
 */
@Composable
internal fun ControlArea(c: SurveyController) {
    val t = T
    val busy = c.phase == Phase.ACQUIRING
    val status = c.gv.status.status
    val reason = c.gv.status.reason
    val blocked = status == SurveyStatus.UNAVAILABLE
    val caution = status == SurveyStatus.CAUTION
    // 0.1.30 T24: 수신기 없음·끊김 → 회색 「수신기 연결 필요」(자리 그대로). 눌러도 측점 안 함 — 안내 + [연결] + 실패음
    val notConn = c.gv.noReceiver
    val strict = c.settingsState?.requireFixed == true
    val strictBlock = strict && caution
    val sel = c.typeIndex.coerceIn(0, (c.types.size - 1).coerceAtLeast(0))
    val a = c.arch
    Column(Modifier.fillMaxWidth().height(SurveyDims.CONTROL_AREA_DP.dp).background(t.card).testTag("control_area")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.35f)))
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 8.dp, top = (SurveyDims.CTRL_PAD_TOP_DP - 1).dp, bottom = SurveyDims.CTRL_PAD_BOTTOM_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            val tools: @Composable RowScope.() -> Unit = {
                // 0.1.14 목업 15 순서: 선으로 연결 · 면으로 연결 · 삭제하기 · 새 연결 시작 (위치 종류 칩은 지도 왼쪽 아래)
                BarItem(Glyph.POLYLINE, "선으로\n연결", "tool_link_line", "외곽을 선으로 연결", a.outlineMode && !a.preferClosed) { c.setLinkMode(closed = false) }
                BarItem(Glyph.POLYGON, "면으로\n연결", "tool_link_area", "외곽을 면으로 연결", a.outlineMode && a.preferClosed) { c.setLinkMode(closed = true) }
                BarItem(Glyph.UNDO, "삭제하기", "tool_undo", "마지막 측점 삭제(되돌리기)") { c.askUndo() }
                BarItem(Glyph.PLUS, "새 연결\n시작", "tool_new_link", "외곽 마무리하고 새 연결 시작") { c.startNewLink() }
            }
            val measure: @Composable RowScope.() -> Unit = {
                // 0.1.26: 미연결→연결 유도(활성), 엄격·주의=흐리게, 주의(기본)=확인 후 측점
                val dim = busy || blocked || strictBlock
                MBtn(
                    Modifier.width(SurveyDims.MEASURE_WIDTH_DP.dp).height(SurveyDims.MEASURE_BUTTON_DP.dp).testTag("measure_button"),
                    // 0.1.30 UX-0130-1(선택): 「수신기 연결 필요」·「측점 불가」는 반투명 대신 진회색 단색. 측정 중(busy)은 기존대로 흐리게
                    MKind.PRIMARY, 22.dp, alpha = if (busy) 0.5f else 1f, solid = if (!busy && (notConn || dim)) t.blockedButton else null, onLongClick = { if (!busy) c.setupOpen = true },
                    onClick = {
                        when {
                            busy -> {}
                            else -> c.onMeasure()
                        }
                    },
                ) {
                    when {
                        notConn -> {
                            Text(MEASURE_NO_RECEIVER, style = Ty.button.copy(fontSize = 17.sp, lineHeight = 21.sp), color = t.onPrimary, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.testTag("measure_disabled_reason"))
                        }
                        strictBlock && c.gv.sigmaMissingOnFix -> {
                            Text(SigmaText.STRICT_BUTTON, style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary)
                            Text(SigmaText.STRICT_BUTTON_SUB, style = Ty.small.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1, modifier = Modifier.testTag("measure_disabled_reason"))
                        }
                        strictBlock -> {
                            Text("측점 불가", style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary)
                            Text("엄격 · FIX 필요", style = Ty.small.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1, modifier = Modifier.testTag("measure_disabled_reason"))
                        }
                        blocked -> {
                            Text("측점 불가", style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary)
                            Text(reason.textKo, style = Ty.small.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1, modifier = Modifier.testTag("measure_disabled_reason"))
                        }
                        caution && c.gv.sigmaMissingOnFix -> {
                            GlyphIcon(Glyph.POLE, t.onPrimary, 24.dp)
                            Text("측량하기", style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary, maxLines = 1)
                            Text(c.nextName + " · " + HeaderModel.SIGMA_MISSING, style = Ty.coord.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1, modifier = Modifier.testTag("measure_sigma_hint"))
                        }
                        caution -> {
                            GlyphIcon(Glyph.POLE, t.onPrimary, 24.dp)
                            Text("주의 측점", style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary, maxLines = 1)
                            Text(c.nextName + " · 확인 후", style = Ty.coord.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1, modifier = Modifier.testTag("measure_caution_hint"))
                        }
                        else -> {
                            GlyphIcon(Glyph.POLE, t.onPrimary, 24.dp)
                            Text("측량하기", style = Ty.button.copy(fontSize = 18.sp, lineHeight = 22.sp), color = t.onPrimary, maxLines = 1)
                            Text(c.nextName, style = Ty.coord.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.onPrimary, maxLines = 1)
                        }
                    }
                }
            }
            if (c.poleOnRight) { tools(); Spacer(Modifier.width(4.dp)); measure() } else { measure(); Spacer(Modifier.width(4.dp)); tools() }
        }
    }
}

/** 더보기 시트: 도구 바로가기 + 위치 종류 전체 목록 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun QuickSheet(c: SurveyController) {
    val t = T
    val close = { c.quickSheetOpen = false }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.3f)).hClickable { close() })
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
                    MBtn(Modifier.height(48.dp).testTag("quick_type_$i"), if (on) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = { c.typeIndex = i; close() }) {
                        Text(name, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 14.dp), maxLines = 1)
                    }
                }
            }
            val rows = listOf(
                Triple<String, String, () -> Unit>("되돌리기", "quick_undo", { c.askUndo() }),
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


/**
 * 전자 기포(폰 센서). 누르는 기능 없음. 바깥 원 = 허용 각도의 2배, 안쪽 원 = 허용 각도.
 * 기포(점)는 높은 쪽으로 가므로 폴 위쪽을 기포 쪽으로 옮기면 가운데로 온다. 허용을 넘으면 주의색.
 */
@Composable
internal fun TiltBubble(tc: TiltController, modifier: Modifier = Modifier) {
    val t = T
    val st = tc.state
    if (st == TiltState.OFF || st == TiltState.NO_SENSOR) return
    val over = st == TiltState.OVER
    val col = when (st) { TiltState.OVER -> t.caution; TiltState.OK -> t.fixed; else -> t.inkSub }
    val ang = tc.angleDeg
    val label = when (st) { TiltState.NO_DATA -> "—"; else -> "${tc.f1(ang ?: 0.0)}°" }
    Column(
        modifier.width(48.dp).clip(RoundedCornerShape(12.dp)).background(if (over) t.caution.copy(alpha = 0.16f) else t.raised)
            .border(if (over) 1.5.dp else 1.dp, if (over) t.caution else t.line, RoundedCornerShape(12.dp)).padding(vertical = 4.dp)
            .semantics { contentDescription = "폴 기울기 $label" + if (over) ", 허용 넘음" else "" }.testTag("tilt_bubble"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val b = tc.bubble; val limit = tc.limitDeg
        androidx.compose.foundation.Canvas(Modifier.size(36.dp)) {
            val r = size.minDimension / 2f
            val c0 = center
            drawCircle(t.line, r, c0, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(col.copy(alpha = 0.6f), r / 2f, c0, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            if (b != null && st != TiltState.NO_DATA) {
                // 성분(sin) → 각도 → 바깥 원 = 허용×2
                val full = Math.toRadians(limit * 2.0)
                val sx = (kotlin.math.asin(b.x.coerceIn(-1.0, 1.0)) / full).coerceIn(-1.0, 1.0)
                val sy = (kotlin.math.asin(b.y.coerceIn(-1.0, 1.0)) / full).coerceIn(-1.0, 1.0)
                drawCircle(col, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(c0.x + (sx * (r - 5.dp.toPx())).toFloat(), c0.y + (sy * (r - 5.dp.toPx())).toFloat()))
            }
        }
        Text(label, style = Ty.coord.copy(fontSize = 12.sp), color = if (over) t.ink else t.inkSub, maxLines = 1, modifier = Modifier.testTag("tilt_angle"))
    }
}
