package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.archsurvey.map.VWorldTileResponse
import kr.archsurvey.map.VWorldUrls
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.1.12 V월드 타일 그림 — **메모리에만** 잠깐 보관(LRU 160장). 약관상 타일 영구 저장·오프라인 팩은 하지 않는다(vworld-spec.md §4).
 * 화면은 [version] 을 읽어 새 타일이 오면 다시 그린다. 실패한 URL 은 60초 동안 다시 받지 않는다.
 * 오류 문구에는 키를 넣지 않는다.
 * 0.1.24: 지적 레거시 2DCache 실패 시 공식 OpenAPI WMS 한 번 폴백. ServiceException code/문구 표시.
 */
object TileImages {
    private val cache = android.util.LruCache<String, ImageBitmap>(160)
    private val failedAt = HashMap<String, Long>()
    private val inflight = HashSet<String>()
    private val gate = Semaphore(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 새 타일이 들어올 때마다 1 증가 */
    var version by mutableIntStateOf(0); private set
    /** 마지막 실패 이유(키 가림). 성공하면 지움 */
    var lastError by mutableStateOf<String?>(null); private set
    /** 시험(JVM)에서는 꺼서 네트워크를 쓰지 않는다 */
    @Volatile var networkEnabled = true

    fun get(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (!networkEnabled) return null
        synchronized(inflight) {
            if (url in inflight) return null
            val f = failedAt[url]; if (f != null && System.currentTimeMillis() - f < 60_000) return null
            inflight += url
        }
        scope.launch {
            try {
                gate.withPermit { fetch(url) }?.let { cache.put(url, it); lastError = null; version++ }
            } catch (e: Exception) {
                synchronized(inflight) { failedAt[url] = System.currentTimeMillis() }
                runCatching { android.util.Log.w("TileImages", "tile fetch failed", e) }
                lastError = VWorldTileResponse.networkErrorKo(url, e.javaClass.simpleName)
            } finally { synchronized(inflight) { inflight -= url } }
        }
        return null
    }

    /** 0.1.46 오류 칩 [다시]: 실패 기록만 지우고 다시 받기(캐시는 유지) */
    fun retry() { synchronized(inflight) { failedAt.clear() }; lastError = null; version++ }

    fun clear() { cache.evictAll(); synchronized(inflight) { failedAt.clear() }; lastError = null; version++ }

    private fun fetch(url: String): ImageBitmap? {
        val primary = fetchOnce(url)
        if (primary.image != null) return primary.image
        val alt = VWorldUrls.cadastralAlternate(url)
        if (alt != null) {
            val secondary = fetchOnce(alt)
            if (secondary.image != null) return secondary.image
            // 둘 다 실패 → 더 구체적인 예외(공식 쪽) 우선, 없으면 레거시 쪽
            val err = secondary.error ?: primary.error
            if (err != null) {
                synchronized(inflight) { failedAt[url] = System.currentTimeMillis() }
                lastError = err
            }
            return null
        }
        if (primary.error != null) {
            synchronized(inflight) { failedAt[url] = System.currentTimeMillis() }
            lastError = primary.error
        }
        return null
    }

    private data class Once(val image: ImageBitmap?, val error: String?)

    private fun fetchOnce(url: String): Once {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; setRequestProperty("User-Agent", "kr.archsurvey.field")
        }
        try {
            val code = c.responseCode
            val type = c.contentType ?: ""
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code in 200..299 && VWorldTileResponse.acceptAsImage(type, bytes)) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                return if (bmp != null) Once(bmp, null) else Once(null, VWorldTileResponse.fetchErrorKo(url, code, "그림 디코드 실패"))
            }
            val txt = String(bytes, Charsets.UTF_8).take(800)
            val ex = VWorldTileResponse.parseServiceException(txt)
            val msg = ex.text
            return Once(null, VWorldTileResponse.fetchErrorKo(url, code, msg, ex.code))
        } finally { c.disconnect() }
    }
}
