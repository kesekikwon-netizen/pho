package kr.archsurvey.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** 0.1.12 배경맵(V월드). SmartTopo 작업 정보의 「배경맵」 칸 자리. 키가 없으면 어느 것을 골라도 점만 그린다 */
enum class MapBase(val id: String, val labelKo: String) {
    NONE("NONE", "없음"),
    CADASTRAL("CADASTRAL", "V월드 지적도 (일반 지도 + 지적선)"),
    SATELLITE_CADASTRAL("SAT_CAD", "V월드 위성 + 지적선"),
    SATELLITE("SATELLITE", "V월드 위성");

    companion object {
        fun fromId(s: String?) = entries.firstOrNull { it.id == s || it.name == s } ?: NONE
    }
}

/** V월드 접근 정보(설정 저장소에서 읽음). [lineOnly] = 지적 스타일 *_line(선만) */
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
 * V월드 타일 URL (키는 사용자 입력, 코드에 내장하지 않음).
 * 배경/위성: WMTS  https://api.vworld.kr/req/wmts/1.0.0/{KEY}/{Layer}/{z}/{y}/{x}.{ext}  (y,x 순서 주의)
 * 지적도: WMS 1.3.0 GetMap, 연속지적도 lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun, CRS=EPSG:900913(공식 예시와 같음), 슬리피 타일 경계를 BBOX 로.
 * 공식 가이드(v4dv_wmsguide2_s001) 요지: layers 최대 4개, styles 는 layers 와 1:1, 2D 스타일 = 레이어명, *_line = 선 스타일,
 * EPSG:900913/3857 의 bbox 는 (xmin,ymin,xmax,ymax). domain = 키 발급 때 적은 서비스 URL(웹뷰어가 아닌 곳에서 쓸 때 추가).
 * ※ 실제 키로 폰에서 검증하지 못했다 [실기기 미시험].
 */
object VWorldUrls {
    const val WMTS = "https://api.vworld.kr/req/wmts/1.0.0"
    const val WMS = "https://api.vworld.kr/req/wms"
    const val CADASTRAL_LAYERS = "lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun"
    const val CADASTRAL_STYLES = "lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun"
    const val CADASTRAL_LINE_STYLES = "lp_pa_cbnd_bubun_line,lp_pa_cbnd_bonbun_line"

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")

    fun satellite(key: String, z: Int, x: Int, y: Int) = "$WMTS/${enc(key)}/Satellite/$z/$y/$x.jpeg"
    fun base(key: String, z: Int, x: Int, y: Int) = "$WMTS/${enc(key)}/Base/$z/$y/$x.png"

    fun cadastral(key: String, domain: String, z: Int, x: Int, y: Int, lineOnly: Boolean = false): String {
        val b = TileMath.tileBbox3857(x, y, z)
        val bbox = b.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it) }
        val sb = StringBuilder("$WMS?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0")
        sb.append("&LAYERS=$CADASTRAL_LAYERS&STYLES=").append(if (lineOnly) CADASTRAL_LINE_STYLES else CADASTRAL_STYLES)
        sb.append("&CRS=EPSG:900913&BBOX=$bbox&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE")
        sb.append("&EXCEPTIONS=text/xml&KEY=").append(enc(key))
        if (domain.isNotBlank()) sb.append("&DOMAIN=").append(enc(domain))
        return sb.toString()
    }

    /** 로그·오류 문구용: URL 에서 키 값을 가린다 */
    fun redact(url: String, key: String): String {
        var u = url
        if (key.isNotBlank()) { u = u.replace(key.trim(), "****"); u = u.replace(enc(key), "****") }
        return u.replace(Regex("(?i)(KEY=)[^&]+"), "$1****")
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
    const val CADASTRAL_MIN_ZOOM = 15

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
