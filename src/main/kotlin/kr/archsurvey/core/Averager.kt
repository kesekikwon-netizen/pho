package kr.archsurvey.core

/** 측점 에폭 평균. 평균 위치와 최악 품질을 함께 기록. */
class PointAverager(private val targetEpochs: Int) {
    private val samples = ArrayList<GgaMessage>()
    private var sigmaH: Double? = null
    private var sigmaV: Double? = null
    private var sigmaE: Double? = null
    private var sigmaN: Double? = null
    private var lastGgaTime: String? = null
    private var startedAtMs: Long? = null
    private var endedAtMs: Long? = null
    private var rejectedDup = 0
    private var rejectedNoPos = 0
    private var maxAge: Double? = null
    private var minAge: Double? = null
    private var epochsWithoutAge = 0

    val count: Int get() = samples.size
    val progress: Float get() = (samples.size.toFloat() / targetEpochs).coerceIn(0f, 1f)
    val done: Boolean get() = samples.size >= targetEpochs

    /**
     * GGA 에폭 추가 (같은 시각 중복 문장은 무시). 위치 없는 문장은 무시.
     * [nowMs]: 호출자 단조 시계(elapsedRealtime 등). 주면 평균화 시작/종료 시각을 기록한다(없어도 기존처럼 동작).
     */
    fun add(gga: GgaMessage, gst: GstMessage?, nowMs: Long? = null): Boolean {
        if (!gga.hasPosition) { rejectedNoPos++; return false }
        // 시각 필드가 비어 있는 수신기는 중복 판정을 하지 않는다(전부 같은 "" 로 보여 첫 에폭만 쓰이던 문제)
        if (gga.utcTime.isNotEmpty() && gga.utcTime == lastGgaTime) { rejectedDup++; return false }
        lastGgaTime = gga.utcTime
        if (nowMs != null) { if (startedAtMs == null) startedAtMs = nowMs; endedAtMs = nowMs }
        val age = gga.correctionAgeSec
        if (age == null) epochsWithoutAge++ else {
            maxAge = maxOf(maxAge ?: age, age); minAge = minOf(minAge ?: age, age)
        }
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
            startedAtMs = startedAtMs, endedAtMs = endedAtMs,
            maxCorrectionAgeSec = maxAge, minCorrectionAgeSec = minAge, epochsWithoutAge = epochsWithoutAge,
            fixedEpochs = samples.count { it.quality == FixQuality.RTK_FIXED },
            floatEpochs = samples.count { it.quality == FixQuality.RTK_FLOAT },
            rejectedDuplicate = rejectedDup, rejectedNoPosition = rejectedNoPos,
            positions = samples.map { Triple(it.lat!!, it.lon!!, it.ellipsoidalHeight) },
        )
    }

    private fun rank(q: FixQuality) = when (q) {
        FixQuality.RTK_FIXED -> 5; FixQuality.RTK_FLOAT -> 4; FixQuality.DGPS -> 3
        FixQuality.PPS -> 3; FixQuality.SBAS -> 3; FixQuality.SINGLE -> 2; else -> 1  // 0.1.11: SBAS = DGPS 급(전문가 GNSS P1)
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
    /** 평균화 시작/종료 시각(호출자 단조 시계 ms; 벽시계 변환은 호출자 몫). add(nowMs) 를 안 줬으면 null */
    val startedAtMs: Long? = null, val endedAtMs: Long? = null,
    /** 사용한 에폭 중 보정 연령의 최대/최소(초). correctionAgeSec(마지막 값)만으로는 중간에 보정이 끊겼던 것을 알 수 없다 */
    val maxCorrectionAgeSec: Double? = null, val minCorrectionAgeSec: Double? = null, val epochsWithoutAge: Int = 0,
    val fixedEpochs: Int = 0, val floatEpochs: Int = 0,
    val rejectedDuplicate: Int = 0, val rejectedNoPosition: Int = 0,
    /** 평균에 쓴 각 에폭의 위치(위도, 경도, 타원체고) — 점 상세의 ‘각 측정값과 편차’용 */
    val positions: List<Triple<Double, Double, Double?>> = emptyList(),
) {
    val durationMs: Long? get() = if (startedAtMs != null && endedAtMs != null) endedAtMs - startedAtMs else null
}
