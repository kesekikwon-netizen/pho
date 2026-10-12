package kr.archsurvey.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kr.archsurvey.data.ArchDao
import kr.archsurvey.data.SurveyDao
import java.io.File

/**
 * 0.1.37 사진 기준점 저장 즉시 iTwin 파일 자동 저장(「내보내기」에 안 가도 됨).
 *  - 앱 폴더: <앱 외부 파일>/Documents/exports/itwin_gcp_live/<작업>/ — 공유(FileProvider 「exports」)용, 매번 통째로 다시 씀
 *  - 공용 폴더(Android 10+): Documents/ExcavationSurvey/<작업>/itwin_gcp/ — MediaStore, 같은 이름이면 덮어씀(권한 불필요).
 *    USB 로 PC 에 연결하면 「내장 저장공간 › Documents › ExcavationSurvey › <작업> › itwin_gcp」.
 *  Android 9 이하는 앱 폴더만(공유 단추로 보냄).
 * 내용은 [ItwinGcpExport.build] 와 같다(유구별 XML·TXT + 겹치지 않으면 전체 + 표 + README).
 */
class GcpAutoSaver(private val ctx: Context, private val dao: SurveyDao, private val arch: ArchDao?) {
    data class Saved(val featureLabel: String, val xmlName: String?, val publicPath: String?, val localDir: File, val count: Int)

    fun safe(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")
    fun localDir(project: String) = File(File(ctx.getExternalFilesDir("Documents") ?: ctx.filesDir, "exports"), "itwin_gcp_live/${safe(project)}")
    fun publicRel(project: String) = "Documents/ExcavationSurvey/${safe(project)}/itwin_gcp"

    /** [featureId] 유구의 파일을 포함해 작업 전체 사진 기준점 파일을 다시 쓴다. 표적이 없으면 null */
    suspend fun save(project: String, featureId: Long?): Saved? {
        val pts = dao.pointsOf(project)
        val labels = arch?.outlines(project).orEmpty().associate { it.id to it.label }
        val r = ItwinGcpExport.build(pts, labels, project)
        if (r.files.isEmpty()) return null
        val dir = localDir(project)
        dir.deleteRecursively(); dir.mkdirs()
        for (f in r.files) File(dir, f.path.substringAfter('/')).writeText(f.text, Charsets.UTF_8)
        val g = r.groups.firstOrNull { it.featureId == featureId && it.used.isNotEmpty() }
        val xml = g?.let { ItwinGcpExport.baseName(it.label, it.epsg, it.heightTag) + ".xml" }
        val pub = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { publish(dir, publicRel(project)) }.getOrNull() else null
        return Saved(g?.label ?: (featureId?.let { labels[it] } ?: ItwinGcpExport.OUTSIDE), xml, pub, dir, g?.used?.size ?: 0)
    }

    @androidx.annotation.RequiresApi(29)
    private fun publish(dir: File, rel: String): String? {
        val cr = ctx.contentResolver
        val coll = android.provider.MediaStore.Files.getContentUri("external")
        for (f in dir.listFiles()?.filter { it.isFile }.orEmpty()) {
            val mime = when (f.extension.lowercase()) { "xml" -> "text/xml"; "csv" -> "text/csv"; else -> "text/plain" }
            val existing = cr.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$rel/", f.name), null)
                ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null }
            val uri = existing ?: cr.insert(coll, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
            }) ?: return null
            (cr.openOutputStream(uri, "wt") ?: return null).use { o -> f.inputStream().use { it.copyTo(o) } }
        }
        return rel
    }

    /** 유구 카드 「XML 공유」: 그 유구의 XML + TXT */
    fun shareIntent(project: String, featureLabel: String): Intent? {
        val dir = localDir(project)
        val files = dir.listFiles()?.filter { it.name.startsWith(ItwinGcpExport.safe(featureLabel) + "_AprilTag_") && (it.extension == "xml" || it.extension == "txt") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/xml").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, "$featureLabel iTwin 사진 기준점").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "$featureLabel XML 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
