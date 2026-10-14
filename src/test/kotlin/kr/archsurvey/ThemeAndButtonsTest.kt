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
        assertTrue("바탕은 아이보리/오프화이트(0.1.11 목업)", t.bg.red >= 0.94f && t.bg.green >= 0.94f && t.bg.blue >= 0.92f)
        for ((name, fg, bg) in listOf(
            Triple("본문/바탕", t.ink, t.bg), Triple("보조글자/바탕", t.inkSub, t.bg), Triple("보조글자/카드", t.inkSub, t.card),
            Triple("강조글자/바탕", t.accentText, t.bg),
            Triple("고정 띠", t.onFixed, t.fixed), Triple("불가 띠", t.onBad, t.bad), Triple("주의 띠", t.onCaution, t.caution),
            Triple("본문/지도바탕", t.ink, t.mapBg), Triple("본문/선택면", t.ink, t.primaryCont),
            Triple("정상 표시", t.onFixed, t.ok), Triple("보조글자/알약", t.inkSub, t.chip),
        )) assertTrue("$name 대비 ${"%.2f".format(ratio(fg, bg))}", ratio(fg, bg) >= 4.5)
        // 0.1.46 공용 토큰 #C6613F: 주 버튼 글자는 18sp 이상 굵게만 쓰므로 WCAG 큰 글자 기준 3:1
        assertTrue("주 버튼 대비", ratio(t.onPrimary, t.primary) >= 3.0)
    }

    /** 0.1.9 고대비 유지 + 0.1.11 목업: 흰 카드, 본문 7:1, 경계·테두리 3:1 이상(0.1.7 경계선 1.7:1 문제) */
    @Test fun highContrastWhite_0_1_9() {
        assertEquals(1f, t.card.red); assertEquals(1f, t.card.green); assertEquals(1f, t.card.blue)
        assertEquals(1f, t.raised.red); assertEquals(1f, t.raised.green); assertEquals(1f, t.raised.blue)
        for ((name, fg) in listOf("본문" to t.ink, "보조글자" to t.inkSub, "강조글자" to t.accentText))
            assertTrue("$name 7:1 (${"%.2f".format(ratio(fg, t.bg))})", ratio(fg, t.bg) >= 7.0)
        assertTrue("보조글자/카드 7:1", ratio(t.inkSub, t.card) >= 7.0)
        assertTrue("고정 띠 7:1", ratio(t.onFixed, t.fixed) >= 7.0)
        for ((name, c) in listOf("경계선" to t.line, "입력 테두리" to t.outline))
            assertTrue("$name 3:1 (${"%.2f".format(ratio(c, t.bg))})", ratio(c, t.bg) >= 3.0)
    }

    @Test fun themeNeverFollowsSystemDark() {
        val src = File("src/main/kotlin/kr/archsurvey/ui/Theme.kt").readText()
        assertFalse(src.contains("isSystemInDarkTheme"))
        assertTrue(File("src/main/res/values-v29/themes.xml").readText().contains("forceDarkAllowed"))
    }

    /** 0.1.11 목업: 주 버튼은 검정(글자 흰색). 테라코타 강조색은 쓰지 않는다 */
    @Test fun primaryIsTerracottaUserHtml_0_1_35() {
        // 0.1.35: 사용자 HTML 시안(「조용한 도판」) 테라코타. 측점 버튼 흰 글자(큰 글씨) 4.5:1 이상
        assertEquals(0xFFC6613F.toInt(), t.primary.toArgb()) // 0.1.46 공용 토큰
        assertEquals(0xFFFFFFFF.toInt(), t.onPrimary.toArgb())
        assertTrue("측점 버튼 글자(18sp 굵게, 큰 글자 3:1)", ratio(t.onPrimary, t.primary) >= 3.0)
    }

    private fun Color.toArgb() = ((alpha * 255).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    @Test fun noRawColorLiteralsOutsideThemeFile() {
        // 지도 데이터색(구역 색)만 CanvasMapSurface 에 둔다
        File("src/main/kotlin/kr/archsurvey/ui").walk().filter { it.extension == "kt" && it.name != "Theme.kt" && it.name != "D43Kit.kt" && it.name != "FieldIvory.kt" && it.name != "Sunlight.kt" /* 0.1.52 고대비 토큰 */ && it.name != "CanvasMapSurface.kt" }.forEach { f ->
            assertFalse("${f.name} 에 색 직접 지정", Regex("Color\\(0x").containsMatchIn(f.readText()))
        }
    }
}
