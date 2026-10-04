package kr.archsurvey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** design-system/mockups/v2/THEME.md 토큰 (아이보리·테라코타, 얇은 글꼴 300/400). 굵기 Bold 이상은 쓰지 않는다. */
data class Tokens(
    val bg: Color, val card: Color, val line: Color, val outline: Color,
    val ink: Color, val inkSub: Color,
    val primary: Color, val onPrimary: Color, val accent: Color, val accentText: Color,
    val fixed: Color, val onFixed: Color,
    val caution: Color, val onCaution: Color,
    val bad: Color, val onBad: Color,
    val scrim: Color,
)

private fun c(h: Long) = Color(0xFF000000 or h)

val LightTokens = Tokens(
    bg = c(0xFAF9F5), card = c(0xF0EEE6), line = c(0xE8E6DC), outline = c(0x8D8A80),
    ink = c(0x141413), inkSub = c(0x5E5D59),
    primary = c(0xD97757), onPrimary = c(0x141413), accent = c(0xC6613F), accentText = c(0x9C4426),
    fixed = c(0x1B6BB0), onFixed = c(0xFAF9F5),
    caution = c(0xF2B600), onCaution = c(0x141413),
    bad = c(0xA3123A), onBad = c(0xFAF9F5),
    scrim = Color(0x66141413),
)

val DarkTokens = Tokens(
    bg = c(0x1F1E1D), card = c(0x2B2A27), line = c(0x3A3935), outline = c(0x8F8C81),
    ink = c(0xF0EEE6), inkSub = c(0xB7B4A9),
    primary = c(0xD97757), onPrimary = c(0x141413), accent = c(0xD97757), accentText = c(0xEDA98F),
    fixed = c(0x6DB6F5), onFixed = c(0x141413),
    caution = c(0xF2C14B), onCaution = c(0x141413),
    bad = c(0xFF7D96), onBad = c(0x141413),
    scrim = Color(0x99000000),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }
val T: Tokens @Composable get() = LocalTokens.current

@Composable
fun SurveyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = t.primary, onPrimary = t.onPrimary, background = t.bg, onBackground = t.ink,
        surface = t.bg, onSurface = t.ink, surfaceVariant = t.card, onSurfaceVariant = t.inkSub, outline = t.outline,
    )
    CompositionLocalProvider(LocalTokens provides t) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** 글꼴: 시스템 기본(한글은 기기 기본 고딕). Noto Sans KR / JetBrains Mono 번들은 APK 용량 영향 때문에 다음 라운드. */
object Ty {
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Light)
    val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light)
    val title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal)
    val bandMain = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal)
    val coord = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
}
