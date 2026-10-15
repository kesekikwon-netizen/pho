package kr.archsurvey.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.imports.SmartTopoImport
import kotlin.coroutines.resume

/** 0.1.59 B4: 문서 선택기로 SmartTopo 작업 zip / *_point.dbf / *_Data.csv 를 골라 지금 작업에 점으로 넣는다 */
class AndroidSmartTopoImport(private val ctx: Context, private val docs: DocBridge, private val project: () -> String) {
    private fun nameOf(u: Uri): String = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: (u.lastPathSegment ?: "")

    /** 반환 = 사용자에게 보일 결과 한 줄(취소면 null) */
    suspend fun pickAndImport(zone: KoreanTmZone): String? {
        val uris = suspendCancellableCoroutine<List<Uri>> { k -> docs.openMany { k.resume(it) } }
        if (uris.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val dbf = ArrayList<SmartTopoImport.StPoint>(); val csv = ArrayList<SmartTopoImport.StPoint>()
            for (u in uris) {
                val n = nameOf(u)
                val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                when {
                    n.contains("StakeOut", true) -> {}
                    n.endsWith(".zip", true) -> SmartTopoImport.readZip(bytes).let { (d, c) -> dbf += d; csv += c }
                    n.endsWith(".dbf", true) -> dbf += SmartTopoImport.fromDbf(SmartTopoImport.readDbf(bytes))
                    n.endsWith(".csv", true) -> csv += SmartTopoImport.fromCsv(String(bytes, SmartTopoImport.CP949))
                }
            }
            val all = SmartTopoImport.merge(dbf, csv)
            if (all.isEmpty()) return@withContext "SmartTopo 점을 찾지 못했어요 (작업 zip, _point.dbf, _Data.csv 중 하나를 고르세요)"
            val dao = SurveyDb.get(ctx).dao()
            val job = project()
            val existing = dao.allPointIds(job).toSet()
            val r = SmartTopoImport.toEntities(all, job, zone, existing, System.currentTimeMillis())
            for (e in r.points) dao.insertPointRaw(e)
            buildString {
                append("SmartTopo 점 ${r.points.size}개를 가져왔어요")
                if (r.skippedDuplicate > 0) append(" · 같은 이름 ${r.skippedDuplicate}개 건너뜀")
                if (r.skippedDeleted > 0) append(" · 삭제점 ${r.skippedDeleted}개 뺌")
                if (r.skippedNoPosition > 0) append(" · 좌표 없음 ${r.skippedNoPosition}개")
                r.maxNeDiffM?.let { if (it > 0.05) append(" · SmartTopo 좌표계와 최대 ${"%.2f".format(it)} m 차이(위경도로 다시 계산)") }
            }
        }
    }
}
