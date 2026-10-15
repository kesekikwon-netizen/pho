package kr.archsurvey.engine

import android.content.Context
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.export.ExportResult
import kr.archsurvey.export.ExportService
import kr.archsurvey.ui.ExportOutput
import kr.archsurvey.ui.ExportPlatform
import kr.archsurvey.ui.ExportSummary
import kr.archsurvey.voice.VoiceStore
import kr.archsurvey.photo.PhotoStore
import java.io.File

/** [ExportPlatform] 의 Android 구현. ZIP 은 앱 전용 폴더에 만들고, 공유(FileProvider)·저장(문서 선택기)으로 내보낸다. */
class AndroidExportPlatform(
    private val ctx: Context,
    private val project: () -> String,
    private val photoStore: PhotoStore,
    private val voiceStore: VoiceStore,
    private val docs: DocBridge,
    /** 0.1.38 내보낸 묶음을 작업 폴더 내보내기/ 에도 */
    private val jobFolder: AndroidJobFolder? = null,
) : ExportPlatform {
    private val dao = SurveyDb.get(ctx).dao()
    private val svc = ExportService(ctx, dao, photoStore, voiceStore, SurveyDb.get(ctx).arch())
    private var lastZip: File? = null

    override suspend fun summary(): ExportSummary {
        val pts = dao.pointsOf(project())
        var ph = 0; var vo = 0
        for (p in pts) { ph += dao.photoCount(p.rowId); vo += dao.voiceCount(p.rowId) }
        return ExportSummary(project(), pts.size, ph, vo, pts.count { "가짜" in it.device })
    }

    override suspend fun export(includePhotos: Boolean, includeVoices: Boolean): ExportOutput = exportFormats(kr.archsurvey.export.ExportFormat.DEFAULT, includePhotos, includeVoices)

    override suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean): ExportOutput =
        exportFormats(formats, includePhotos, includeVoices, false)

    override suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean, itwinKeepNames: Boolean): ExportOutput =
        exportBundle(formats, includePhotos, includeVoices, itwinKeepNames, kr.archsurvey.dig.DrawingScale.DEFAULT, null)

    override suspend fun exportBundle(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean, itwinKeepNames: Boolean, scale: Int, todayKey: String?): ExportOutput {
        val job = project()
        val r: ExportResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            svc.export(job, includePhotos, includeVoices, formats, itwinKeepNames, scale, todayKey, publishHgis = jobFolder == null)
        }
        lastZip = r.zip
        // 0.1.38: 공용 사본 = Documents/ExcavationSurvey/[상위/]작업/내보내기/<작업_시각>/ (예전 …/hgis/… 대신)
        // 0.1.56 M6·S5: 새 뿌리 발굴조사/…/측량/보내기/<작업>_측량.zip (덮어쓰기 + 이전/ 1개). 경로 한 줄은 앞 화면과 같은 글
        val sent = jobFolder?.let { f -> runCatching { f.addSend(job, r.zip) }.onFailure { kr.archsurvey.diag.CrashLog.record("PC로 보내기 저장", it) }.getOrNull() }
        val file = sent?.first ?: r.zip
        return ExportOutput(file.name, file.length(), file.path, r.pointCount, r.photoCount, r.voiceCount, null, where = sent?.second ?: kr.archsurvey.job.SendFolder.displayLocal(r.zip))
    }

    override fun sendWhere(): String? = jobFolder?.let { runCatching { it.sendDisplay(project()) }.getOrNull() }

    override fun share(out: ExportOutput) {
        ctx.startActivity(svc.shareIntent(File(out.path)))
    }

    override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) {
        val f = File(out.path)
        docs.create(out.fileName) { uri ->
            if (uri == null) onDone(false)
            else onDone(runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false))
        }
    }
}
