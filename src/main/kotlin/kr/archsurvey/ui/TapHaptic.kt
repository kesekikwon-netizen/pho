package kr.archsurvey.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * 버튼 탭 햅틱. 앱 설정「진동」을 따른다(LocalTapHaptic, SharedPreferences 기본 ON).
 *
 * 0.1.16: 시스템「터치 진동」과 무관하게 Vibrator one-shot 으로 울린다.
 * (0.1.14~0.1.15 는 View.performHapticFeedback 만 써서 시스템 OFF 시 현장 무반응)
 */
object TapHaptics {
    /** 시험용 카운터: 탭 / 길게 / 완료 / 오류 / 직접 진동기 호출 */
    @Volatile var taps = 0
    @Volatile var longs = 0
    @Volatile var oks = 0
    @Volatile var errors = 0
    @Volatile var directPulses = 0
    fun reset() { taps = 0; longs = 0; oks = 0; errors = 0; directPulses = 0 }

    fun tap(v: View) { taps++; vibrateMs(v.context, 18L) }
    fun long(v: View) { longs++; vibrateMs(v.context, 40L) }
    fun ok(v: View) { oks++; vibrateMs(v.context, 25L) }
    fun error(v: View) { errors++; vibrateMs(v.context, 55L) }

    /** 요청 횟수(directPulses)는 하드웨어 유무와 무관하게 센다 — 단위시험·진단용. */
    @Suppress("DEPRECATION")
    internal fun vibrateMs(ctx: Context, ms: Long) {
        directPulses++
        try {
            val vib = vibrator(ctx) ?: return
            if (!vib.hasVibrator()) return
            val d = ms.coerceAtLeast(1L)
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(d)
            }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) { null }
}

/** 탭 햅틱 켜짐 여부(설정「진동」, 기본 true). 사진측량 앱은 기본 true */
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
