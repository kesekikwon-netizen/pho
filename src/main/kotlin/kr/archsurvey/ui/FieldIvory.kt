package kr.archsurvey.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Field Ivory 디자인 토큰 v2 (2026-10-14, design-program/mockups-v2/design-system.png). 발굴 측량·사진 기준점 두 앱이 같은 값을 쓴다.
 * 이 파일은 두 앱에서 글자 하나 다르지 않게 유지한다(바꾸면 두 앱 함께).
 */
object FieldIvory {
    val bg = Color(0xFFFAF9F5); val card = Color(0xFFFFFFFF); val line = Color(0xFFE3DFD3)
    val ink = Color(0xFF141413); val inkSub = Color(0xFF5E5D59)
    /** 장식 전용(글자색 금지) */
    val decor = Color(0xFFD97757)
    /** 주 행동(흰 글자 18sp 이상 굵게) */
    val primary = Color(0xFFC6613F); val onPrimary = Color(0xFFFFFFFF); val pressed = Color(0xFFA94F31)
    /** 완료 칸 바탕(연테라코타) */
    val primarySoft = Color(0xFFF6E3DA)
    val fix = Color(0xFF1F7A3A); val float = Color(0xFFA86400); val error = Color(0xFFB42318)
    val disabled = Color(0xFFEFECE6); val onDisabled = Color(0xFF8C8B86)

    val minText = 16.sp; val body = 16.sp; val title = 22.sp; val coord = 17.sp; val display = 64.sp
    val minTouch = 56.dp; val measureButton = 96.dp; val gap = 8.dp
    /** 펼침(폴드 안쪽) 판단 폭과 오른쪽 패널 폭 */
    val unfoldedMinWidth = 600.dp; val sidePanel = 340.dp
}
