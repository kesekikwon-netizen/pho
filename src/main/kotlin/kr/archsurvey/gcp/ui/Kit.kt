package kr.archsurvey.gcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.device.GnssSource
import kr.archsurvey.device.LinkState
import kr.archsurvey.gcp.measure.MeasureGate
import kr.archsurvey.gcp.model.Stage
import kr.archsurvey.ui.Fonts
import kr.archsurvey.ui.hClickable
import kr.archsurvey.ui.hCombinedClickable

/**
 * 사진 기준점 색(시안 v2): 측량 앱과 같은 집안(아이보리·명조·Plex Mono) +
 * 주 단추 먹색, 헤더 아래 체크 띠, 검은 ID 상자. 테라코타는 「아직 안 한 것」 에만.
 */
object G {
    val bg = Color(0xFFFAF9F5)
    val card = Color(0xFFFFFFFF)
    val ink = Color(0xFF141413)
    val sub = Color(0xFF5C5B57)
    val line = Color(0xFFD9D7CF)
    val todo = Color(0xFFB5573A)
    val todoSoft = Color(0xFFF7E4DB)
    val fix = Color(0xFF3F6B31)
    val fixSoft = Color(0xFFE6EEDF)
    val warn = Color(0xFF8A5A00)
    val bad = Color(0xFFA3123A)
    val chip = Color(0xFFEDECE7)
    val scrim = Color(0x99141413)
    val featureSoft = Color(0xFFFBEDE7)
    val sectionSoft = Color(0xFFE6EDF5)
    val sectionInk = Color(0xFF2D4B6E)

    fun stage(s: Stage) = when (s) { Stage.MEASURE -> todo; Stage.PHOTO, Stage.HANDOFF -> ink; Stage.DONE -> fix }
}

object GT {
    val serif = TextStyle(fontFamily = Fonts.serif, fontWeight = FontWeight.Normal)
    val mono = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Normal)
    val monoM = TextStyle(fontFamily = Fonts.mono, fontWeight = FontWeight.Medium)
    val sans = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Normal)
    val sansL = TextStyle(fontFamily = Fonts.sans, fontWeight = FontWeight.Light)
}

fun TextStyle.sz(s: TextUnit) = copy(fontSize = s)

/** 헤더 아래 흑백 체크 띠(표적 무늬) */
@Composable
fun CheckerBand() {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val w = 12.dp.toPx(); var x = 0f; var i = 0
        while (x < size.width) { if (i % 2 == 0) drawRect(G.ink, Offset(x, 0f), Size(w, size.height)); x += w; i++ }
    }
}

/** 검은 ID 상자: 「GN-」 작게 + 「A012」 크게 */
@Composable
fun IdBox(code: String, big: Boolean = false, modifier: Modifier = Modifier) {
    val (pre, body) = kr.archsurvey.gcp.model.ObjIds.split(code)
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(G.ink).padding(horizontal = if (big) 12.dp else 9.dp, vertical = if (big) 6.dp else 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(pre, style = GT.mono.sz(if (big) 13.sp else 11.sp), color = Color(0xFFBDBBB3))
        Text(body, style = GT.monoM.sz(if (big) 20.sp else 17.sp), color = Color.White)
    }
}

/** 유적 약호 상자 */
@Composable
fun CodeBox(code: String, size: Dp = 44.dp) {
    Box(Modifier.height(size).widthIn(min = size).clip(RoundedCornerShape(10.dp)).background(G.ink).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(code, style = GT.monoM.sz(18.sp), color = Color.White)
    }
}

/** 머리글 한 줄 + 체크 띠 */
@Composable
fun GHeader(onBack: (() -> Unit)?, tag: String, title: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(G.bg).testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).hClickable(onClickLabel = "뒤로") { onBack() }.testTag("back"), contentAlignment = Alignment.Center) {
                Text("‹", style = GT.sans.sz(30.sp), color = G.ink)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = title)
            trailing()
        }
        CheckerBand()
    }
}

/** 상태 알약: FIX ±0.8 cm / FLOAT / 연결 안 됨. 누르면 수신기 화면 */
@Composable
fun FixPill(src: GnssSource, onClick: () -> Unit) {
    val g by src.gnss.collectAsState()
    val l by src.link.collectAsState()
    val tick = kr.archsurvey.ui.rememberTick()
    val r = remember(g, l, tick) { StatusJudge.judge(g, l.state, src.clockMs()) }
    val text = MeasureGate.pill(r, if (l.state == LinkState.STREAMING) g.quality else FixQuality.NONE)
    val fixed = r.status == kr.archsurvey.core.SurveyStatus.FIXED
    val col = when { fixed -> G.fix; g.quality == FixQuality.RTK_FIXED && l.state == LinkState.STREAMING -> G.warn; l.state == LinkState.STREAMING -> G.todo; else -> G.sub }
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(2.dp, col, RoundedCornerShape(22.dp)).background(G.card)
            .hClickable(onClickLabel = "수신기") { onClick() }.padding(horizontal = 10.dp).testTag("fix_pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(col))
        Spacer(Modifier.width(6.dp))
        Text(text, style = GT.monoM.sz(13.sp), color = col, maxLines = 1)
    }
}

enum class BtnKind { INK, TODO, PLAIN, DASHED }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun GBtn(label: String, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.INK, enabled: Boolean = true, tag: String = "", sub: String? = null, height: Dp = 56.dp, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val bg = when { !enabled -> G.chip; kind == BtnKind.INK -> G.ink; kind == BtnKind.TODO -> G.todo; else -> G.card }
    val fg = when { !enabled -> Color(0xFF9A988F); kind == BtnKind.INK || kind == BtnKind.TODO -> Color.White; kind == BtnKind.DASHED -> G.todo; else -> G.ink }
    var m = modifier.heightIn(min = height).clip(shape).background(bg)
    m = when (kind) {
        BtnKind.PLAIN -> m.border(1.dp, if (enabled) G.sub else G.line, shape)
        BtnKind.DASHED -> m.drawDashed(G.todo)
        else -> m
    }
    Column(
        m.hCombinedClickable(enabled = enabled, onLongClick = onLong, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp).let { if (tag.isNotEmpty()) it.testTag(tag) else it },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = GT.sans.sz(17.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = GT.mono.sz(12.sp), color = fg.copy(alpha = 0.8f), maxLines = 1)
    }
}

fun Modifier.drawDashed(c: Color): Modifier = this.then(Modifier.drawBehindDashed(c))
private fun Modifier.drawBehindDashed(c: Color) = this.drawBehind {
    drawRoundRect(c, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
}

/** 칩(선택형) */
@Composable
fun GChip(label: String, on: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).background(if (on) G.ink else G.card).border(1.dp, if (on) G.ink else G.line, shape)
            .hClickable { onClick() }.padding(horizontal = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = GT.sans.sz(14.sp), color = if (on) Color.White else G.ink, maxLines = if ('\n' in label) 2 else 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

/** 종류 배지(유구 테라코타 · 토층 파랑 · 기타 회색) */
@Composable
fun KindBadge(kind: kr.archsurvey.gcp.model.ObjKind) {
    val (bg, fg) = when (kind) {
        kr.archsurvey.gcp.model.ObjKind.FEATURE -> G.featureSoft to G.todo
        kr.archsurvey.gcp.model.ObjKind.SECTION -> G.sectionSoft to G.sectionInk
        kr.archsurvey.gcp.model.ObjKind.OTHER -> G.chip to G.sub
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(kind.ko, style = GT.sans.sz(12.sp), color = fg)
    }
}

/** 측점·사진·전달 세 칸(지금 단계는 검정, 지난 단계는 초록 글자, 남은 단계는 회색) */
@Composable
fun StageChips(st: Stage) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (s in listOf(Stage.MEASURE, Stage.PHOTO, Stage.HANDOFF)) {
            val cur = s == st; val past = st.ordinal > s.ordinal
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(when { cur -> G.ink; past -> G.fixSoft; else -> G.chip }).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(s.short, style = GT.sans.sz(11.sp), color = when { cur -> Color.White; past -> G.fix; else -> Color(0xFF9A988F) })
            }
        }
    }
}

@Composable
fun GField(label: String, value: String, tag: String, kb: KeyboardType = KeyboardType.Text, placeholder: String = "", mono: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, style = GT.sansL.sz(13.sp)) }, singleLine = true,
        placeholder = { Text(placeholder, style = GT.sansL.sz(14.sp), color = G.sub) },
        textStyle = (if (mono) GT.mono else GT.sans).sz(17.sp).copy(color = G.ink),
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = G.ink, unfocusedBorderColor = G.sub, cursorColor = G.ink, focusedLabelColor = G.ink),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** 아래에서 올라오는 시트 틀 */
@Composable
fun Sheet(title: String, tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(G.scrim).hClickable(onClickLabel = "닫기") { onClose() })
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 720.dp).clip(shape).background(G.bg)
                .verticalScrollSafe().padding(horizontal = 18.dp, vertical = 16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = GT.serif.sz(22.sp), color = G.ink, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).hClickable(onClickLabel = "닫기") { onClose() }.testTag("${tag}_close"), contentAlignment = Alignment.Center) {
                    Text("✕", style = GT.sans.sz(18.sp), color = G.ink)
                }
            }
            content()
        }
    }
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier = this.verticalScroll(rememberScrollState())

@Composable
fun Stepper(value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GBtn("−", Modifier.width(56.dp), BtnKind.PLAIN, tag = "${tag}_minus", height = 48.dp, onClick = onMinus)
        Text(value, style = GT.monoM.sz(20.sp), color = G.ink, modifier = Modifier.widthIn(min = 64.dp).testTag("${tag}_value"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        GBtn("+", Modifier.width(56.dp), BtnKind.PLAIN, tag = "${tag}_plus", height = 48.dp, onClick = onPlus)
    }
}

@Composable
fun FolderLine(text: String, trailing: String? = null, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("▭ ", style = GT.mono.sz(12.sp), color = G.sub)
        Text(text, style = GT.mono.sz(12.sp), color = G.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) Text(" $trailing", style = GT.mono.sz(12.sp), color = if (warn) G.warn else G.ink, maxLines = 1)
    }
}
