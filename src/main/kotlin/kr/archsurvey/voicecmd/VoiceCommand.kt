package kr.archsurvey.voicecmd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * 0.1.28 T14 음성 「저장」 측점. Android 의존 없음 → JVM 단위 시험.
 * 원칙(plan/research-voice-command-save.md §7·§9·§14):
 *  - 음성·이어폰 버튼은 측량 버튼과 **같은 경로**(SurveyController.onMeasure)만 부른다. 품질 게이트 우회 없음.
 *  - 화면 확인(주의·기울기·중복)은 음성으로도 **하나씩** 묻고, 승인은 음성 「예」 또는 화면 탭만. 10초 지나면 취소.
 *  - 이어폰 버튼 = 저장 **요청만**(승인 불가, 확인 대기 중 누르면 취소).
 *  - 측량 화면이 앞에 있고 수신기가 연결돼 있을 때만 듣는다. 아니면 멈추고, 그동안 들린 「저장」은 버린다(나중에 실행 안 함).
 *  - 마이크는 중재자 1개(음성 메모 녹음과 배타). TTS 중에는 인식을 멈춘다(자기 트리거 방지).
 */

/** 알아듣는 말 세 가지 */
enum class VoiceWord { SAVE, YES, CANCEL }

/**
 * 오인식 방지: 인식 결과 **첫 번째 후보 하나**를 공백·문장부호를 없앤 뒤 **정확히** 비교한다.
 * 「저장」 단독만 저장. 「저장해」「저장소」「저장 저장」「P12 저장됨」 등은 버린다.
 */
object VoiceMatcher {
    private val junk = Regex("[\\s\\p{P}\\p{S}]+")
    fun normalize(s: String?): String = s?.replace(junk, "") ?: ""
    fun match(text: String?): VoiceWord? = when (normalize(text)) {
        "저장" -> VoiceWord.SAVE
        "예" -> VoiceWord.YES
        "취소", "아니요", "아니오" -> VoiceWord.CANCEL
        else -> null
    }
    /** 엔진이 준 후보 목록 중 **첫 번째만** 본다(다른 후보에 「저장」이 섞여도 받지 않음) */
    fun matchTop(alternatives: List<String>?): VoiceWord? = match(alternatives?.firstOrNull())
}

/** 마이크 주인. 명령 듣기 ↔ 음성 메모 녹음은 동시에 쓰지 않는다 */
enum class MicOwner { NONE, COMMAND, MEMO }

/** 마이크 중재자(앱에 1개). 메모 녹음이 오면 명령 듣기를 먼저 멈추게 하고 넘겨받는다 */
class MicArbiter {
    var owner: MicOwner = MicOwner.NONE; private set
    /** 명령 듣기를 즉시 멈추는 콜백(음성 명령 컨트롤러가 등록) */
    var stopCommand: (() -> Unit)? = null

    fun tryAcquire(who: MicOwner): Boolean {
        if (who == MicOwner.NONE) return false
        if (owner == who) return true
        return when (owner) {
            MicOwner.NONE -> { owner = who; true }
            MicOwner.COMMAND -> if (who == MicOwner.MEMO) { stopCommand?.invoke(); owner = MicOwner.MEMO; true } else false
            MicOwner.MEMO -> false
        }
    }
    fun release(who: MicOwner) { if (owner == who) owner = MicOwner.NONE }
}

/** 인식 방식. ON_DEVICE = 기기 안(오프라인), ONLINE = 표준 인식기(인터넷으로 말소리가 보내질 수 있음), NONE = 쓸 수 없음 */
enum class RecognizerMode(val labelKo: String) { ON_DEVICE("기기 내 인식"), ONLINE("온라인 인식"), NONE("인식 불가") }

/** 엔진 상태 알림 */
enum class EngineEvent { READY, NETWORK_ERROR, AUDIO_ERROR, PERMISSION_DENIED, ON_DEVICE_UNAVAILABLE, UNAVAILABLE }

/** 연속 듣기 엔진. start 후 stop 까지 발화마다 세션을 다시 연다(엔진 안에서) */
interface SpeechEngine {
    fun start(onText: (List<String>) -> Unit, onEvent: (EngineEvent) -> Unit)
    /** 멈추고 진행 중 결과는 버린다 */
    fun stop()
    fun release() { stop() }
}

/** 음성 안내(TTS). 쓸 수 없으면 speak 가 false */
interface Speaker {
    fun speak(text: String, onDone: () -> Unit): Boolean
    fun stop()
    fun release() {}
}

/** 기기 쪽 기능(권한·엔진·설정 저장) */
interface VoicePlatform {
    /** 이 폰에서 음성 인식 서비스를 찾을 수 있는가 */
    val supported: Boolean
    fun hasMicPermission(): Boolean
    fun requestMicPermission(onResult: (Boolean) -> Unit)
    /** 인식 방식 확인(비동기). note = 사용자 안내 한 줄(예: 언어팩 내려받기 요청) */
    fun probe(onResult: (RecognizerMode, String?) -> Unit)
    fun engine(mode: RecognizerMode): SpeechEngine
    fun speaker(): Speaker
    /** 저장 값: 켜짐, 온라인 인식 동의 */
    var savedOn: Boolean
    var onlineConsent: Boolean
}

/** 화면에 떠 있는 확인 하나. token 은 같은 확인인지 보는 열쇠(같은 객체여야 답한다) */
data class VoicePrompt(val kind: Kind, val token: Any, val question: String) {
    enum class Kind { CAUTION, TILT, DUP }
}

/** 측량 화면 쪽(SurveyController) */
interface VoiceHost {
    /** 측량 화면이 맨 앞(다른 창·재생 없음) */
    fun screenReady(): Boolean
    /** 수신기 연결됨(미연결·끊김 아님) */
    fun linkUp(): Boolean
    /** 측정 중·녹음 중 */
    fun busy(): Boolean
    /** 측량 버튼과 같은 경로 */
    fun requestSave()
    fun prompt(): VoicePrompt?
    /** 그 확인에 답한다. 이미 사라졌으면 false */
    fun answer(p: VoicePrompt, yes: Boolean): Boolean
    /** 저장(새 점·재측량)마다 1씩 오르는 번호 */
    fun saveSerial(): Int
    fun lastSavedText(): String?
    fun lastMessage(): String?
    fun notice(text: String, ok: Boolean)
    fun ask(title: String, body: String, okLabel: String, onOk: () -> Unit, onCancel: () -> Unit)
    /** 인식 순간 햅틱·소리(설정 따름) */
    fun heardFeedback()
}

/** 화면 표시용 상태 */
enum class VoiceState { OFF, WAITING, LISTENING, SPEAKING, CONFIRM, COOLDOWN }

/**
 * 음성 명령 상태 기계. 화면 스레드에서 [tick] 을 약 200ms 마다 부른다(시험은 직접 부름).
 */
class VoiceCommandController(
    private val host: VoiceHost,
    private val platform: VoicePlatform,
    val mic: MicArbiter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val CONFIRM_TIMEOUT_MS = 10_000L
        /** 기존 연타 방지 규칙(FeedbackPlayer)과 같은 1.5초 */
        const val ACCEPT_COOLDOWN_MS = 1_500L
        /** 연속 오인식(알아듣지 못한 말) 이 횟수면 1.5초 쉼 */
        const val MISS_LIMIT = 3
        /** TTS 끝난 뒤 잔향이 들리지 않게 쉬는 시간 */
        const val TTS_GUARD_MS = 500L
        const val TTS_MAX_MS = 8_000L
        /** 0.1.30 T24: 수신기 없이 저장 요청(이어폰 키·음성) — 측점 버튼과 같은 경로로 막고 이렇게 말한다 */
        const val NO_RECEIVER_SPEECH = "수신기 없음, 저장 안 됨"
        /** 음성 저장 후 이 시간 안에 되돌리면 잘못 들은 것으로 센다 */
        const val FALSE_ACCEPT_WINDOW_MS = 10_000L
        const val FALSE_ACCEPT_SPAN_MS = 10 * 60_000L
        const val FALSE_ACCEPT_LIMIT = 2
        const val FALSE_ACCEPT_PAUSE_MS = 5 * 60_000L
        const val SESSION_MAX_MS = 120_000L
    }

    enum class Source { VOICE, MEDIA_KEY }

    var enabled by mutableStateOf(false); private set
    /** 이 폰에 음성 인식 서비스가 있는가(없으면 설정에서 숨김·비활성) */
    val supported: Boolean get() = platform.supported
    var mode by mutableStateOf(RecognizerMode.NONE); private set
    var state by mutableStateOf(VoiceState.OFF); private set
    /** 엔진 문제 안내(예: 온라인 인식 연결 안 됨). 없으면 null */
    var issue by mutableStateOf<String?>(null); private set
    /** 확인 대기 남은 초(표시용) */
    var confirmLeftSec by mutableIntStateOf(0); private set
    /** 지금 묻고 있는 확인(표시용) */
    var asking by mutableStateOf<VoicePrompt?>(null); private set
    /** 앱이 앞에 있는가(Activity onResume/onPause) */
    var foreground = false; private set

    // ---- 시험·진단용 계수 ----
    var droppedResults = 0; private set
    var acceptedSaves = 0; private set
    var lastHeard: String? = null; private set

    private var engine: SpeechEngine? = null
    private var speaker: Speaker? = null
    private var engineOn = false
    private var gen = 0
    private var misses = 0
    private var holdUntil = 0L
    private var lastAcceptAt = Long.MIN_VALUE / 2
    private var pausedUntil = 0L
    private val falseAccepts = ArrayList<Long>()
    private var lastVoiceSaveAt = Long.MIN_VALUE / 2

    private class Session(val serialAtStart: Int, val startedAt: Long, val source: Source)
    private var session: Session? = null
    private var confirm: VoicePrompt? = null
    private var lastAskedToken: Any? = null
    private var confirmDeadline = 0L

    private var speaking = false
    /** 수신기 없음 안내 발화: 듣기 조건(연결)이 꺼져 있어도 끝까지 말한다 */
    private var keepSpeech = false
    private var speakEndAt = 0L
    private var speakStartAt = 0L
    /** 말이 끝나면 확인 대기로 들어갈지 */
    private var confirmAfterSpeech = false

    init { mic.stopCommand = { stopEngine() } }

    // ------------------------------------------------------------ 켜기·끄기

    /** 앱 시작: 저장된 켜짐 값 복원(권한·방식 다시 확인, 온라인은 동의가 있을 때만) */
    fun restore() {
        if (!platform.savedOn) return
        if (!platform.supported || !platform.hasMicPermission()) { platform.savedOn = false; return }
        platform.probe { m, _ ->
            when {
                m == RecognizerMode.ON_DEVICE -> activate(m)
                m == RecognizerMode.ONLINE && platform.onlineConsent -> activate(m)
                else -> platform.savedOn = false
            }
        }
    }

    /** 설정 토글. 켤 때만 마이크 권한을 묻는다 */
    fun toggle(on: Boolean) {
        if (!on) { deactivate(); platform.savedOn = false; host.notice("음성 저장 끔", true); return }
        if (!platform.supported) { host.notice("이 폰에서는 음성 인식 서비스를 찾을 수 없어요", false); return }
        val go = {
            platform.probe { m, note ->
                when (m) {
                    RecognizerMode.NONE -> host.notice("이 폰에서는 한국어 음성 인식을 쓸 수 없어요" + (note?.let { " · $it" } ?: ""), false)
                    RecognizerMode.ON_DEVICE -> { activate(m); platform.savedOn = true
                        host.notice("음성 저장 켬 · 기기 내 인식(오프라인)" + (note?.let { " · $it" } ?: ""), true) }
                    RecognizerMode.ONLINE -> host.ask(
                        "온라인 인식으로 켤까요?",
                        "이 폰은 한국어 기기 내 인식을 쓸 수 없어요. 측량 화면에서 듣는 동안 말소리가 음성 인식 서비스(인터넷)로 보내질 수 있어요. 인터넷이 없으면 인식되지 않아요. 앱은 소리를 저장하지 않아요." + (note?.let { "\n$it" } ?: ""),
                        "온라인 인식 켜기",
                        { platform.onlineConsent = true; activate(m); platform.savedOn = true; host.notice("음성 저장 켬 · 온라인 인식", true) },
                        { host.notice("음성 저장을 켜지 않았어요", false) },
                    )
                }
            }
        }
        if (platform.hasMicPermission()) go()
        else platform.requestMicPermission { ok -> if (ok) go() else host.notice("마이크 권한이 없어 음성 저장을 켜지 않았어요 — 설정 > 앱 > 권한 > 마이크", false) }
    }

    private fun activate(m: RecognizerMode) {
        engine?.release(); engine = platform.engine(m)
        if (speaker == null) speaker = platform.speaker() // TTS 초기화는 비동기라 미리 깨운다
        mode = m; enabled = true; issue = null; state = VoiceState.WAITING
        tick()
    }

    private fun deactivate() {
        stopEngine(); clearFlow()
        speaker?.stop()
        engine?.release(); engine = null
        enabled = false; state = VoiceState.OFF; issue = null
    }

    fun release() { deactivate(); speaker?.release(); speaker = null }

    // ------------------------------------------------------------ 생명주기

    /** Activity onResume(true)/onPause(false). 멈추면 듣던 것은 버린다 */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (!fg) { stopEngine(); speaker?.stop(); speaking = false; clearFlow(); if (enabled) state = VoiceState.WAITING }
        else tick()
    }

    /** 지금 들어도 되는가(측량 화면 앞·연결·마이크·쉼 아님) */
    private fun canHear(now: Long): Boolean =
        enabled && foreground && now >= pausedUntil && host.screenReady() && host.linkUp() && mic.owner != MicOwner.MEMO

    private fun startEngine() {
        if (engineOn) return
        val e = engine ?: return
        if (!mic.tryAcquire(MicOwner.COMMAND)) return
        engineOn = true
        val g = ++gen
        e.start({ texts -> onHeard(g, texts) }, { ev -> onEngineEvent(g, ev) })
    }

    private fun stopEngine() {
        if (engineOn) { engineOn = false; gen++; engine?.stop() }
        mic.release(MicOwner.COMMAND)
    }

    private fun clearFlow() {
        session = null; confirm = null; asking = null; lastAskedToken = null; confirmAfterSpeech = false; confirmLeftSec = 0
    }

    // ------------------------------------------------------------ 주기 처리

    fun tick() {
        val now = clock()
        if (!enabled) { if (engineOn) stopEngine(); state = VoiceState.OFF; return }
        if (!canHear(now)) {
            // 화면을 떠남·연결 끊김·녹음 중: 듣기를 멈추고 진행 중이던 음성 흐름을 버린다(화면의 확인 창은 손으로 답할 수 있게 그대로 둔다)
            if (engineOn) stopEngine()
            if (speaking && (!keepSpeech || now - speakStartAt > TTS_MAX_MS)) { speaker?.stop(); speaking = false; keepSpeech = false }
            clearFlow()
            state = if (now < pausedUntil) VoiceState.COOLDOWN else VoiceState.WAITING
            return
        }
        if (speaking) {
            if (now - speakStartAt > TTS_MAX_MS) { speaker?.stop(); speaking = false; speakEndAt = now }
            else { state = VoiceState.SPEAKING; return }
        }
        if (now < speakEndAt + TTS_GUARD_MS) { state = VoiceState.SPEAKING; return }
        if (confirmAfterSpeech) {
            confirmAfterSpeech = false
            if (confirm != null) confirmDeadline = now + CONFIRM_TIMEOUT_MS
        }
        val c = confirm
        if (c != null) {
            val p = host.prompt()
            if (p == null || p.token !== c.token) {
                // 화면에서 답함 → 다음 확인이 있으면 이어서 묻는다
                confirm = null; asking = null; confirmLeftSec = 0
            } else if (now >= confirmDeadline) {
                host.answer(c, false)
                confirm = null; asking = null; session = null; confirmLeftSec = 0
                say("저장 안 함")
                return
            } else {
                confirmLeftSec = (((confirmDeadline - now) + 999) / 1000).toInt()
                state = VoiceState.CONFIRM
                if (now >= holdUntil) startEngine()
                return
            }
        }
        if (session != null) checkSession(now)
        if (speaking || confirm != null) return
        if (now < holdUntil) { if (engineOn) stopEngine(); state = VoiceState.COOLDOWN; return }
        startEngine()
        state = if (engineOn) VoiceState.LISTENING else VoiceState.WAITING
    }

    /** 음성으로 시작한 측점 흐름: 새 확인이 뜨면 묻고, 끝나면 결과를 말한다 */
    private fun checkSession(now: Long) {
        val s = session ?: return
        val p = host.prompt()
        if (p != null) {
            if (p.token !== lastAskedToken) askPrompt(p)
            return
        }
        if (host.busy()) { if (now - s.startedAt > SESSION_MAX_MS) session = null; return }
        session = null
        if (host.saveSerial() != s.serialAtStart) {
            lastVoiceSaveAt = now
            say(host.lastSavedText() ?: "저장됨")
        } else if (!host.linkUp()) {
            say(NO_RECEIVER_SPEECH)
        } else {
            val why = host.lastMessage()?.substringAfter(" — ", "")?.takeIf { it.isNotBlank() }
            say("저장 안 됨" + (why?.let { ", $it" } ?: ""))
        }
    }

    private fun askPrompt(p: VoicePrompt) {
        lastAskedToken = p.token
        confirm = p; asking = p
        confirmLeftSec = (CONFIRM_TIMEOUT_MS / 1000).toInt()
        if (!say(p.question)) { confirmDeadline = clock() + CONFIRM_TIMEOUT_MS; state = VoiceState.CONFIRM; startEngine() }
        else confirmAfterSpeech = true
    }

    /** TTS. 말하는 동안 인식을 멈춘다. TTS 를 쓸 수 없으면 false */
    private fun say(text: String): Boolean {
        stopEngine()
        val sp = speaker ?: platform.speaker().also { speaker = it }
        val now = clock()
        val started = sp.speak(text) { speaking = false; keepSpeech = false; speakEndAt = clock() }
        if (started) { speaking = true; speakStartAt = now; state = VoiceState.SPEAKING }
        return started
    }

    // ------------------------------------------------------------ 입력

    /** 엔진 결과. 세대가 다르거나(멈춘 뒤 늦게 온 결과) 지금 들을 수 없으면 버린다 */
    fun onHeard(g: Int, texts: List<String>) {
        val now = clock()
        if (g != gen || !engineOn || !canHear(now) || speaking) { droppedResults++; return }
        lastHeard = texts.firstOrNull()
        val w = VoiceMatcher.matchTop(texts)
        if (w == null) {
            if (++misses >= MISS_LIMIT) { misses = 0; holdUntil = now + ACCEPT_COOLDOWN_MS; stopEngine(); state = VoiceState.COOLDOWN }
            return
        }
        misses = 0
        val c = confirm
        if (c != null && !confirmAfterSpeech) {
            when (w) {
                VoiceWord.YES -> {
                    host.heardFeedback()
                    confirm = null; asking = null; confirmLeftSec = 0
                    host.answer(c, true)
                    // 다음 확인(있으면)·결과는 tick 이 이어서
                    checkSession(now)
                }
                VoiceWord.CANCEL -> {
                    host.heardFeedback()
                    host.answer(c, false)
                    confirm = null; asking = null; session = null; confirmLeftSec = 0
                    say("저장 안 함")
                }
                VoiceWord.SAVE -> {} // 「저장」은 승인이 아니다(§14) — 무시
            }
            return
        }
        if (w == VoiceWord.SAVE) requestSave(Source.VOICE)
    }

    private fun onEngineEvent(g: Int, ev: EngineEvent) {
        if (g != gen) return
        when (ev) {
            EngineEvent.READY -> issue = null
            EngineEvent.NETWORK_ERROR -> issue = "온라인 인식 연결 안 됨"
            EngineEvent.AUDIO_ERROR -> issue = "마이크를 쓸 수 없어요"
            EngineEvent.PERMISSION_DENIED -> { deactivate(); platform.savedOn = false; host.notice("마이크 권한이 꺼져 음성 저장을 껐어요", false) }
            EngineEvent.ON_DEVICE_UNAVAILABLE -> { deactivate(); platform.savedOn = false; host.notice("기기 내 한국어 인식을 쓸 수 없어 음성 저장을 껐어요 — 설정에서 다시 켜면 온라인 인식을 고를 수 있어요", false) }
            EngineEvent.UNAVAILABLE -> { deactivate(); platform.savedOn = false; host.notice("음성 인식 서비스를 쓸 수 없어 음성 저장을 껐어요", false) }
        }
    }

    /** 이어폰 재생/일시정지 키. 처리했으면 true(앱이 키를 먹음). 확인 대기 중이면 **취소**(승인 불가) */
    fun onMediaKey(): Boolean {
        val now = clock()
        if (!canHear(now)) {
            // 0.1.30 T24: 연결만 빠졌으면(수신기 없음) 측점 버튼과 같은 경로(onMeasure → 안내·실패음)로 막고 「수신기 없음, 저장 안 됨」
            if (enabled && foreground && now >= pausedUntil && host.screenReady() && mic.owner != MicOwner.MEMO && !host.linkUp() && !host.busy() && !speaking) {
                if (now - lastAcceptAt < ACCEPT_COOLDOWN_MS) return true
                lastAcceptAt = now
                host.requestSave()
                if (say(NO_RECEIVER_SPEECH)) keepSpeech = true
                return true
            }
            return false
        }
        val c = confirm
        if (c != null) {
            host.answer(c, false)
            confirm = null; asking = null; session = null; confirmLeftSec = 0; confirmAfterSpeech = false
            speaker?.stop(); speaking = false
            say("저장 안 함")
            return true
        }
        requestSave(Source.MEDIA_KEY)
        return true
    }

    /** 저장 요청: 쿨다운·측정 중·확인 창 떠 있음·이미 진행 중이면 버린다. 아니면 버튼과 같은 경로 */
    private fun requestSave(src: Source) {
        val now = clock()
        if (now - lastAcceptAt < ACCEPT_COOLDOWN_MS) { droppedResults++; return }
        if (host.busy() || session != null || host.prompt() != null || speaking) { droppedResults++; return }
        lastAcceptAt = now
        acceptedSaves++
        host.heardFeedback()
        session = Session(host.saveSerial(), now, src)
        host.requestSave()
        checkSession(now)
        if (!speaking && confirm == null) tick()
    }

    /** 점을 되돌렸을 때(SurveyController). 음성 저장 직후 되돌림이 잦으면 음성 저장을 5분 쉰다 */
    fun onPointUndone() {
        val now = clock()
        if (!enabled || now - lastVoiceSaveAt > FALSE_ACCEPT_WINDOW_MS) return
        falseAccepts.add(now)
        falseAccepts.removeAll { now - it > FALSE_ACCEPT_SPAN_MS }
        if (falseAccepts.size >= FALSE_ACCEPT_LIMIT) {
            falseAccepts.clear()
            pausedUntil = now + FALSE_ACCEPT_PAUSE_MS
            stopEngine(); clearFlow(); state = VoiceState.COOLDOWN
            host.notice("음성 저장 직후 되돌리기가 반복돼 5분 쉬어요", false)
        }
    }

    /** 화면 한 줄 */
    fun indicatorText(): String = when (state) {
        VoiceState.OFF -> ""
        VoiceState.LISTENING -> "듣는 중 · 「저장」"
        VoiceState.SPEAKING -> "안내 중"
        VoiceState.CONFIRM -> "「예」 또는 화면 · ${confirmLeftSec}초 뒤 취소"
        VoiceState.COOLDOWN -> if (clock() < pausedUntil) "음성 저장 쉬는 중" else "잠시 쉼"
        VoiceState.WAITING -> when {
            !foreground -> "대기"
            !host.linkUp() -> "수신기 연결 대기"
            mic.owner == MicOwner.MEMO -> "녹음 중 · 듣기 멈춤"
            else -> "대기"
        }
    }
}
