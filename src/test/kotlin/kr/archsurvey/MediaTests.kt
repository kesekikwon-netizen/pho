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
        var cameraOk = true; var pickCount = 2; var cameraCalls = 0; var pickCalls = 0; var lastRemaining = -1
        var finishResult: VoiceItem? = VoiceItem(9, "a.m4a", 4000)
        override suspend fun photos(pointRowId: Long) = photoList.toList()
        override suspend fun voices(pointRowId: Long) = voiceList.toList()
        override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Boolean) -> Unit) {
            cameraCalls++
            if (cameraOk) photoList.add(PhotoItem(photoList.size + 1L, "p$cameraCalls.jpg")); onDone(cameraOk)
        }
        override fun pickPhotos(pointId: String, pointRowId: Long, remaining: Int, onDone: (Int) -> Unit) {
            pickCalls++; lastRemaining = remaining
            val n = minOf(pickCount, remaining); repeat(n) { photoList.add(PhotoItem(photoList.size + 1L, "g$it.jpg")) }; onDone(n)
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

    @Test fun cameraAddsUpToFourThenRefuses() {
        val (c, m, _) = setup()
        repeat(4) { c.addPhotoCamera() }
        assertEquals(4, c.photos.size)
        c.addPhotoCamera()
        assertEquals(4, m.cameraCalls); assertTrue(c.message!!.contains("4장"))
    }

    @Test fun cameraCancelShowsMessage() {
        val (c, m, _) = setup(); m.cameraOk = false
        c.addPhotoCamera()
        assertEquals(0, c.photos.size); assertTrue(c.message!!.contains("저장되지 않았어요"))
    }

    @Test fun pickerOnlyOffersRemainingSlots() {
        val (c, m, _) = setup()
        c.addPhotoCamera(); c.addPhotoCamera(); c.addPhotoCamera()
        c.addPhotoPicker()
        assertEquals(1, m.lastRemaining); assertEquals(4, c.photos.size)
        c.addPhotoPicker(); assertEquals(1, m.pickCalls) // 가득 차면 선택기를 열지 않는다
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
