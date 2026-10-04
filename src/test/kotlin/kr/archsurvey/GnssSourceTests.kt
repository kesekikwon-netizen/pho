package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.device.*
import org.junit.Assert.*
import org.junit.Test

private data class Obs(val q: FixQuality, val link: LinkState, val sigma: Double?, val status: SurveyStatus, val lat: Double?, val lon: Double?)

private fun FakeGnssSource.observe(): Obs {
    val s = gnss.value
    val j = StatusJudge.judge(s, link.value.state, clockMs())
    return Obs(s.quality, link.value.state, s.gst?.sigmaHoriz, j.status, s.gga?.lat, s.gga?.lon)
}

private fun FakeGnssSource.runAll(): List<Obs> {
    val out = ArrayList<Obs>()
    while (emitNext()) out += observe()
    return out
}

/** WP-1: GnssSource + FakeGnssSource 시나리오 */
class FakeGnssSourceTest {
    @Test fun scenarioLengthAndSegmentOrder() {
        val sc = FakeScenarios.fieldWalk()
        assertEquals(89, sc.totalEpochs)
        val f = FakeGnssSource(sc)
        val obs = f.runAll()
        assertEquals(89, obs.size)
        assertFalse(f.emitNext()); assertTrue(f.finished)
    }
    @Test fun qualitySequenceFixFloatSingleDropRecoverFix() {
        val o = FakeGnssSource(FakeScenarios.fieldWalk()).runAll()
        // 구간 경계: 고정 0..29, 플로트 30..39, 단독 40..49, 끊김 50..57, 복구 58..62, 고정 63..82, 정밀도 낮음 83..88
        assertTrue((0..29).all { o[it].q == FixQuality.RTK_FIXED && o[it].link == LinkState.STREAMING })
        assertTrue((30..39).all { o[it].q == FixQuality.RTK_FLOAT })
        assertTrue((40..49).all { o[it].q == FixQuality.SINGLE })
        assertTrue("끊김: 연결 상태가 재연결", (50..57).all { o[it].link == LinkState.RECONNECTING })
        assertTrue("끊김 중 마지막 스냅샷은 남아 있다(실제 엔진과 동일)", (50..57).all { o[it].q == FixQuality.SINGLE })
        assertTrue((58..62).all { o[it].q == FixQuality.RTK_FLOAT && o[it].link == LinkState.STREAMING })
        assertTrue((63..82).all { o[it].q == FixQuality.RTK_FIXED })
        assertTrue((83..88).all { o[it].q == FixQuality.RTK_FIXED })
    }
    @Test fun statusSequenceMatchesJudge() {
        val o = FakeGnssSource(FakeScenarios.fieldWalk()).runAll()
        assertTrue((0..29).all { o[it].status == SurveyStatus.FIXED })
        assertTrue((30..39).all { o[it].status == SurveyStatus.CAUTION })
        assertTrue((40..49).all { o[it].status == SurveyStatus.UNAVAILABLE })
        assertTrue((50..57).all { o[it].status == SurveyStatus.UNAVAILABLE })
        assertTrue((58..62).all { o[it].status == SurveyStatus.CAUTION })
        assertTrue((63..82).all { o[it].status == SurveyStatus.FIXED })
        assertTrue("σ 3 cm 고정은 주의", (83..88).all { o[it].status == SurveyStatus.CAUTION })
    }
    @Test fun precisionChangesBetweenSegments() {
        val o = FakeGnssSource(FakeScenarios.fieldWalk()).runAll()
        assertTrue((0..29).all { o[it].sigma!! in 0.0108..0.0132 })      // 0.012 ±10%
        assertTrue((30..39).all { o[it].sigma!! in 0.225..0.275 })
        assertTrue((40..49).all { o[it].sigma!! in 1.35..1.65 })
        assertTrue((83..88).all { o[it].sigma!! in 0.027..0.033 })
    }
    @Test fun positionMovesWhileWalking() {
        val o = FakeGnssSource(FakeScenarios.fieldWalk()).runAll()
        val d = distM(o[0].lat!!, o[0].lon!!, o[29].lat!!, o[29].lon!!)
        // 30 에폭 * 1 s * hypot(0.30, 0.20)=0.36 m/s ≈ 10.4 m (잡음 σ 1.2 cm)
        assertTrue("이동 거리 $d", d in 9.8..11.2)
    }
    @Test fun sameSeedIsReproducibleDifferentSeedIsNot() {
        val a = FakeGnssSource(FakeScenarios.fieldWalk(7)).runAll()
        val b = FakeGnssSource(FakeScenarios.fieldWalk(7)).runAll()
        val c = FakeGnssSource(FakeScenarios.fieldWalk(8)).runAll()
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
    @Test fun emitsRealNmeaThatParserAccepts() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        repeat(3) { f.emitNext() }
        assertTrue(f.sentences.all { it.startsWith("\$GPGGA") || it.startsWith("\$GPGST") })
        val p = NmeaParser()
        val msgs = f.sentences.flatMap { p.feed(it.toByteArray()) }
        assertEquals(6, msgs.size); assertEquals(0, p.stats.checksumErrors)
        val g = msgs.filterIsInstance<GgaMessage>().last()
        assertEquals(24, g.satellites); assertEquals(FixQuality.RTK_FIXED, g.quality)
        assertEquals(38.0 + 26.0, g.ellipsoidalHeight!!, 1e-6)
    }
    @Test fun snapshotAgeGrowsDuringDropAndJudgeSaysUnavailable() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        repeat(50) { f.emitNext() }                       // 단독 구간 끝
        val last = f.gnss.value.lastGgaAtMs
        repeat(7) { f.emitNext() }                        // 끊김 7초
        assertEquals(last, f.gnss.value.lastGgaAtMs)
        assertEquals(7_000L, f.clockMs() - last)
        val r = StatusJudge.judge(f.gnss.value, f.link.value.state, f.clockMs())
        assertEquals(SurveyStatus.UNAVAILABLE, r.status); assertEquals(StatusReason.LINK_DOWN, r.reason)
    }
    @Test fun skipToNextSegmentAndLoop() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk(), loop = true)
        f.skipToNextSegment()
        assertEquals(30, f.epochIndex); assertEquals("플로트", f.currentSegment!!.label)
        repeat(89 - 30 + 1) { assertTrue(f.emitNext()) }  // 끝까지 가고 loop 로 처음부터 다시
        assertEquals(1, f.epochIndex)
    }
    @Test fun gstMissingScenarioGivesNoSigma() {
        val sc = FakeScenario("no-gst", listOf(FakeSegment("고정 σ 없음", 3, sigmaHM = null)))
        val f = FakeGnssSource(sc); f.runAll()
        assertNull(f.gnss.value.gst)
        assertEquals(StatusReason.NO_SIGMA, StatusJudge.judge(f.gnss.value, f.link.value.state, f.clockMs()).reason)
    }

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dn = (lat2 - lat1) * 111_320.0
        val de = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians(lat1))
        return Math.hypot(dn, de)
    }
}

/** WP-2: 고정/주의/불가 경계값 */
class StatusJudgeTest {
    private val now = 10_000L
    private fun snap(q: FixQuality, sigmaH: Double?, ageMs: Long = 500, lat: Double? = 36.5, sats: Int = 20): GnssSnapshot {
        val gga = GgaMessage("raw", "090000.00", lat, if (lat == null) null else 127.0, q, sats, 0.7, 38.0, 26.0, 1.0, "0000")
        val gst = sigmaH?.let { GstMessage("raw", 0.01, it / Math.sqrt(2.0), it / Math.sqrt(2.0), 0.03) }
        return GnssSnapshot(gga = gga, gst = gst, lastGgaAtMs = now - ageMs, lastAnyAtMs = now - ageMs)
    }
    private fun j(s: GnssSnapshot, link: LinkState = LinkState.STREAMING) = StatusJudge.judge(s, link, now)

    @Test fun boundaryTable() {
        data class C(val q: FixQuality, val s: Double?, val want: SurveyStatus, val why: StatusReason)
        val table = listOf(
            C(FixQuality.RTK_FIXED, 0.000, SurveyStatus.FIXED, StatusReason.OK),
            C(FixQuality.RTK_FIXED, 0.012, SurveyStatus.FIXED, StatusReason.OK),
            C(FixQuality.RTK_FIXED, 0.020, SurveyStatus.FIXED, StatusReason.OK),            // 경계 포함(≤)
            C(FixQuality.RTK_FIXED, 0.0201, SurveyStatus.CAUTION, StatusReason.LOW_PRECISION),
            C(FixQuality.RTK_FIXED, 0.5, SurveyStatus.CAUTION, StatusReason.LOW_PRECISION),
            C(FixQuality.RTK_FIXED, null, SurveyStatus.CAUTION, StatusReason.NO_SIGMA),
            C(FixQuality.RTK_FLOAT, 0.005, SurveyStatus.CAUTION, StatusReason.FLOAT),       // Float 는 σ가 작아도 주의
            C(FixQuality.RTK_FLOAT, 0.3, SurveyStatus.CAUTION, StatusReason.FLOAT),
            C(FixQuality.RTK_FLOAT, null, SurveyStatus.CAUTION, StatusReason.FLOAT),
            C(FixQuality.DGPS, 0.01, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.SINGLE, 0.01, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.PPS, null, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.DEAD_RECKONING, 0.01, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.MANUAL, null, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.SIMULATION, null, SurveyStatus.UNAVAILABLE, StatusReason.SINGLE_OR_DGPS),
            C(FixQuality.NONE, null, SurveyStatus.UNAVAILABLE, StatusReason.NO_POSITION),
        )
        for (c in table) {
            val r = j(snap(c.q, c.s))
            assertEquals("${c.q}/${c.s}", c.want, r.status); assertEquals("${c.q}/${c.s}", c.why, r.reason)
        }
    }
    @Test fun noPositionEvenIfFixQuality() {
        assertEquals(StatusReason.NO_POSITION, j(snap(FixQuality.RTK_FIXED, 0.01, lat = null)).reason)
    }
    @Test fun staleBoundary() {
        assertEquals(SurveyStatus.FIXED, j(snap(FixQuality.RTK_FIXED, 0.01, ageMs = 5_000)).status)       // 5.000 s 까지 허용
        val r = j(snap(FixQuality.RTK_FIXED, 0.01, ageMs = 5_001))
        assertEquals(SurveyStatus.UNAVAILABLE, r.status); assertEquals(StatusReason.STALE, r.reason)
    }
    @Test fun linkStatesOverrideEverything() {
        val good = snap(FixQuality.RTK_FIXED, 0.01)
        for (l in listOf(LinkState.IDLE, LinkState.CONNECTING, LinkState.CONNECTED_NO_DATA, LinkState.RECONNECTING, LinkState.NEEDS_USER)) {
            val r = j(good, l); assertEquals(l.name, SurveyStatus.UNAVAILABLE, r.status); assertEquals(if (l == LinkState.IDLE) StatusReason.NOT_CONNECTED else StatusReason.LINK_DOWN, r.reason)
        }
        assertEquals(StatusReason.STALE, j(good, LinkState.STALE).reason)
    }
    @Test fun noGgaMeansNoData() {
        val r = StatusJudge.judge(GnssSnapshot(), LinkState.STREAMING, now)
        assertEquals(SurveyStatus.UNAVAILABLE, r.status); assertEquals(StatusReason.NO_DATA, r.reason)
    }
    @Test fun customThresholds() {
        val th = StatusThresholds(fixedMaxSigmaHM = 0.04, staleAfterMs = 1_000)
        assertEquals(SurveyStatus.FIXED, StatusJudge.judge(snap(FixQuality.RTK_FIXED, 0.035), LinkState.STREAMING, now, th).status)
        assertEquals(SurveyStatus.UNAVAILABLE, StatusJudge.judge(snap(FixQuality.RTK_FIXED, 0.01, ageMs = 1_500), LinkState.STREAMING, now, th).status)
    }
    @Test fun savePolicy() {
        assertEquals(SavePolicy.ALLOW, StatusJudge.savePolicy(SurveyStatus.FIXED))
        assertEquals(SavePolicy.ALLOW_WITH_WARNING, StatusJudge.savePolicy(SurveyStatus.CAUTION))
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(SurveyStatus.UNAVAILABLE))
    }
    @Test fun labels() {
        assertEquals(listOf("고정", "주의", "불가"), SurveyStatus.entries.map { it.labelKo })
    }
}
