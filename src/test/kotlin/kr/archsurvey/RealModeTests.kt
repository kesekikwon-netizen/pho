package kr.archsurvey

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

/** 수신기가 없을 때의 동작: 가짜 위치 없음, 측점 불가, 원인별 VRS 오류 문구, 릴리스 소스에 가짜 클래스 없음 */
class RealModeTests {
    /** 시험용 수신기 대역: 시험이 직접 link/gnss 를 넣는다(앱 소스에는 없는 클래스) */
    private class StubSource : GnssSource {
        override val link = MutableStateFlow(LinkStatus())
        override val gnss = MutableStateFlow(GnssSnapshot())
        override fun clockMs() = 10_000L
        override fun stop() {}
    }

    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private fun ctl(s: GnssSource) = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun idleLinkIsNotConnectedAndBlocksSaving() {
        val r = StatusJudge.judge(GnssSnapshot(), LinkState.IDLE, 10_000L)
        assertEquals(SurveyStatus.UNAVAILABLE, r.status); assertEquals(StatusReason.NOT_CONNECTED, r.reason)
        assertEquals("수신기 없음", r.reason.textKo) // 0.1.30 T24 UX리뷰 문구
        assertEquals(SavePolicy.BLOCK, StatusJudge.savePolicy(r.status))
    }

    @Test fun startsWithoutAnyPositionAndRefusesToMeasure() {
        val c = ctl(StubSource())
        assertEquals(StatusReason.NOT_CONNECTED, c.gv.status.reason)
        assertNull(c.gv.lat); assertNull(c.gv.lon)
        assertNull(c.mapState().current)
        c.onMeasure()
        assertEquals(SurveyController.NO_RECEIVER_MSG, c.message) // 0.1.30 T24
        assertEquals(0, c.points.size); assertEquals(Phase.IDLE, c.phase)
    }

    @Test fun lastPositionIsHiddenWhenLinkDrops() {
        val s = StubSource(); val c = ctl(s)
        val gga = GgaMessage("", "000000", 37.0, 127.0, FixQuality.RTK_FIXED, 21, 0.8, 50.0, 25.0, 1.0, "0001")
        val gst = GstMessage("", 0.01, 0.006, 0.006, 0.012)
        s.link.value = LinkStatus(state = LinkState.STREAMING)
        s.gnss.value = GnssSnapshot(gga, gst, lastGgaAtMs = 10_000L)
        assertEquals(21, c.gv.satellites); assertNotNull(c.gv.lat); assertEquals(0.012, c.gv.sigmaVM!!, 1e-9)
        assertEquals(SurveyStatus.FIXED, c.gv.status.status)
        s.link.value = LinkStatus(state = LinkState.RECONNECTING)
        assertNull(c.gv.lat); assertNull(c.mapState().current)
        s.link.value = LinkStatus()   // 연결 해제
        assertEquals(StatusReason.NOT_CONNECTED, c.gv.status.reason)
    }

    @Test fun ntripErrorsAreSpecificPerCause() {
        fun card(st: NtripState, msg: String) = ErrorCards.ntripCard(NtripStatus(state = st, message = msg))!!
        assertEquals("NT-401", card(NtripState.AUTH_FAILED, "아이디/비밀번호가 맞지 않아요 (401)").code)
        assertEquals("NT-403", card(NtripState.AUTH_FAILED, "접속이 거부됐어요 (403)").code)
        assertEquals("NT-404", card(NtripState.ERROR, "마운트포인트 'X' 를 찾을 수 없어요 (소스테이블이 반환됨)").code)
        assertEquals("NT-301", card(NtripState.RECONNECTING, "후 다시 접속 [UnknownHostException] vrs.example").code)
        assertEquals("NT-302", card(NtripState.RECONNECTING, "후 다시 접속 [ConnectException] Connection refused").code)
        assertEquals("NT-303", card(NtripState.RECONNECTING, "후 다시 접속 [SocketTimeoutException] timed out").code)
        assertEquals("NT-310", card(NtripState.RECONNECTING, "후 다시 접속 [NtripNoDataException] 보정 데이터가 10초 동안 오지 않아요").code)
        assertEquals("NT-311", card(NtripState.RECONNECTING, "후 다시 접속 [NtripProtocolException] 서버가 연결을 닫았어요").code)
        assertNull(ErrorCards.ntripCard(NtripStatus(state = NtripState.RECEIVING)))
    }

    private fun nmea(body: String): String = "$" + body + "*" + "%02X".format(body.fold(0) { a, ch -> a xor ch.code })

    @Test fun gsvGivesSatellitesInViewPerConstellation() {
        val p = NmeaParser()
        val m = p.parseLine(nmea("GPGSV,3,1,12,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45")) as GsvMessage
        assertEquals("GP", m.talker); assertEquals(12, m.satsInView)
        val m2 = p.parseLine(nmea("GLGSV,2,1,07,70,12,034,40,71,60,120,42")) as GsvMessage
        assertEquals("GL", m2.talker); assertEquals(7, m2.satsInView)
        val drv = NmeaDriver(kr.archsurvey.device.DeviceProfiles.R10)
        drv.onBytes((nmea("GPGSV,3,1,12,01,40,083,46") + "\r\n" + nmea("GLGSV,2,1,07,70,12,034,40") + "\r\n").toByteArray(), 0, 0L)
        val b = (nmea("GPGSV,3,1,12,01,40,083,46") + "\r\n" + nmea("GLGSV,2,1,07,70,12,034,40") + "\r\n").toByteArray()
        drv.onBytes(b, b.size, 1L)
        assertEquals(19, drv.snapshot.satsInView)
        assertNull(GnssSnapshot().satsInView)
    }

    @Test fun receiverNmeaOffIsExplained() {
        val cards = ErrorCards.from(LinkStatus(state = LinkState.NEEDS_USER, error = kr.archsurvey.bt.BtError.NO_DATA), null, false)
        assertEquals("BT-130", cards.single().code)
        assertTrue(cards.single().action.contains("NMEA"))
    }

    @Test fun releaseSourceHasNoFakeOrSimulationClasses() {
        val bad = Regex("""\b(class|object|interface)\s+(Fake\w*|Switchable\w*|\w*Simulat\w*)""")
        val hits = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            bad.findAll(f.readText()).map { f.name + ": " + it.value }
        }.toList()
        assertTrue("main 소스에 가짜/시뮬레이션 클래스: $hits", hits.isEmpty())
        val shown = File("src/main/kotlin/kr/archsurvey/ui").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""Text\([^\n]*(가짜|시뮬레이션|예시 점|디자인용)""").findAll(f.readText()).map { f.name + ": " + it.value.take(80) } }.toList()
        // 이전 시험 버전 점 안내 문구(옛 데이터가 있을 때만 보임)는 허용: '가짜 신호' 라는 표현은 없어야 한다
        assertTrue("화면 문구에 시뮬레이션 표현: $shown", shown.isEmpty())
    }

    @Test fun positionTypesSeedHasThreeArchaeologyTypes() {
        val c = ctl(StubSource())
        assertTrue(c.types.containsAll(listOf("유구 경계점", "유물 출토 위치", "그리드 모서리")))
        assertNull(c.addType("토기편"))
        assertNotNull(c.addType("토기편"))               // 중복
        assertNotNull(c.addType("아주아주긴이름이다"))      // 8자 초과
        assertNull(c.renameType("토기편", "토기"))
        assertNull(c.removeType("토기"))
    }
}
