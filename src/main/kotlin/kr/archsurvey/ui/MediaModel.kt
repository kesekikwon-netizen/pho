package kr.archsurvey.ui

import androidx.compose.ui.graphics.ImageBitmap

data class PhotoItem(val id: Long, val rel: String, val addedAtMs: Long = 0L)
data class VoiceItem(val id: Long, val rel: String, val durationMs: Long)

/** 사진·음성 메모를 Android 에 맡기는 얇은 인터페이스(Android 의존 없음 → 가짜로 시험 가능). */
interface MediaPlatform {
    suspend fun photos(pointRowId: Long): List<PhotoItem>
    suspend fun voices(pointRowId: Long): List<VoiceItem>

    /** 시스템 카메라 앱을 연다. 끝나면 [onDone] (새 사진 id, 실패 null). 장수 제한 없음 */
    fun takePhoto(pointId: String, pointRowId: Long, onDone: (Long?) -> Unit)
    /** Photo Picker(권한 불필요). 장수 제한 없음. 끝나면 [onDone] (등록된 장수) */
    fun pickPhotos(pointId: String, pointRowId: Long, onDone: (Int) -> Unit)
    suspend fun deletePhoto(id: Long)
    fun thumb(rel: String): ImageBitmap?

    fun hasMicPermission(): Boolean
    fun requestMicPermission(onResult: (Boolean) -> Unit)
    /** 녹음 시작. 실패하면 사용자에게 보여 줄 문구, 성공이면 null */
    fun startRecording(pointId: String): String?
    suspend fun finishRecording(pointRowId: Long): VoiceItem?
    fun cancelRecording()
    fun play(rel: String, onDone: () -> Unit): Boolean
    fun stopPlayback()
    suspend fun deleteVoice(id: Long)
}

/** 0.1.59: 사진은 제한 없음(사용자 규칙). 음성만 제한 */
object MediaLimits { const val VOICES = 3; const val VOICE_SECONDS = 30 }
