package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ExportSummary(val project: String, val points: Int, val photos: Int, val voices: Int, val fakePoints: Int,
    /** UX-0127-1: 높이가 없어 iTwin XML 에서 빠질 점 수(내보내기 전 안내) */
    val noHeightPoints: Int = 0)
data class ExportOutput(
    val fileName: String, val sizeBytes: Long, val path: String, val points: Int, val photos: Int, val voices: Int,
    /** hgis 묶음을 둔 공용 폴더(예: Documents/ExcavationSurvey/hgis/작업_시각). 없으면 null */
    val publicFolder: String? = null,
    /** UX-0127-1: iTwin XML 에서 높이가 없어 뺀 점 수·번호 */
    val itwinDroppedCount: Int = 0,
    val itwinDroppedNames: List<String> = emptyList(),
    /** 0.1.56 M5: 저장 경로 한 줄(보내기 앞 화면과 같은 글) */
    val where: String? = null,
)

/** 내보내기에 필요한 Android 기능. 시험에서는 가짜로 바꾼다. */
interface ExportPlatform {
    suspend fun summary(): ExportSummary
    suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput
    /** 0.1.14 고른 양식만(목업 11). 기본 구현은 예전 세 가지로 만든다(시험용 가짜 호환) */
    suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean): ExportOutput = export(includePhotos, includeVoices)
    /** iTwin XML 이름 선택까지. 기본 구현은 위 것(시험용 가짜 호환) */
    suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean, itwinKeepNames: Boolean): ExportOutput =
        exportFormats(formats, includePhotos, includeVoices)
    /** 0.1.35 축척(도면 DXF 글자)·오늘만. 기본 구현은 위 것(시험용 가짜 호환) */
    suspend fun exportBundle(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean, itwinKeepNames: Boolean, scale: Int, todayKey: String?): ExportOutput =
        exportFormats(formats, includePhotos, includeVoices, itwinKeepNames)
    /** 공유 시트(카카오톡·메일·드라이브 등) 열기 */
    fun share(out: ExportOutput)
    /** 파일 저장 위치 고르기(시스템 문서 선택기). 끝나면 onDone(성공 여부) */
    fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit)
    /** 0.1.56 M5: 보낼 파일 경로 한 줄(만들기 전). 모르면 null */
    fun sendWhere(): String? = null
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
    /** 0.1.38: 쓰지 않음(iTwin 내보내기 제거). 플랫폼 시그니처 호환용 */
    var itwinKeepNames by mutableStateOf(false)
    /** 0.1.35 도면 축척 분모(1:20/1:100/1:200) */
    var scale by mutableStateOf(kr.archsurvey.dig.DrawingScale.DEFAULT)
    /** 0.1.35 오늘 잰 점만 */
    var todayOnly by mutableStateOf(false)
    var todayKey: () -> String = { kr.archsurvey.dig.DayKey.of(System.currentTimeMillis()) }
    /** 「저녁 도면용 한 번에」: 도면 DXF + SHP + 야장 CSV + 기본 CSV + 사진·음성 */
    fun runEvening() { if (running) return; formats = kr.archsurvey.export.ExportFormat.EVENING; includePhotos = true; includeVoices = true; output = null; run() }
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

    fun refresh() { where = runCatching { platform.sendWhere() }.getOrNull(); scope.launch { summary = runCatching { platform.summary() }.getOrNull() } }
    /** 0.1.56 M5: 저장 경로 한 줄(앞·완료 같은 글) */
    var where by mutableStateOf<String?>(null); private set
    /** 0.1.56 M8: 잰 점이 0개면 보내기 꺼짐 */
    val canSend: Boolean get() = !running && (summary?.points ?: 0) > 0 && formats.isNotEmpty()
    /** 0.1.56 S4: 들어가는 것 접기(기본 접힘) */
    var optionsOpen by mutableStateOf(false)

    /** 0.1.55 한 번 누르기: 만들고 바로 공유 창. 저장 위치는 [savedText] 로 화면에 남긴다 */
    fun runAndShare() { shareAfter = true; run() }
    private var shareAfter = false
    /** 0.1.55 저장 위치 한 줄(내장 저장공간/…) */
    var savedText by mutableStateOf<String?>(null); private set

    fun run() {
        if (running) { shareAfter = false; return }
        val s = summary
        // 0.1.56 M8: 잰 점 0개 = 만들지 않음(빈 zip 0). M5: 토스트 없이 화면 안에만
        if (s == null || s.points == 0) { shareAfter = false; error = kr.archsurvey.job.SendFolder.EMPTY; return }
        if (formats.isEmpty()) { shareAfter = false; error = "양식을 하나 이상 고르세요."; return }
        val fs = formats
        val thenShare = shareAfter; shareAfter = false
        running = true; error = null; notice = null; output = null; savedText = null
        scope.launch {
            try {
                val o = platform.exportBundle(fs, includePhotos && (s?.photos ?: 1) > 0, includeVoices && (s?.voices ?: 1) > 0, itwinKeepNames, scale, if (todayOnly) todayKey() else null)
                output = o
                // 0.1.56 M5: 토스트 없음 · 경로 한 가지(앞 화면과 같은 글)
                o.where?.let { where = it }
                savedText = o.where ?: where ?: o.fileName
                if (thenShare) share()
            }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = UserError.of("export", e, "PC로 보내기에 실패했어요", "저장 공간을 확인하고 다시 시도하세요")
            }
            finally { running = false }
        }
    }

    fun share() {
        val o = output ?: return
        runCatching { platform.share(o) }
            .onSuccess { notice = "공유 창을 열었어요 · 받을 앱을 고르세요" }
            .onFailure { notice = UserError.of("share", it, "공유 창을 열지 못했어요") }
    }
    fun saveAs() { output?.let { o -> platform.saveAs(o) { ok -> notice = if (ok) "파일로 저장했어요" else "저장을 취소했거나 실패했어요" } } }

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
