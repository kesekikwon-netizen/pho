package kr.archsurvey.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.photo.PhotoStore
import kr.archsurvey.ui.MediaPlatform
import kr.archsurvey.ui.PhotoItem
import kr.archsurvey.ui.VoiceItem
import kr.archsurvey.voice.VoiceStore

/**
 * [MediaPlatform] 의 Android 구현. 사진: 시스템 카메라(TakePicture) / Photo Picker 는 Activity 가 [launcher] 로 연결.
 * 앱 전용 폴더(권한 불필요)에 저장한다. 카메라 앱이 떠 있는 동안 우리 프로세스가 죽어도
 * 촬영 파일 경로를 SharedPreferences 에 적어 두었다가 다음 실행 때 [recoverPending] 으로 등록한다.
 */
class AndroidMediaPlatform(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val project: String,
) : MediaPlatform {
    private val dao = SurveyDb.get(ctx).dao()
    val photoStore = PhotoStore(ctx, dao)
    val voiceStore = VoiceStore(ctx, dao)
    private val prefs = ctx.getSharedPreferences("media_pending", Context.MODE_PRIVATE)

    /** Activity 가 채운다 */
    interface Launcher {
        fun launchCamera(uri: Uri)
        fun launchPicker()
        fun requestMic()
    }
    var launcher: Launcher? = null

    // 결과 콜백은 화면 회전 등으로 Activity 가 다시 만들어져도 살아 있도록 여기(ViewModel 수명)에 둔다
    private var cameraCb: ((Boolean) -> Unit)? = null
    private var pickerCb: ((List<Uri>) -> Unit)? = null
    private var micCb: ((Boolean) -> Unit)? = null
    fun deliverCamera(ok: Boolean) { cameraCb?.invoke(ok); cameraCb = null }
    fun deliverPicker(uris: List<Uri>) { pickerCb?.invoke(uris); pickerCb = null }
    fun deliverMic(ok: Boolean) { micCb?.invoke(ok); micCb = null }

    override suspend fun photos(pointRowId: Long) = dao.photosOf(pointRowId).map { PhotoItem(it.id, it.relPath) }
    override suspend fun voices(pointRowId: Long) = dao.voicesOf(pointRowId).map { VoiceItem(it.id, it.relPath, it.durationMs) }

    override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Boolean) -> Unit) {
        val l = launcher ?: return onDone(false)
        val (rel, uri) = photoStore.newCameraTarget(project, pointId)
        prefs.edit().putString("rel", rel).putLong("row", pointRowId).apply()
        cameraCb = { ok ->
            scope.launch {
                val saved = ok && photoStore.registerCaptured(pointRowId, rel)
                if (!saved) photoStore.file(rel).delete()
                prefs.edit().clear().apply()
                onDone(saved)
            }
        }
        l.launchCamera(uri)
    }

    override fun pickPhotos(pointId: String, pointRowId: Long, remaining: Int, onDone: (Int) -> Unit) {
        val l = launcher ?: return onDone(0)
        pickerCb = { uris ->
            if (uris.isEmpty()) onDone(0)
            else scope.launch(Dispatchers.IO) {
                val n = photoStore.importFromUris(pointRowId, project, pointId, uris.take(remaining))
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(n) }
            }
        }
        l.launchPicker()
    }

    /** 앱이 다시 시작될 때: 카메라 도중 프로세스가 죽었다면 촬영 파일을 등록 */
    fun recoverPending() {
        val rel = prefs.getString("rel", null) ?: return
        val row = prefs.getLong("row", 0L)
        scope.launch(Dispatchers.IO) {
            if (row > 0) photoStore.registerCaptured(row, rel)
            prefs.edit().clear().apply()
        }
    }

    override suspend fun deletePhoto(id: Long) {
        val all = dao.photoById(id) ?: return
        photoStore.delete(all)
    }

    override fun thumb(rel: String): ImageBitmap? = photoStore.loadThumb(rel)?.asImageBitmap()

    override fun hasMicPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun requestMicPermission(onResult: (Boolean) -> Unit) {
        val l = launcher ?: return onResult(false)
        micCb = onResult
        l.requestMic()
    }

    override fun startRecording(pointId: String): String? = voiceStore.start(project, pointId) { /* 30초 자동 정지는 컨트롤러 타이머가 처리 */ }
    override suspend fun finishRecording(pointRowId: Long): VoiceItem? =
        voiceStore.finish(pointRowId)?.let { VoiceItem(it.id, it.relPath, it.durationMs) }
    override fun cancelRecording() = voiceStore.cancelRecording()
    override fun play(rel: String, onDone: () -> Unit) = voiceStore.play(rel, onDone)
    override fun stopPlayback() = voiceStore.stopPlayback()
    override suspend fun deleteVoice(id: Long) {
        // id 로 행을 찾는다 (점 목록을 거치지 않도록 DAO 조회)
        val v = dao.voiceById(id) ?: return
        voiceStore.delete(v)
    }
}
