package kr.archsurvey.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.export.GcpAutoSaver
import kr.archsurvey.ui.GcpFilePlatform
import kr.archsurvey.ui.GcpSaveResult

/** 0.1.37 [GcpFilePlatform] Android 구현: 저장 즉시 iTwin XML·TXT 를 공용 Documents 와 앱 폴더에 덮어쓴다 */
class AndroidGcpFiles(private val ctx: Context, private val project: () -> String) : GcpFilePlatform {
    private val saver by lazy { SurveyDb.get(ctx).let { GcpAutoSaver(ctx, it.dao(), it.arch()) } }
    override suspend fun save(featureId: Long?): GcpSaveResult? = withContext(Dispatchers.IO) {
        saver.save(project(), featureId)?.let { GcpSaveResult(it.featureLabel, it.xmlName, it.publicPath, it.count) }
    }
    override fun share(featureLabel: String): Boolean {
        val i = saver.shareIntent(project(), featureLabel) ?: return false
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}
