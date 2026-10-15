package kr.archsurvey.core

import kotlin.math.floor

/**
 * 0.1.12 작업별 지오이드(표고) 모델. SmartTopo「작업 정보」의 Geoid 칸과 같은 자리(구조만 참고).
 * - RECEIVER: 수신기가 GGA 로 보낸 해발고(수신기 내부 지오이드)를 그대로 쓴다 — 0.1.11 까지의 동작, 기본값.
 * - EGM96: 앱이 내장한 EGM96 15' 격자(한국 영역)로 N 을 구해 표고 = 타원체고 − N. 타원체고는 GGA 의 해발고 + 지오이드 분리(둘 다 있어야).
 * - NONE: 지오이드 없음 → 표고 칸에 타원체고.
 * KNGeoid18(국토지리정보원)은 격자 파일을 구하지 못해 넣지 않았다(가짜 값 금지).
 */
enum class GeoidModel(val id: String, val labelKo: String, val shortKo: String) {
    RECEIVER("RECEIVER", "수신기 출력 (GGA 해발고)", "수신기"),
    EGM96("EGM96", "EGM96 (앱 내장 격자)", "EGM96"),
    NONE("NONE", "없음 (타원체고)", "타원체고");

    companion object {
        fun fromId(s: String?) = entries.firstOrNull { it.id == s } ?: RECEIVER
    }
}

/** EGM96 지오이드고(N, m). 한국 영역(위도 32~40, 경도 123~133) 밖이면 null. 쌍선형 보간(PROJ vgridshift 와 같은 방식) */
object Egm96Korea {
    private val grid: IntArray by lazy { Egm96KoreaData.MM.split(',').map { it.trim().toInt() }.toIntArray() }

    fun undulation(lat: Double, lon: Double): Double? {
        val d = Egm96KoreaData
        val fy = (lat - d.LAT0) / d.STEP; val fx = (lon - d.LON0) / d.STEP
        if (fy.isNaN() || fx.isNaN() || fy < 0 || fx < 0 || fy > d.ROWS - 1 || fx > d.COLS - 1) return null
        val i = floor(fy).toInt().coerceAtMost(d.ROWS - 2); val j = floor(fx).toInt().coerceAtMost(d.COLS - 2)
        val ty = fy - i; val tx = fx - j
        fun g(r: Int, c: Int) = grid[r * d.COLS + c] / 1000.0
        val a = g(i, j) * (1 - tx) + g(i, j + 1) * tx
        val b = g(i + 1, j) * (1 - tx) + g(i + 1, j + 1) * tx
        return a * (1 - ty) + b * ty
    }
}

/** 점 하나의 표고 계산 결과. [usedModel] 은 실제로 쓴 모델(요청과 다르면 [note] 에 이유) */
data class OrthoHeight(val heightM: Double?, val usedModel: GeoidModel, val note: String? = null)

object GeoidHeights {
    /**
     * 안테나 위상중심(APC)의 표고. 지면 표고는 여기서 안테나 높이를 뺀다.
     * @param mslM GGA 해발고(필드 9), @param sepM GGA 지오이드 분리(필드 11) — 없으면 null
     */
    fun apcOrtho(model: GeoidModel, lat: Double, lon: Double, mslM: Double?, sepM: Double?): OrthoHeight {
        val ell = if (mslM != null && sepM != null) mslM + sepM else null
        return when (model) {
            GeoidModel.RECEIVER -> OrthoHeight(mslM, GeoidModel.RECEIVER)
            GeoidModel.NONE -> if (ell != null) OrthoHeight(ell, GeoidModel.NONE)
                else OrthoHeight(mslM, GeoidModel.RECEIVER, "수신기가 지오이드 분리를 보내지 않아 타원체고를 못 구했어요 — 수신기 해발고로 저장")
            GeoidModel.EGM96 -> {
                val n = Egm96Korea.undulation(lat, lon)
                when {
                    ell == null -> OrthoHeight(mslM, GeoidModel.RECEIVER, "수신기가 지오이드 분리를 보내지 않아 EGM96 을 적용 못 했어요 — 수신기 해발고로 저장")
                    n == null -> OrthoHeight(mslM, GeoidModel.RECEIVER, "EGM96 격자는 한국 영역만 들어 있어요 — 수신기 해발고로 저장")
                    else -> OrthoHeight(ell - n, GeoidModel.EGM96)
                }
            }
        }
    }
}
