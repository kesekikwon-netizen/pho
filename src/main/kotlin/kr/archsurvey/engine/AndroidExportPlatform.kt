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
) : ExportPlatform {
    private val dao = SurveyDb.get(ctx).dao()
    private val svc = ExportService(ctx, dao, photoStore, voiceStore)
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

    override suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean, itwinKeepNames: Boolean): ExportOutput {
        val r: ExportResult = svc.export(project(), includePhotos, includeVoices, formats, itwinKeepNames)
        lastZip = r.zip
        return ExportOutput(r.zip.name, r.zip.length(), r.zip.path, r.pointCount, r.photoCount, r.voiceCount, r.publicFolder)
    }

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
