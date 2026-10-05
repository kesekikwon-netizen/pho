package kr.archsurvey.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.voicecmd.EngineEvent
import kr.archsurvey.voicecmd.RecognizerMode
import kr.archsurvey.voicecmd.SpeechEngine
import kr.archsurvey.voicecmd.Speaker
import kr.archsurvey.voicecmd.VoicePlatform
import java.util.Locale

/**
 * 0.1.28 T14: 플랫폼 SpeechRecognizer(추가 라이브러리·모델 없음, APK 크기 영향 0).
 *  - API 31+ 이고 기기 내 인식이 있으면 createOnDeviceSpeechRecognizer(오프라인). API 33+ 는 checkRecognitionSupport 로 ko-KR 설치 확인.
 *  - 아니면 표준 인식기 + EXTRA_PREFER_OFFLINE(API 23+, 보장 없음) = 「온라인 인식」(동의 후에만).
 *  - BT SCO 는 열지 않는다(폰 내장 마이크 주경로). 인식기 시작음(삑)은 앱이 끌 수 없음 [현장확인].
 * 마이크 권한은 음성 메모와 같은 요청 통로(AndroidMediaPlatform)를 쓴다.
 */
class AndroidVoicePlatform(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val mic: AndroidMediaPlatform,
) : VoicePlatform {
    private val main = Handler(Looper.getMainLooper())
    private var tts: AndroidSpeaker? = null

    override val supported: Boolean get() =
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT >= 31 && onDeviceAvailable31())

    @RequiresApi(31)
    private fun onDeviceAvailable31(): Boolean = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    override fun hasMicPermission(): Boolean = mic.hasMicPermission()
    override fun requestMicPermission(onResult: (Boolean) -> Unit) = mic.requestMicPermission(onResult)

    private fun standardOk() = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
    private fun fallback() = if (standardOk()) RecognizerMode.ONLINE else RecognizerMode.NONE

    override fun probe(onResult: (RecognizerMode, String?) -> Unit) {
        main.post {
            if (Build.VERSION.SDK_INT >= 31 && onDeviceAvailable31()) {
                if (Build.VERSION.SDK_INT >= 33) probe33(onResult)
                else onResult(RecognizerMode.ON_DEVICE, null) // 31~32: 언어 확인 API 없음 → 써 보고 안 되면 ON_DEVICE_UNAVAILABLE
            } else onResult(fallback(), null)
        }
    }

    @RequiresApi(33)
    private fun probe33(onResult: (RecognizerMode, String?) -> Unit) {
        var done = false
        fun finish(m: RecognizerMode, note: String?) { if (!done) { done = true; onResult(m, note) } }
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) { finish(fallback(), null); return }
        val intent = recognizeIntent()
        // 응답이 없으면 4초 뒤 기기 내 인식을 써 본다(안 되면 엔진이 ON_DEVICE_UNAVAILABLE)
        main.postDelayed({ if (!done) { finish(RecognizerMode.ON_DEVICE, null); runCatching { r.destroy() } } }, 4000)
        runCatching {
            r.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(ctx), object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(s: android.speech.RecognitionSupport) {
                    val ko = { l: List<String> -> l.any { it.lowercase(Locale.US).startsWith("ko") } }
                    when {
                        ko(s.installedOnDeviceLanguages) -> finish(RecognizerMode.ON_DEVICE, null)
                        ko(s.pendingOnDeviceLanguages) -> finish(fallback(), "한국어 기기 내 인식 언어팩을 받는 중이에요 — 받은 뒤 다시 켜면 오프라인으로 돼요")
                        ko(s.supportedOnDeviceLanguages) -> {
                            runCatching { r.triggerModelDownload(intent) }
                            finish(fallback(), "한국어 기기 내 인식 언어팩 내려받기를 요청했어요 — 받은 뒤 다시 켜면 오프라인으로 돼요")
                        }
                        else -> finish(fallback(), null)
                    }
                    main.post { runCatching { r.destroy() } }
                }
                override fun onError(error: Int) { finish(RecognizerMode.ON_DEVICE, null); main.post { runCatching { r.destroy() } } }
            })
        }.onFailure { finish(RecognizerMode.ON_DEVICE, null); runCatching { r.destroy() } }
    }

    override fun engine(mode: RecognizerMode): SpeechEngine = AndroidSpeechEngine(ctx, mode)

    override fun speaker(): Speaker = tts ?: AndroidSpeaker(ctx).also { tts = it }

    override var savedOn: Boolean
        get() = settings.voiceSaveOn
        set(v) { settings.voiceSaveOn = v }
    override var onlineConsent: Boolean
        get() = settings.voiceOnlineOk
        set(v) { settings.voiceOnlineOk = v }

    companion object {
        /** ko-KR 짧은 명령. 후보 1개만(오인식 방지는 VoiceMatcher 가 첫 후보만 봄) */
        fun recognizeIntent(packageName: String? = null): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            packageName?.let { putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, it) }
            if (Build.VERSION.SDK_INT >= 23) preferOffline23(this)
            if (Build.VERSION.SDK_INT >= 33) bias33(this)
        }
        @RequiresApi(23) private fun preferOffline23(i: Intent) { i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
        @RequiresApi(33) private fun bias33(i: Intent) {
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf("저장", "예", "취소"))
        }
    }
}

/**
 * 연속 듣기: 발화(세션)가 끝날 때마다 다시 시작한다. stop() 은 세대를 올려 늦게 온 결과·예약된 재시작을 모두 버린다.
 * 모든 호출은 화면(main) 스레드.
 */
class AndroidSpeechEngine(private val ctx: Context, private val mode: RecognizerMode) : SpeechEngine {
    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var wanted = false
    private var gen = 0
    private var onText: ((List<String>) -> Unit)? = null
    private var onEvent: ((EngineEvent) -> Unit)? = null

    override fun start(onText: (List<String>) -> Unit, onEvent: (EngineEvent) -> Unit) {
        this.onText = onText; this.onEvent = onEvent
        wanted = true
        val g = ++gen
        h.post { begin(g) }
    }

    override fun stop() {
        wanted = false; gen++
        h.post { runCatching { sr?.cancel() } }
    }

    override fun release() {
        stop()
        h.post { runCatching { sr?.destroy() }; sr = null }
    }

    private fun create(): SpeechRecognizer? = runCatching {
        if (mode == RecognizerMode.ON_DEVICE && Build.VERSION.SDK_INT >= 31) onDevice31() else SpeechRecognizer.createSpeechRecognizer(ctx)
    }.getOrNull()

    @RequiresApi(31) private fun onDevice31(): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)

    private fun recreate() { runCatching { sr?.destroy() }; sr = null }

    private fun again(g: Int, delayMs: Long) { h.postDelayed({ begin(g) }, delayMs) }

    private fun begin(g: Int) {
        if (!wanted || g != gen) return
        val r = sr ?: create()?.also { sr = it }
        if (r == null) { wanted = false; onEvent?.invoke(EngineEvent.UNAVAILABLE); return }
        r.setRecognitionListener(listener(g))
        try { r.startListening(AndroidVoicePlatform.recognizeIntent(ctx.packageName)) }
        catch (e: Exception) { recreate(); again(g, 1000) }
    }

    private fun listener(g: Int) = object : RecognitionListener {
        private fun live() = wanted && g == gen
        override fun onReadyForSpeech(params: Bundle?) { if (live()) onEvent?.invoke(EngineEvent.READY) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            if (!live()) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            onText?.invoke(list)
            if (live()) again(g, 150)
        }
        override fun onError(error: Int) {
            if (!live()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again(g, 150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recreate(); again(g, 800) }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { wanted = false; onEvent?.invoke(EngineEvent.PERMISSION_DENIED) }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> { onEvent?.invoke(EngineEvent.NETWORK_ERROR); again(g, 3000) }
                SpeechRecognizer.ERROR_AUDIO -> { onEvent?.invoke(EngineEvent.AUDIO_ERROR); again(g, 2000) }
                // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 31)
                12, 13 -> if (mode == RecognizerMode.ON_DEVICE) { wanted = false; onEvent?.invoke(EngineEvent.ON_DEVICE_UNAVAILABLE) } else { onEvent?.invoke(EngineEvent.NETWORK_ERROR); again(g, 3000) }
                // 11 = ERROR_SERVER_DISCONNECTED, 10 = ERROR_TOO_MANY_REQUESTS (API 31), 5 = ERROR_CLIENT
                11, SpeechRecognizer.ERROR_CLIENT -> { recreate(); again(g, 1000) }
                10 -> again(g, 3000)
                else -> again(g, 1000)
            }
        }
    }
}

/** 한국어 TTS. 한국어 음성이 없으면 speak=false(화면·효과음만) */
class AndroidSpeaker(ctx: Context) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var seq = 0
    private val pending = HashMap<String, () -> Unit>()
    private val tts: TextToSpeech? = runCatching {
        var ref: TextToSpeech? = null
        ref = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = runCatching { ref?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                ready = r >= TextToSpeech.LANG_AVAILABLE
            }
        }
        ref
    }.getOrNull()

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId) }
            @Deprecated("API 21 abstract") override fun onError(utteranceId: String?) { finish(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
        })
    }

    private fun finish(id: String?) { main.post { id?.let { pending.remove(it)?.invoke() } } }

    override fun speak(text: String, onDone: () -> Unit): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val id = "v${++seq}"
        pending[id] = onDone
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) { pending.remove(id); return false }
        return true
    }

    override fun stop() {
        runCatching { tts?.stop() }
        val cbs = pending.values.toList(); pending.clear(); cbs.forEach { it() }
    }

    override fun release() { stop(); runCatching { tts?.shutdown() } }
}
