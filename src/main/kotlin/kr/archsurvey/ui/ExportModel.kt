package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ExportSummary(val project: String, val points: Int, val photos: Int, val voices: Int, val fakePoints: Int)
data class ExportOutput(
    val fileName: String, val sizeBytes: Long, val path: String, val points: Int, val photos: Int, val voices: Int,
    /** hgis 묶음을 둔 공용 폴더(예: Documents/ExcavationSurvey/hgis/작업_시각). 없으면 null */
    val publicFolder: String? = null,
)

/** 내보내기에 필요한 Android 기능. 시험에서는 가짜로 바꾼다. */
interface ExportPlatform {
    suspend fun summary(): ExportSummary
    suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput
    /** 0.1.14 고른 양식만(목업 11). 기본 구현은 예전 세 가지로 만든다(시험용 가짜 호환) */
    suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean): ExportOutput = export(includePhotos, includeVoices)
    /** 공유 시트(카카오톡·메일·드라이브 등) 열기 */
    fun share(out: ExportOutput)
    /** 파일 저장 위치 고르기(시스템 문서 선택기). 끝나면 onDone(성공 여부) */
    fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit)
}

/** 3-G 내보내기 화면 상태. 만들기 → 결과 카드(공유/파일로 저장). */
class ExportController(private val platform: ExportPlatform, private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var summary by mutableStateOf<ExportSummary?>(null); private set
    var includePhotos by mutableStateOf(true)
    var includeVoices by mutableStateOf(true)
    /** 0.1.14 목업 11 양식 선택(여러 개) */
    var formats by mutableStateOf(kr.archsurvey.export.ExportFormat.DEFAULT)
    fun toggleFormat(f: kr.archsurvey.export.ExportFormat) { formats = if (f in formats) formats - f else formats + f; output = null }
    /** 「hgis용 SHP」 한 번 누르기: Shape 한 벌 + hgis 기준점 CSV 만 골라 바로 만든다 */
    fun runHgis() { if (running) return; formats = kr.archsurvey.export.ExportFormat.HGIS; output = null; run() }
    /** 0.1.14 목업 12 전송 방식: true = 무선 전송(공유), false = 장치 내 저장 */
    var methodShare by mutableStateOf(true)
    fun send() { if (methodShare) share() else saveAs() }
    var running by mutableStateOf(false); private set
    var output by mutableStateOf<ExportOutput?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    /** 0.1.14 끝 멘트(완료/오류). SurveyController.exporter 에 넣을 때 연결 */
    var onResult: ((Boolean, String) -> Unit)? = null

    fun openScreen() { open = true; output = null; error = null; notice = null; refresh() }
    fun close() { open = false }

    fun refresh() { scope.launch { summary = runCatching { platform.summary() }.getOrNull() } }

    fun run() {
        if (running) return
        val s = summary
        if (s != null && s.points == 0) { error = "내보낼 점이 없어요. 먼저 측점을 하세요."; onResult?.invoke(false, error!!); return }
        if (formats.isEmpty()) { error = "양식을 하나 이상 고르세요."; onResult?.invoke(false, error!!); return }
        val fs = formats
        running = true; error = null; notice = null; output = null
        scope.launch {
            try {
                val o = platform.exportFormats(fs, includePhotos && (s?.photos ?: 1) > 0, includeVoices && (s?.voices ?: 1) > 0)
                output = o
                o.publicFolder?.let { notice = hgisFolderText(it) }
                onResult?.invoke(true, "내보내기 파일을 만들었어요 · ${o.fileName} (${sizeText(o.sizeBytes)}) · ${fs.size}개 양식 · " +
                    (if (o.publicFolder != null) "PC로 옮길 폴더: 내장 저장공간/${o.publicFolder}" else "전송 방식을 고르세요"))
            }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "내보내기에 실패했어요 (${e.javaClass.simpleName}). 저장 공간을 확인하고 다시 시도하세요."
                onResult?.invoke(false, error!!)
            }
            finally { running = false }
        }
    }

    fun share() {
        val o = output ?: return
        runCatching { platform.share(o) }
            .onSuccess { notice = "공유 창을 열었어요 · 받을 앱을 고르세요"; onResult?.invoke(true, "무선 전송 · ${o.fileName} 공유 창을 열었어요") }
            .onFailure { notice = "공유 창을 열지 못했어요 (${it.javaClass.simpleName})"; onResult?.invoke(false, notice!!) }
    }
    fun saveAs() { output?.let { o -> platform.saveAs(o) { ok -> notice = if (ok) "파일로 저장했어요" else "저장을 취소했거나 실패했어요"; onResult?.invoke(ok, notice!!) } } }

    companion object {
        fun hgisFolderText(folder: String) =
            "폰을 USB로 PC에 연결 → 내장 저장공간/$folder 폴더를 PC에 복사 → 그 안의 points_EPSG.shp 를 hgis 지도에 끌어 놓으세요"
        fun sizeText(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
        }
    }
}
