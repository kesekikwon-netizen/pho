package kr.archsurvey.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

enum class MapBase(val labelKo: String) {
    NONE("지도 없음 (점만)"), CADASTRAL("지적도"), SATELLITE("위성");
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
 * V-World 타일 URL 생성 (키는 사용자 입력, 코드에 내장하지 않음).
 * 위성/배경: WMTS  https://api.vworld.kr/req/wmts/1.0.0/{KEY}/{Layer}/{z}/{y}/{x}.{ext}
 * 지적도 오버레이: WMS (EPSG:3857, lp_pa_cbnd_bubun/bonbun, 투명 PNG)
 * ※ 실제 키로 검증하지 못했다 — 레이어명/도메인 요구는 [확인 필요]
 */
object VWorldUrls {
    const val WMTS = "https://api.vworld.kr/req/wmts/1.0.0"
    const val WMS = "https://api.vworld.kr/req/wms"
    const val CADASTRAL_LAYERS = "lp_pa_cbnd_bubun,lp_pa_cbnd_bonbun"

    fun satellite(key: String, z: Int, x: Int, y: Int) = "$WMTS/$key/Satellite/$z/$y/$x.jpeg"
    fun base(key: String, z: Int, x: Int, y: Int) = "$WMTS/$key/Base/$z/$y/$x.png"

    fun cadastral(key: String, domain: String, z: Int, x: Int, y: Int): String {
        val b = TileMath.tileBbox3857(x, y, z)
        val bbox = b.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it) }
        val sb = StringBuilder("$WMS?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0")
        sb.append("&LAYERS=$CADASTRAL_LAYERS&STYLES=$CADASTRAL_LAYERS")
        sb.append("&CRS=EPSG:3857&BBOX=$bbox&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE")
        sb.append("&EXCEPTIONS=text/xml&KEY=$key")
        if (domain.isNotBlank()) sb.append("&DOMAIN=$domain")
        return sb.toString()
    }
}

/** 어떤 타일 레이어를 어떤 순서·투명도로 그릴지 */
data class TileLayerSpec(val id: String, val urlOf: (z: Int, x: Int, y: Int) -> String, val alpha: Float, val ext: String)

object MapLayers {
    const val MIN_ZOOM = 6
    const val MAX_ZOOM = 19

    /**
     * 선택값 → 레이어 목록 (아래→위).
     * 키가 없으면 빈 목록(= 점만 그리는 평면도로 폴백).
     * SATELLITE + overlay → 위성 위에 반투명 지적도. CADASTRAL → V-World 배경지도 위에 지적선.
     */
    fun resolve(base: MapBase, cadastralOverlay: Boolean, key: String, domain: String): List<TileLayerSpec> {
        if (key.isBlank() || base == MapBase.NONE) return emptyList()
        val out = ArrayList<TileLayerSpec>()
        when (base) {
            MapBase.SATELLITE -> {
                out += TileLayerSpec("sat", { z, x, y -> VWorldUrls.satellite(key, z, x, y) }, 1f, "jpg")
                if (cadastralOverlay) out += TileLayerSpec("cad", { z, x, y -> VWorldUrls.cadastral(key, domain, z, x, y) }, 0.7f, "png")
            }
            MapBase.CADASTRAL -> {
                out += TileLayerSpec("base", { z, x, y -> VWorldUrls.base(key, z, x, y) }, 1f, "png")
                out += TileLayerSpec("cad", { z, x, y -> VWorldUrls.cadastral(key, domain, z, x, y) }, 1f, "png")
            }
            MapBase.NONE -> {}
        }
        return out
    }

    fun fallbackMessage(base: MapBase, key: String): String? = when {
        base == MapBase.NONE -> null
        key.isBlank() -> "지도 키(V-World)가 없어서 배경 지도를 못 보여줘요. 설정 → 배경 지도에서 키를 입력하세요. 지금은 점만 표시해요."
        else -> null
    }
}
