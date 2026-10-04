package kr.archsurvey.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kr.archsurvey.data.SurveyDao
import kr.archsurvey.photo.PhotoStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(val dir: File, val zip: File, val pointCount: Int, val photoCount: Int)

class ExportService(private val ctx: Context, private val dao: SurveyDao, private val photos: PhotoStore) {
    suspend fun export(project: String, includePhotos: Boolean): ExportResult {
        val pts = dao.pointsOf(project)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = File(ctx.getExternalFilesDir("Documents") ?: ctx.filesDir, "exports").also { it.mkdirs() }
        val safe = project.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")
        val dir = File(base, "${safe}_$stamp").also { it.mkdirs() }

        val byPoint = LinkedHashMap<Long, List<String>>()
        val photoRows = ArrayList<Triple<String, String, Long>>()
        var photoCount = 0
        for (p in pts) {
            val list = dao.photosOf(p.rowId)
            byPoint[p.rowId] = list.map { "photos/" + it.relPath.substringAfterLast('/') }
            for (ph in list) {
                photoRows.add(Triple(p.pointId, "photos/" + ph.relPath.substringAfterLast('/'), ph.addedAtUtcMs))
                if (includePhotos) {
                    val dst = File(dir, "photos/" + ph.relPath.substringAfterLast('/')); dst.parentFile?.mkdirs()
                    runCatching { photos.file(ph.relPath).copyTo(dst, overwrite = true); photoCount++ }
                }
            }
        }
        File(dir, "points.csv").writeText(Formats.pointsCsv(pts, byPoint), Charsets.UTF_8)
        File(dir, "photos.csv").writeText(Formats.photosCsv(photoRows), Charsets.UTF_8)
        File(dir, "points.geojson").writeText(Formats.pointsGeoJson(pts, byPoint), Charsets.UTF_8)

        val zip = File(base, "${safe}_$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return ExportResult(dir, zip, pts.size, photoCount)
    }

    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "내보내기 공유")
    }
}
