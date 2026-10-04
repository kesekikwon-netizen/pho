package kr.archsurvey.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.NmeaDriver
import kr.archsurvey.core.NmeaParser
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.sqrt

/** 시나리오의 한 구간. emit=false 면 문장이 오지 않고 연결이 끊긴 것으로 본다. */
data class FakeSegment(
    val label: String,
    val epochs: Int,
    val quality: FixQuality = FixQuality.RTK_FIXED,
    /** 수평 σ(m). null 이면 GST 문장을 보내지 않는다 */
    val sigmaHM: Double? = 0.012,
    val satellites: Int = 24,
    val hdop: Double = 0.6,
    /** 이동 속도(m/s). 북(+)/동(+) */
    val northMps: Double = 0.0,
    val eastMps: Double = 0.0,
    val emit: Boolean = true,
)

data class FakeScenario(
    val name: String,
    val segments: List<FakeSegment>,
    val startLat: Double = 36.50000,
    val startLon: Double = 127.00000,
    val startAltMsl: Double = 38.0,
    val geoidSeparation: Double = 26.0,
    val seed: Long = 1L,
) {
    val totalEpochs: Int get() = segments.sumOf { it.epochs }
}

object FakeScenarios {
    /** 고정 유지 → 주의(Float) → 불가(단독) → 끊김 → 복구 → 고정 → 고정이나 정밀도 낮음(σ>2 cm). 모두 가짜 값. */
    fun fieldWalk(seed: Long = 1L) = FakeScenario(
        name = "field-walk", seed = seed,
        segments = listOf(
            FakeSegment("고정 유지", 30, FixQuality.RTK_FIXED, 0.012, 24, 0.6, northMps = 0.30, eastMps = 0.20),
            FakeSegment("플로트", 10, FixQuality.RTK_FLOAT, 0.250, 20, 0.9, northMps = 0.30, eastMps = 0.20),
            FakeSegment("단독", 10, FixQuality.SINGLE, 1.500, 14, 1.4, northMps = 0.30, eastMps = 0.20),
            FakeSegment("끊김", 8, emit = false),
            FakeSegment("복구(플로트)", 5, FixQuality.RTK_FLOAT, 0.180, 18, 0.9, northMps = 0.30, eastMps = 0.20),
            FakeSegment("고정 복귀", 20, FixQuality.RTK_FIXED, 0.015, 24, 0.6, northMps = 0.0, eastMps = 0.30),
            FakeSegment("고정이나 정밀도 낮음", 6, FixQuality.RTK_FIXED, 0.030, 22, 0.8, eastMps = 0.30),
        ),
    )

    /** 계속 고정, 제자리 걸음 느리게 이동 — 측점 흐름 시험용 */
    fun allFixed(seed: Long = 1L) = FakeScenario(
        name = "all-fixed", seed = seed,
        segments = listOf(FakeSegment("고정", 600, FixQuality.RTK_FIXED, 0.012, 24, 0.6, northMps = 0.25, eastMps = 0.15)),
    )
}

/**
 * 시나리오를 재생하는 가짜 수신기. 진짜 NMEA 문장(체크섬 포함)을 만들어 실제 [NmeaDriver] 를 통과시키므로
 * 파서·드라이버 경로까지 함께 시험된다. 시계는 가상 시계(에폭당 [epochMs])라 시험이 결정적이다.
 * 같은 seed → 같은 값 시퀀스.
 */
class FakeGnssSource(
    val scenario: FakeScenario,
    private val profile: DeviceProfile = DeviceProfiles.R10,
    val epochMs: Long = 1000L,
    private val loop: Boolean = false,
) : GnssSource {
    override val link = MutableStateFlow(LinkStatus(LinkState.CONNECTING, message = "가짜 소스 시작 전"))
    override val gnss = MutableStateFlow(GnssSnapshot())

    /** 지금까지 만든 NMEA 문장(최근 것부터 [keepSentences] 개) — 시험·진단용 */
    val sentences = ArrayDeque<String>()
    var keepSentences = 64

    private var rnd = Random(scenario.seed)
    private var driver = NmeaDriver(profile)
    private var index = 0             // 지금까지 재생한 에폭 수 (이번 루프 기준)
    private var clock = 0L            // 가상 시계 ms
    private var northM = 0.0
    private var eastM = 0.0
    private var wasDown = true
    private var job: Job? = null

    /** 현재 구간 라벨 (끝났으면 null) */
    val currentSegment: FakeSegment? get() = segmentAt(index)?.first
    val epochIndex: Int get() = index
    val finished: Boolean get() = !loop && index >= scenario.totalEpochs

    override fun clockMs(): Long = clock

    private fun segmentAt(i: Int): Pair<FakeSegment, Int>? {
        var acc = 0
        for (s in scenario.segments) {
            if (i < acc + s.epochs) return s to (i - acc)
            acc += s.epochs
        }
        return null
    }

    /** 다음 구간 시작 에폭으로 건너뛴다(시연·스크린샷용). 에폭은 건너뛰지 않고 순서대로 재생해 상태가 일관되게 한다. */
    fun skipToNextSegment() {
        val cur = segmentAt(index) ?: return
        val remaining = cur.first.epochs - cur.second
        repeat(remaining) { emitNext() }
    }

    /** 한 에폭 진행. 더 없으면 false (loop 면 처음부터 다시) */
    fun emitNext(): Boolean {
        if (index >= scenario.totalEpochs) {
            if (!loop) return false
            reset()
        }
        val (seg, _) = segmentAt(index)!!
        clock += epochMs
        index++
        if (!seg.emit) {
            wasDown = true
            link.value = LinkStatus(LinkState.RECONNECTING, attempt = 1, message = "다시 연결하는 중… (가짜 끊김)")
            return true
        }
        if (wasDown) { // 복구: 실제 엔진처럼 새 드라이버로 시작
            driver = NmeaDriver(profile).also { it.reset(clock) }
            wasDown = false
        }
        link.value = LinkStatus(LinkState.STREAMING, message = "수신 중 (가짜)")
        val sigma = seg.sigmaHM?.let { it * (1.0 + (rnd.nextDouble() - 0.5) * 0.2) }
        val noise = (sigma ?: 0.02) / sqrt(2.0)
        northM += seg.northMps * epochMs / 1000.0
        eastM += seg.eastMps * epochMs / 1000.0
        val n = northM + rnd.nextGaussian() * noise
        val e = eastM + rnd.nextGaussian() * noise
        val lat = scenario.startLat + n / M_PER_DEG
        val lon = scenario.startLon + e / (M_PER_DEG * cos(Math.toRadians(scenario.startLat)))
        val t = timeOf(clock)
        val gga = ggaBody(t, lat, lon, seg)
        val out = ArrayList<String>(2)
        out += NmeaParser.build(gga)
        if (sigma != null) out += NmeaParser.build(gstBody(t, sigma, sigma * 1.6))
        for (s in out) {
            sentences.addLast(s)
            while (sentences.size > keepSentences) sentences.removeFirst()
            val b = s.toByteArray(Charsets.US_ASCII)
            driver.onBytes(b, b.size, clock)
        }
        gnss.value = driver.snapshot
        return true
    }

    private fun ggaBody(t: String, lat: Double, lon: Double, seg: FakeSegment): String {
        val hasPos = seg.quality != FixQuality.NONE
        val latS = if (hasPos) ddmm(lat, 2) + "," + (if (lat >= 0) "N" else "S") else ","
        val lonS = if (hasPos) ddmm(lon, 3) + "," + (if (lon >= 0) "E" else "W") else ","
        val age = if (seg.quality == FixQuality.RTK_FIXED || seg.quality == FixQuality.RTK_FLOAT) "1.0" else ""
        val st = if (age.isNotEmpty()) "0000" else ""
        return String.format(Locale.US, "GPGGA,%s,%s,%s,%d,%02d,%.1f,%.3f,M,%.3f,M,%s,%s",
            t, latS, lonS, seg.quality.code, seg.satellites, seg.hdop, scenario.startAltMsl, scenario.geoidSeparation, age, st)
    }

    private fun gstBody(t: String, sigmaH: Double, sigmaV: Double): String {
        val s = sigmaH / sqrt(2.0)
        return String.format(Locale.US, "GPGST,%s,0.010,0.020,0.010,45.0,%.4f,%.4f,%.4f", t, s, s, sigmaV)
    }

    private fun timeOf(ms: Long): String {
        val sec = (9 * 3600 + ms / 1000).toInt() % 86400 // 09:00:00 UTC 시작 (가짜)
        return String.format(Locale.US, "%02d%02d%02d.00", sec / 3600, sec / 60 % 60, sec % 60)
    }

    private fun ddmm(deg: Double, degDigits: Int): String {
        val a = Math.abs(deg)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return String.format(Locale.US, "%0${degDigits}d%010.7f", d, m)
    }

    fun reset() {
        rnd = Random(scenario.seed)
        driver = NmeaDriver(profile)
        index = 0; clock = 0; northM = 0.0; eastM = 0.0; wasDown = true
        sentences.clear()
        gnss.value = GnssSnapshot()
        link.value = LinkStatus(LinkState.CONNECTING, message = "가짜 소스 시작 전")
    }

    /** 실시간 재생. 에폭당 [realDelayMs] 만큼 쉰다. */
    fun start(scope: CoroutineScope, realDelayMs: Long = epochMs) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                if (!emitNext()) break
                delay(realDelayMs)
            }
        }
    }

    override fun stop() { job?.cancel(); job = null }

    companion object { const val M_PER_DEG = 111_320.0 }
}
