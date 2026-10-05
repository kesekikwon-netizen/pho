package kr.archsurvey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 0.1.7 테마: 흰색에 가까운 바탕, 얇은 중립 회색 선, 단일 강조색(테라코타), 얇은 글꼴 300/400. 색은 이 파일 한 곳에서만 정한다(지도 데이터색 제외). 굵기 Bold 이상은 쓰지 않는다. */
data class Tokens(
    val bg: Color, val card: Color, val line: Color, val outline: Color,
    val ink: Color, val inkSub: Color,
    val primary: Color, val onPrimary: Color, val accent: Color, val accentText: Color,
    val fixed: Color, val onFixed: Color,
    val caution: Color, val onCaution: Color,
    val bad: Color, val onBad: Color,
    val scrim: Color,
    val raised: Color = Color(0xFFFFFFFF), val primaryCont: Color = Color(0xFFFBEDE7),
    /** 지도 바탕(격자 뒤) */
    val mapBg: Color = Color(0xFFF5F5F5),
)

private fun c(h: Long) = Color(0xFF000000 or h)

val LightTokens = Tokens(
    bg = c(0xFFFFFF), card = c(0xF5F5F5), line = c(0xE6E6E6), outline = c(0xC9C9C9),
    ink = c(0x111111), inkSub = c(0x595959),
    primary = c(0xD97757), onPrimary = c(0x141413), accent = c(0xC6613F), accentText = c(0x9C4426),
    fixed = c(0x1B6BB0), onFixed = c(0xFFFFFF),
    caution = c(0xF2B600), onCaution = c(0x141413),
    bad = c(0xA3123A), onBad = c(0xFFFFFF),
    scrim = Color(0x66111111),
)

val DarkTokens = Tokens(
    bg = c(0x1F1E1D), card = c(0x2B2A27), line = c(0x3A3935), outline = c(0x8F8C81),
    ink = c(0xF0EEE6), inkSub = c(0xB7B4A9),
    primary = c(0xD97757), onPrimary = c(0x141413), accent = c(0xD97757), accentText = c(0xEDA98F),
    fixed = c(0x6DB6F5), onFixed = c(0x141413),
    caution = c(0xF2C14B), onCaution = c(0x141413),
    bad = c(0xFF7D96), onBad = c(0x141413),
    scrim = Color(0x99000000), raised = c(0x33322E), primaryCont = c(0x4A2E24), mapBg = Color(0xFF26251F),
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
    CompositionLocalProvider(LocalTokens provides t) { MaterialTheme(colorScheme = scheme, typography = bundledTypography(), content = content) }
}

/** 번들 글꼴(OFL): Noto Sans KR 300/400, JetBrains Mono 300/400. 한글은 KS X 1001 2,350자 + 기호만 담았다(그 밖의 글자는 기기 글꼴로 대체). */
object Fonts {
    val sans = FontFamily(
        Font(kr.archsurvey.R.font.noto_sans_kr_light, FontWeight.Light),
        Font(kr.archsurvey.R.font.noto_sans_kr_regular, FontWeight.Normal),
        Font(kr.archsurvey.R.font.noto_sans_kr_regular, FontWeight.Medium),
    )
    val mono = FontFamily(
        Font(kr.archsurvey.R.font.jetbrains_mono_light, FontWeight.Light),
        Font(kr.archsurvey.R.font.jetbrains_mono_regular, FontWeight.Normal),
    )
}

/** 0.1.7: 평평한 면(그림자·광택 없음). 이전 이름을 유지해 호출부를 건드리지 않는다. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glossy(base: Color, enabled: Boolean = true, strength: Float = 0f): Modifier = this.background(base)

/** 글꼴: 번들 Noto Sans KR / JetBrains Mono */
object Ty {
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, fontFamily = Fonts.sans)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Light, fontFamily = Fonts.sans)
    val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Light, fontFamily = Fonts.sans)
    val title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, fontFamily = Fonts.sans)
    val bandMain = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, fontFamily = Fonts.sans)
    val button = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, fontFamily = Fonts.sans)
    val coord = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Light, fontFamily = Fonts.mono)
}

private fun bundledTypography(): androidx.compose.material3.Typography {
    val d = androidx.compose.material3.Typography()
    fun f(s: TextStyle) = s.copy(fontFamily = Fonts.sans, fontWeight = if ((s.fontWeight?.weight ?: 400) > 400) FontWeight.Normal else FontWeight.Light)
    return d.copy(
        displayLarge = f(d.displayLarge), displayMedium = f(d.displayMedium), displaySmall = f(d.displaySmall),
        headlineLarge = f(d.headlineLarge), headlineMedium = f(d.headlineMedium), headlineSmall = f(d.headlineSmall),
        titleLarge = f(d.titleLarge), titleMedium = f(d.titleMedium), titleSmall = f(d.titleSmall),
        bodyLarge = f(d.bodyLarge), bodyMedium = f(d.bodyMedium), bodySmall = f(d.bodySmall),
        labelLarge = f(d.labelLarge), labelMedium = f(d.labelMedium), labelSmall = f(d.labelSmall),
    )
}
