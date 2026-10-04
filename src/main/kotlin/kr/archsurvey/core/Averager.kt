package kr.archsurvey.core

/** 측점 에폭 평균. 평균 위치와 최악 품질을 함께 기록. */
class PointAverager(private val targetEpochs: Int) {
    private val samples = ArrayList<GgaMessage>()
    private var sigmaH: Double? = null
    private var sigmaV: Double? = null
    private var sigmaE: Double? = null
    private var sigmaN: Double? = null
    private var lastGgaTime: String? = null

    val count: Int get() = samples.size
    val progress: Float get() = (samples.size.toFloat() / targetEpochs).coerceIn(0f, 1f)
    val done: Boolean get() = samples.size >= targetEpochs

    /** GGA 에폭 추가 (같은 시각 중복 문장은 무시). 위치 없는 문장은 무시. */
    fun add(gga: GgaMessage, gst: GstMessage?): Boolean {
        if (!gga.hasPosition) return false
        if (gga.utcTime == lastGgaTime) return false
        lastGgaTime = gga.utcTime
        samples.add(gga)
        gst?.let { g ->
            sigmaN = g.sigmaLat; sigmaE = g.sigmaLon; sigmaV = g.sigmaAlt; sigmaH = g.sigmaHoriz
        }
        return true
    }

    fun result(): AveragedPoint? {
        if (samples.isEmpty()) return null
        val n = samples.size
        val lat = samples.sumOf { it.lat!! } / n
        val lon = samples.sumOf { it.lon!! } / n
        val hs = samples.mapNotNull { it.ellipsoidalHeight }
        val ms = samples.mapNotNull { it.altitudeMsl }
        val worst = samples.minByOrNull { rank(it.quality) }!!.quality
        val last = samples.last()
        return AveragedPoint(
            lat = lat, lon = lon,
            ellipsoidalHeight = if (hs.isEmpty()) null else hs.average(),
            altitudeMsl = if (ms.isEmpty()) null else ms.average(),
            geoidSeparation = last.geoidSeparation,
            worstQuality = worst,
            allFixed = samples.all { it.quality == FixQuality.RTK_FIXED },
            satellites = samples.minOf { it.satellites },
            hdop = samples.mapNotNull { it.hdop }.maxOrNull(),
            sigmaE = sigmaE, sigmaN = sigmaN, sigmaH = sigmaH, sigmaU = sigmaV,
            correctionAgeSec = last.correctionAgeSec,
            baseStationId = last.baseStationId,
            epochs = n,
        )
    }

    private fun rank(q: FixQuality) = when (q) {
        FixQuality.RTK_FIXED -> 5; FixQuality.RTK_FLOAT -> 4; FixQuality.DGPS -> 3
        FixQuality.PPS -> 3; FixQuality.SINGLE -> 2; else -> 1
    }
}

data class AveragedPoint(
    val lat: Double, val lon: Double,
    val ellipsoidalHeight: Double?, val altitudeMsl: Double?, val geoidSeparation: Double?,
    val worstQuality: FixQuality, val allFixed: Boolean,
    val satellites: Int, val hdop: Double?,
    val sigmaE: Double?, val sigmaN: Double?, val sigmaH: Double?, val sigmaU: Double?,
    val correctionAgeSec: Double?, val baseStationId: String?,
    val epochs: Int,
)
