package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.device.GnssSource
import kr.archsurvey.device.LinkState
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.ui.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.1.1 화면 부품 — 측량 앱(발굴 측량 0.1.35–0.1.37) 지도 화면과 같은 모양:
 * 아이보리 바탕 · 흰 카드 · 테라코타 주 단추 · 명조 제목 · 상태 알약(초록 테두리). 모두 장갑 손가락 크기(56dp 이상).
 */

/** 화면이 쓰는 Android 쪽 값(지도 키·버전). 시험은 빈 값 */
class GcpEnv(
    val mapAccess: () -> VWorldAccess = { VWorldAccess("", "") },
    val saveMapKey: (String) -> Unit = {},
    val version: String = "0.1.1",
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MD = DateTimeFormatter.ofPattern("MM/dd")
fun GcpController.timeLabel(t: Long?): String {
    if (t == null) return ""
    val z = Instant.ofEpochMilli(t).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return if (z.toLocalDate() == today) "오늘 " + HM.format(z) else MD.format(z)
}

enum class PTone { FIXED, CAUTION, BAD, NO_RECEIVER }

/** 상태 알약(측량 앱 DigStatusPill 과 같은 문구·색): 측량 가능 ±cm / FIX 기다리는 중 / 수신기 연결 안 됨 */
data class PillText(val text: String, val sub: String?, val tone: PTone)

fun pillFor(r: kr.archsurvey.core.StatusResult, link: LinkState, q: FixQuality): PillText = when {
    link == LinkState.IDLE || r.reason == kr.archsurvey.core.StatusReason.NOT_CONNECTED -> PillText("수신기 연결 안 됨", null, PTone.NO_RECEIVER)
    r.status == SurveyStatus.FIXED && q == FixQuality.RTK_FIXED -> PillText("측량 가능", r.sigmaHM?.let { String.format(Locale.US, "±%.1f", it * 100) } ?: "σ 없음", PTone.FIXED)
    r.status == SurveyStatus.UNAVAILABLE -> PillText("측량 불가", null, PTone.BAD)
    else -> PillText("FIX 기다리는 중", if (q == FixQuality.RTK_FLOAT) "FLOAT" else null, PTone.CAUTION)
}

@Composable
fun rememberPill(src: GnssSource): PillText {
    val g by src.gnss.collectAsState()
    val l by src.link.collectAsState()
    val tick = rememberTick()
    return remember(g, l, tick) { pillFor(StatusJudge.judge(g, l.state, src.clockMs()), l.state, g.quality) }
}

@Composable
fun GcpPill(src: GnssSource, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = T
    val p = rememberPill(src)
    val (dot, border) = when (p.tone) {
        PTone.FIXED -> t.fixed to t.fixed
        PTone.CAUTION -> t.caution to t.cautionInk
        PTone.BAD -> t.bad to t.bad
        else -> t.inkSub to t.outline
    }
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (p.tone == PTone.CAUTION) t.caution.copy(alpha = 0.18f) else t.raised)
            .border(2.dp, border, RoundedCornerShape(24.dp)).hClickable(onClickLabel = "수신기") { onClick() }
            .padding(horizontal = 12.dp).semantics { contentDescription = p.text + (p.sub?.let { " $it" } ?: "") }.testTag("status_pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Text(p.text, style = Ty.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = if (p.tone == PTone.FIXED) t.fixed else t.ink, maxLines = 1, modifier = Modifier.testTag("pill_fix"))
        p.sub?.let { Text(it, style = Ty.coord.copy(fontSize = 13.sp), color = t.inkSub, maxLines = 1) }
    }
}

/** 위 한 줄(측량 앱 DigHeader 모양): ‹ 제목(명조) / 작은 줄 | 상태 알약 | ≡ */
@Composable
fun GcpHeader(title: String, sub: String?, src: GnssSource?, onBack: (() -> Unit)?, onPill: () -> Unit = {}, onMenu: (() -> Unit)? = null, tag: String = "app_header") {
    val t = T
    Column(Modifier.fillMaxWidth().background(t.bg).testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(Glyph.BACK, "뒤로", "header_back", onBack) else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.Center) {
                val n = title.codePointCount(0, title.length)
                Text(title, style = Ty.serifHead.copy(fontSize = (if (n <= 8) 19 else if (n <= 14) 17 else 15).sp, lineHeight = 20.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_title"))
                if (sub != null) Text(sub, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_sub"))
            }
            if (src != null) GcpPill(src, Modifier.padding(horizontal = 4.dp), onPill)
            if (onMenu != null) IconBtn(Glyph.MENU, "메뉴", "menu", onMenu)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.35f)))
    }
}

@Composable
fun IconBtn(g: Glyph, desc: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).hClickable(onClickLabel = desc) { onClick() }.semantics { contentDescription = desc }.testTag(tag), contentAlignment = Alignment.Center) {
        GlyphIcon(g, T.ink, 24.dp)
    }
}

/** 큰 고르기 타일(종류·차수·좌표계·이름). [sub] = 둘째 줄 */
@Composable
fun Tile(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, sub: String? = null, enabled: Boolean = true, height: Dp = 64.dp, big: Boolean = false, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = height).clip(shape)
            .background(when { !enabled -> t.chip; selected -> t.primary; else -> t.raised })
            .border(if (selected) 2.dp else 1.dp, if (selected) t.primary else if (enabled) t.outline else t.line.copy(alpha = 0.4f), shape)
            .hClickable(enabled = enabled, onClickLabel = label) { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = (if (big) Ty.ledger.copy(fontSize = 24.sp) else Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium)), color = when { !enabled -> t.inkSub.copy(alpha = 0.6f); selected -> t.onPrimary; else -> t.ink },
            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = Ty.small.copy(fontSize = 12.sp), color = if (selected) t.onPrimary else t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** 주 단추(테라코타) / 보통 단추(흰 바탕) */
@Composable
fun Btn(label: String, tag: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, sub: String? = null, height: Dp = 60.dp, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = height).clip(shape).background(when { !enabled -> t.blockedButton; primary -> t.primary; else -> t.raised })
            .then(if (primary || !enabled) Modifier else Modifier.border(1.dp, t.outline, shape))
            .hClickable(enabled = enabled, onClickLabel = label) { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = Ty.label.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = if (primary || !enabled) t.onPrimary else t.ink, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = Ty.small.copy(fontSize = 12.sp), color = if (primary || !enabled) t.onPrimary else t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 아래에서 올라오는 창(엄지 닿는 곳). 제목 + 닫기 ✕ + 내용(길면 굴림) + 맨 아래 고정 단추 줄.
 * 바깥(어두운 곳)을 누르면 닫힌다.
 */
@Composable
fun BottomSheet(title: String, tag: String, onClose: () -> Unit, maxHeightFrac: Float = 0.9f, bottom: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = T
    BoxWithConstraints(Modifier.fillMaxSize().background(t.scrim).hClickable(onClickLabel = "닫기") { onClose() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * maxHeightFrac)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(t.bg).hClickable {}.windowInsetsPadding(WindowInsets.navigationBars).testTag(tag),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Ty.serifHead.copy(fontSize = 19.sp), color = t.ink, modifier = Modifier.weight(1f), maxLines = 2)
                IconBtn(Glyph.CLOSE, "닫기", "${tag}_close", onClose)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            if (bottom != null) Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = bottom)
        }
    }
}

@Composable
fun Caption(text: String, color: Color = T.inkSub, tag: String = "") {
    Text(text, style = Ty.small.copy(fontSize = 13.sp, lineHeight = 17.sp), color = color, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag))
}

/** 타일을 [cols] 칸씩 줄지어 */
@Composable
fun <T> TileGrid(items: List<T>, cols: Int, gap: Dp = 8.dp, cell: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in items.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (it in row) cell(it)
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
