package kr.archsurvey.t0133

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 0.1.33 현장 질문 「PDOP 1.6 / H 0.008 / V 0.018 · 고정 · 위성 17 · 보정 양호 · Ant.H 1.800 — 제대로 받는 건가」 코드 검증.
 * SP60(Ashtech 계열, SP80 ICD Rel 1.2 §3.2.24/32/33) 꼴 문장을 실제 수신 경로(NmeaDriver → StatusJudge → SurveyController → HeaderModel)에 넣어 본다.
 */
class NmeaVerify0133Tests {
    /** 시험용 수신기 대역: 시계를 시험이 움직인다 */
    private class StubSource : GnssSource {
        var now = 100_000L
        override val link = MutableStateFlow(LinkStatus(state = LinkState.STREAMING))
        override val gnss = MutableStateFlow(GnssSnapshot())
        override fun clockMs() = now
        override fun stop() {}
    }

    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    /** 앱과 독립된 체크섬 계산(앱의 NmeaParser.checksum 을 쓰지 않음) */
    private fun nmea(body: String): String = "$" + body + "*" + "%02X".format(body.fold(0) { a, ch -> a xor ch.code }) + "\r\n"

    private fun feed(d: NmeaDriver, now: Long, vararg lines: String): List<NmeaMessage> {
        val b = lines.joinToString("") { if (it.endsWith("\r\n")) it else it + "\r\n" }.toByteArray(Charsets.US_ASCII)
        return d.onBytes(b, b.size, now)
    }

    // ---- 제주 SP60 한 에폭(사용자 화면 재현용 값). σlat 0.006 · σlon 0.005 → √(0.006²+0.005²)=0.00781 → "0.008" ----
    private val gga = nmea("GPGGA,021530.00,3330.123456,N,12631.654321,E,4,17,0.8,45.123,M,23.456,M,1.0,0000")
    private val gst = nmea("GNGST,021530.00,0.012,0.007,0.004,12.3,0.006,0.005,0.018")
    private val gsaGps = nmea("GNGSA,A,3,02,05,07,13,15,18,20,29,,,,,1.6,0.8,1.4")
    private val gsaGlo = nmea("GNGSA,A,3,65,66,72,81,88,,,,,,,,1.6,0.8,1.4")
    private val gsaGal = nmea("GNGSA,A,3,101,103,111,112,,,,,,,,,1.6,0.8,1.4")

    // ================= 1. 공식 문서 예문(제조사 체크섬 그대로)이 통과하고 필드가 맞게 읽히는가 =================

    @Test fun vendorExamplesFromSp80IcdAndSpectraHelpParse() {
        val p = NmeaParser()
        // SP80 ICD §3.2.33 예문: 반장축/반단축/방향이 비어 있는 GST(Ashtech 실제 출력 꼴)
        val a = p.parseLine("\$GNGST,145623.50,,,,,0.023,0.023,0.029*40") as GstMessage
        assertNull(a.rmsRange); assertEquals(0.023, a.sigmaLat!!, 1e-12); assertEquals(0.023, a.sigmaLon!!, 1e-12); assertEquals(0.029, a.sigmaAlt!!, 1e-12)
        assertEquals(Math.sqrt(0.023 * 0.023 * 2), a.sigmaHoriz!!, 1e-12)
        // Spectra 도움말 예문: 반장축 0.023/반단축 0.020 이 위도/경도 σ(0.023/0.020)와 다른 자리 — 6·7·8 번을 읽어야 함
        val b = p.parseLine("\$GPGST,172814.0,0.006,0.023,0.020,273.6,0.023,0.020,0.031*6A") as GstMessage
        assertEquals(0.006, b.rmsRange!!, 1e-12); assertEquals(0.023, b.sigmaLat!!, 1e-12); assertEquals(0.020, b.sigmaLon!!, 1e-12); assertEquals(0.031, b.sigmaAlt!!, 1e-12)
        // ProMark 800 예문: 모든 필드가 서로 다른 값 → 자리 하나라도 어긋나면 틀린다
        val c = p.parseLine("\$GNGST,154013.80,0.642,1.746,1.303,27.197,1.663,1.407,2.456*79") as GstMessage
        assertEquals(0.642, c.rmsRange!!, 1e-12); assertEquals(1.663, c.sigmaLat!!, 1e-12); assertEquals(1.407, c.sigmaLon!!, 1e-12); assertEquals(2.456, c.sigmaAlt!!, 1e-12)
        // SP80 ICD §3.2.32 예문: 다중 위성계 GNGSA 두 줄, DOP 은 같은 값
        val g1 = p.parseLine("\$GNGSA,A,3,20,11,13,23,17,04,31,,,,,,1.6,0.9,1.3*21") as GsaMessage
        val g2 = p.parseLine("\$GNGSA,A,3,81,83,68,,,,,,,,,,1.6,0.9,1.3*2C") as GsaMessage
        for (g in listOf(g1, g2)) { assertEquals(3, g.fixType); assertEquals(1.6, g.pdop!!, 1e-12); assertEquals(0.9, g.hdop!!, 1e-12); assertEquals(1.3, g.vdop!!, 1e-12) }
        // SP80 ICD §3.2.24 GGA 예문: 품질 4, 위성 10, 고도 35.655, 분리 47.290, 보정 연령 3.0, 기준국 1000
        val q = p.parseLine("\$GPGGA,131745.00,4717.960847,N,00130.499476,W,4,10,0.8,35.655,M,47.290,M,3.0,1000*61") as GgaMessage
        assertEquals(FixQuality.RTK_FIXED, q.quality); assertEquals(10, q.satellites); assertEquals(0.8, q.hdop!!, 1e-12)
        assertEquals(35.655, q.altitudeMsl!!, 1e-12); assertEquals(47.290, q.geoidSeparation!!, 1e-12); assertEquals(3.0, q.correctionAgeSec!!, 1e-12); assertEquals("1000", q.baseStationId)
        assertEquals(0, p.stats.checksumErrors)
    }

    @Test fun nmea41GsaWithSystemIdKeepsPdopIndex() {
        val p = NmeaParser()
        // NMEA 4.10/4.11: 끝에 System ID 가 붙어도 PDOP/HDOP/VDOP 은 15/16/17 그대로
        val g = p.parseLine(nmea("GNGSA,A,3,05,07,10,11,,,,,,,,,1.1,0.5,1.0,4").trim()) as GsaMessage
        assertEquals(1.1, g.pdop!!, 1e-12); assertEquals(0.5, g.hdop!!, 1e-12); assertEquals(1.0, g.vdop!!, 1e-12)
    }

    @Test fun talkerIdsAndBadChecksumsAndEmptyFields() {
        val p = NmeaParser()
        for (t in listOf("GP", "GN", "GL", "GA", "GB", "BD")) {
            val m = p.parseLine(nmea("${t}GST,021530.00,0.012,,,,0.006,0.005,0.018").trim())
            assertTrue("$t GST", m is GstMessage)
        }
        // 체크섬 틀림 → 버림(값 안 바뀜)
        assertNull(p.parseLine("\$GNGST,021530.00,0.012,0.007,0.004,12.3,0.006,0.005,0.018*00"))
        // 체크섬 없음 → 버림
        assertNull(p.parseLine("\$GNGST,021530.00,0.012,0.007,0.004,12.3,0.006,0.005,0.018"))
        assertEquals(1, p.stats.checksumErrors)
        // 소문자 16진 체크섬도 받음
        val body = "GNGST,021530.00,0.012,0.007,0.004,12.3,0.006,0.005,0.018"
        assertTrue(p.parseLine("$" + body + "*" + "%02x".format(NmeaParser.checksum(body))) is GstMessage)
        // 해 없을 때 빈 GST → σ 없음(0 으로 바꾸지 않음)
        val e = p.parseLine(nmea("GNGST,021531.00,,,,,,,").trim()) as GstMessage
        assertNull(e.sigmaHoriz); assertNull(e.sigmaAlt)
        // 빈 GSA → PDOP 없음
        val ge = p.parseLine(nmea("GNGSA,A,1,,,,,,,,,,,,,,,").trim()) as GsaMessage
        assertNull(ge.pdop)
    }

    // ================= 2. 실제 수신 경로 끝까지: 사용자 화면 재현 =================

    @Test fun jejuSp60EpochReproducesHeaderExactly() {
        val src = StubSource()
        val c = SurveyController(src, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val d = NmeaDriver(DeviceProfiles.SP60).also { it.reset(src.now) }
        // SP60 은 한 에폭에 GGA → GST → GSA 3줄(위성계마다) 을 보낸다. 청크가 문장 중간에서 잘려 와도 같아야 한다
        val all = (gga + gst + gsaGps + gsaGlo + gsaGal).toByteArray(Charsets.US_ASCII)
        val cut = 37
        d.onBytes(all.copyOfRange(0, cut), cut, src.now)
        d.onBytes(all.copyOfRange(cut, all.size), all.size - cut, src.now)
        src.gnss.value = d.snapshot

        val s = d.snapshot
        assertEquals(setOf("GGA", "GST", "GSA"), s.seenTypes)
        assertNull("GSV 를 안 받았으면 보이는 위성 수 없음", s.satsInView)
        // GST: 6=σ위도, 7=σ경도, 8=σ높이
        assertEquals(0.006, s.gst!!.sigmaLat!!, 1e-12); assertEquals(0.005, s.gst!!.sigmaLon!!, 1e-12); assertEquals(0.018, s.gst!!.sigmaAlt!!, 1e-12)
        assertEquals(Math.sqrt(0.006 * 0.006 + 0.005 * 0.005), s.gst!!.sigmaHoriz!!, 1e-12)
        // 반장축(0.007)을 H 로 쓰지 않음
        assertNotEquals(0.007, s.gst!!.sigmaHoriz!!, 1e-6)

        val gv = c.gv
        assertEquals(StatusReason.OK, gv.status.reason)
        assertEquals(SurveyStatus.FIXED, gv.status.status)
        assertEquals("PDOP 1.6 / H 0.008 / V 0.018", HeaderModel.metrics(gv))
        val pill = HeaderModel.pill(gv, NtripStatus(state = NtripState.RECEIVING, lastRtcmAtMs = 50_000), true, 51_000)
        assertEquals("17", pill.sats)          // GGA 7번(사용 위성), GSA 합계(8+5+4=17)와 같음
        assertEquals("고정", pill.fix)          // GGA 6번 = 4
        assertEquals("보정 양호", pill.correction) // VRS: 3초 안에 CRC 맞는 RTCM 프레임
        assertEquals("Ant.H 1.800", HeaderModel.antH(1.8))
        // PDOP 은 GSA 15번. 16번(HDOP 0.8)·GGA HDOP(0.8)으로 바뀌지 않음
        assertEquals(1.6, gv.pdop!!, 1e-12); assertEquals(1.4, gv.vdop!!, 1e-12); assertEquals(0.8, gv.hdop!!, 1e-12)
    }

    @Test fun pdopFromLastGsaOfEpochIsSameAsFirst() {
        val d = NmeaDriver(DeviceProfiles.SP60).also { it.reset(0) }
        feed(d, 1_000, gga, gst, gsaGps)
        val first = d.snapshot.gsa!!.pdop
        feed(d, 1_010, gsaGlo, gsaGal)
        assertEquals(first!!, d.snapshot.gsa!!.pdop!!, 1e-12)
    }

    @Test fun ggaSatsAbove12NotCapped() {
        val p = NmeaParser()
        val g = p.parseLine(nmea("GPGGA,021530.00,3330.123456,N,12631.654321,E,4,26,0.6,45.123,M,23.456,M,1.0,0000").trim()) as GgaMessage
        assertEquals(26, g.satellites) // SP80 ICD: 3-26
    }

    @Test fun fixQualityMapping() {
        fun q(n: Int) = (NmeaParser().parseLine(nmea("GPGGA,021530.00,3330.123456,N,12631.654321,E,$n,17,0.8,45.123,M,23.456,M,1.0,0000").trim()) as GgaMessage).quality
        assertEquals(FixQuality.RTK_FIXED, q(4)); assertEquals("고정", HeaderModel.fixLabel(q(4)))
        assertEquals(FixQuality.RTK_FLOAT, q(5)); assertEquals("유동", HeaderModel.fixLabel(q(5)))
        assertEquals(FixQuality.SBAS, q(9))
        assertEquals(FixQuality.DGPS, q(2))
        assertEquals(FixQuality.SINGLE, q(1))
    }

    @Test fun receiverGeoidHeightIsNotAppliedTwice() {
        // 작업 지오이드 = 수신기 값: 지면 표고 = GGA 해발고(9번) − 안테나 위상중심 높이. 분리(11번)를 다시 빼지 않음
        val o = GeoidHeights.apcOrtho(GeoidModel.RECEIVER, 33.5, 126.5, 45.123, 23.456)
        assertEquals(45.123, o.heightM!!, 1e-12)
        val sp60 = db.getValue("SPECTRA_SP60")
        val r = AntennaMath.compute(AntennaConfig(inputValueM = 1.8, refPointId = "bottom_of_receiver_arp"), sp60)!!
        assertEquals(1.8687, r.totalToApcM, 1e-9) // 폴 1.800 + SP60 ARP→L1 위상중심 0.0687
        assertEquals(45.123 - 1.8687, o.heightM!! - r.totalToApcM, 1e-9)
    }

    // ================= 3. 신선도: GST/GSA 가 끊기면 옛 값을 계속 보여 주지 않는가 =================

    @Test fun staleGstAndGsaAreNotShownWhileGgaKeepsComing() {
        val src = StubSource()
        val c = SurveyController(src, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val d = NmeaDriver(DeviceProfiles.SP60).also { it.reset(src.now) }
        feed(d, src.now, gga, gst, gsaGps, gsaGlo, gsaGal)
        src.gnss.value = d.snapshot
        assertEquals("PDOP 1.6 / H 0.008 / V 0.018", HeaderModel.metrics(c.gv))
        // 그 뒤 10초 동안 GGA 만 온다(GST·GSA 출력이 멈춤: 다른 앱이 NME 를 끄거나 포트 설정이 바뀐 경우)
        for (i in 1..10) {
            src.now += 1_000
            val t = "0215%02d.00".format(30 + i)
            feed(d, src.now, nmea("GPGGA,$t,3330.123456,N,12631.654321,E,4,17,0.8,45.123,M,23.456,M,1.0,0000"))
            src.gnss.value = d.snapshot
        }
        val gv = c.gv
        // 10초 묵은 σ 로 「측량 가능(고정)」 판정하지 않음 → σ 없음(고정·주의)
        assertNull("10초 묵은 수평 σ", gv.status.sigmaHM)
        assertNull("10초 묵은 수직 σ", gv.sigmaVM)
        assertNull("10초 묵은 PDOP", gv.pdop)
        assertEquals(StatusReason.NO_SIGMA, gv.status.reason)
        assertEquals("HDOP 0.8 / H — / V — · σ 없음", HeaderModel.metrics(gv))
        // 다시 GST·GSA 가 오면 곧바로 회복
        src.now += 1_000
        feed(d, src.now, gga, gst, gsaGps)
        src.gnss.value = d.snapshot
        assertEquals("PDOP 1.6 / H 0.008 / V 0.018", HeaderModel.metrics(c.gv))
        assertEquals(SurveyStatus.FIXED, c.gv.status.status)
    }

    @Test fun gstAtOneSecondRateWithJitterStaysFresh() {
        val d = NmeaDriver(DeviceProfiles.SP60).also { it.reset(0) }
        var now = 10_000L
        feed(d, now, gga, gst)
        // GGA 는 매초, GST 는 블루투스 지연으로 2.5초 늦게 와도 5초 기준 안이면 그대로
        now += 2_500
        feed(d, now, gga)
        val r = StatusJudge.judge(d.snapshot, LinkState.STREAMING, now)
        assertEquals(SurveyStatus.FIXED, r.status)
        assertEquals(Math.sqrt(0.000061), r.sigmaHM!!, 1e-12)
    }

    @Test fun averagerDoesNotRecordStaleSigma() {
        val d = NmeaDriver(DeviceProfiles.SP60).also { it.reset(0) }
        feed(d, 1_000, gga, gst)
        feed(d, 20_000, nmea("GPGGA,021549.00,3330.123456,N,12631.654321,E,4,17,0.8,45.123,M,23.456,M,1.0,0000"))
        val s = d.snapshot
        val avg = PointAverager(1)
        assertTrue(avg.add(s.gga!!, s.freshGst(20_000), s.lastGgaAtMs))
        assertNull(avg.result()!!.sigmaH)
    }
}
