package kr.archsurvey.pg.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.pg.data.Still
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.tag.TagDetector
import kr.archsurvey.pg.tag.TargetNaming
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.ui.releaseTap
import java.io.File

/** ARCore 없는 폰: 기본 카메라로 한 장씩(원본 해상도) → 표적 자동 검출 → iTwin 내보내기. 실시간 3D 없음 */
@Composable
fun FallbackScreen(store: Store, name: String, onBack: () -> Unit, onResult: () -> Unit) {
    val ctx = LocalContext.current
    var session by remember { mutableStateOf(store.load(name)) }
    var pending by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending ?: return@rememberLauncherForActivityResult
        if (!ok || !f.exists() || f.length() == 0L) { f.delete(); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            val st = withContext(Dispatchers.Default) { analyse(f, (session.stills.maxOfOrNull { it.id } ?: 0) + 1) }
            if (st != null) { session = session.copy(stills = session.stills + st); store.save(session) }
            busy = false
        }
    }
    fun shoot() {
        val id = (session.stills.maxOfOrNull { it.id } ?: 0) + 1
        val f = File(store.photos(name), String.format(java.util.Locale.US, "IMG_%04d.jpg", id))
        pending = f
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        take.launch(uri)
    }
    val counts = session.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.groupingBy { it }.eachCount()
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).releaseTap("뒤로") { onBack() }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
            Txt("$name · 사진만", size = FieldIvory.title, bold = true)
        }
        Txt("이 폰은 실시간 3D 가 안 돼서 사진과 표적만 모아요. 3D 는 iTwin 에서 만들어요.", color = P.sub)
        Spacer(Modifier.height(16.dp))
        Txt("사진 ${session.stills.size}장", size = 26.sp, bold = true)
        Spacer(Modifier.height(8.dp))
        if (counts.isEmpty()) Txt("아직 표적이 안 보였어요", color = P.sub)
        counts.toSortedMap().forEach { (no, n) -> Txt("Target #$no · ${minOf(n, 3)}/3", bold = true, color = if (n >= 3) P.fix else P.float) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlineBtn("결과 · 내보내기", enabled = session.stills.isNotEmpty()) { onResult() }
            Spacer(Modifier.weight(1f))
            Shutter(if (busy) "…" else "촬영") { if (!busy) shoot() }
        }
    }
}

/**
 * P0.1 #4: 기본 카메라 사진은 EXIF 회전값이 있을 수 있다 → 화소를 실제로 돌려 다시 저장(품질 95, EXIF 방향 = 1).
 * iTwin 이 회전을 적용하든 안 하든 표시 화소와 같아진다. 회전이 없으면 원본 그대로.
 */
private fun analyse(f: File, id: Int): Still? = runCatching {
    val ex0 = ExifInterface(f)
    val orient = ex0.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val f35 = ex0.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
    var b = BitmapFactory.decodeFile(f.path) ?: return null
    val deg = when (orient) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
    if (deg != 0) {
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        val r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true); b.recycle(); b = r
        val keep = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME).associateWith { ex0.getAttribute(it) }
        val tmp = File(f.path + ".rot")
        tmp.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(tmp).apply { keep.forEach { (k, v) -> if (v != null) setAttribute(k, v) }; setAttribute(ExifInterface.TAG_ORIENTATION, "1"); saveAttributes() }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        kr.archsurvey.pg.diag.PgLog.i("fallback photo ${f.name} rotated $deg° → orientation 1")
    }
    val w = b.width; val h = b.height
    val gray = ByteArray(w * h); val row = IntArray(w)
    for (y in 0 until h) {
        b.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) { val c = row[x]; gray[y * w + x] = ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8).toByte() }
    }
    b.recycle()
    val q = kr.archsurvey.pg.diag.Quality.check(gray, w, h)
    if (!q.ok) kr.archsurvey.pg.diag.PgLog.w("fallback photo ${f.name} quality=${q.reason} sharp=%.1f mean=${q.mean} (kept: user shot)".format(q.sharpness))
    val (tags, small) = kr.archsurvey.pg.diag.Quality.filterTags(TagDetector.detect(gray, w, h, decimate = 2f, maxHamming = 0))
    small.forEach { kr.archsurvey.pg.diag.PgLog.i("tag DROP photo=${f.name} Target#${it.target} size=%.0fpx < 35".format(kr.archsurvey.pg.diag.Quality.tagPx(it))) }
    kr.archsurvey.pg.diag.PgLog.i("fallback photo ${f.name} ${w}x${h} tags=${tags.map { "Target#${it.target}@(%.1f,%.1f)".format(it.cx, it.cy) }}")
    val fpx = if (f35 > 0) f35 / 36.0 * maxOf(w, h) else 0.8 * maxOf(w, h)
    Still(id, f.name, w, h, fpx, fpx, w / 2.0, h / 2.0, null, System.currentTimeMillis(), tags)
}.onFailure { kr.archsurvey.pg.diag.PgLog.e("fallback analyse failed ${f.name}", it) }.getOrNull()
