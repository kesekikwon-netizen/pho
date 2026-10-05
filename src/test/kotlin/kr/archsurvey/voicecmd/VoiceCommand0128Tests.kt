package kr.archsurvey.voicecmd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.FakeTiltPlatform
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.tiltReading
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// ---------------------------------------------------------------- 가짜 기기

class FakeEngine : SpeechEngine {
    var running = false; var starts = 0; var stops = 0
    /** 지금까지 start 때 받은 콜백(멈춘 뒤 늦게 오는 결과 흉내용) */
    val callbacks = ArrayList<(List<String>) -> Unit>()
    var events: ((EngineEvent) -> Unit)? = null
    override fun start(onText: (List<String>) -> Unit, onEvent: (EngineEvent) -> Unit) { running = true; starts++; callbacks += onText; events = onEvent }
    override fun stop() { if (running) stops++; running = false }
    /** 지금 세션 결과 */
    fun emit(vararg alts: String) { callbacks.last().invoke(alts.toList()) }
    /** 멈춘 뒤 늦게 도착한 결과 */
    fun emitLate(vararg alts: String) { callbacks.last().invoke(alts.toList()) }
}

class FakeSpeaker(var available: Boolean = true) : Speaker {
    val said = ArrayList<String>()
    private var done: (() -> Unit)? = null
    override fun speak(text: String, onDone: () -> Unit): Boolean { if (!available) return false; said += text; done = onDone; return true }
    override fun stop() { val d = done; done = null; d?.invoke() }
    fun finish() { val d = done; done = null; d?.invoke() }
}

class FakeVoicePlatform(val eng: FakeEngine, val spk: FakeSpeaker, var probeMode: RecognizerMode = RecognizerMode.ON_DEVICE) : VoicePlatform {
    override var supported = true
    var perm = false; var grant = true; var permRequests = 0
    override fun hasMicPermission() = perm
    override fun requestMicPermission(onResult: (Boolean) -> Unit) { permRequests++; perm = grant; onResult(grant) }
    override fun probe(onResult: (RecognizerMode, String?) -> Unit) = onResult(probeMode, null)
    override fun engine(mode: RecognizerMode): SpeechEngine = eng
    override fun speaker(): Speaker = spk
    override var savedOn = false
    override var onlineConsent = false
}

private class MemStore : PointStore {
    val saved = ArrayList<SavedPoint>()
    override suspend fun loadAll() = saved.toList()
    override suspend fun maxSeq() = saved.size
    override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
    override suspend fun hideLast() {}
}

private class Sett(override var requireFixed: Boolean = false) : SettingsPlatform {
    override var zone = KoreanTmZone.CENTRAL
    override var poleRight = true
    override var sound = true
    override var vibration = true
    override var keepScreenOn = false
}

private class FakeMedia : MediaPlatform {
    var recording = false
    override suspend fun photos(pointRowId: Long) = emptyList<PhotoItem>()
    override suspend fun voices(pointRowId: Long) = emptyList<VoiceItem>()
    override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Boolean) -> Unit) {}
    override fun pickPhotos(pointId: String, pointRowId: Long, remaining: Int, onDone: (Int) -> Unit) {}
    override suspend fun deletePhoto(id: Long) {}
    override fun thumb(rel: String) = null
    override fun hasMicPermission() = true
    override fun requestMicPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun startRecording(pointId: String): String? { recording = true; return null }
    override suspend fun finishRecording(pointRowId: Long): VoiceItem? { recording = false; return VoiceItem(1, "a.m4a", 2000) }
    override fun cancelRecording() { recording = false }
    override fun play(rel: String, onDone: () -> Unit) = true
    override fun stopPlayback() {}
    override suspend fun deleteVoice(id: Long) {}
}

private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

/** 진짜 SurveyController + 가짜 수신기 + 가짜 음성 엔진 */
private class Rig(
    scenario: FakeScenario = FakeScenarios.allFixed(),
    strict: Boolean = false,
    epochs: Int = 3,
    mode: RecognizerMode = RecognizerMode.ON_DEVICE,
) {
    val f = FakeGnssSource(scenario)
    val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = epochs, store = MemStore())
    val eng = FakeEngine(); val spk = FakeSpeaker()
    val plat = FakeVoicePlatform(eng, spk, mode).also { it.perm = true }
    var now = 1_000_000L
    val v = VoiceCommandController(c.voiceHost, plat, c.mic) { now }
    var heard = 0
    val results = ArrayList<Boolean>()
    init {
        c.settingsState = SettingsState(c, Sett(strict))
        c.voice = v
        c.voiceHeard = { heard++ }
        c.feedback = { results += it }
    }
    fun on(): Rig { v.toggle(true); v.setForeground(true); v.tick(); return this }
    fun advance(ms: Long) { now += ms; v.tick() }
    /** TTS 끝 + 잔향 쉼 */
    fun speechDone() { spk.finish(); advance(VoiceCommandController.TTS_GUARD_MS + 50) }
    fun emit(n: Int) = repeat(n) { f.emitNext() }
}

private fun stationary() = FakeScenario("still", listOf(FakeSegment("고정", 600, FixQuality.RTK_FIXED, 0.004, 24, 0.6)))

class VoiceMatcher0128Tests {
    @Test fun onlyStandaloneSaveMatches() {
        for (ok in listOf("저장", " 저장 ", "저장.", "저장!", "「저장」", "저장?", "\"저장\"")) assertEquals(ok, VoiceWord.SAVE, VoiceMatcher.match(ok))
        for (bad in listOf("저장해", "저장소", "저장 해", "저장했어", "저장 저장", "P-0012 저장됨", "자장", "저장할까요", "측점 저장", "", " ", null))
            assertNull(bad.toString(), VoiceMatcher.match(bad))
    }
    @Test fun onlyTopAlternativeCounts() {
        assertNull(VoiceMatcher.matchTop(listOf("저장해", "저장")))
        assertEquals(VoiceWord.SAVE, VoiceMatcher.matchTop(listOf("저장", "저장해")))
        assertNull(VoiceMatcher.matchTop(emptyList())); assertNull(VoiceMatcher.matchTop(null))
    }
    @Test fun yesAndCancelWords() {
        assertEquals(VoiceWord.YES, VoiceMatcher.match("예"))
        assertEquals(VoiceWord.YES, VoiceMatcher.match("예."))
        assertNull("「네」는 승인 아님(명세 「예」만)", VoiceMatcher.match("네"))
        assertNull(VoiceMatcher.match("예 저장"))
        for (w in listOf("취소", "아니요", "아니오")) assertEquals(VoiceWord.CANCEL, VoiceMatcher.match(w))
    }
}

class MicArbiter0128Tests {
    @Test fun memoPreemptsCommandAndCommandWaits() {
        val a = MicArbiter(); var stopped = 0; a.stopCommand = { stopped++; a.release(MicOwner.COMMAND) }
        assertTrue(a.tryAcquire(MicOwner.COMMAND)); assertEquals(MicOwner.COMMAND, a.owner)
        assertTrue(a.tryAcquire(MicOwner.MEMO)); assertEquals(1, stopped); assertEquals(MicOwner.MEMO, a.owner)
        assertFalse("녹음 중엔 명령 듣기 불가", a.tryAcquire(MicOwner.COMMAND))
        a.release(MicOwner.COMMAND); assertEquals(MicOwner.MEMO, a.owner) // 남의 것은 못 놓음
        a.release(MicOwner.MEMO); assertEquals(MicOwner.NONE, a.owner)
        assertTrue(a.tryAcquire(MicOwner.COMMAND))
    }

    @Test fun voiceMemoStopsListeningAndResumesAfter() {
        val r = Rig(epochs = 1).on()
        val media = FakeMedia(); r.c.media = media
        r.emit(2); r.v.tick()
        r.c.onMeasure()
        assertEquals(Phase.RESULT, r.c.phase)
        assertTrue(r.eng.running)
        r.c.toggleVoice() // 녹음 시작
        assertTrue(media.recording); assertEquals(MicOwner.MEMO, r.c.mic.owner)
        assertFalse("녹음 시작 전에 명령 듣기를 멈춤", r.eng.running)
        r.advance(200)
        assertFalse(r.eng.running); assertEquals(VoiceState.WAITING, r.v.state)
        r.eng.emitLate("저장"); assertEquals(Phase.RECORDING, r.c.phase); assertEquals(1, r.c.points.size)
        r.c.toggleVoice() // 녹음 끝
        assertNotEquals(MicOwner.MEMO, r.c.mic.owner)
        r.advance(200)
        assertTrue("녹음 끝나면 다시 듣기", r.eng.running); assertEquals(MicOwner.COMMAND, r.c.mic.owner)
    }
}

class VoiceGatePath0128Tests {
    @Test fun defaultOffAndPermissionOnlyWhenTurnedOn() {
        val r = Rig(); r.plat.perm = false
        r.v.restore(); r.v.setForeground(true); r.v.tick()
        assertFalse(r.v.enabled); assertEquals(0, r.plat.permRequests); assertFalse(r.eng.running)
        r.plat.grant = false
        r.v.toggle(true)
        assertEquals(1, r.plat.permRequests); assertFalse(r.v.enabled); assertFalse(r.plat.savedOn)
        r.plat.grant = true
        r.v.toggle(true)
        assertEquals(2, r.plat.permRequests); assertTrue(r.v.enabled); assertTrue(r.plat.savedOn)
        assertEquals(RecognizerMode.ON_DEVICE, r.v.mode)
        r.v.toggle(false); assertFalse(r.v.enabled); assertFalse(r.plat.savedOn); assertFalse(r.eng.running)
    }

    @Test fun onlineModeNeedsConsentAndIsLabelled() {
        val r = Rig(mode = RecognizerMode.ONLINE)
        r.v.toggle(true)
        assertFalse("동의 전에는 켜지지 않음", r.v.enabled)
        val ask = r.c.ui.confirm!!
        assertTrue(ask.title.contains("온라인")); assertTrue(ask.body.contains("인터넷"))
        r.c.ui.answer(true)
        assertTrue(r.v.enabled); assertEquals(RecognizerMode.ONLINE, r.v.mode); assertTrue(r.plat.onlineConsent)
        // 거절
        val r2 = Rig(mode = RecognizerMode.ONLINE); r2.v.toggle(true); r2.c.ui.answer(false)
        assertFalse(r2.v.enabled); assertFalse(r2.plat.savedOn)
        // 복원: 온라인은 동의가 있어야
        val r3 = Rig(mode = RecognizerMode.ONLINE); r3.plat.savedOn = true; r3.v.restore(); assertFalse(r3.v.enabled); assertFalse(r3.plat.savedOn)
        val r4 = Rig(mode = RecognizerMode.ONLINE); r4.plat.savedOn = true; r4.plat.onlineConsent = true; r4.v.restore(); assertTrue(r4.v.enabled)
        assertEquals("온라인 인식", RecognizerMode.ONLINE.labelKo)
        assertTrue(kr.archsurvey.ui.VoiceCopy.modeLine(RecognizerMode.ONLINE).contains("인터넷"))
    }

    @Test fun unsupportedPhoneStaysOff() {
        val r = Rig(); r.plat.supported = false
        r.v.toggle(true); assertFalse(r.v.enabled); assertFalse(r.v.supported)
        val r2 = Rig(mode = RecognizerMode.NONE); r2.v.toggle(true); assertFalse(r2.v.enabled)
    }

    @Test fun fixSaveUsesSameOnMeasurePathAsButton() {
        val voice = Rig(epochs = 3).on(); voice.emit(2); voice.v.tick()
        val button = Rig(epochs = 3); button.emit(2)
        assertEquals(SurveyStatus.FIXED, voice.c.gv.status.status)
        assertTrue(voice.eng.running); assertEquals(VoiceState.LISTENING, voice.v.state)
        voice.eng.emit("저장")
        button.c.onMeasure()
        assertEquals(button.c.phase, voice.c.phase); assertEquals(Phase.ACQUIRING, voice.c.phase)
        assertEquals(1, voice.heard)
        voice.emit(4); button.emit(4)
        assertEquals(1, voice.c.points.size); assertEquals(button.c.points.size, voice.c.points.size)
        assertEquals(button.c.points[0].name, voice.c.points[0].name)
        assertEquals(button.c.points[0].cautionFlag, voice.c.points[0].cautionFlag)
        assertEquals(button.results, voice.results) // 같은 저장음(SURVEY_OK) 1회
        voice.advance(200)
        assertEquals("P-0001 저장됨", voice.spk.said.last())
        assertFalse("TTS 중엔 인식 멈춤(자기 트리거 방지)", voice.eng.running)
        voice.speechDone(); assertTrue(voice.eng.running)
    }

    @Test fun strictCautionBlockedExactlyLikeButton() {
        fun caution(r: Rig) { r.emit(2); r.f.skipToNextSegment(); r.emit(1) }
        val voice = Rig(FakeScenarios.fieldWalk(), strict = true).on(); caution(voice)
        val button = Rig(FakeScenarios.fieldWalk(), strict = true); caution(button)
        assertEquals(SurveyStatus.CAUTION, voice.c.gv.status.status)
        voice.v.tick(); assertTrue(voice.eng.running)
        voice.eng.emit("저장"); button.c.onMeasure()
        assertEquals(Phase.IDLE, voice.c.phase); assertNull(voice.c.ui.confirm)
        assertEquals(button.c.message, voice.c.message); assertTrue(voice.c.message!!.contains("엄격"))
        assertTrue(voice.c.points.isEmpty())
        assertEquals(listOf(false), voice.results); assertEquals(button.results, voice.results) // 실패음 1회(중복 없음)
        assertTrue(voice.spk.said.last().startsWith("저장 안 됨"))
    }

    @Test fun defaultCautionAsksByVoiceSaveIsNotApprovalYesIs() {
        val r = Rig(FakeScenarios.fieldWalk(), strict = false, epochs = 5).on()
        r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장")
        val conf = r.c.ui.confirm!!
        assertTrue(conf.title.contains("주의")) // 버튼과 같은 확인 창
        assertEquals(Phase.IDLE, r.c.phase)
        assertTrue(r.spk.said.last().contains("주의")); assertTrue(r.spk.said.last().contains("저장할까요"))
        assertNotNull(r.v.asking)
        r.speechDone()
        assertEquals(VoiceState.CONFIRM, r.v.state); assertTrue(r.eng.running)
        r.eng.emit("저장") // 「저장」은 승인 아님
        assertSame(conf, r.c.ui.confirm); assertEquals(Phase.IDLE, r.c.phase)
        r.eng.emit("네") // 명세 밖 단어도 승인 아님
        assertSame(conf, r.c.ui.confirm)
        r.eng.emit("예")
        assertNull(r.c.ui.confirm); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.emit(6)
        assertEquals(1, r.c.points.size); assertTrue(r.c.points.single().cautionFlag)
        r.advance(200); assertTrue(r.spk.said.last().contains("저장됨"))
    }

    @Test fun confirmTimeoutTenSecondsCancels() {
        val r = Rig(FakeScenarios.fieldWalk(), strict = false).on()
        r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장"); r.speechDone()
        assertNotNull(r.c.ui.confirm)
        r.advance(9_000); assertNotNull("10초 전엔 그대로", r.c.ui.confirm); assertEquals(VoiceState.CONFIRM, r.v.state)
        assertTrue(r.v.confirmLeftSec in 1..2)
        r.advance(1_100)
        assertNull(r.c.ui.confirm); assertEquals(Phase.IDLE, r.c.phase); assertTrue(r.c.points.isEmpty())
        assertEquals("저장 안 함", r.spk.said.last())
    }

    @Test fun cancelWordAndMediaKeyCancelNeverApprove() {
        val r = Rig(FakeScenarios.fieldWalk()).on()
        r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장"); r.speechDone()
        r.eng.emit("취소")
        assertNull(r.c.ui.confirm); assertTrue(r.c.points.isEmpty()); assertEquals("저장 안 함", r.spk.said.last())
        r.speechDone(); r.advance(2_000)
        // 이어폰 버튼: 요청은 되지만 확인 대기 중 다시 누르면 취소(승인 아님)
        assertTrue(r.v.onMediaKey())
        assertNotNull(r.c.ui.confirm); r.speechDone()
        assertTrue(r.v.onMediaKey())
        assertNull(r.c.ui.confirm); assertEquals(Phase.IDLE, r.c.phase); assertTrue(r.c.points.isEmpty())
    }

    @Test fun mediaKeyRequestsSaveViaSamePath() {
        val r = Rig(epochs = 2).on(); r.emit(2); r.v.tick()
        assertTrue(r.v.onMediaKey()); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.emit(1); assertEquals(1, r.c.points.size)
        val off = Rig(); off.emit(2)
        assertFalse("음성 저장이 꺼져 있으면 키를 먹지 않음", off.v.onMediaKey())
    }

    @Test fun tiltAndCautionAskedOneAtATime() {
        val r = Rig(FakeScenarios.fieldWalk(), strict = false, epochs = 3)
        val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(TiltPolicy.CONFIRM)
        repeat(60) { tc.onSample(tiltReading(3.0)) }
        r.c.tilt = tc
        r.on(); r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장")
        assertNotNull(r.c.ui.confirm); assertNull("한 번에 하나", r.c.tiltPrompt)
        r.speechDone(); r.eng.emit("예")
        assertNull(r.c.ui.confirm); assertNotNull(r.c.tiltPrompt); assertEquals(Phase.IDLE, r.c.phase)
        assertTrue(r.spk.said.last().contains("기울기"))
        r.eng.emit("예") // 질문(TTS) 중엔 무시
        assertNotNull(r.c.tiltPrompt)
        r.speechDone(); r.eng.emit("예")
        assertNull(r.c.tiltPrompt); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.emit(4); assertEquals(1, r.c.points.size)
    }

    @Test fun buttonPathCautionThenTiltNoEndlessLoop() {
        val r = Rig(FakeScenarios.fieldWalk(), strict = false, epochs = 3)
        val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(TiltPolicy.CONFIRM)
        repeat(60) { tc.onSample(tiltReading(3.0)) }
        r.c.tilt = tc
        r.emit(2); r.f.skipToNextSegment(); r.emit(1)
        r.c.onMeasure(); assertNotNull(r.c.ui.confirm)
        r.c.ui.answer(true); assertNotNull(r.c.tiltPrompt); assertNull(r.c.ui.confirm)
        r.c.answerTilt(true)
        assertNull("주의 확인을 다시 묻지 않음", r.c.ui.confirm); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.emit(4); assertEquals(1, r.c.points.size); assertTrue(r.c.points.single().cautionFlag)
        // 기울기만(주의 승인 없이) 승인했으면 주의 확인은 그대로 묻는다
        r.c.onMeasure(); r.c.ui.answer(false)
        assertNull(r.c.tiltPrompt); assertTrue(r.c.message == null || r.c.points.size == 1)
    }

    @Test fun tiltScreenAnswerEndsVoiceConfirm() {
        val r = Rig(epochs = 1)
        val tc = TiltController(FakeTiltPlatform(), { 0L }); tc.changePolicy(TiltPolicy.CONFIRM)
        repeat(60) { tc.onSample(tiltReading(3.0)) }
        r.c.tilt = tc
        r.on(); r.emit(2); r.v.tick()
        r.eng.emit("저장"); r.speechDone()
        assertEquals(VoiceState.CONFIRM, r.v.state)
        r.c.answerTilt(false) // 화면에서 취소
        r.advance(200)
        assertNull(r.v.asking); assertTrue(r.c.points.isEmpty())
        r.advance(15_000); assertTrue(r.c.points.isEmpty())
    }

    @Test fun duplicatePromptAskedAndTimeoutDiscards() {
        val r = Rig(stationary(), epochs = 1).on(); r.emit(2); r.v.tick()
        r.eng.emit("저장"); r.emit(1); assertEquals(1, r.c.points.size)
        r.advance(200); r.speechDone(); r.advance(2_000)
        r.eng.emit("저장"); r.emit(1)
        assertNotNull(r.c.dupPrompt); r.advance(200)
        assertTrue(r.spk.said.last().contains("같은 자리"))
        r.speechDone(); r.advance(10_100)
        assertNull(r.c.dupPrompt); assertEquals(1, r.c.points.size); assertEquals("저장 안 함", r.spk.said.last())
        // 「예」 = 새 점으로 따로 저장(기존 점 덮어쓰기 아님)
        r.speechDone(); r.advance(2_000)
        r.eng.emit("저장"); r.emit(1); r.advance(200); r.speechDone()
        r.eng.emit("예")
        assertNull(r.c.dupPrompt); assertEquals(2, r.c.points.size)
    }

    @Test fun cooldownPreventsConsecutiveSaves() {
        val r = Rig(epochs = 2).on(); r.emit(2); r.v.tick()
        r.eng.emit("저장"); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.eng.emit("저장") // 측정 중
        r.emit(1); assertEquals(1, r.c.points.size)
        r.advance(100); r.speechDone() // +650ms
        assertTrue(r.eng.running)
        r.eng.emit("저장") // 1.5초 안 → 버림
        assertEquals(Phase.RESULT, r.c.phase); assertEquals(1, r.c.points.size)
        r.advance(1_000)
        r.eng.emit("저장"); assertEquals(Phase.ACQUIRING, r.c.phase)
        r.emit(1); assertEquals(2, r.c.points.size)
        assertEquals(2, r.v.acceptedSaves)
    }

    @Test fun leavingScreenDropsResultsNoDelayedExecution() {
        val r = Rig(epochs = 1).on(); r.emit(2); r.v.tick()
        assertTrue(r.eng.running)
        r.v.setForeground(false)
        assertFalse(r.eng.running)
        r.eng.emitLate("저장") // 멈춘 뒤 도착한 결과
        assertEquals(Phase.IDLE, r.c.phase)
        r.v.setForeground(true); r.advance(200); r.advance(5_000)
        assertEquals(Phase.IDLE, r.c.phase); assertTrue(r.c.points.isEmpty())
        assertTrue(r.v.droppedResults >= 1)
        // 다른 화면(점 목록)으로 가도 멈춤
        r.c.hub.open(ToolScreen.POINTS); r.advance(200)
        assertFalse(r.eng.running); assertEquals(VoiceState.WAITING, r.v.state)
        r.eng.emitLate("저장"); r.c.hub.closeAll(); r.advance(200); r.advance(3_000)
        assertEquals(Phase.IDLE, r.c.phase); assertTrue(r.c.points.isEmpty())
        assertTrue(r.eng.running)
    }

    @Test fun linkDisconnectStopsAndDrops() {
        val r = Rig(epochs = 1).on(); r.emit(2); r.v.tick()
        assertTrue(r.eng.running)
        r.f.link.value = LinkStatus(LinkState.RECONNECTING, message = "끊김")
        assertEquals(StatusReason.LINK_DOWN, r.c.gv.status.reason)
        r.v.tick()
        assertFalse(r.eng.running); assertEquals("수신기 연결 대기", r.v.indicatorText())
        r.eng.emitLate("저장")
        r.emit(2) // 다시 연결
        assertEquals(SurveyStatus.FIXED, r.c.gv.status.status)
        r.advance(200); r.advance(3_000)
        assertEquals(Phase.IDLE, r.c.phase); assertTrue(r.c.points.isEmpty())
        assertTrue(r.eng.running)
    }

    @Test fun linkDropDuringConfirmAbandonsVoiceConfirm() {
        val r = Rig(FakeScenarios.fieldWalk()).on()
        r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장"); r.speechDone()
        assertEquals(VoiceState.CONFIRM, r.v.state)
        r.v.setForeground(false)
        assertNull(r.v.asking)
        r.eng.emitLate("예")
        assertTrue("늦게 온 「예」로 승인되지 않음", r.c.points.isEmpty()); assertEquals(Phase.IDLE, r.c.phase)
    }

    @Test fun ttsSelfTriggerBlocked() {
        val r = Rig(epochs = 1).on(); r.emit(2); r.v.tick()
        r.eng.emit("저장"); r.emit(1); r.advance(200)
        assertEquals(VoiceState.SPEAKING, r.v.state); assertFalse(r.eng.running)
        r.eng.emitLate("저장") // 스피커 소리를 다시 들은 경우
        assertEquals(1, r.c.points.size)
        r.spk.finish(); r.advance(100)
        assertEquals("잔향 쉼", VoiceState.SPEAKING, r.v.state); assertFalse(r.eng.running)
        r.advance(600); assertTrue(r.eng.running)
    }

    @Test fun misrecognitionCooldown() {
        val r = Rig().on(); r.emit(2); r.v.tick()
        r.eng.emit("저쪽"); r.eng.emit("자장"); assertTrue(r.eng.running)
        r.eng.emit("저장해")
        assertFalse(r.eng.running); assertEquals(VoiceState.COOLDOWN, r.v.state)
        r.advance(1_000); assertFalse(r.eng.running)
        r.advance(600); assertTrue(r.eng.running)
        assertTrue(r.c.points.isEmpty()); assertEquals(Phase.IDLE, r.c.phase)
    }

    @Test fun unrelatedDialogOrPlaybackPausesListening() {
        val r = Rig(epochs = 1).on(); r.emit(2); r.v.tick()
        r.c.ui.ask(ConfirmRequest("P-0001 을(를) 되돌릴까요?", "", "되돌리기") {})
        r.advance(200); assertFalse(r.eng.running)
        r.c.ui.answer(false); r.advance(200); assertTrue(r.eng.running)
        r.c.playingVoiceId = 3L; r.advance(200); assertFalse(r.eng.running)
        r.c.playingVoiceId = null; r.advance(200); assertTrue(r.eng.running)
    }

    @Test fun repeatedUndoAfterVoiceSavePausesFiveMinutes() {
        val r = Rig(epochs = 1).on(); r.emit(2); r.v.tick()
        repeat(2) {
            r.eng.emit("저장"); r.emit(1); r.advance(200); r.speechDone()
            r.c.undo()
            r.advance(2_000)
        }
        assertEquals(VoiceState.COOLDOWN, r.v.state); assertFalse(r.eng.running)
        r.advance(4 * 60_000L); assertFalse(r.eng.running)
        r.advance(61_000L); assertTrue(r.eng.running)
    }

    @Test fun noTtsStillWorksWithScreenAndTimeout() {
        val r = Rig(FakeScenarios.fieldWalk()); r.spk.available = false; r.on()
        r.emit(2); r.f.skipToNextSegment(); r.emit(1); r.v.tick()
        r.eng.emit("저장")
        assertEquals(VoiceState.CONFIRM, r.v.state); assertTrue(r.eng.running)
        r.advance(10_100); assertNull(r.c.ui.confirm); assertTrue(r.c.points.isEmpty())
    }

    @Test fun voiceHeardEventIsShortAndDistinct() {
        val e = FeedbackEvent.VOICE_HEARD
        assertNotEquals(FeedbackEvent.SURVEY_OK.tone to FeedbackEvent.SURVEY_OK.toneMs, e.tone to e.toneMs)
        assertTrue(e.toneMs <= 120); assertTrue(e.pattern.sum() <= 60)
    }

    @Test fun manifestQueriesRecognitionAndTtsServices() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.speech.RecognitionService")); assertTrue(m.contains("android.intent.action.TTS_SERVICE"))
        assertTrue(m.contains("android.permission.RECORD_AUDIO"))
        assertFalse("마이크 FGS 타입 추가 안 함", m.contains("foregroundServiceType=\"microphone"))
    }
}
