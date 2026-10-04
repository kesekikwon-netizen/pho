package kr.archsurvey.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kr.archsurvey.data.SettingsStore

/** 현장 피드백 이벤트. 햅틱(진동 패턴) + 효과음 + 시각 효과(UI 오버레이)가 한 쌍으로 대응. */
enum class FeedbackEvent(
    /** 진동 패턴 [대기, 진동, 대기, 진동 ...] ms */
    val pattern: LongArray,
    val tone: Int,
    val toneMs: Int,
    val severity: Severity,
    val messageKo: String,
) {
    SURVEY_OK(longArrayOf(0, 60, 70, 60), ToneGenerator.TONE_PROP_ACK, 180, Severity.OK, "측점 저장됨"),
    SURVEY_FAIL(longArrayOf(0, 400, 120, 400, 120, 400), ToneGenerator.TONE_PROP_NACK, 700, Severity.ERROR, "측량 실패"),
    FIX_ACQUIRED(longArrayOf(0, 40, 40, 40, 40, 140), ToneGenerator.TONE_PROP_BEEP2, 250, Severity.OK, "RTK 고정 확보"),
    FIX_LOST(longArrayOf(0, 220, 100, 220), ToneGenerator.TONE_PROP_PROMPT, 400, Severity.WARN, "RTK 고정이 풀렸어요"),
    LINK_LOST(longArrayOf(0, 700), ToneGenerator.TONE_CDMA_ABBR_ALERT, 800, Severity.ERROR, "측량기 연결 끊김"),
    LINK_RESTORED(longArrayOf(0, 50, 60, 50), ToneGenerator.TONE_PROP_BEEP, 200, Severity.OK, "측량기 다시 연결됨"),
    NTRIP_LOST(longArrayOf(0, 160, 100, 160), ToneGenerator.TONE_PROP_PROMPT, 300, Severity.WARN, "VRS 보정 끊김"),
    NTRIP_OK(longArrayOf(0, 50, 60, 50), ToneGenerator.TONE_PROP_BEEP, 150, Severity.OK, "VRS 보정 수신 중"),
}

enum class Severity { OK, WARN, ERROR }

/** 진동·소리 출력. 설정(소리/진동) 존중. 실패해도 앱은 계속 동작. 진동은 API 26+ VibrationEffect, 이하 구형 API 로 분기. */
class FeedbackPlayer(private val ctx: Context, private val settings: SettingsStore) {
    private val handler = Handler(Looper.getMainLooper())
    private val lastAt = HashMap<FeedbackEvent, Long>()

    fun play(e: FeedbackEvent) {
        val now = System.currentTimeMillis()
        if (now - (lastAt[e] ?: 0L) < 1500) return // 같은 이벤트 연타 방지
        lastAt[e] = now
        if (settings.vibrationOn) vibrate(e.pattern)
        if (settings.soundOn) beep(e)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(p: LongArray) {
        try {
            val v = ContextCompat.getSystemService(ctx, Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1))
            else v.vibrate(p, -1)
        } catch (_: Exception) { }
    }

    private fun beep(e: FeedbackEvent) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tg.startTone(e.tone, e.toneMs)
            handler.postDelayed({ runCatching { tg.release() } }, e.toneMs + 200L)
        } catch (_: Exception) { }
    }
}
