package kr.archsurvey.gcp.engine

import kr.archsurvey.gcp.model.AddressParts
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 0.1.1 지금 위치 → 주소(V월드 주소 API getAddress, 지도와 같은 키). 유적 이름 제안에만 쓴다.
 * 안 되면 null(오프라인·키 문제) — 화면은 최근 이름·「유적 날짜」로 넘어간다. 키 원문은 기록하지 않는다.
 */
object VWorldAddress {
    fun url(key: String, lat: Double, lon: Double): String =
        "https://api.vworld.kr/req/address?service=address&request=getAddress&version=2.0&crs=epsg:4326" +
            "&point=${String.format(java.util.Locale.US, "%.6f,%.6f", lon, lat)}&format=json&type=both&zipcode=false&simple=false&key=" + URLEncoder.encode(key, "UTF-8")

    fun lookup(key: String, lat: Double, lon: Double, timeoutMs: Int = 6000): AddressParts? {
        if (key.isBlank()) return null
        return runCatching {
            val c = (URL(url(key, lat, lon)).openConnection() as HttpURLConnection).apply { connectTimeout = timeoutMs; readTimeout = timeoutMs }
            try { if (c.responseCode !in 200..299) null else parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) } finally { c.disconnect() }
        }.getOrNull()
    }

    /** 응답 JSON → 법정동(지번 주소 level4L) · 행정동(level4A). 지번 결과를 먼저 본다 */
    fun parse(json: String): AddressParts? {
        val r = org.json.JSONObject(json).optJSONObject("response") ?: return null
        if (r.optString("status") != "OK") return null
        val arr = r.optJSONArray("result") ?: return null
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val pick = items.firstOrNull { it.optString("type") == "parcel" } ?: items.firstOrNull() ?: return null
        val st = pick.optJSONObject("structure") ?: return null
        return AddressParts(st.optString("level1"), st.optString("level2"), st.optString("level4L"), st.optString("level4A"))
    }
}
