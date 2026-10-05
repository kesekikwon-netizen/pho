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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.1.12 V월드 타일 그림 — **메모리에만** 잠깐 보관(LRU 160장). 약관상 타일 영구 저장·오프라인 팩은 하지 않는다(vworld-spec.md §4).
 * 화면은 [version] 을 읽어 새 타일이 오면 다시 그린다. 실패한 URL 은 60초 동안 다시 받지 않는다.
 * 오류 문구에는 키를 넣지 않는다.
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
                lastError = "배경맵을 받지 못했어요 (${e.javaClass.simpleName}) — 인터넷·V월드 키·도메인을 확인하세요"
            } finally { synchronized(inflight) { inflight -= url } }
        }
        return null
    }

    fun clear() { cache.evictAll(); synchronized(inflight) { failedAt.clear() }; lastError = null; version++ }

    private fun fetch(url: String): ImageBitmap? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; setRequestProperty("User-Agent", "kr.archsurvey.field")
        }
        try {
            val code = c.responseCode; val type = c.contentType ?: ""
            if (code !in 200..299 || !type.startsWith("image")) {
                val txt = runCatching { (c.errorStream ?: c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8).take(300) } }.getOrNull().orEmpty()
                val msg = Regex(">([^<]{3,120})<").find(txt)?.groupValues?.get(1)?.trim()
                synchronized(inflight) { failedAt[url] = System.currentTimeMillis() }
                lastError = "배경맵 오류 HTTP $code" + (msg?.let { " · " + kr.archsurvey.map.VWorldUrls.redact(it, "") } ?: "") + " — V월드 키·도메인을 확인하세요"
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } finally { c.disconnect() }
    }
}
