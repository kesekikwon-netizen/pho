package kr.archsurvey.pg.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.ui.Fonts
import kr.archsurvey.ui.Sunlight
import kr.archsurvey.ui.pressSpring
import kr.archsurvey.ui.releaseTap

/** 앱 색: Field Ivory, 햇빛 고대비면 Sunlight 값 */
object P {
    val bg get() = if (Sunlight.on) Sunlight.bg else FieldIvory.bg
    val card get() = if (Sunlight.on) Sunlight.bg else FieldIvory.card
    val ink get() = if (Sunlight.on) Sunlight.ink else FieldIvory.ink
    val sub get() = if (Sunlight.on) Sunlight.sub else FieldIvory.inkSub
    val line get() = if (Sunlight.on) Sunlight.line else FieldIvory.line
    val primary get() = if (Sunlight.on) Sunlight.primary else FieldIvory.primary
    val fix get() = if (Sunlight.on) Sunlight.fix else FieldIvory.fix
    val float get() = if (Sunlight.on) Sunlight.float else FieldIvory.float
    val error get() = if (Sunlight.on) Sunlight.error else FieldIvory.error
    val stage = Color(0xFF4A4038)
}

fun ts(size: TextUnit = FieldIvory.body, bold: Boolean = false, color: Color = P.ink, mono: Boolean = false) =
    TextStyle(fontFamily = if (mono) Fonts.mono else Fonts.sans, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = size, color = color,
        fontFeatureSettings = "tnum")

@Composable
fun Txt(s: String, size: TextUnit = FieldIvory.body, bold: Boolean = false, color: Color = P.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(s, style = ts(size, bold, color), modifier = modifier, maxLines = maxLines)

/** 테두리 단추(보조) */
@Composable
fun OutlineBtn(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val sh = RoundedCornerShape(16.dp)
    Box(modifier.heightIn(min = FieldIvory.minTouch).widthIn(min = 96.dp).clip(sh).background(P.card).border(1.5.dp, if (enabled) P.ink else P.line, sh)
        .releaseTap(label, enabled) { onClick() }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Txt(label, bold = true, color = if (enabled) P.ink else P.sub, maxLines = 1)
    }
}

/** 주 행동(오른손 엄지 자리) */
@Composable
fun PrimaryBtn(label: String, modifier: Modifier = Modifier, color: Color = P.primary, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = FieldIvory.measureButton).pressSpring(24.dp, 36.dp).background(if (enabled) color else P.line).releaseTap(label, enabled) { onClick() }.padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center) { Txt(label, size = 20.sp, bold = true, color = FieldIvory.onPrimary, maxLines = 2) }
}

/** 둥근 셔터 */
@Composable
fun Shutter(label: String, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val base = modifier.size(72.dp).pressSpring(48.dp, 40.dp).background(P.primary).border(4.dp, Color.White, CircleShape)
    // S10: 길게 누르기 = 자동 촬영 켜기/끄기
    val m = if (onLong == null) base.releaseTap(label, guardMs = 300) { onClick() }
        else base.semantics { contentDescription = label }.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLong() }) }
    Box(m, contentAlignment = Alignment.Center) { Txt(label, size = 20.sp, bold = true, color = FieldIvory.onPrimary) }
}

/** 선택 칩(타자 없음) */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val sh = RoundedCornerShape(24.dp)
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).clip(sh).background(if (selected) P.ink else P.card).border(1.5.dp, P.ink, sh)
        .releaseTap(label, guardMs = 200) { onClick() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Txt(label, bold = true, color = if (selected) P.bg else P.ink, maxLines = 1)
    }
}

@Composable
fun LegendChip(color: Color, label: String) {
    val sh = RoundedCornerShape(16.dp)
    Row(Modifier.clip(sh).background(Color.White).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)); Txt(label, size = 14.sp, color = FieldIvory.ink)
    }
}
