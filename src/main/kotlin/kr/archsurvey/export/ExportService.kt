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

data class ExportResult(val dir: File, val zip: File, val pointCount: Int, val photoCount: Int, val voiceCount: Int = 0)

class ExportService(
    private val ctx: Context, private val dao: SurveyDao, private val photos: PhotoStore,
    private val voices: kr.archsurvey.voice.VoiceStore? = null,
) {
    suspend fun export(project: String, includePhotos: Boolean, includeVoices: Boolean = false): ExportResult {
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
        var voiceCount = 0
        val voiceRows = StringBuilder("\uFEFFpoint_id,voice_file,duration_s,added_utc\r\n")
        if (includeVoices && voices != null) {
            for (p in pts) for (v in dao.voicesOf(p.rowId)) {
                val name = "voices/" + v.relPath.substringAfterLast('/')
                val dst = File(dir, name); dst.parentFile?.mkdirs()
                if (runCatching { voices!!.file(v.relPath).copyTo(dst, overwrite = true) }.isSuccess) {
                    voiceCount++
                    voiceRows.append(Formats.csvEscape(p.pointId)).append(',').append(Formats.csvEscape(name)).append(',')
                        .append(String.format(Locale.US, "%.1f", v.durationMs / 1000.0)).append(',').append(Formats.utcOf(v.addedAtUtcMs)).append("\r\n")
                }
            }
            File(dir, "voice.csv").writeText(voiceRows.toString(), Charsets.UTF_8)
        }
        File(dir, "points.csv").writeText(Formats.pointsCsv(pts, byPoint), Charsets.UTF_8)
        File(dir, "photos.csv").writeText(Formats.photosCsv(photoRows), Charsets.UTF_8)
        File(dir, "points.geojson").writeText(Formats.pointsGeoJson(pts, byPoint), Charsets.UTF_8)
        // DXF: 지면 표고(표고 우선, 없으면 타원체고). 축 순서 기본 X=동·Y=북, 한글은 CP949
        File(dir, "points.dxf").writeBytes(DxfWriter.write(pts.map { DxfPoint(it.pointId, it.easting, it.northing, it.groundMsl ?: it.groundEllipsoidalHeight) }))
        File(dir, "README.txt").writeText(readme(project, pts.size), Charsets.UTF_8)

        val zip = File(base, "${safe}_$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return ExportResult(dir, zip, pts.size, photoCount, voiceCount)
    }

    private fun readme(project: String, n: Int) = """
        작업: $project / 점 $n 개
        points.csv      모든 항목(UTF-8, 엑셀 한글 가능)
        points.geojson  경위도(WGS84)+속성
        points.dxf      CAD용 점·번호·표고 (R2000, 단위 m, 한글 CP949, X=동 Y=북, 레이어 PT/PT_NO/PT_ELEV)
        photos.csv, photos/  점별 사진(최대 4장)
        voice.csv, voices/   점별 음성 메모(m4a, AAC 16kHz 모노). 내보낼 때 '음성 포함'을 켠 경우만
        좌표계는 CSV의 crs_epsg 열 참고. 실제 CAD 프로그램 열람은 현장에서 확인 필요.
    """.trimIndent() + "\n"

    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "내보내기 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
