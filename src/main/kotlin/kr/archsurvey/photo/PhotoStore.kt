package kr.archsurvey.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import kr.archsurvey.data.PhotoEntity
import kr.archsurvey.data.SurveyDao
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object JobFoldersPublic { val parent = kr.archsurvey.job.JobFolders.PUBLIC_PARENT }

/**
 * 사진 저장소(0.1.59).
 * - 원본: 앱 폴더 Pictures/발굴조사/발굴 측량/[폴더/]<작업>/사진/<점>_<n>.jpg (백업·복구가 그대로 담는다)
 * - 공용 사본: Documents/발굴조사/발굴 측량/[폴더/]<작업>/사진/ (MediaStore, 갤러리·PC 연결에서 보임, 앱을 지워도 남음)
 * - 장수 제한 없음. 찍은 사진마다 EXIF 에 점 이름·좌표·σ·FIX.
 * DB 에는 원본 기준 상대경로만 저장.
 */
class PhotoStore(private val ctx: Context, private val dao: SurveyDao, private val jobRel: (String) -> String = { "ExcavationSurvey/" + it }) {
    val root: File = (ctx.getExternalFilesDir("Pictures") ?: File(ctx.filesDir, "Pictures")).also { it.mkdirs() }
    private val thumbRoot: File get() = File(root, ".thumbs").also { it.mkdirs() }

    fun file(rel: String) = File(root, rel)
    fun thumbFile(rel: String) = File(thumbRoot, rel.replace('/', '_'))

    fun dirRel(project: String) = PhotoLayout.dirRel(jobRel(project))

    /** 카메라 촬영용 대상 파일(상대경로 + FileProvider URI). 이름 = <점>_<n>.jpg */
    fun newCameraTarget(project: String, pointId: String): Pair<String, Uri> {
        val dir = dirRel(project)
        val d = file(dir).also { it.mkdirs() }
        val rel = "$dir/" + PhotoLayout.nextName(pointId, d.list()?.toList().orEmpty())
        val f = file(rel)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return rel to uri
    }

    /** 촬영 완료 후 등록 → 새 사진 id (실패 null). 0바이트면 지움. 이미 등록돼 있으면 그 id */
    suspend fun registerCaptured(pointRowId: Long, rel: String): Long? {
        val f = file(rel)
        if (!f.exists() || f.length() == 0L) { f.delete(); return null }
        dao.photoIdOfPath(rel)?.let { return it }
        stampExif(pointRowId, f)
        val (w, h) = bounds(f)
        val thumb = makeThumb(rel)
        val id = dao.insertPhoto(PhotoEntity(pointRowId = pointRowId, relPath = rel, thumbRelPath = thumb, source = "CAMERA",
            addedAtUtcMs = System.currentTimeMillis(), width = w, height = h))
        publish(rel)
        return id
    }

    /** 갤러리(Photo Picker) 에서 고른 URI 들을 작업 사진 폴더로 복사(긴 변 2560px 이하, EXIF 회전 반영). 제한 없음 */
    suspend fun importFromUris(pointRowId: Long, project: String, pointId: String, uris: List<Uri>): Int {
        var ok = 0
        val dir = dirRel(project)
        for (u in uris) {
            try {
                val d = file(dir).also { it.mkdirs() }
                val rel = "$dir/" + PhotoLayout.nextName(pointId, d.list()?.toList().orEmpty())
                val out = file(rel)
                if (!copyScaled(u, out)) { out.delete(); continue }
                stampExif(pointRowId, out)
                val (w, h) = bounds(out)
                val thumb = makeThumb(rel)
                dao.insertPhoto(PhotoEntity(pointRowId = pointRowId, relPath = rel, thumbRelPath = thumb, source = "GALLERY",
                    addedAtUtcMs = System.currentTimeMillis(), width = w, height = h))
                publish(rel)
                ok++
            } catch (e: Exception) { kr.archsurvey.diag.CrashLog.record("앨범 사진 가져오기", e) }
        }
        return ok
    }

    suspend fun delete(p: PhotoEntity) {
        unpublish(p.relPath)
        file(p.relPath).delete(); thumbFile(p.relPath).delete()
        dao.deletePhoto(p)
    }

    /** 점 정보를 EXIF 에(실패해도 사진은 남긴다) */
    suspend fun stampExif(pointRowId: Long, f: File) {
        val pt = dao.getPoint(pointRowId) ?: return
        val info = PhotoPointInfo(pt.pointId, pt.lat, pt.lon, pt.northing, pt.easting, pt.crsEpsg,
            pt.groundMsl ?: pt.groundEllipsoidalHeight, pt.groundMsl != null, pt.sigmaH, pt.sigmaU, pt.fixQuality)
        runCatching {
            val ex = ExifInterface(f.path)
            for ((k, v) in PhotoExif.tags(info)) ex.setAttribute(k, v)
            ex.setAttribute(ExifInterface.TAG_XMP, PhotoExif.xmpAscii(info))
            ex.saveAttributes()
        }.onFailure { kr.archsurvey.diag.CrashLog.record("사진 EXIF 쓰기", it) }
    }

    // ---------- 공용 Documents 사본 (Android 10+) ----------
    private fun publicRelOf(rel: String): String = JobFoldersPublic.parent + "/" + rel.substringBeforeLast('/') + "/"

    /** 공용 사본 쓰기(같은 이름이면 덮어씀). Android 9 이하 = 앱 폴더만 */
    fun publish(rel: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val f = file(rel)
        return runCatching {
            val cr = ctx.contentResolver
            val coll = android.provider.MediaStore.Files.getContentUri("external")
            val relPath = publicRelOf(rel)
            val existing = findPublic(relPath, f.name)
            val uri = existing ?: cr.insert(coll, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }) ?: error("insert")
            (cr.openOutputStream(uri, "wt") ?: error("open")).use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        }.getOrElse { kr.archsurvey.diag.CrashLog.record("사진 공용 사본", it); false }
    }

    private fun findPublic(relPath: String, name: String): Uri? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val coll = android.provider.MediaStore.Files.getContentUri("external")
        return ctx.contentResolver.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relPath, name), null)?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null }
    }

    /** 앱 안에서 지운 사진은 공용 사본도 지운다(우리 앱이 만든 것만 지워진다) */
    private fun unpublish(rel: String) {
        if (!PhotoLayout.isNewLayout(rel)) return
        runCatching { findPublic(publicRelOf(rel), rel.substringAfterLast('/'))?.let { ctx.contentResolver.delete(it, null, null) } }
    }

    /** 0.1.59 옛 위치(<작업>/<점>/…) → 새 위치. 복사·확인 뒤에만 옛 파일을 지운다 */
    suspend fun migrate(project: String): MigrationReport {
        val rows = dao.photoRowsOf(project)
        if (rows.none { !PhotoLayout.isNewLayout(it.rel) }) return MigrationReport(0, rows.size, 0, 0)
        val ops = object : PhotoFileOps {
            override fun exists(rel: String) = file(rel).isFile
            override fun list(dirRel: String) = file(dirRel).list()?.toList().orEmpty()
            override fun copy(fromRel: String, toRel: String) { val d = file(toRel); d.parentFile?.mkdirs(); file(fromRel).inputStream().use { kr.archsurvey.io.AtomicFiles.copy(it, d) } }
            override fun sha256(rel: String): String? = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                file(rel).inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            override fun size(rel: String) = file(rel).length()
            override fun delete(rel: String) { file(rel).delete() }
        }
        return PhotoMigration(ops) { id, rel -> dao.updatePhotoRel(id, rel) }.run(rows, dirRel(project)) { old, new ->
            thumbFile(old.rel).delete()
            file(old.rel).parentFile?.let { d -> if (d.list()?.isEmpty() == true) d.delete() }
            publish(new)
        }
    }
    private fun bounds(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    private fun copyScaled(src: Uri, dst: File): Boolean {
        val maxSide = 2560
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return false
        if (o.outWidth <= 0) return false
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, dec) } ?: return false
        val rot = ctx.contentResolver.openInputStream(src)?.use { rotationOf(it) } ?: 0
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f || rot != 0) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rot != 0) m.postRotate(rot.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        kr.archsurvey.io.AtomicFiles.write(dst) { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return true
    }

    private fun rotationOf(ins: java.io.InputStream): Int = try {
        when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
        }
    } catch (_: Exception) { 0 }

    private fun makeThumb(rel: String): String? = try {
        val f = file(rel)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2
        var b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        if (b != null) {
            val rot = try { f.inputStream().use { rotationOf(it) } } catch (_: Exception) { 0 }
            if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            kr.archsurvey.io.AtomicFiles.write(thumbFile(rel)) { b!!.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            rel
        } else null
    } catch (_: Exception) { null }

    /** 목록 표시용 썸네일 비트맵 (없으면 즉석 생성) */
    fun loadThumb(rel: String): Bitmap? {
        val t = thumbFile(rel)
        if (!t.exists()) makeThumb(rel)
        return if (t.exists()) BitmapFactory.decodeFile(t.path) else null
    }
}
