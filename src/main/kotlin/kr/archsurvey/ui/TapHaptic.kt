package kr.archsurvey.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

/**
 * 0.1.14 버튼 탭 햅틱. 앱 설정「진동」을 따르고(LocalTapHaptic), 폰의 시스템「터치 진동」설정도 존중한다
 * (View.performHapticFeedback 은 시스템 설정이 꺼져 있으면 울리지 않음). 진동기가 없어도 조용히 넘어간다.
 */
object TapHaptics {
    /** 시험용 카운터: 탭 / 길게 / 완료 / 오류 햅틱이 몇 번 요청됐나 */
    @Volatile var taps = 0
    @Volatile var longs = 0
    @Volatile var oks = 0
    @Volatile var errors = 0
    fun reset() { taps = 0; longs = 0; oks = 0; errors = 0 }

    fun tap(v: View) { taps++; runCatching { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
    fun long(v: View) { longs++; runCatching { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }
    /** 완료(저장·적용·내보내기 끝) — API 30+ CONFIRM, 이하 짧은 탭 */
    fun ok(v: View) { oks++; runCatching { v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) } }
    /** 오류 — API 30+ REJECT, 이하 길게 */
    fun error(v: View) { errors++; runCatching { v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS) } }
}

/** 탭 햅틱 켜짐 여부(설정「진동」). SurveyMapScreen 이 c.settingsState?.vibration 으로 제공 */
val LocalTapHaptic = staticCompositionLocalOf { true }

/** clickable 과 같지만 누를 때 짧은 햅틱 */
fun Modifier.hClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current; val on = LocalTapHaptic.current
    clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) { if (on) TapHaptics.tap(view); onClick() }
}

/** combinedClickable + 햅틱(탭 = 짧게, 길게 누름 = LONG_PRESS) */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hCombinedClickable(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current; val on = LocalTapHaptic.current
    combinedClickable(
        enabled = enabled,
        onLongClick = onLongClick?.let { lc -> { if (on) TapHaptics.long(view); lc() } },
        onClick = { if (on) TapHaptics.tap(view); onClick() },
    )
}
