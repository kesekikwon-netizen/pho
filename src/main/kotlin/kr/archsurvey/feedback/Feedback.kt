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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
    // 0.1.18 HTML 상호작용 규칙: 수신기 연결 끊김 = 길게 3회 + 경고음 (예전 길게 1회)
    LINK_LOST(longArrayOf(0, 600, 200, 600, 200, 600), ToneGenerator.TONE_CDMA_ABBR_ALERT, 800, Severity.ERROR, "측량기 연결 끊김"),
    LINK_RESTORED(longArrayOf(0, 50, 60, 50), ToneGenerator.TONE_PROP_BEEP, 200, Severity.OK, "측량기 다시 연결됨"),
    NTRIP_LOST(longArrayOf(0, 160, 100, 160), ToneGenerator.TONE_PROP_PROMPT, 300, Severity.WARN, "VRS 보정 끊김"),
    NTRIP_OK(longArrayOf(0, 50, 60, 50), ToneGenerator.TONE_PROP_BEEP, 150, Severity.OK, "VRS 보정 수신 중"),
    // 0.1.19 연결 전용(최초 STREAMING / 첫 연결 실패 / 사용자 해제). LINK_* 와 경로를 나눔(중첩 비프 방지)
    CONNECT_OK(longArrayOf(0, 50, 50, 80), ToneGenerator.TONE_PROP_ACK, 220, Severity.OK, "수신기 연결됨"),
    CONNECT_FAIL(longArrayOf(0, 280, 120, 280), ToneGenerator.TONE_PROP_NACK, 500, Severity.ERROR, "수신기 연결 실패"),
    DISCONNECT(longArrayOf(0, 80), ToneGenerator.TONE_PROP_BEEP, 120, Severity.WARN, "수신기 연결 해제"),
    // 0.1.28 T14: 음성 「저장」·「예」(또는 이어폰 버튼)를 알아들은 순간 — 짧은 틱 1회. 저장 결과음(SURVEY_OK/FAIL)은 따로 그대로
    VOICE_HEARD(longArrayOf(0, 35), ToneGenerator.TONE_PROP_BEEP, 90, Severity.OK, "음성 명령 알아들음"),
}

enum class Severity { OK, WARN, ERROR }

/**
 * 엔진 [SharedFlow] → [FeedbackPlayer.play] 배선.
 * ViewModel 수명(scope)에 묶고, 같은 이벤트의 연타 방지는 Player 쪽이 맡는다.
 */
fun bindEngineFeedback(scope: CoroutineScope, events: SharedFlow<FeedbackEvent>, play: (FeedbackEvent) -> Unit): Job =
    scope.launch { events.collect { play(it) } }

/** 진동·소리 출력. 설정(소리/진동) 존중. 실패해도 앱은 계속 동작. 진동은 API 26+ VibrationEffect, 이하 구형 API 로 분기. */
class FeedbackPlayer(private val ctx: Context, private val settings: SettingsStore) {
    private val handler = Handler(Looper.getMainLooper())
    private val lastAt = HashMap<FeedbackEvent, Long>()

    /** 시험용: 마지막 play 에서 beep/vibrate 를 시도했는지(실기기 Tone/Vibrator 와 무관) */
    var lastBeep: FeedbackEvent? = null; private set
    var lastVibrate: FeedbackEvent? = null; private set
    var playCount: Int = 0; private set

    fun play(e: FeedbackEvent) {
        val now = System.currentTimeMillis()
        if (now - (lastAt[e] ?: 0L) < 1500) return // 같은 이벤트 연타 방지
        lastAt[e] = now
        playCount++
        if (settings.vibrationOn) { lastVibrate = e; vibrate(e.pattern) } else lastVibrate = null
        if (settings.soundOn) { lastBeep = e; beep(e) } else lastBeep = null
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
