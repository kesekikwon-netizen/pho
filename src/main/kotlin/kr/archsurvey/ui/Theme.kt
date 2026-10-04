package kr.archsurvey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** design-system/tools/tokens.mjs 와 동일한 토큰 */
data class SurveyColors(
    val bg: Color, val surface: Color, val surfaceVar: Color, val outline: Color, val outlineVar: Color,
    val ink: Color, val inkSub: Color, val primary: Color, val onPrimary: Color, val primaryCont: Color, val onPrimaryCont: Color,
    val fixed: Color, val onFixed: Color, val fixedCont: Color, val fixedText: Color,
    val float: Color, val onFloat: Color, val floatCont: Color, val floatText: Color,
    val single: Color, val onSingle: Color, val singleCont: Color, val singleText: Color,
    val none: Color, val noneCont: Color,
    val ok: Color, val onOk: Color, val okCont: Color, val okText: Color,
)

private fun c(h: Long) = Color(0xFF000000 or h)

val LightTokens = SurveyColors(
    c(0xF7F5F0), c(0xFFFFFF), c(0xECE8DE), c(0x7A7467), c(0xD6D0C2), c(0x15181A), c(0x444B50),
    c(0x2F4B2E), c(0xFFFFFF), c(0xDCE8D2), c(0x0F2410),
    c(0x0A63B5), c(0xFFFFFF), c(0xD6E7F8), c(0x0A63B5),
    c(0xF2A900), c(0x1F1400), c(0xFFEFC2), c(0x8A5200),
    c(0xC62E0E), c(0xFFFFFF), c(0xFBDAD3), c(0xB02808),
    c(0x5E656A), c(0xE3E1DB),
    c(0x1B7A3A), c(0xFFFFFF), c(0xD8EEDC), c(0x176632),
)
val DarkTokens = SurveyColors(
    c(0x101412), c(0x1A201C), c(0x252C27), c(0x8A938B), c(0x39423B), c(0xECEFEA), c(0xB4BBB3),
    c(0xA8D89A), c(0x0B2308), c(0x2F4B2E), c(0xD2EAC8),
    c(0x6DB6F5), c(0x06223C), c(0x0E2F4F), c(0x8CC8FA),
    c(0xFFC83D), c(0x241800), c(0x4A3600), c(0xFFD66E),
    c(0xFF8566), c(0x2E0A02), c(0x4D1D12), c(0xFF9F86),
    c(0xA3AAA4), c(0x2B322D),
    c(0x7FD39A), c(0x062812), c(0x12351C), c(0x8EDBA8),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }
val T: SurveyColors @Composable get() = LocalTokens.current

@Composable
fun SurveyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.primary, onPrimary = t.onPrimary, primaryContainer = t.primaryCont, onPrimaryContainer = t.onPrimaryCont,
        background = t.bg, onBackground = t.ink, surface = t.surface, onSurface = t.ink, surfaceVariant = t.surfaceVar,
        onSurfaceVariant = t.inkSub, outline = t.outline, outlineVariant = t.outlineVar, error = t.single, onError = t.onSingle,
        secondaryContainer = t.primaryCont, onSecondaryContainer = t.onPrimaryCont,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object Ty {
    val big = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 18.sp)
    val bodyB = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val small = TextStyle(fontSize = 15.sp)
    val coord = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}
