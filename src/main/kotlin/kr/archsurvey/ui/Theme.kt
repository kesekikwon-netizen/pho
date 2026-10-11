package kr.archsurvey.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 테마 토큰(0.1.11 목업: 아이보리·흰 카드·검정 주 버튼). 얇은 글꼴 300/400. 색은 이 파일 한 곳에서만 정한다(지도 데이터색 제외). 굵기 Bold 이상은 쓰지 않는다. */
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
    /** 정상(연결됨·수신 중) 초록 — 점·글자 표시용 */
    val ok: Color = Color(0xFF1E7A46),
    /** 상태 알약·세그먼트 바탕(연한 회색) */
    val chip: Color = Color(0xFFEDECE7),
    /** 0.1.30 「수신기 없음」 글자: 주의(노랑) 계열을 회색 바탕에서 읽히게 짙게(노랑 F2B600 은 회색 위 대비 약 1.6:1) */
    val cautionInk: Color = Color(0xFF7A5600),
    /** 0.1.30 UX-0130-1: 측점 못 하는 버튼(수신기 연결 필요·측점 불가) 진회색 단색 */
    val blockedButton: Color = Color(0xFF5C5B57),
    /** 0.1.11 SkyPlot 위성계 점 색(GPS·GLONASS·Galileo·BeiDou·QZSS). 상태색과 겹치지 않게 짙은 계열 */
    val satGps: Color = Color(0xFF1E7A46),
    val satGlo: Color = Color(0xFF1F4FA8),
    val satGal: Color = Color(0xFFB07A00),
    val satBds: Color = Color(0xFF8A2BA0),
    val satOther: Color = Color(0xFF5C5B57),
    /** 0.1.31 시작 화면(모션 로딩): 아이보리 바탕·검정 글자·테라코타 선 두 단계 */
    val splashBg: Color = Color(0xFFFAF9F5),
    val splashInk: Color = Color(0xFF141413),
    val splashLine: Color = Color(0xFFD97757),
    val splashLineDeep: Color = Color(0xFFC6613F),
    /** 0.1.34 그림자 색(Strata Ink 141413). 알파는 [Lift] 단계가 정한다 */
    val shadow: Color = Color(0xFF141413),
    /** 0.1.34 「측량 준비」 카드: 끝남 ✓ 글자(Strata Done 3F6B31) */
    val done: Color = Color(0xFF3F6B31),
)

private fun c(h: Long) = Color(0xFF000000 or h)

/**
 * 0.1.11 목업 테마(강제 라이트 고대비): 아이보리 바탕 + 흰 카드 + 검정 글자 + 검정 주 버튼.
 * 상태색은 고정(측량 가능)=초록(0.1.31, 예전 파랑), 유동(주의)=노랑, 정상=초록, 불가·파괴=빨강. 본문 7:1 이상, 경계선·입력 테두리 3:1 이상.
 * 시스템 다크 모드와 무관하게 항상 이 토큰을 쓴다.
 */
val LightTokens = Tokens(
    // 0.1.34 디자인: 바탕·글자·칩·고른 면·지도 바탕을 Strata 「조용한 도판」 값(Ground FAF9F5 · Ink 141413 · Ink2 3D3D3A · Desk F0EEE6 · Oat E3DACC)으로.
    // 경계선·입력 테두리는 현장 대비 3:1 기준 때문에 그대로, 주 버튼 검정(0.1.11)도 그대로
    bg = c(0xFAF9F5), card = c(0xFFFFFF), line = c(0x8A8A86), outline = c(0x767672),
    ink = c(0x141413), inkSub = c(0x3D3D3A),
    primary = c(0x111111), onPrimary = c(0xFFFFFF), accent = c(0x141413), accentText = c(0x141413),
    fixed = c(0x146536), onFixed = c(0xFFFFFF), // 0.1.31: 측량 가능 = 초록(흰 글자 7.1:1)
    caution = c(0xF2B600), onCaution = c(0x141413),
    bad = c(0xA3123A), onBad = c(0xFFFFFF),
    scrim = Color(0x66111111),
    raised = c(0xFFFFFF), primaryCont = c(0xE3DACC), mapBg = c(0xF0EEE6),
    ok = c(0x1E7A46), chip = c(0xF0EEE6),
)

val DarkTokens = Tokens(
    bg = c(0x1F1E1D), card = c(0x2B2A27), line = c(0x3A3935), outline = c(0x8F8C81),
    ink = c(0xF0EEE6), inkSub = c(0xB7B4A9),
    primary = c(0xD97757), onPrimary = c(0x141413), accent = c(0xD97757), accentText = c(0xEDA98F),
    fixed = c(0x5BC48A), onFixed = c(0x141413),
    caution = c(0xF2C14B), onCaution = c(0x141413),
    bad = c(0xFF7D96), onBad = c(0x141413),
    scrim = Color(0x99000000), raised = c(0x33322E), primaryCont = c(0x4A2E24), mapBg = Color(0xFF26251F), chip = c(0x33322E), ok = c(0x5BC48A),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }
val T: Tokens @Composable get() = LocalTokens.current

@Composable
/** [dark] 는 시험용으로만 남김. 앱은 시스템 다크 모드를 따르지 않고 항상 흰 테마(0.1.9) */
fun SurveyTheme(dark: Boolean = false, content: @Composable () -> Unit) {
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

/**
 * 0.1.34 높이 세 단계(Strata 「밋밋함 보완」: 새 색 없이 그림자로만).
 * CARD = 카드·판(0 1 2 .06), FLOAT = 지도 위 단추·좌표 카드·측량하기(0 2 12 .12), SHEET = 시트·창·서랍(0 8 28 .18).
 */
enum class Lift(val elevation: androidx.compose.ui.unit.Dp, val alpha: Float) {
    NONE(0.dp, 0f), CARD(1.dp, 0.10f), FLOAT(4.dp, 0.18f), SHEET(12.dp, 0.24f)
}

/** [shape] 모양 그대로 [level] 높이의 그림자. clip 은 하지 않으니 뒤에 clip(shape) 을 붙인다 */
fun Modifier.lift(level: Lift, shape: androidx.compose.ui.graphics.Shape, color: Color = LightTokens.shadow): Modifier =
    if (level == Lift.NONE) this
    else this.shadow(level.elevation, shape, clip = false, ambientColor = color.copy(alpha = level.alpha), spotColor = color.copy(alpha = level.alpha))

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
