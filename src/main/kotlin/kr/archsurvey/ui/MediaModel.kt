package kr.archsurvey.ui

import androidx.compose.ui.graphics.ImageBitmap

data class PhotoItem(val id: Long, val rel: String)
data class VoiceItem(val id: Long, val rel: String, val durationMs: Long)

/** 사진·음성 메모를 Android 에 맡기는 얇은 인터페이스(Android 의존 없음 → 가짜로 시험 가능). */
interface MediaPlatform {
    suspend fun photos(pointRowId: Long): List<PhotoItem>
    suspend fun voices(pointRowId: Long): List<VoiceItem>

    /** 시스템 카메라 앱을 연다. 끝나면 [onDone] (등록 성공 여부). 권한 불필요(앱 전용 폴더 + FileProvider) */
    fun takePhoto(pointId: String, pointRowId: Long, onDone: (Boolean) -> Unit)
    /** Photo Picker(권한 불필요). 최대 [remaining] 장. 끝나면 [onDone] (등록된 장수) */
    fun pickPhotos(pointId: String, pointRowId: Long, remaining: Int, onDone: (Int) -> Unit)
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

object MediaLimits { const val PHOTOS = 4; const val VOICES = 3; const val VOICE_SECONDS = 30 }
