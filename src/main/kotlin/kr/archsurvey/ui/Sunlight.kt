package kr.archsurvey.ui

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 0.1.52 (05-world-class #5·#6·#9) 두 앱 공용 — 이 파일은 두 앱에서 글자 하나 다르지 않게 유지한다.
 * - 햇빛 고대비: 홈에서 한 번 고르면 저장(선택만, 타자 없음). 색 값 자체(FieldIvory)는 바꾸지 않는다.
 * - 상태 표시: 색 + 모양 ● FIX / ◐ FLOAT / ○ 없음 (색맹·햇빛에서도 구분)
 * - 측량 단추 눌림: 모서리·크기 스프링(M3 Expressive 원칙, 측량 단추에만)
 */
object Sunlight {
    private const val PREF = "ui_prefs"; private const val KEY = "sun_high_contrast"
    var on by mutableStateOf(false)
    fun load(ctx: Context) { on = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false) }
    fun set(ctx: Context, v: Boolean) { on = v; ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply() }
    /** 고대비 값: 흰 바탕 · 검은 글자 · 진한 선/상태색 (흰 글자 대비 7:1 이상) */
    val bg = Color(0xFFFFFFFF); val ink = Color(0xFF000000); val sub = Color(0xFF1F1F1F); val line = Color(0xFF000000)
    val hair = Color(0xFF3A3A3A); val chip = Color(0xFFE8E8E8); val sel = Color(0xFFD6D6D6)
    val fix = Color(0xFF0A5424); val float = Color(0xFF6E3F00); val error = Color(0xFF8E1B12); val primary = Color(0xFF8F3A1C)
}

enum class StatusMark(val glyph: String) { FIX("●"), FLOAT("◐"), NONE("○") }

/** ● 채움 / ◐ 왼쪽 반 채움 + 테두리 / ○ 테두리만 */
@Composable
fun StatusDot(mark: StatusMark, color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f; val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val sw = (this.size.minDimension * 0.16f).coerceAtLeast(1.5f)
        when (mark) {
            StatusMark.FIX -> drawCircle(color, r, c)
            StatusMark.FLOAT -> {
                drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r))
                drawCircle(color, r - sw / 2f, c, style = Stroke(sw))
            }
            StatusMark.NONE -> drawCircle(color, r - sw / 2f, c, style = Stroke(sw))
        }
    }
}

/** 측량 단추 눌림 스프링: 누르는 동안 모서리 [rest]→[pressed], 0.96배. 탭 처리는 그대로(이 modifier 는 이벤트를 소비하지 않음) */
fun Modifier.pressSpring(rest: Dp = 16.dp, pressed: Dp = 32.dp): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val corner by animateDpAsState(if (down) pressed else rest, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "corner")
    val scale by animateFloatAsState(if (down) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "scale")
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); down = true
            waitForUpOrCancellation(PointerEventPass.Initial); down = false
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(corner))
}

/**
 * 0.1.53 / 0.1.15 (06-minimal) 보기 방식 [미니멀 | 일반]. 표시만 바꾼다 — 기능·데이터·연결 로직은 같다.
 * 기본 = 일반(0.1.52 / 0.1.14 그대로). 숨긴 기능은 모두 ⋯ 메뉴([moreOpen]) 한 곳에서 닿는다. 폰에 저장(선택만).
 */
object Minimal {
    private const val PREF = "ui_prefs"; private const val KEY = "minimal_mode"; private const val KEY_ZOOM = "minimal_zoom_buttons"
    var on by mutableStateOf(false)
    /** 미니멀에서도 확대 +/− 단추 보이기(장갑) */
    var zoomButtons by mutableStateOf(false)
    /** ⋯ 메뉴 열림(저장 안 함) */
    var moreOpen by mutableStateOf(false)
    fun load(ctx: Context) { val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); on = p.getBoolean(KEY, false); zoomButtons = p.getBoolean(KEY_ZOOM, false) }
    fun set(ctx: Context, v: Boolean) { on = v; ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply() }
    fun setZoomButtons(ctx: Context, v: Boolean) { zoomButtons = v; ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ZOOM, v).apply() }
}

/** 2칸 선택칩 [미니멀 | 일반] — 한 번 누르면 그 칸으로 바뀌고 저장. 색은 앱 팔레트에서 받는다 */
@Composable
fun ModeChip(ink: Color, bg: Color, line: Color, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sh = RoundedCornerShape(24.dp)
    androidx.compose.foundation.layout.Row(modifier.androidx_height48().clip(sh).border(1.5.dp, line, sh).testTagMode("mode_chip")) {
        for ((label, min) in listOf("미니멀" to true, "일반" to false)) {
            val sel = Minimal.on == min
            androidx.compose.foundation.layout.Box(
                Modifier.androidx_height48().widthIn(min = 56.dp).background(if (sel) ink else bg)
                    .clickableSel(label, sel) { Minimal.set(ctx, min) }.padding(horizontal = 10.dp)
                    .testTagMode(if (min) "mode_min" else "mode_normal"),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { androidx.compose.material3.Text(label, style = androidx.compose.ui.text.TextStyle(fontFamily = Fonts.sans, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp), color = if (sel) bg else ink, maxLines = 1) }
        }
    }
}
private fun Modifier.androidx_height48(): Modifier = this.then(Modifier.heightIn(min = 48.dp))
private fun Modifier.testTagMode(t: String): Modifier = this.then(Modifier.testTag(t))
private fun Modifier.clickableSel(label: String, sel: Boolean, onClick: () -> Unit): Modifier =
    this.then(Modifier.semantics { selected = sel; role = androidx.compose.ui.semantics.Role.Tab }.clickable(onClickLabel = label, onClick = onClick))
