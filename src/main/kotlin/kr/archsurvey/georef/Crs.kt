package kr.archsurvey.georef

import kotlin.math.*

data class LonLat(val lon: Double, val lat: Double)

enum class Datum { KGD2002_GRS80, KOREAN1985_BESSEL, WGS84 }

enum class CrsKind { TRANSVERSE_MERCATOR, GEOGRAPHIC }

/**
 * 앱이 아는 좌표계 한 개. [supported]=false 이면 후보로 보여 주되 변환은 하지 않는다(측지계 변환 근거 없음).
 */
data class CrsDef(
    val epsg: Int, val name: String, val kind: CrsKind, val datum: Datum,
    val lat0: Double = 0.0, val lon0: Double = 0.0, val k0: Double = 1.0, val fe: Double = 0.0, val fn: Double = 0.0,
    val supported: Boolean = true, val note: String? = null,
) {
    val isGeographic get() = kind == CrsKind.GEOGRAPHIC
}

class UnsupportedCrsException(val epsg: Int, message: String) : RuntimeException(message)

/**
 * 한국 좌표계 목록과 변환.
 * 결정(WP-10 ⑥): 5179(통합)·5181/5183(구 FN=500000 계열)은 **전부 구현**(GRS80, 파라미터만 다른 TM). 5174(구 한국측지계, Bessel)는
 * 후보로만 올리고 **변환하지 않는다**(측지계 이동 근거 미확인; PROJ 로 잰 이동량은 약 311 m — crs5174_shift.json). 5174 도면은 수동 정렬 권장.
 * 파라미터는 PROJ 3.8.0 조회값과 테스트로 대조한다.
 */
object KoreanCrs {
    private fun tm(epsg: Int, name: String, lon0: Double, fn: Double, k0: Double = 1.0, fe: Double = 200000.0, note: String? = null) =
        CrsDef(epsg, name, CrsKind.TRANSVERSE_MERCATOR, Datum.KGD2002_GRS80, 38.0, lon0, k0, fe, fn, note = note)

    val EPSG_5185 = tm(5185, "KGD2002 / West Belt 2010", 125.0, 600000.0)
    val EPSG_5186 = tm(5186, "KGD2002 / Central Belt 2010", 127.0, 600000.0)
    val EPSG_5187 = tm(5187, "KGD2002 / East Belt 2010", 129.0, 600000.0)
    val EPSG_5188 = tm(5188, "KGD2002 / East Sea Belt 2010", 131.0, 600000.0)
    val EPSG_5179 = tm(5179, "KGD2002 / Unified CS", 127.5, 2000000.0, k0 = 0.9996, fe = 1000000.0)
    val EPSG_5181 = tm(5181, "KGD2002 / Central Belt", 127.0, 500000.0, note = "구 도면용(북 좌표 FN=500000). 5186 과 북쪽으로 100 km 어긋남")
    val EPSG_5183 = tm(5183, "KGD2002 / East Belt", 129.0, 500000.0, note = "구 도면용(북 좌표 FN=500000)")
    val EPSG_5174 = CrsDef(
        5174, "Korean 1985 / Modified Central Belt", CrsKind.TRANSVERSE_MERCATOR, Datum.KOREAN1985_BESSEL,
        38.0, 127.00289027777778, 1.0, 200000.0, 500000.0, supported = false,
        note = "구 한국측지계(Bessel). 측지계 변환 미적용 시 약 300 m 어긋남(PROJ 실측 약 311 m) — 수동 정렬 권장",
    )
    val WGS84 = CrsDef(4326, "WGS 84", CrsKind.GEOGRAPHIC, Datum.WGS84)
    val KGD2002_GEO = CrsDef(4737, "KGD2002", CrsKind.GEOGRAPHIC, Datum.KGD2002_GRS80)

    /** 투영 후보(우선순위 순: 5186 기본). */
    val projected: List<CrsDef> = listOf(EPSG_5186, EPSG_5185, EPSG_5187, EPSG_5188, EPSG_5179, EPSG_5181, EPSG_5183, EPSG_5174)
    val all: List<CrsDef> = projected + listOf(WGS84, KGD2002_GEO)
    fun byEpsg(e: Int): CrsDef? = all.firstOrNull { it.epsg == e }

    // 한국 영역(남한 + 제주·이어도·독도 포함 근사 상자). [임시값]
    const val KOREA_LON_MIN = 123.5; const val KOREA_LON_MAX = 132.0
    const val KOREA_LAT_MIN = 32.0; const val KOREA_LAT_MAX = 39.0
    fun inKorea(p: LonLat) = p.lon in KOREA_LON_MIN..KOREA_LON_MAX && p.lat in KOREA_LAT_MIN..KOREA_LAT_MAX

    fun toLonLat(crs: CrsDef, x: Double, y: Double): LonLat {
        if (crs.isGeographic) return LonLat(x, y)
        if (!crs.supported) throw UnsupportedCrsException(crs.epsg, "EPSG:${crs.epsg} 는 측지계 변환을 아직 지원하지 않아요")
        return engine(crs).inverse(x, y)
    }

    fun fromLonLat(crs: CrsDef, p: LonLat): XY {
        if (crs.isGeographic) return XY(p.lon, p.lat)
        if (!crs.supported) throw UnsupportedCrsException(crs.epsg, "EPSG:${crs.epsg} 는 측지계 변환을 아직 지원하지 않아요")
        return engine(crs).forward(p.lon, p.lat)
    }

    private val engines = HashMap<Int, TransverseMercator>()
    @Synchronized private fun engine(crs: CrsDef) = engines.getOrPut(crs.epsg) {
        TransverseMercator(crs.lat0, crs.lon0, crs.k0, crs.fe, crs.fn)
    }
}

/** EPSG:3857 (지도 표시 투영) */
object WebMercator {
    private const val R = 6378137.0
    const val MAX_LAT = 85.0511287798
    fun forward(p: LonLat): XY {
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT)
        return XY(R * Math.toRadians(p.lon), R * ln(tan(PI / 4 + Math.toRadians(lat) / 2)))
    }
    fun inverse(x: Double, y: Double) = LonLat(Math.toDegrees(x / R), Math.toDegrees(2 * atan(exp(y / R)) - PI / 2))
}

/**
 * 횡메르카토르 (GRS80). Krüger 급수 n^4 차(폐곡 형태가 아닌 급수; PROJ 의 etmerc 와 같은 계열의 해법).
 * 기존 core/TmProjection(Redfearn 6차, 5185~5188 고정)은 그대로 두고, 이 클래스가 파라미터를 받아 5179·5181·5183 까지 처리한다.
 * 정확도는 pyproj 오라클 시험으로 측정한다(GeorefTests TmOracle).
 */
class TransverseMercator(private val lat0: Double, private val lon0: Double, private val k0: Double, private val fe: Double, private val fn: Double) {
    private val a = 6378137.0
    private val f = 1.0 / 298.257222101
    private val n = f / (2 - f)
    private val n2 = n * n; private val n3 = n2 * n; private val n4 = n3 * n
    private val bigA = a / (1 + n) * (1 + n2 / 4 + n4 / 64)
    private val alpha = doubleArrayOf(
        n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180,
        13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440,
        61 * n3 / 240 - 103 * n4 / 140,
        49561 * n4 / 161280)
    private val beta = doubleArrayOf(
        n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360,
        n2 / 48 + n3 / 15 - 437 * n4 / 1440,
        17 * n3 / 480 - 37 * n4 / 840,
        4397 * n4 / 161280)
    private val delta = doubleArrayOf(
        2 * n - 2 * n2 / 3 - 2 * n3 + 116 * n4 / 45,
        7 * n2 / 3 - 8 * n3 / 5 - 227 * n4 / 45,
        56 * n3 / 15 - 136 * n4 / 35,
        4279 * n4 / 630)
    private val e = sqrt(f * (2 - f))
    private val m0: Double = xiEta(Math.toRadians(lat0), 0.0).first * bigA * k0

    private fun xiEta(phi: Double, dLam: Double): Pair<Double, Double> {
        val sinPhi = sin(phi)
        val tau = sinh(atanh(sinPhi) - e * atanh(e * sinPhi))
        val xiP = atan2(tau, cos(dLam))
        val etaP = atanh(sin(dLam) / sqrt(1 + tau * tau))
        var xi = xiP; var eta = etaP
        for (j in 1..4) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return xi to eta
    }

    /** 경도·위도(도) -> (동, 북) */
    fun forward(lonDeg: Double, latDeg: Double): XY {
        val (xi, eta) = xiEta(Math.toRadians(latDeg), Math.toRadians(lonDeg - lon0))
        return XY(fe + k0 * bigA * eta, fn + k0 * bigA * xi - m0)
    }

    fun inverse(x: Double, y: Double): LonLat {
        val xi = (y - fn + m0) / (k0 * bigA); val eta = (x - fe) / (k0 * bigA)
        var xiP = xi; var etaP = eta
        for (j in 1..4) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        var phi = chi
        for (j in 1..4) phi += delta[j - 1] * sin(2 * j * chi)
        val lam = Math.toRadians(lon0) + atan2(sinh(etaP), cos(xiP))
        return LonLat(Math.toDegrees(lam), Math.toDegrees(phi))
    }
}

fun haversineKm(a: LonLat, b: LonLat): Double {
    val r = 6371.0088
    val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}
