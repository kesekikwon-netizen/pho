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

data class ExportResult(
    val dir: File, val zip: File, val pointCount: Int, val photoCount: Int, val voiceCount: Int = 0,
    /** 공용 문서 폴더에 둔 hgis 묶음 경로(예: Documents/ExcavationSurvey/hgis/작업_20261005_101000). 못 만들었으면 null */
    val publicFolder: String? = null,
    /** UX-0127-1: iTwin XML 을 골랐을 때 높이가 없어 XML 에서 뺀 점 수·번호(대조표 CSV 에만 적힘) */
    val itwinDroppedCount: Int = 0,
    val itwinDroppedNames: List<String> = emptyList(),
)

/** 공용 문서 폴더 안 hgis 묶음 상위 경로 */
const val PUBLIC_ROOT = "Documents/ExcavationSurvey/hgis"

/** MediaStore 로 공용 Documents 에 파일 복사(API 29+). */
@androidx.annotation.RequiresApi(29)
internal object PublicDocs {
    fun copyTopLevel(ctx: Context, dir: File, relPath: String): String? {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        val cr = ctx.contentResolver
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val created = ArrayList<Uri>()
        try {
            for (f in files) {
                val ext = f.extension.lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$relPath/")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(collection, cv) ?: error("insert ${f.name}")
                created.add(uri)
                (cr.openOutputStream(uri) ?: error("open ${f.name}")).use { o -> f.inputStream().use { it.copyTo(o) } }
                cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            for (u in created) runCatching { cr.delete(u, null, null) }
            return null
        }
        return relPath
    }
}

class ExportService(
    private val ctx: Context, private val dao: SurveyDao, private val photos: PhotoStore,
    private val voices: kr.archsurvey.voice.VoiceStore? = null,
    /** 0.1.35 도면 DXF·야장 CSV 용 유구·그리드 */
    private val arch: kr.archsurvey.data.ArchDao? = null,
) {
    suspend fun export(project: String, includePhotos: Boolean, includeVoices: Boolean = false, formats: Set<ExportFormat> = ExportFormat.DEFAULT,
        /** 0.1.38: 쓰지 않음(사진 실측 iTwin 내보내기는 별도 앱으로 옮김). 시그니처 호환용 */
        itwinKeepNames: Boolean = false,
        /** 0.1.35 도면 축척 분모(20/100/200) — 도면 DXF 글자 크기 */
        drawingScale: Int = kr.archsurvey.dig.DrawingScale.DEFAULT,
        /** 0.1.35 오늘 잰 점만(yyyy-MM-dd, 폰 시간대). null = 전체 */
        todayKey: String? = null,
    ): ExportResult {
        val all = dao.pointsOf(project)
        val pts = if (todayKey == null) all else DigExport.todayOnly(all, todayKey) { kr.archsurvey.dig.DayKey.of(it) }
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
        // 0.1.14: 고른 양식만 만든다(목업 11). 사진 목록 CSV 는 사진을 넣을 때 또는 기본 양식일 때
        if (ExportFormat.CSV in formats) File(dir, "points.csv").writeText(Formats.pointsCsv(pts, byPoint), Charsets.UTF_8)
        if (ExportFormat.CSV in formats || includePhotos) File(dir, "photos.csv").writeText(Formats.photosCsv(photoRows), Charsets.UTF_8)
        if (ExportFormat.GEOJSON in formats) File(dir, "points.geojson").writeText(Formats.pointsGeoJson(pts, byPoint), Charsets.UTF_8)
        // DXF: 지면 표고(표고 우선, 없으면 타원체고). 축 순서 기본 X=동·Y=북, 한글은 CP949
        if (ExportFormat.DXF in formats) File(dir, "points.dxf").writeBytes(DxfWriter.write(pts.map { DxfPoint(it.pointId, it.easting, it.northing, it.groundMsl ?: it.groundEllipsoidalHeight) }))
        if (ExportFormat.DRAWING_DXF in formats || ExportFormat.FIELD_CSV in formats) {
            val a = arch
            val verts = a?.outlineVertices(project).orEmpty().groupBy { it.outlineId }
            val outs = a?.outlines(project).orEmpty().map { o -> DigOutline(o.id, o.label, o.kind, o.featureType, o.closed, verts[o.id].orEmpty().sortedBy { it.orderNo }.map { it.pointRowId }) }
            val grids = a?.grids(project).orEmpty().map { kr.archsurvey.arch.GridDef(it.id, it.areaId, it.name, kr.archsurvey.core.NE(it.originN, it.originE), kr.archsurvey.core.NE(it.dirN, it.dirE), it.spacingM, it.cols, it.rows, it.crsEpsg) }
            if (ExportFormat.DRAWING_DXF in formats) File(dir, "drawing_1-$drawingScale.dxf").writeBytes(DxfWriter.writeDrawing(DigExport.drawing(pts, outs, grids, drawingScale)))
            if (ExportFormat.FIELD_CSV in formats) File(dir, "field_book.csv").writeText(DigExport.fieldCsv(pts, outs, byPoint.mapValues { it.value.size }), Charsets.UTF_8)
        }
        if (ExportFormat.REPORT in formats) File(dir, "point_report.csv").writeText(MoreFormats.reportCsv(pts, byPoint), Charsets.UTF_8)
        if (ExportFormat.KML in formats) File(dir, "points.kml").writeText(MoreFormats.kml(project, pts), Charsets.UTF_8)
        // hgis(Strata)·QGIS: 좌표계(작업 좌표계 = 점의 crsEpsg)마다 points_<EPSG>.shp/.shx/.dbf/.prj/.cpg 한 벌. 5179 를 기본으로 쓰지 않는다
        val shpEpsgs = ArrayList<Int>()
        if (ExportFormat.SHP in formats) {
            for (sh in MoreFormats.shapesByCrs(pts, byPoint)) {
                val b = "points_${sh.epsg}"
                File(dir, "$b.shp").writeBytes(sh.shp); File(dir, "$b.shx").writeBytes(sh.shx); File(dir, "$b.dbf").writeBytes(sh.dbf)
                sh.prj?.let { File(dir, "$b.prj").writeText(it, Charsets.US_ASCII) }
                File(dir, "$b.cpg").writeText(sh.cpg, Charsets.US_ASCII)
                shpEpsgs.add(sh.epsg)
            }
        }
        if (ExportFormat.HGIS_CSV in formats) {
            for ((epsg, group) in pts.groupBy { it.crsEpsg })
                File(dir, "hgis_control_points_$epsg.csv").writeText(MoreFormats.hgisControlCsv(group), Charsets.UTF_8)
        }
        File(dir, "README.txt").writeText(readme(project, pts.size, shpEpsgs), Charsets.UTF_8)

        val zip = File(base, "${safe}_$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        // PC 로 옮겨 hgis 에 끌어 놓기 쉽게: 위 파일(사진·음성 폴더 제외)을 공용 문서 폴더에도 한 폴더로 둔다(Android 10 이상)
        val publicFolder = if (ExportFormat.SHP in formats || ExportFormat.HGIS_CSV in formats) publish(dir, "${safe}_$stamp") else null
        return ExportResult(dir, zip, pts.size, photoCount, voiceCount, publicFolder)
    }

    /**
     * 내보낸 폴더의 맨 위 파일들을 `Documents/ExcavationSurvey/hgis/<작업>_<시각>/` 에 복사한다(MediaStore, 권한 불필요, API 29+).
     * USB 로 PC 에 연결하면 「내장 저장공간 › Documents › ExcavationSurvey › hgis」 에 보인다. 실패하거나 Android 9 이하면 null(ZIP 공유/저장은 그대로).
     */
    private fun publish(dir: File, folder: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return runCatching { PublicDocs.copyTopLevel(ctx, dir, "${PUBLIC_ROOT}/$folder") }.getOrNull()
    }

    private fun readme(project: String, n: Int, shpEpsgs: List<Int> = emptyList()) = """
        작업: $project / 점 $n 개
        points.csv      모든 항목(UTF-8, 엑셀 한글 가능)
        points.geojson  경위도(WGS84)+속성
        drawing_1-<축척>.dxf  도면용 DXF(레이어: 조사구역·그리드·트렌치·유구·유물·레벨·토층·기준점·기타·점번호·해발, 글자 = 축척 × 2.5 mm, 한글 CP949)
        field_book.csv  야장(점번호·종류·유구·유물번호·레벨·칸·해발·정밀도·사진 수·메모)
        points.dxf      CAD용 점·번호·표고 (R2000, 단위 m, 한글 CP949, X=동 Y=북, 레이어 PT/PT_NO/PT_ELEV)
        point_report.csv 점 보고서(엑셀용 핵심 열)
        points.kml      구글어스(경위도+지면 타원체고)
        points_<EPSG>.shp/.shx/.dbf/.prj/.cpg  점 Shapefile(작업 좌표계, X=동 Y=북, Z=지면 표고, 속성 UTF-8)
        hgis_control_points_<EPSG>.csv  hgis 「CSV 기준점」 양식(point_id,x,y,z,datum,ellipsoid,projection,origin,accuracy_m,pdop,fix_type)
        (고른 양식만 들어 있어요)
        photos.csv, photos/  점별 사진(최대 4장)
        voice.csv, voices/   점별 음성 메모(m4a, AAC 16kHz 모노). 내보낼 때 '음성 포함'을 켠 경우만
        좌표계는 CSV의 crs_epsg 열 참고. 실제 CAD 프로그램 열람은 현장에서 확인 필요.
    """.trimIndent() + "\n" + (if (shpEpsgs.isEmpty()) "" else hgisReadme(shpEpsgs))

    private fun hgisReadme(epsgs: List<Int>) = "\n" + """
        [hgis(Strata)에 넣기]
        1) 이 폴더를 통째로 PC에 복사하세요(휴대폰 화면에서 바로 끌면 안 돼요: PC에 먼저 복사).
           ZIP으로 받았으면 먼저 압축을 푸세요(hgis는 ZIP을 끌어 놓기로 받지 않아요).
        2) 복사한 폴더에서 ${epsgs.joinToString(", ") { "points_$it.shp" }} 를 hgis 지도나 레이어 목록에 끌어 놓으세요.
           .shx .dbf .prj .cpg 가 같은 폴더에 같은 이름으로 있어야 해요(여러 파일을 같이 끌어도 .shp 만 읽어요).
        3) 좌표계는 .prj 로 ${epsgs.joinToString(", ") { "EPSG:$it" }} 로 읽혀요. 조사 좌표계와 달라도 hgis가 화면에 맞춰 보여 줘요.
           점 번호(point_id)가 라벨로 떠요. 표고는 z 칸과 도형 Z(모든 점에 높이가 있을 때)에 있어요.
        4) 기준점으로 넣으려면: 기타 › 더보기 › CSV 기준점 → hgis_control_points_<EPSG>.csv.
           이 CSV는 좌표계를 따로 읽지 않으니 같은 좌표계(5186/5187) 조사에 불러오세요. 모든 점이 들어 있으니 필요 없는 행은 지우고 쓰세요.
        제출용 EPSG:5179 SHP는 hgis 「검수·제출」에서 만드세요(이 앱은 작업 좌표계로만 내보내요).
    """.trimIndent() + "\n"

    fun shareIntent(zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "내보내기 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
