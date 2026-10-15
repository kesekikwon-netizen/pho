package kr.archsurvey

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 사진·음성 메모 컨트롤러 로직 (가짜 MediaPlatform). 실제 카메라·마이크·MediaRecorder 는 시험하지 않았다. */
class MediaTests {
    private class MemStore : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = emptyList<SavedPoint>()
        override suspend fun maxSeq() = 0
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }
    private class FakeMedia : MediaPlatform {
        val photoList = ArrayList<PhotoItem>(); val voiceList = ArrayList<VoiceItem>()
        var mic = true; var micGrant = true; var startError: String? = null; var recording = false; var cancelled = 0
        var cameraOk = true; var pickCount = 2; var cameraCalls = 0; var pickCalls = 0; val cameraRows = ArrayList<Long>()
        var finishResult: VoiceItem? = VoiceItem(9, "a.m4a", 4000)
        override suspend fun photos(pointRowId: Long) = photoList.toList()
        override suspend fun voices(pointRowId: Long) = voiceList.toList()
        override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Long?) -> Unit) {
            cameraCalls++; cameraRows.add(pointRowId)
            if (cameraOk) { val id = photoList.size + 1L; photoList.add(PhotoItem(id, "p$cameraCalls.jpg", System.currentTimeMillis())); onDone(id) } else onDone(null)
        }
        override fun pickPhotos(pointId: String, pointRowId: Long, onDone: (Int) -> Unit) {
            pickCalls++
            val n = pickCount; repeat(n) { photoList.add(PhotoItem(photoList.size + 1L, "g$it.jpg")) }; onDone(n)
        }
        override suspend fun deletePhoto(id: Long) { photoList.removeAll { it.id == id } }
        override fun thumb(rel: String): ImageBitmap? = null
        override fun hasMicPermission() = mic
        override fun requestMicPermission(onResult: (Boolean) -> Unit) { if (micGrant) mic = true; onResult(micGrant) }
        override fun startRecording(pointId: String): String? { if (startError == null) recording = true; return startError }
        override suspend fun finishRecording(pointRowId: Long): VoiceItem? { recording = false; return finishResult?.also { voiceList.add(it) } }
        override fun cancelRecording() { cancelled++; recording = false }
        override fun play(rel: String, onDone: () -> Unit) = true
        override fun stopPlayback() {}
        override suspend fun deleteVoice(id: Long) { voiceList.removeAll { it.id == id } }
    }

    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun setup(): Triple<SurveyController, FakeMedia, FakeGnssSource> {
        val f = FakeGnssSource(FakeScenarios.allFixed()); val m = FakeMedia()
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), store = MemStore())
        c.media = m
        repeat(2) { f.emitNext() }; c.onMeasure(); repeat(6) { f.emitNext() }
        assertEquals(Phase.RESULT, c.phase)
        return Triple(c, m, f)
    }

    @Test fun cameraHasNoLimitAndNoConfirmScreen() {
        val (c, m, _) = setup()
        repeat(12) { c.addPhotoCamera() }
        assertEquals(12, c.photos.size); assertEquals(12, m.cameraCalls)
        // 저장 즉시 + 되돌리기 알림(전체 화면 확인 없음)
        assertEquals("되돌리기", c.ui.toastActionLabel)
        assertTrue(c.ui.toast!!, c.ui.toast!!.contains("사진 12장째") && c.ui.toast!!.contains("측정 후"))
    }

    @Test fun undoToastDeletesJustTakenPhoto() {
        val (c, _, _) = setup()
        c.addPhotoCamera(); c.addPhotoCamera()
        c.ui.runToastAction()
        assertEquals(1, c.photos.size); assertEquals(1L, c.photos.single().id)
        assertNull(c.ui.toastActionLabel)
    }

    @Test fun cameraCancelShowsMessage() {
        val (c, m, _) = setup(); m.cameraOk = false
        c.addPhotoCamera()
        assertEquals(0, c.photos.size); assertTrue(c.message!!.contains("저장되지 않았어요"))
    }

    @Test fun pickerHasNoLimit() {
        val (c, m, _) = setup(); m.pickCount = 9
        repeat(5) { c.addPhotoCamera() }
        c.addPhotoPicker()
        assertEquals(1, m.pickCalls); assertEquals(14, c.photos.size)
    }

    @Test fun photoGoesToSelectedPointNotLast() {
        val (c, m, f) = setup()
        c.nextPoint(); repeat(6) { f.emitNext() }
        assertEquals(2, c.points.size)
        val first = c.points.first(); val last = c.points.last()
        assertNotEquals(first.rowId, last.rowId)
        c.addPhotoFor(first)
        assertEquals(first.rowId, m.cameraRows.last())
        c.mediaFocus = null
        c.addPhotoCamera()
        assertEquals(last.rowId, m.cameraRows.last())
    }

    @Test fun recoveryNoticeIsShown() {
        val (c, _, _) = setup()
        c.notifyPhotoRecovered("P-0001", 1)
        assertTrue(c.ui.toast!!.contains("P-0001") && c.ui.toast!!.contains("복구"))
    }

    @Test fun memoCarryOverB5() {
        val (c, _, f) = setup()
        c.pendingMemo = "5-3구역 9호 토층"
        c.nextPoint(); repeat(6) { f.emitNext() }
        assertEquals("5-3구역 9호 토층", c.points.last().memo)
        assertEquals("5-3구역 9호 토층", c.memoSuggestion)
        // 이어가기 켬 → 다음 점도 같은 메모
        c.toggleMemoCarry(); assertTrue(c.memoCarry)
        c.nextPoint(); repeat(6) { f.emitNext() }
        assertEquals("5-3구역 9호 토층", c.points.last().memo)
        // 끄면 비움
        c.toggleMemoCarry()
        c.nextPoint(); repeat(6) { f.emitNext() }
        assertEquals("", c.points.last().memo)
    }

    @Test fun deletePhotoRefreshes() {
        val (c, _, _) = setup()
        c.addPhotoCamera(); c.addPhotoCamera()
        c.deletePhoto(c.photos.first())
        assertEquals(1, c.photos.size)
    }

    @Test fun voiceFlowSavesMemo() {
        val (c, m, _) = setup()
        c.toggleVoice(); assertEquals(Phase.RECORDING, c.phase); assertTrue(m.recording)
        c.toggleVoice(); assertEquals(Phase.RESULT, c.phase)
        assertEquals(1, c.voices.size)
    }

    @Test fun micDeniedExplainsAndDoesNotRecord() {
        val (c, m, _) = setup(); m.mic = false; m.micGrant = false
        c.toggleVoice()
        assertEquals(Phase.RESULT, c.phase); assertFalse(m.recording); assertTrue(c.message!!.contains("마이크"))
    }

    @Test fun micGrantedLaterStartsRecording() {
        val (c, m, _) = setup(); m.mic = false; m.micGrant = true
        c.toggleVoice()
        assertEquals(Phase.RECORDING, c.phase)
    }

    @Test fun recorderStartFailureShowsMessage() {
        val (c, m, _) = setup(); m.startError = "녹음을 시작하지 못했어요"
        c.toggleVoice()
        assertEquals(Phase.RESULT, c.phase); assertEquals("녹음을 시작하지 못했어요", c.message)
    }

    @Test fun tooShortRecordingIsDiscardedWithMessage() {
        val (c, m, _) = setup(); m.finishResult = null
        c.toggleVoice(); c.toggleVoice()
        assertTrue(c.voices.isEmpty()); assertTrue(c.message!!.contains("너무 짧"))
    }

    @Test fun voiceLimitThree() {
        val (c, m, _) = setup()
        repeat(3) { m.finishResult = VoiceItem(10L + it, "v$it.m4a", 3000); c.toggleVoice(); c.toggleVoice() }
        assertEquals(3, c.voices.size)
        c.toggleVoice(); assertEquals(Phase.RESULT, c.phase); assertTrue(c.message!!.contains("3개"))
    }

    @Test fun undoWhileRecordingCancelsRecorder() {
        val (c, m, _) = setup()
        c.toggleVoice(); c.undo()
        assertEquals(1, m.cancelled); assertTrue(c.points.isEmpty()); assertEquals(Phase.IDLE, c.phase)
    }
}
