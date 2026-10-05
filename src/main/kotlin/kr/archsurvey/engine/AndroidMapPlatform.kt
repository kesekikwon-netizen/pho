package kr.archsurvey.engine

import kr.archsurvey.data.SettingsStore
import kr.archsurvey.map.TileMath
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.map.VWorldUrls
import kr.archsurvey.ui.JobConfig
import kr.archsurvey.ui.JobConfigPlatform
import kr.archsurvey.ui.MapKeyPlatform
import java.net.HttpURLConnection
import java.net.URL

/** 작업별 설정 = SharedPreferences "job_cfg_<작업>" */
class AndroidJobConfigPlatform(private val s: SettingsStore) : JobConfigPlatform {
    override fun load(job: String) = JobConfig.decode(s.jobConfigRaw(job))
    override fun save(job: String, cfg: JobConfig) = s.setJobConfigRaw(job, cfg.encode())
    override fun delete(job: String) = s.setJobConfigRaw(job, null)
}

/**
 * V월드 키 저장(암호화 SharedPreferences, API 21~22 는 일반 SharedPreferences) + 연결 시험.
 * 키 값은 로그·오류 기록·화면 문구에 넣지 않는다(문구는 [VWorldUrls.redact] 로 가림).
 */
class AndroidMapPlatform(private val s: SettingsStore) : MapKeyPlatform {
    override var key: String
        get() = s.mapKey
        set(v) { s.mapKey = v }
    override var domain: String
        get() = s.mapDomain
        set(v) { s.mapDomain = v }
    override var lineOnly: Boolean
        get() = s.mapLineOnly
        set(v) { s.mapLineOnly = v }

    override fun access() = VWorldAccess(s.mapKey, s.mapDomain, s.mapLineOnly)

    /** 서울 시청 부근 타일 하나씩(배경 WMTS z=16, 지적 WMS z=17)을 받아 본다. 이미지면 성공 */
    override fun test(a: VWorldAccess): String {
        if (a.key.isBlank()) return "키를 먼저 넣으세요"
        fun tx(z: Int) = TileMath.tileIndex(TileMath.lonToX(126.9780, z))
        fun ty(z: Int) = TileMath.tileIndex(TileMath.latToY(37.5665, z))
        val base = probe(VWorldUrls.base(a.key, 16, tx(16), ty(16)), a.key)
        val cad = probe(VWorldUrls.cadastral(a.key, a.domain, 17, tx(17), ty(17), a.lineOnly), a.key)
        return "배경지도(WMTS): $base\n지적도(WMS): $cad"
    }

    private fun probe(url: String, key: String): String = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "kr.archsurvey.field")
        }
        try {
            val code = c.responseCode
            val type = c.contentType ?: ""
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            when {
                code in 200..299 && type.startsWith("image") -> "성공 (그림 ${body.size} B)"
                else -> {
                    val txt = String(body, Charsets.UTF_8).take(300)
                    val msg = Regex("<(?:ServiceException|ows:ExceptionText|text|message)[^>]*>([^<]+)<").find(txt)?.groupValues?.get(1)?.trim()
                    "실패 HTTP $code ${VWorldUrls.redact(msg ?: type, key)}"
                }
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { "실패 (${e.javaClass.simpleName}) 인터넷·키를 확인하세요" }
}
