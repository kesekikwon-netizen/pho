package kr.archsurvey.comm

import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.gst
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.core.*
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.bt.BtError
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.engine.NtripFailureKind
import kr.archsurvey.engine.RtcmFreshness
import kr.archsurvey.ntrip.*
import org.junit.Assert.*
import org.junit.Test

class Rtcm3Tests {
    @Test fun crc24qCheckValue() {
        assertEquals(0xCDE703, Crc24q.compute("123456789".toByteArray()))
    }

    @Test fun framesAcrossOneByteChunks() {
        val f = rtcmFrame(1005, 19)
        val out = ArrayList<ByteArray>()
        val fr = Rtcm3Framer()
        for (b in f) fr.feed(byteArrayOf(b), 1, 1000) { out.add(it) }
        assertEquals(1, out.size); assertArrayEquals(f, out[0])
        assertEquals(1, fr.messageCounts[1005])
        assertEquals(1000L, fr.lastFrameAtMs)
    }

    @Test fun garbageAndCorruptFrameAreDropped_andResyncs() {
        val good1 = rtcmFrame(1077, 40); val good2 = rtcmFrame(1087, 40); val good3 = rtcmFrame(1097, 220)
        val bad = rtcmFrame(1005, 19).also { it[8] = (it[8].toInt() xor 0x01).toByte() } // 본문 1비트 오염 -> CRC 불일치
        val html = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>captive portal</html>".toByteArray()
        // 가짜 프리앰블(D3 00 D3 ..)은 길이 211 로 읽혀 CRC 로 걸러지기까지 뒤 프레임을 잠시 붙잡지만, 1바이트씩 재탐색해 모두 복구한다
        val stream = html + good1 + bad + byteArrayOf(0xD3.toByte(), 0x00) + good2 + good3
        val out = ArrayList<ByteArray>()
        val fr = Rtcm3Framer()
        fr.feed(stream, stream.size, 5) { out.add(it) }
        assertEquals(3, out.size)
        assertArrayEquals(good1, out[0]); assertArrayEquals(good2, out[1]); assertArrayEquals(good3, out[2])
        assertTrue("CRC 오류가 집계돼야 함", fr.crcErrors >= 1)
        assertTrue(fr.skippedBytes > 0)
    }

    @Test fun pureTextProducesNoFrames() {
        val fr = Rtcm3Framer(); var n = 0
        val t = ByteArray(5000) { 'A'.code.toByte() }
        fr.feed(t, t.size, 1) { n++ }
        assertEquals(0, n); assertEquals(0L, fr.framesOk)
    }

    @Test fun reservedBitsRejected() {
        // 길이 필드 상위 6비트가 0 이 아니면 프레임이 아니다
        val fr = Rtcm3Framer(); var n = 0
        val b = byteArrayOf(0xD3.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)
        fr.feed(b, b.size, 1) { n++ }
        assertEquals(0, n)
    }
}

class NmeaCommTests {
    private fun driver() = NmeaDriver(DeviceProfiles.R10).also { it.reset(0) }

    @Test fun sentenceSplitAcrossChunksAndChecksumErrorsCounted() {
        val d = driver()
        val g = gga(); val bad = g.dropLast(4) + "00\r\n" // 체크섬 강제 오류
        val all = (g + bad + gst()).toByteArray()
        var parsed = 0
        var i = 0
        while (i < all.size) { val n = minOf(7, all.size - i); parsed += d.onBytes(all.copyOfRange(i, i + n), n, 100L + i).size; i += n }
        assertEquals(2, parsed) // 올바른 GGA + GST
        assertEquals(1L, d.snapshot.checksumErrors)
        assertEquals(setOf("GGA", "GST"), d.snapshot.seenTypes)
        assertEquals(FixQuality.RTK_FIXED, d.snapshot.quality)
    }

    @Test fun binaryRtcmMixedInDoesNotBreakNextSentence() {
        val d = driver()
        val junk = rtcmFrame(1005, 19)
        val bytes = junk + gga().toByteArray()
        val m = d.onBytes(bytes, bytes.size, 10)
        assertEquals(1, m.size)
    }

    @Test fun snapshotKeepsSameSetWhenNothingNew() {
        val d = driver()
        val a = (gga("000001.00") + gst()).toByteArray(); d.onBytes(a, a.size, 1)
        val before = d.snapshot.seenTypes
        val b = gga("000002.00").toByteArray(); d.onBytes(b, b.size, 2)
        assertSame("새 문장 종류가 없으면 집합을 복사하지 않는다", before, d.snapshot.seenTypes)
        assertEquals(2L, d.snapshot.lastGgaAtMs)
    }

    @Test fun gsvPerTalkerCount() {
        val d = driver()
        val s = (NmeaParser.build("GPGSV,1,1,09,01,10,100,40") + NmeaParser.build("GLGSV,1,1,06,65,10,100,40")).toByteArray()
        d.onBytes(s, s.size, 1)
        assertEquals(15, d.snapshot.satsInView)
    }

    @Test fun errorRateNeedsEnoughSamples() {
        val st = ParserStats(valid = 5, checksumErrors = 5)
        assertEquals(0.0, st.checksumErrorRate, 0.0)
        assertEquals(0.5, ParserStats(valid = 50, checksumErrors = 50).checksumErrorRate, 1e-9)
    }

    @Test fun correctionAgeGrowsWithTimeSinceGga() {
        val d = driver()
        val g = gga(age = "2.0").toByteArray(); d.onBytes(g, g.size, 10_000)
        assertEquals(2.0, d.snapshot.correctionAgeNowSec(10_000)!!, 1e-9)
        assertEquals(7.0, d.snapshot.correctionAgeNowSec(15_000)!!, 1e-9)
        val g2 = gga(age = "").toByteArray(); val d2 = driver(); d2.onBytes(g2, g2.size, 1)
        assertNull(d2.snapshot.correctionAgeNowSec(5))
    }

    @Test fun fixClassification() {
        assertEquals(FixQuality.RTK_FIXED, FixQuality.fromCode(4)); assertEquals(FixQuality.RTK_FLOAT, FixQuality.fromCode(5))
        assertEquals(FixQuality.DGPS, FixQuality.fromCode(2)); assertEquals(FixQuality.SINGLE, FixQuality.fromCode(1))
        assertTrue(FixQuality.RTK_FIXED.isRtk && FixQuality.RTK_FLOAT.isRtk && !FixQuality.DGPS.isRtk)
        assertTrue(FixQuality.DGPS.isCorrected && !FixQuality.SINGLE.isCorrected)
        assertEquals(FixQuality.NONE, FixQuality.fromCode(99))
        assertEquals(FixQuality.SBAS, FixQuality.fromCode(9))
        assertTrue(FixQuality.SBAS.isCorrected && !FixQuality.SBAS.isRtk)
    }

    @Test fun statusCautionWhenCorrectionTooOld() {
        fun snap(age: String, at: Long = 1000): GnssSnapshot {
            val d = driver(); val b = (gga(age = age) + gst()).toByteArray(); d.onBytes(b, b.size, at); return d.snapshot
        }
        val ok = StatusJudge.judge(snap("2.0"), LinkState.STREAMING, 1500)
        assertEquals(SurveyStatus.FIXED, ok.status)
        val old = StatusJudge.judge(snap("20.0"), LinkState.STREAMING, 1500)
        assertEquals(SurveyStatus.CAUTION, old.status); assertEquals(StatusReason.OLD_CORRECTION, old.reason)
        // GGA 가 5초 안이어도 경과 시간이 더해져 문턱을 넘는다(2s + 8s > 10s) -> 그 전에 STALE 이 먼저 잡히는지 확인(5초 초과)
        val stale = StatusJudge.judge(snap("2.0"), LinkState.STREAMING, 1000 + 8_000)
        assertEquals(StatusReason.STALE, stale.reason)
        val noAge = StatusJudge.judge(snap(""), LinkState.STREAMING, 1500)
        assertEquals(SurveyStatus.FIXED, noAge.status) // 값이 없으면 판정에 쓰지 않는다(수신기가 안 주는 경우)
    }

    @Test fun ggaRelayRejectsNoPositionAndNormalizesTalker() {
        val noFix = NmeaParser.build("GNGGA,092751.00,,,,,0,00,99.9,,M,,M,,")
        assertNull(GgaRelay.forCaster(noFix))
        val ok = GgaRelay.forCaster(gga(q = 1))!!
        assertTrue(ok.startsWith("\$GPGGA,")); assertTrue(ok.endsWith("\r\n"))
        assertTrue(NmeaParser().parseLine(ok) is GgaMessage)
        assertNull(GgaRelay.forCaster(null)); assertNull(GgaRelay.forCaster("\$GNGST,1,2*00"))
    }
}

class AveragerCommTests {
    private fun g(time: String, q: Int = 4, age: String = "1.0") = NmeaParser().parseLine(gga(time = time, q = q, age = age).trim()) as GgaMessage

    @Test fun recordsStartEndSamplesAndAgeRange() {
        val a = PointAverager(4)
        assertTrue(a.add(g("000001.00", age = "1.0"), null, nowMs = 10_000))
        assertFalse("같은 시각 중복", a.add(g("000001.00"), null, nowMs = 10_100))
        assertTrue(a.add(g("000002.00", q = 5, age = "9.0"), null, nowMs = 11_000))
        assertTrue(a.add(g("000003.00", age = "3.0"), null, nowMs = 12_000))
        assertTrue(a.add(g("000004.00", age = ""), null, nowMs = 13_000))
        val r = a.result()!!
        assertEquals(10_000L, r.startedAtMs); assertEquals(13_000L, r.endedAtMs); assertEquals(3_000L, r.durationMs)
        assertEquals(4, r.epochs)
        assertEquals(9.0, r.maxCorrectionAgeSec!!, 1e-9); assertEquals(1.0, r.minCorrectionAgeSec!!, 1e-9)
        assertEquals(1, r.epochsWithoutAge)
        assertEquals(3, r.fixedEpochs); assertEquals(1, r.floatEpochs)
        assertEquals(1, r.rejectedDuplicate)
        assertEquals(FixQuality.RTK_FLOAT, r.worstQuality); assertFalse(r.allFixed)
    }

    @Test fun oldCallSignatureStillWorksAndTimesAreNull() {
        val a = PointAverager(1)
        assertTrue(a.add(g("000001.00"), null))
        val r = a.result()!!
        assertNull(r.startedAtMs); assertNull(r.durationMs)
    }

    @Test fun receiversWithoutTimeFieldStillAverageEveryEpoch() {
        val a = PointAverager(3)
        val m = NmeaParser().parseLine(NmeaParser.build("GNGGA,,3733.9900,N,12658.6800,E,4,12,0.9,45.5,M,25.3,M,1.0,0001").trim()) as GgaMessage
        assertTrue(a.add(m, null)); assertTrue(a.add(m, null)); assertTrue(a.add(m, null))
        assertTrue(a.done)
    }

    @Test fun noPositionEpochsAreCounted() {
        val a = PointAverager(2)
        val none = NmeaParser().parseLine(NmeaParser.build("GNGGA,000001.00,,,,,0,00,99.9,,M,,M,,").trim()) as GgaMessage
        assertFalse(a.add(none, null)); a.add(g("000002.00"), null)
        assertEquals(1, a.result()!!.rejectedNoPosition)
    }
}

class StatusAndErrorTests {
    @Test fun freshnessLevels() {
        val st = NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 10_000)
        assertEquals(RtcmFreshness.FRESH, st.freshness(11_000))
        assertEquals(RtcmFreshness.AGING, st.freshness(15_000))
        assertEquals(RtcmFreshness.LOST, st.freshness(21_000))
        assertEquals(RtcmFreshness.NONE, NtripStatus().freshness(5))
        assertEquals(4_000L, st.rtcmAgeMs(14_000))
        assertNull(NtripStatus().rtcmAgeMs(1))
    }

    @Test fun btRefineAndClassify() {
        assertEquals(BtError.POWER_OR_RANGE, BtError.classify(java.io.IOException("Host is down")))
        assertEquals(BtError.CONNECT_REFUSED, BtError.classify(java.io.IOException("Service discovery failed")))
        assertEquals(BtError.CONNECT_TIMEOUT, BtError.refineAfterLoss(BtError.CONNECT_TIMEOUT, true, 1))
        assertEquals(BtError.POWER_OR_RANGE, BtError.refineAfterLoss(BtError.CONNECT_TIMEOUT, true, 2))
        assertEquals(BtError.CONNECT_TIMEOUT, BtError.refineAfterLoss(BtError.CONNECT_TIMEOUT, false, 9)) // 한 번도 연결된 적 없으면 추정하지 않음
        assertEquals(BtError.BT_OFF, BtError.refineAfterLoss(BtError.BT_OFF, true, 9))
        assertFalse(BtError.POWER_OR_RANGE.needsUserAction)
        assertEquals(BtError.entries.size, BtError.entries.map { it.code }.toSet().size)
    }

    @Test fun ntripFailureClassification() {
        assertEquals(NtripFailureKind.AUTH, NtripFailureKind.classify(NtripAuthException(401, "x")))
        assertEquals(NtripFailureKind.MOUNT, NtripFailureKind.classify(NtripMountException("x")))
        assertEquals(NtripFailureKind.DNS, NtripFailureKind.classify(NtripNetworkException("x", java.net.UnknownHostException())))
        assertEquals(NtripFailureKind.NETWORK, NtripFailureKind.classify(NtripNetworkException("x", java.net.ConnectException())))
        assertEquals(NtripFailureKind.TIMEOUT, NtripFailureKind.classify(NtripTimeoutException(NtripTimeoutException.Phase.CONNECT, "x")))
        assertEquals(NtripFailureKind.TIMEOUT, NtripFailureKind.classify(java.net.SocketTimeoutException()))
        assertEquals(NtripFailureKind.SERVER_CLOSED, NtripFailureKind.classify(java.net.SocketException("Connection reset")))
        assertEquals(NtripFailureKind.SERVER_ERROR, NtripFailureKind.classify(NtripServerException(503, "x")))
        assertEquals(NtripFailureKind.NO_DATA, NtripFailureKind.classify(NtripNoDataException("x")))
        assertEquals(NtripFailureKind.UNKNOWN, NtripFailureKind.classify(IllegalStateException()))
        assertFalse(NtripFailureKind.AUTH.autoRetry); assertFalse(NtripFailureKind.MOUNT.autoRetry)
        // 지수 백오프: 2,4,8,16,30(상한)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..6).map { NtripFailureKind.DNS.delayMs(it) })
        // 기존 화면 단서 호환
        assertTrue("UnknownHost" in NtripFailureKind.DNS.tag); assertTrue("Timeout" in NtripFailureKind.TIMEOUT.tag); assertTrue("ConnectException" in NtripFailureKind.NETWORK.tag)
    }

    @Test fun ggaIntervalClamp() {
        assertEquals(1000L, NtripTiming.clampGgaIntervalMs(0)); assertEquals(1000L, NtripTiming.clampGgaIntervalMs(200))
        assertEquals(10_000L, NtripTiming.clampGgaIntervalMs(60_000)); assertEquals(5000L, NtripTiming.clampGgaIntervalMs(5000))
    }

    @Test fun sourcetableParseAndRecommend() {
        val t = """SOURCETABLE 200 OK
CAS;caster.example;2101;Example;NGII;0;KOR;37.5;127.0;http://x
STR;VRS-RTCM31;Seoul VRS;RTCM 3.1;1004(1),1005(10);2;GPS+GLO;NGII;KOR;37.5;127.0;1;1;gen;none;B;N;9600;
STR;PHYS-NEAR;Near;RTCM 3.2;1077(1);2;GPS+GLO+GAL;NGII;KOR;37.50;127.00;0;0;gen;none;B;N;5000;
STR;PHYS-FAR;Far;RTCM 3.2;1077(1);2;GPS;NGII;KOR;35.1;129.0;0;0;gen;none;B;N;3000;
STR;CMR-X;Old;CMR+;;2;GPS;NGII;KOR;37.5;127.0;0;0;gen;none;B;N;2000;
STR;SHORT;x
ENDSOURCETABLE
"""
        val l = Sourcetable.parse(t)
        assertEquals(listOf("VRS-RTCM31", "PHYS-NEAR", "PHYS-FAR", "CMR-X"), l.map { it.mountPoint })
        assertTrue(l[0].needsNmea); assertEquals(37.5, l[0].lat!!, 1e-9); assertEquals("B", l[0].authentication); assertEquals(9600, l[0].bitrate)
        val rec = Sourcetable.recommend(l, 37.51, 127.01)
        assertEquals(listOf("VRS-RTCM31", "PHYS-NEAR", "PHYS-FAR"), rec.map { it.mountPoint }) // CMR 제외, VRS 우선, 가까운 순
        val rec2 = Sourcetable.recommend(l, 35.2, 129.1, preferVrs = false)
        assertEquals(listOf("PHYS-FAR", "PHYS-NEAR", "VRS-RTCM31"), rec2.map { it.mountPoint }.take(3))
        assertEquals(0.0, Sourcetable.haversineKm(37.5, 127.0, 37.5, 127.0), 1e-9)
        assertEquals(111.19, Sourcetable.haversineKm(0.0, 0.0, 1.0, 0.0), 0.1)
    }
}
