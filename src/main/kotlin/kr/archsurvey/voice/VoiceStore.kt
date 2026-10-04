package kr.archsurvey.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kr.archsurvey.data.SurveyDao
import kr.archsurvey.data.VoiceEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹음 설정(문서화된 값): AAC-LC, 16kHz, 모노, 32kbps, 최대 30초 */
object VoiceFormat {
    const val SAMPLE_RATE = 16000
    const val BIT_RATE = 32000
    const val CHANNELS = 1
    const val MAX_MS = 30_000
}

/**
 * 음성 메모 저장·녹음·재생. 앱 전용 외부 폴더(권한 불필요) 아래 Recordings/<작업>/<점>/ 에 .m4a 로 둔다.
 * 마이크 권한(RECORD_AUDIO)은 녹음 시작 직전에 Activity 가 요청한다.
 */
class VoiceStore(private val ctx: Context, private val dao: SurveyDao) {
    val root: File = (ctx.getExternalFilesDir("Recordings") ?: File(ctx.filesDir, "Recordings")).also { it.mkdirs() }
    fun file(rel: String) = File(root, rel)
    private fun safe(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")

    private var recorder: MediaRecorder? = null
    private var recFile: String? = null
    private var recStartMs = 0L
    private var player: MediaPlayer? = null

    /** 녹음 시작. 성공하면 true. 실패 이유는 null 대신 예외 메시지 문자열로 반환 */
    fun start(project: String, pointId: String, onAutoStop: () -> Unit): String? {
        if (recorder != null) return "이미 녹음 중이에요"
        stopPlayback()
        val rel = "${safe(project)}/${safe(pointId)}/${safe(pointId)}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
        val f = file(rel); f.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(VoiceFormat.CHANNELS)
            r.setAudioSamplingRate(VoiceFormat.SAMPLE_RATE)
            r.setAudioEncodingBitRate(VoiceFormat.BIT_RATE)
            r.setMaxDuration(VoiceFormat.MAX_MS)
            r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onAutoStop() }
            r.prepare(); r.start()
            recorder = r; recFile = rel; recStartMs = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            runCatching { r.release() }; f.delete()
            "녹음을 시작하지 못했어요 (${e.javaClass.simpleName})"
        }
    }

    /** 녹음 종료 후 DB 등록. 너무 짧거나(0.5초 미만) 파일이 비면 버리고 null. 3개 제한 초과도 null */
    suspend fun finish(pointRowId: Long): VoiceEntity? {
        val r = recorder ?: return null
        val rel = recFile ?: return null
        recorder = null; recFile = null
        val dur = System.currentTimeMillis() - recStartMs
        try { r.stop() } catch (_: RuntimeException) { /* 너무 짧은 녹음 */ }
        runCatching { r.release() }
        val f = file(rel)
        if (dur < 500 || !f.exists() || f.length() == 0L) { f.delete(); return null }
        val v = VoiceEntity(pointRowId = pointRowId, relPath = rel, durationMs = minOf(dur, VoiceFormat.MAX_MS.toLong()), addedAtUtcMs = System.currentTimeMillis())
        val id = dao.insertVoiceLimited(v)
        if (id < 0) { f.delete(); return null }
        return v.copy(id = id)
    }

    fun cancelRecording() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }; runCatching { r.release() }
        recFile?.let { file(it).delete() }; recFile = null
    }

    val isRecording: Boolean get() = recorder != null

    fun play(rel: String, onDone: () -> Unit): Boolean {
        stopPlayback()
        return try {
            val p = MediaPlayer()
            p.setDataSource(file(rel).path)
            p.setOnCompletionListener { stopPlayback(); onDone() }
            p.prepare(); p.start(); player = p
            true
        } catch (_: Exception) { false }
    }

    fun stopPlayback() { player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null }

    suspend fun delete(v: VoiceEntity) { stopPlayback(); file(v.relPath).delete(); dao.deleteVoice(v) }
}
