package kr.archsurvey.core

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 한국 TM 좌표계 (GRS80). EPSG:5185 서부(125E) / 5186 중부(127E) / 5187 동부(129E) / 5188 동해(131E). */
enum class KoreanTmZone(val epsg: Int, val labelKo: String, val lon0: Double) {
    WEST(5185, "서부원점 (EPSG:5185)", 125.0),
    CENTRAL(5186, "중부원점 (EPSG:5186)", 127.0),
    EAST(5187, "동부원점 (EPSG:5187)", 129.0),
    EAST_SEA(5188, "동해원점 (EPSG:5188)", 131.0);

    /** 0.1.46 화면 표시용 짧은 이름(서부·중부·동부·동해). 표시 전용, 좌표 계산과 무관 */
    val shortKo: String get() = labelKo.substring(0, 2)
    /** 머리줄 표기 「EPSG:5187 동부」 */
    val displayKo: String get() = "EPSG:$epsg $shortKo"
    /** 0.1.56 S7: 이름+번호 「동부(EPSG 5187)」 */
    val nameEpsg: String get() = "$shortKo(EPSG $epsg)"

    companion object {
        fun fromEpsg(e: Int) = entries.firstOrNull { it.epsg == e } ?: CENTRAL
    }
}

data class PlanarCoord(val northing: Double, val easting: Double)

/**
 * 횡메르카토르 정투영 (Redfearn 급수). 위도원점 38°N, 축척 1.0, FE=200000, FN=600000.
 * 입력 위경도는 WGS84 ≈ KGD2002 로 간주(차이는 수 cm 이내이나 [확인 필요]).
 */
object TmProjection {
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)
    private const val LAT0 = 38.0
    private const val K0 = 1.0
    private const val FE = 200000.0
    private const val FN = 600000.0

    private fun meridianArc(phi: Double): Double {
        val e4 = E2 * E2; val e6 = e4 * E2
        return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi))
    }

    fun forward(latDeg: Double, lonDeg: Double, zone: KoreanTmZone = KoreanTmZone.CENTRAL): PlanarCoord {
        val phi = Math.toRadians(latDeg)
        val dl = Math.toRadians(lonDeg - zone.lon0)
        val sinP = sin(phi); val cosP = cos(phi); val tanP = tan(phi)
        val nu = A / sqrt(1 - E2 * sinP * sinP)
        val t = tanP * tanP
        val c = EP2 * cosP * cosP
        val a = dl * cosP
        val m = meridianArc(phi)
        val m0 = meridianArc(Math.toRadians(LAT0))
        val easting = FE + K0 * nu * (a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120)
        val northing = FN + K0 * (m - m0 + nu * tanP * (a * a / 2 +
                (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720))
        return PlanarCoord(northing, easting)
    }
}
