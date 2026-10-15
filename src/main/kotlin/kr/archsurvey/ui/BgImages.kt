package kr.archsurvey.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.archsurvey.georef.TiffRaster
import java.io.File
import java.security.MessageDigest

/**
 * 0.1.58 B2: 업로드 GeoTIFF 의 실제 그림(긴 변 ≤ 2048). 자체 디코더로 한 번 풀어 앱 캐시에 PNG 로 저장해 두고,
 * 다음부터는 PNG 만 읽는다. 메모리에는 최근 4장. 실패하면 이유를 [failure] 에 남기고 지도는 윤곽+안내로 둔다.
 * V월드 타일·키·지적도와는 무관(별도 경로).
 */
object BgImages {
    @Volatile var cacheDir: File? = null
    @Volatile var enabled = true
    private val mem = android.util.LruCache<String, Bitmap>(4)
    private val failures = HashMap<String, String>()
    private val inflight = HashSet<String>()
    private val gate = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var version by mutableIntStateOf(0); private set

    fun key(f: File): String {
        val md = MessageDigest.getInstance("SHA-1").digest("${f.absolutePath}|${f.length()}|${f.lastModified()}|${TiffRaster.DEFAULT_MAX_SIDE}".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun failure(path: String): String? = synchronized(failures) { failures[path] }

    fun get(path: String): Bitmap? {
        mem.get(path)?.let { return it }
        if (!enabled) return null
        synchronized(failures) { if (path in failures) return null }
        synchronized(inflight) { if (!inflight.add(path)) return null }
        scope.launch {
            try {
                gate.withPermit { load(File(path)) }.let { mem.put(path, it) }
                version++
            } catch (e: Throwable) {
                val msg = (e as? kr.archsurvey.georef.TiffUnsupportedException)?.message
                    ?: if (e is OutOfMemoryError) "그림이 너무 커서 윤곽만 보여요" else "그림을 읽지 못해 윤곽만 보여요"
                synchronized(failures) { failures[path] = msg }
                version++
            } finally { synchronized(inflight) { inflight -= path } }
        }
        return null
    }

    private fun load(src: File): Bitmap {
        val dir = cacheDir?.let { File(it, "bgimg").apply { mkdirs() } }
        val cached = dir?.let { File(it, key(src) + ".png") }
        if (cached != null && cached.length() > 0) BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        val r = TiffRaster.decode(src)
        val bmp = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888)
        if (cached != null) runCatching {
            val tmp = File(cached.parentFile, cached.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(cached)) tmp.delete()
        }
        return bmp
    }

    fun clear() { mem.evictAll(); synchronized(failures) { failures.clear() }; version++ }
}
