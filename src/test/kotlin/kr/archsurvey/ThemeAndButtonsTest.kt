package kr.archsurvey

import androidx.compose.ui.graphics.Color
import kr.archsurvey.ui.LightTokens
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.7 테마: 색은 Theme.kt 한 곳, 글자 대비 4.5:1 이상. 계산식은 WCAG 상대 휘도. */
class ThemeTokensTest {
    private fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun ratio(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
    private val t = LightTokens

    @Test fun backgroundIsNearWhiteAndTextContrastIsAtLeast4_5() {
        assertTrue("바탕은 흰색에 가깝다", t.bg.red >= 0.97f && t.bg.green >= 0.97f && t.bg.blue >= 0.97f)
        for ((name, fg, bg) in listOf(
            Triple("본문/바탕", t.ink, t.bg), Triple("보조글자/바탕", t.inkSub, t.bg), Triple("보조글자/카드", t.inkSub, t.card),
            Triple("강조글자/바탕", t.accentText, t.bg), Triple("주 버튼 글자/주 버튼", t.onPrimary, t.primary),
            Triple("고정 띠", t.onFixed, t.fixed), Triple("불가 띠", t.onBad, t.bad), Triple("주의 띠", t.onCaution, t.caution),
            Triple("본문/지도바탕", t.ink, t.mapBg), Triple("본문/선택면", t.ink, t.primaryCont),
        )) assertTrue("$name 대비 ${"%.2f".format(ratio(fg, bg))}", ratio(fg, bg) >= 4.5)
    }

    @Test fun accentIsSingleTerracotta() {
        assertEquals(0xFFD97757.toInt(), t.primary.toArgb())
    }

    private fun Color.toArgb() = ((alpha * 255).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    @Test fun noRawColorLiteralsOutsideThemeFile() {
        // 지도 데이터색(구역 색)만 CanvasMapSurface 에 둔다
        File("src/main/kotlin/kr/archsurvey/ui").walk().filter { it.extension == "kt" && it.name != "Theme.kt" && it.name != "CanvasMapSurface.kt" }.forEach { f ->
            assertFalse("${f.name} 에 색 직접 지정", Regex("Color\\(0x").containsMatchIn(f.readText()))
        }
    }
}
