package kr.archsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SatInfo
import kotlin.math.cos
import kotlin.math.sin

/** 위성계. 실제 GSV(talker·PRN)로만 판단 — 가짜 위성 없음 */
enum class Constellation(val labelKo: String, val prefix: String) {
    GPS("GPS", "G"), GLONASS("GLONASS", "R"), GALILEO("Galileo", "E"), BEIDOU("BeiDou", "C"), QZSS("QZSS", "J"), SBAS("SBAS", "S"), OTHER("기타", "?");
}

/** 0.1.11 SkyPlot 순수 계산(시험 가능) */
object SkyModel {
    fun constellation(s: SatInfo): Constellation = when (s.talker.uppercase()) {
        "GP" -> if (s.prn in 33..64) Constellation.SBAS else Constellation.GPS
        "GL" -> Constellation.GLONASS
        "GA" -> Constellation.GALILEO
        "GB", "BD" -> Constellation.BEIDOU
        "GQ", "QZ" -> Constellation.QZSS
        "GN" -> when (s.prn) { in 1..32 -> Constellation.GPS; in 33..64 -> Constellation.SBAS; in 65..96 -> Constellation.GLONASS; else -> Constellation.OTHER }
        else -> Constellation.OTHER
    }

    fun label(s: SatInfo): String = constellation(s).prefix + s.prn.toString().padStart(2, '0')

    /** 고도·방위 → 원 안 좌표(중심 기준, 반지름 1 = 지평선). 고도/방위가 없으면 null */
    fun project(elevDeg: Int?, azDeg: Int?): Pair<Float, Float>? {
        if (elevDeg == null || azDeg == null || elevDeg < 0 || elevDeg > 90) return null
        val r = (90 - elevDeg) / 90f
        val a = Math.toRadians(azDeg.toDouble())
        return (r * sin(a)).toFloat() to (-r * cos(a)).toFloat()
    }

    /** 표: 고도 높은 순 */
    fun table(sats: List<SatInfo>, hidden: Set<Constellation>, maskDeg: Int): List<SatInfo> =
        sats.filter { constellation(it) !in hidden && (it.elevDeg ?: 0) >= maskDeg }.sortedByDescending { it.elevDeg ?: -1 }

    fun counts(sats: List<SatInfo>): Map<Constellation, Int> = sats.groupingBy { constellation(it) }.eachCount()
}

@Composable
private fun satColor(c: Constellation): Color = when (c) {
    Constellation.GPS -> T.satGps; Constellation.GLONASS -> T.satGlo; Constellation.GALILEO -> T.satGal; Constellation.BEIDOU -> T.satBds; else -> T.satOther
}

/** 0.1.11 SkyPlot(목업 06): 원형 하늘 그림 + 위성계 칩 + PRN 표. GSV 위성 상세를 받을 때만 점이 나온다 */
@Composable
fun SkyPlotScreen(c: SurveyController) {
    val t = T
    val sats = c.gv.sats
    var hidden by remember { mutableStateOf(setOf<Constellation>()) }
    var mask by remember { mutableIntStateOf(0) }
    val shown = SkyModel.table(sats, hidden, mask)
    val counts = SkyModel.counts(sats)
    Column(Modifier.fillMaxSize().background(t.bg).hClickable(enabled = false) {}.testTag("skyplot")) {
        AppHeader(c, "SkyPlot", onBack = { c.skyPlotOpen = false }, backTag = "skyplot_back")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                SkyCanvas(shown, Modifier.fillMaxWidth().aspectRatio(1f).widthIn(max = 360.dp).testTag("skyplot_canvas"))
                if (sats.isEmpty()) Text(
                    "위성 상세(GSV)를 아직 받지 못했어요.\n수신기에서 GSV 출력을 켜면 위성 위치가 나와요.",
                    style = Ty.small, color = t.inkSub, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(t.raised).padding(10.dp).testTag("skyplot_empty"),
                )
            }
            // 위성계 칩(누르면 숨김/보임)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Constellation.GPS, Constellation.GLONASS, Constellation.GALILEO, Constellation.BEIDOU, Constellation.QZSS).forEach { k -> // 0.1.14 목업 06: QZSS 추가
                    val on = k !in hidden
                    Row(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) t.raised else t.chip)
                            .border(if (on) 2.dp else 1.dp, if (on) t.ink else t.line, RoundedCornerShape(24.dp))
                            .hClickable { hidden = if (on) hidden + k else hidden - k }.testTag("skyplot_chip_${k.prefix}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) satColor(k) else t.inkSub))
                        Text(" ${k.prefix} ${counts[k] ?: 0}", style = Ty.label.copy(fontSize = 13.sp), color = if (on) t.ink else t.inkSub, maxLines = 1)
                    }
                }
            }
            Text("사용 ${c.gv.satellites} · 보임 ${c.gv.satsInView ?: sats.size} · 표시 ${shown.size}" + if (mask > 0) " · 고도 ${mask}° 이상" else "", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("skyplot_summary"))
            // 표
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.4f), RoundedCornerShape(16.dp))) {
                SatRow("위성", "고도", "방위", "SNR", header = true)
                if (shown.isEmpty()) Text("표시할 위성이 없어요", style = Ty.small, color = t.inkSub, modifier = Modifier.padding(12.dp))
                shown.forEach { s -> SatRow(SkyModel.label(s), s.elevDeg?.let { "$it°" } ?: "—", s.azDeg?.let { "$it°" } ?: "—", s.snr?.let { "$it" } ?: "—", dot = satColor(SkyModel.constellation(s))) }
            }
            Text("위치는 수신기가 보낸 GSV 그대로예요. 고도 임계값은 표시만 거르고 수신기 설정은 바꾸지 않아요.", style = Ty.small, color = t.inkSub)
        }
        Row(Modifier.fillMaxWidth().background(t.raised).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MBtn(Modifier.weight(1f).height(56.dp).testTag("skyplot_mask"), MKind.PLAIN, 28.dp, onClick = { mask = when (mask) { 0 -> 10; 10 -> 15; else -> 0 } }) {
                Text("고도 임계 ${mask}°", style = Ty.label, color = t.ink)
            }
            MBtn(Modifier.weight(1f).height(56.dp).testTag("skyplot_close"), MKind.PRIMARY, 28.dp, onClick = { c.skyPlotOpen = false }) {
                Text("닫기", style = Ty.label, color = t.onPrimary)
            }
        }
    }
}

@Composable
private fun SatRow(a: String, b: String, cc: String, d: String, header: Boolean = false, dot: Color? = null) {
    val t = T
    val st = if (header) Ty.small.copy(fontSize = 12.sp) else Ty.coord.copy(fontSize = 13.sp)
    val col = if (header) t.inkSub else t.ink
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(dot))
            Text(a, style = st, color = col)
        }
        Text(b, style = st, color = col, modifier = Modifier.weight(1f))
        Text(cc, style = st, color = col, modifier = Modifier.weight(1f))
        Text(d, style = st, color = col, modifier = Modifier.weight(0.8f))
    }
}

@Composable
private fun SkyCanvas(sats: List<SatInfo>, modifier: Modifier) {
    val t = T
    val tm = rememberTextMeasurer()
    val colors = Constellation.entries.associateWith { satColor(it) }
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.86f
        val cx = size.width / 2f; val cy = size.height / 2f
        listOf(1f, 2f / 3f, 1f / 3f).forEach { f -> drawCircle(t.line, r * f, Offset(cx, cy), style = Stroke(1.5f)) }
        drawLine(t.line.copy(alpha = 0.6f), Offset(cx - r, cy), Offset(cx + r, cy), 1f)
        drawLine(t.line.copy(alpha = 0.6f), Offset(cx, cy - r), Offset(cx, cy + r), 1f)
        val lab = TextStyle(fontSize = 13.sp, color = t.ink)
        listOf("N" to Offset(cx, cy - r - 2f), "E" to Offset(cx + r + 4f, cy), "S" to Offset(cx, cy + r + 2f), "W" to Offset(cx - r - 4f, cy)).forEach { (s, at) ->
            val m = tm.measure(s, lab)
            val x = (at.x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            val y = (at.y - m.size.height / 2f).coerceIn(0f, size.height - m.size.height)
            drawText(m, topLeft = Offset(x, y))
        }
        val small = TextStyle(fontSize = 10.sp, color = t.ink)
        sats.forEach { s ->
            val p = SkyModel.project(s.elevDeg, s.azDeg) ?: return@forEach
            val at = Offset(cx + p.first * r, cy + p.second * r)
            val col = colors[SkyModel.constellation(s)] ?: t.inkSub
            drawCircle(col, 9.dp.toPx() / 1.5f, at)
            drawCircle(t.raised, 9.dp.toPx() / 1.5f, at, style = Stroke(2f))
            val m = tm.measure(SkyModel.label(s), small)
            val x = (at.x + 8f).coerceIn(0f, size.width - m.size.width)
            val y = (at.y - m.size.height - 2f).coerceIn(0f, size.height - m.size.height)
            drawText(m, topLeft = Offset(x, y))
        }
    }
}
