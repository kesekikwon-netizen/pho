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

/**
 * 사진 저장소: 앱 전용 외부 폴더(권한 불필요, 스코프드 스토리지 호환, API 19+).
 * DB 에는 상대경로만 저장. 갤러리 선택은 앱 폴더로 복사(원본 삭제와 무관).
 */
class PhotoStore(private val ctx: Context, private val dao: SurveyDao) {
    val root: File = (ctx.getExternalFilesDir("Pictures") ?: File(ctx.filesDir, "Pictures")).also { it.mkdirs() }
    private val thumbRoot: File get() = File(root, ".thumbs").also { it.mkdirs() }

    fun file(rel: String) = File(root, rel)
    fun thumbFile(rel: String) = File(thumbRoot, rel.replace('/', '_'))

    private fun safe(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_")

    /** 카메라 촬영용 대상 파일(상대경로 + FileProvider URI). 촬영 전에 호출해 pending 으로 저장해 둘 것 */
    fun newCameraTarget(project: String, pointId: String): Pair<String, Uri> {
        val rel = "${safe(project)}/${safe(pointId)}/${safe(pointId)}_${stamp()}.jpg"
        val f = file(rel); f.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return rel to uri
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())

    /** 촬영 완료 후 등록. 0바이트면 삭제 후 false. 이미 등록돼 있으면 중복 등록 안 함 */
    suspend fun registerCaptured(pointRowId: Long, rel: String): Boolean {
        val f = file(rel)
        if (!f.exists() || f.length() == 0L) { f.delete(); return false }
        if (dao.countPhotoPath(rel) > 0) return true
        val (w, h) = bounds(f)
        val thumb = makeThumb(rel)
        val id = dao.insertPhotoLimited(PhotoEntity(pointRowId = pointRowId, relPath = rel, thumbRelPath = thumb, source = "CAMERA",
            addedAtUtcMs = System.currentTimeMillis(), width = w, height = h))
        if (id < 0) { f.delete(); thumbFile(rel).delete(); return false } // 4장 제한
        return true
    }

    /** 갤러리(Photo Picker) 에서 고른 URI 들을 앱 폴더로 복사(긴 변 2560px 이하, EXIF 회전 반영) */
    suspend fun importFromUris(pointRowId: Long, project: String, pointId: String, uris: List<Uri>): Int {
        var ok = 0
        for (u in uris) {
            try {
                val rel = "${safe(project)}/${safe(pointId)}/${safe(pointId)}_${stamp()}_g${ok}.jpg"
                val out = file(rel); out.parentFile?.mkdirs()
                if (!copyScaled(u, out)) { out.delete(); continue }
                val (w, h) = bounds(out)
                val thumb = makeThumb(rel)
                val id = dao.insertPhotoLimited(PhotoEntity(pointRowId = pointRowId, relPath = rel, thumbRelPath = thumb, source = "GALLERY",
                    addedAtUtcMs = System.currentTimeMillis(), width = w, height = h))
                if (id < 0) { out.delete(); thumbFile(rel).delete(); break } // 4장 제한: 남은 사진은 받지 않음
                ok++
            } catch (_: Exception) { }
        }
        return ok
    }

    suspend fun delete(p: PhotoEntity) {
        file(p.relPath).delete(); p.thumbRelPath?.let { thumbFile(p.relPath).delete() }
        dao.deletePhoto(p)
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
        FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
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
            FileOutputStream(thumbFile(rel)).use { b!!.compress(Bitmap.CompressFormat.JPEG, 80, it) }
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
