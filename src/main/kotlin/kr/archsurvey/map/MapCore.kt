package kr.archsurvey.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** 0.1.12 배경맵(V월드). SmartTopo 작업 정보의 「배경맵」 칸 자리. 키가 비면 점만(제품은 [VWorldDefaults] 폴백). */
enum class MapBase(val id: String, val labelKo: String) {
    NONE("NONE", "없음"),
    CADASTRAL("CADASTRAL", "V월드 지적도 (일반 지도 + 지적선)"),
    SATELLITE_CADASTRAL("SAT_CAD", "V월드 위성 + 지적선"),
    SATELLITE("SATELLITE", "V월드 위성만 (지적선 없음)");

    companion object {
        /** 비어 있으면 제품 기본(위성+지적). 알 수 없는 id 만 NONE(구 시험·명시적 없음 유지) */
        fun fromId(s: String?) = when {
            s.isNullOrBlank() -> SATELLITE_CADASTRAL
            else -> entries.firstOrNull { it.id == s || it.name == s } ?: NONE
        }
    }
}

/** V월드 접근 정보(설정 저장소에서 읽음). [lineOnly]=false 기본(면+필지번호, 다른 GIS와 동일). true = *_line(선만) */
data class VWorldAccess(val key: String, val domain: String, val lineOnly: Boolean = false) {
    override fun toString() = "VWorldAccess(key=${if (key.isBlank()) "없음" else "****"}, domain=$domain, lineOnly=$lineOnly)"
}

/** 웹 메르카토르 타일 수학 (EPSG:3857, 256px 타일). */
object TileMath {
    const val TILE = 256
    const val R = 6378137.0
    const val ORIGIN = PI * R

    fun worldSize(z: Int): Double = TILE * (1L shl z).toDouble()

    fun lonToX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)
    fun latToY(lat: Double, z: Int): Double {
        val l = lat.coerceIn(-85.05112878, 85.05112878)
        val s = Math.sin(Math.toRadians(l))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(z)
    }
    fun xToLon(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0
    fun yToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** 타일(x,y,z)의 EPSG:3857 BBOX [minx, miny, maxx, maxy] */
    fun tileBbox3857(x: Int, y: Int, z: Int): DoubleArray {
        val size = 2 * ORIGIN / (1L shl z)
        val minx = -ORIGIN + x * size
        val maxy = ORIGIN - y * size
        return doubleArrayOf(minx, maxy - size, minx + size, maxy)
    }

    fun tileIndex(px: Double): Int = floor(px / TILE).toInt()
}

/**
 * V월드 타일 URL. 키는 설정 저장소(비면 [VWorldDefaults.BUILTIN_API_KEY] 폴백)에서 온다.
 * 배경/위성: WMTS  https://api.vworld.kr/req/wmts/1.0.0/{KEY}/{Layer}/{z}/{y}/{x}.{ext}  (y,x 순서 주의)
 * 지적 오버레이(0.1.24 주 경로): 레거시 2DCache WMS2
 *   http://2d.vworld.kr:8895/2DCache/gis/map/WMS2 + APIKEY + LAYERS=LP_PA_CBND_BUBUN,LP_PA_CBND_BONBUN
 *   (다른 GIS 앱·gisdeveloper 패턴. 현장 키가 그쪽에서 동작함이 확인됨)
 * 공식 OpenAPI WMS(`api.vworld.kr/req/wms`, 소문자 layers)는 폴백.
 * 비웹 클라이언트: 공식 경로에서 DOMAIN 이 비면 `DOMAIN=DEFAULT` 전송(발급 URL 이 따로면 사용자가 그 URL을 도메인 칸에).
 * ※ 박스에서 api.vworld.kr / 2d.vworld.kr 실타일 수신 미확인 [실기기 미시험].
 */
object VWorldUrls {
    const val WMTS = "https://api.vworld.kr/req/wmts/1.0.0"
    const val WMS = "https://api.vworld.kr/req/wms"
    /** 레거시 연속지적 WMS2 (HTTP cleartext — network_security_config 로 2d.vworld.kr 만 허용) */
    const val WMS_LEGACY_2DCACHE = "http://2d.vworld.kr:8895/2DCache/gis/map/WMS2"
    /** 공식 OpenAPI GetMap 예시 순서(소문자) */
    const val CADASTRAL_LAYERS = "lp_pa_cbnd_bonbun,lp_pa_cbnd_bubun"
    const val CADASTRAL_STYLES = "lp_pa_cbnd_bonbun,lp_pa_cbnd_bubun"
    const val CADASTRAL_LINE_STYLES = "lp_pa_cbnd_bonbun_line,lp_pa_cbnd_bubun_line"
    /** 레거시 2DCache 대문자 레이어(사용자·gisdeveloper 스펙) */
    const val CADASTRAL_LAYERS_LEGACY = "LP_PA_CBND_BUBUN,LP_PA_CBND_BONBUN"

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")

    fun satellite(key: String, z: Int, x: Int, y: Int) = "$WMTS/${enc(key)}/Satellite/$z/$y/$x.jpeg"
    fun base(key: String, z: Int, x: Int, y: Int) = "$WMTS/${enc(key)}/Base/$z/$y/$x.png"

    /** 지적 주 경로 = 레거시 2DCache WMS2 (기본 면 채우기) */
    fun cadastral(key: String, domain: String, z: Int, x: Int, y: Int, lineOnly: Boolean = false): String =
        cadastralLegacy2dCache(key, z, x, y, lineOnly)

    /**
     * 레거시 2DCache GetMap. WMS 1.1.1 + SRS=EPSG:900913 + APIKEY.
     * 기본 STYLES = 레이어명(면·필지번호). 선만이면 STYLES 비움.
     */
    fun cadastralLegacy2dCache(key: String, z: Int, x: Int, y: Int, lineOnly: Boolean = false): String {
        val b = TileMath.tileBbox3857(x, y, z)
        val bbox = b.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it) }
        val styles = if (lineOnly) "" else CADASTRAL_LAYERS_LEGACY
        val sb = StringBuilder("$WMS_LEGACY_2DCACHE?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1")
        sb.append("&LAYERS=$CADASTRAL_LAYERS_LEGACY&STYLES=$styles")
        sb.append("&SRS=EPSG:900913&BBOX=$bbox&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE")
        sb.append("&APIKEY=").append(enc(key))
        return sb.toString()
    }

    /**
     * 공식 OpenAPI WMS GetMap(폴백). 도메인 빈칸 → DOMAIN=DEFAULT(안드로이드=비웹).
     * 기본 스타일 = 레이어명(면). *_line 은 lineOnly=true 일 때만.
     */
    fun cadastralOfficial(key: String, domain: String, z: Int, x: Int, y: Int, lineOnly: Boolean = false): String {
        val b = TileMath.tileBbox3857(x, y, z)
        val bbox = b.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it) }
        val sb = StringBuilder("$WMS?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0")
        sb.append("&LAYERS=$CADASTRAL_LAYERS&STYLES=").append(if (lineOnly) CADASTRAL_LINE_STYLES else CADASTRAL_STYLES)
        sb.append("&CRS=EPSG:900913&BBOX=$bbox&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE")
        sb.append("&EXCEPTIONS=text/xml&KEY=").append(enc(key))
        val d = domain.trim().ifBlank { "DEFAULT" }
        sb.append("&DOMAIN=").append(enc(d))
        return sb.toString()
    }

    fun isLegacyCadastre(url: String): Boolean {
        val u = url.lowercase()
        return "2d.vworld.kr" in u && "2dcache" in u
    }

    fun isOfficialCadastre(url: String): Boolean {
        val u = url.lowercase()
        return "/req/wms" in u || ("api.vworld.kr" in u && "wms" in u)
    }

    /**
     * 레거시 실패 시 공식 OpenAPI 한 번. 이미 공식이면 null(핑퐁 금지).
     * 실패한 URL 에서 APIKEY/KEY·BBOX 를 읽어 같은 타일 공식 URL 을 만든다.
     */
    fun cadastralAlternate(failedUrl: String, lineOnly: Boolean = false): String? {
        if (!isLegacyCadastre(failedUrl)) return null
        val key = queryParam(failedUrl, "APIKEY") ?: queryParam(failedUrl, "KEY") ?: return null
        val bbox = queryParam(failedUrl, "BBOX") ?: return null
        val domain = queryParam(failedUrl, "DOMAIN")?.trim().orEmpty().ifBlank { "DEFAULT" }
        val sb = StringBuilder("$WMS?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0")
        sb.append("&LAYERS=$CADASTRAL_LAYERS&STYLES=").append(if (lineOnly) CADASTRAL_LINE_STYLES else CADASTRAL_STYLES)
        sb.append("&CRS=EPSG:900913&BBOX=$bbox&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE")
        sb.append("&EXCEPTIONS=text/xml&KEY=").append(enc(key))
        sb.append("&DOMAIN=").append(enc(domain))
        return sb.toString()
    }

    fun queryParam(url: String, name: String): String? {
        val m = Regex("(?i)(?:\\?|&)" + Regex.escape(name) + "=([^&]*)").find(url) ?: return null
        return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
    }

    /** 로그·오류 문구용: URL 에서 키 값을 가린다 */
    fun redact(url: String, key: String): String {
        var u = url
        if (key.isNotBlank()) { u = u.replace(key.trim(), "****"); u = u.replace(enc(key), "****") }
        return u.replace(Regex("(?i)((?:KEY|APIKEY)=)[^&]+"), "$1****")
    }
}

/** 어떤 타일 레이어를 어떤 순서·투명도로 그릴지 */
data class TileLayerSpec(val id: String, val urlOf: (z: Int, x: Int, y: Int) -> String, val alpha: Float, val ext: String) {
    override fun equals(other: Any?) = other is TileLayerSpec && other.id == id && other.alpha == alpha && other.urlOf(10, 1, 1) == urlOf(10, 1, 1)
    override fun hashCode() = id.hashCode()
}

object MapLayers {
    const val MIN_ZOOM = 6
    /** 이 위로는 19 타일을 늘려 그린다 */
    const val MAX_ZOOM = 19
    /** 지적선이 보이기 시작하는 줌 [가설: 공식 값 미확인 — 현장에서 확인] */
    const val CADASTRAL_MIN_ZOOM = 13

    /** 선택값 → 레이어 목록(아래→위). 키가 없으면 빈 목록(= 점만 그리는 평면도로 폴백) */
    fun resolve(base: MapBase, a: VWorldAccess): List<TileLayerSpec> {
        val key = a.key.trim()
        if (key.isBlank() || base == MapBase.NONE) return emptyList()
        val cad = TileLayerSpec("cad", { z, x, y -> VWorldUrls.cadastral(key, a.domain, z, x, y, a.lineOnly) }, 1f, "png")
        return when (base) {
            MapBase.SATELLITE -> listOf(TileLayerSpec("sat", { z, x, y -> VWorldUrls.satellite(key, z, x, y) }, 1f, "jpg"))
            MapBase.SATELLITE_CADASTRAL -> listOf(TileLayerSpec("sat", { z, x, y -> VWorldUrls.satellite(key, z, x, y) }, 1f, "jpg"), cad)
            MapBase.CADASTRAL -> listOf(TileLayerSpec("base", { z, x, y -> VWorldUrls.base(key, z, x, y) }, 1f, "png"), cad)
            MapBase.NONE -> emptyList()
        }
    }

    /** 0.1.11 호환 */
    fun resolve(base: MapBase, cadastralOverlay: Boolean, key: String, domain: String): List<TileLayerSpec> =
        resolve(if (base == MapBase.SATELLITE && cadastralOverlay) MapBase.SATELLITE_CADASTRAL else base, VWorldAccess(key, domain))

    fun fallbackMessage(base: MapBase, key: String): String? = when {
        base == MapBase.NONE -> null
        key.isBlank() -> "V월드 키가 없어 배경맵을 못 보여줘요. 환경설정 → V월드 지적도에서 키를 넣으세요. 지금은 점만 표시해요."
        else -> null
    }

    /** 위성만 선택 시 짧은 안내(오류보다 우선순위 낮게 표시) */
    fun modeHint(base: MapBase): String? =
        if (base == MapBase.SATELLITE) "지적선이 꺼져 있어요. ‘위성 + 지적선’을 고르면 지적 경계가 겹쳐요." else null

    /**
     * 화면 배율(1 m 당 px)·위도 → 타일 줌. 타일 1 px 가 화면 1 px 에 가깝게.
     * 지상 해상도(m/px) = 156543.03392 × cos(위도) / 2^z
     */
    fun zoomFor(pxPerM: Double, latDeg: Double): Int {
        if (pxPerM <= 0 || pxPerM.isNaN()) return MIN_ZOOM
        val z = Math.log(156543.03392 * Math.cos(Math.toRadians(latDeg)) * pxPerM) / Math.log(2.0)
        return Math.round(z).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}

/**
 * 타일 HTTP 응답 판별·오류 문구(순수 로직, JVM 시험 가능).
 * WMS 는 HTTP 200 + text/xml(ServiceException) 이 흔함 — 위성만 되고 지적이 안 될 때 구분.
 */
object VWorldTileResponse {
    data class ServiceExceptionInfo(val code: String?, val text: String?)

    fun isImageContentType(type: String): Boolean =
        type.substringBefore(';').trim().lowercase().startsWith("image")

    fun hasImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        // PNG \x89PNG  / JPEG \xFF\xD8\xFF
        val png = bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val jpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        return png || jpg
    }

    fun acceptAsImage(type: String, bytes: ByteArray): Boolean =
        isImageContentType(type) || hasImageMagic(bytes)

    fun isCadastralRequest(url: String): Boolean {
        val u = url.lowercase()
        return VWorldUrls.isLegacyCadastre(url) || "/req/wms" in u || "layers=lp_pa_cbnd" in u || ("layers=" in u && "wms" in u)
    }

    /** WMS ServiceException / OWS Exception 본문에서 code·문구 추출(키는 호출측 redact) */
    fun parseServiceException(body: String): ServiceExceptionInfo {
        val code = Regex("""(?i)(?:exceptionCode|code)\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
            ?: Regex("""(?i)<(?:ServiceException|Exception)\b[^>]*\b(?:exceptionCode|code)\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.trim()
        val text = Regex("""(?i)<(?:ServiceException|ows:ExceptionText|ExceptionText|text|message)[^>]*>([^<]{2,200})<""").find(body)?.groupValues?.get(1)?.trim()
            ?: Regex(""">([^<]{3,120})<""").find(body)?.groupValues?.get(1)?.trim()
        return ServiceExceptionInfo(code?.takeIf { it.isNotBlank() }, text?.takeIf { it.isNotBlank() })
    }

    fun fetchErrorKo(url: String, code: Int, serverMsg: String?, exceptionCode: String? = null): String {
        val codePart = exceptionCode?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        val detail = serverMsg?.let { " · " + VWorldUrls.redact(it, "") }.orEmpty()
        return if (isCadastralRequest(url))
            "지적 WMS 오류 HTTP $code$codePart$detail — 요청 URL·레이어를 확인하세요(다른 GIS와 같은 2DCache 패턴으로 맞춤)"
        else
            "배경맵 오류 HTTP $code$codePart$detail — V월드 키·도메인을 확인하세요"
    }

    fun networkErrorKo(url: String, simpleName: String): String =
        if (isCadastralRequest(url))
            "지적 WMS를 받지 못했어요 ($simpleName) — 인터넷·요청 주소를 확인하세요"
        else
            "배경맵을 받지 못했어요 ($simpleName) — 인터넷·V월드 키·도메인을 확인하세요"
}
