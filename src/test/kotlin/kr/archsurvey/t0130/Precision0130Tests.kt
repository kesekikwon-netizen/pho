package kr.archsurvey.t0130

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kr.archsurvey.bt.*
import kr.archsurvey.comm.CommTestUtil.gga
import kr.archsurvey.comm.CommTestUtil.gst
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.comm.MockSocket
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.NmeaDriver
import kr.archsurvey.core.NmeaParser
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.ntrip.Crc24q
import kr.archsurvey.ui.PrecisionGuide
import kr.archsurvey.ui.PrecisionNoticeGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 0.1.30 T23: SP60 GST/GSA 자동 요청. 가짜 소켓·가상 시간. 실제 SP60 응답 형식은 [현장확인]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrecisionActivatorTest {
    private fun ack() = NmeaParser.build("PASHR,ACK").trim()
    private fun nak() = NmeaParser.build("PASHR,NAK").trim()
    private fun prt(p: String) = NmeaParser.build("PASHR,PRT,$p,6").trim()

    /** 가상 시간 리그: [respond] 가 보낸 본문마다 (지연ms, 응답줄 또는 null, 들어올 문장 종류) 를 정한다 */
    private class Rig(val ts: TestScope, val respond: (String) -> Triple<Long, String?, String?>) {
        val replies = Channel<String>(64)
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val sent = ArrayList<Pair<Long, String>>()
        var inFlight = 0; var maxInFlight = 0
        suspend fun send(body: String) {
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            sent += ts.testScheduler.currentTime to body
            delay(20) // 쓰기에 시간이 걸려도 겹치지 않아야 함
            inFlight--
            val (d, line, type) = respond(body)
            ts.launch { delay(d); line?.let { replies.send(it) }; type?.let { seen += it } }
        }
        suspend fun run(budget: PrecisionBudget = PrecisionBudget(), act: PrecisionActivator = PrecisionActivator({ ts.testScheduler.currentTime })) =
            act.run({ send(it) }, replies, { seen.toSet() }, budget)
    }

    @Test fun success_prtPortB_thenOnlyAllowedCommands_inOrder_oneAtATime() = runTest {
        val r = Rig(this) { b ->
            when {
                b == PrecisionCommands.PRT_QUERY -> Triple(100L, prt("B"), null)
                "GST" in b -> Triple(100L, ack(), "GST")
                "GSV" in b -> Triple(100L, ack(), "GSV")
                else -> Triple(100L, ack(), "GSA")
            }
        }
        val res = r.run()
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome)
        assertEquals('B', res.port)
        // 명령 문자열 고정. 0.1.36(의도된 변경): SkyPlot 용 GSV 가 맨 뒤에 하나 더
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GST,B,ON,1", "PASHS,NME,GSA,B,ON,1", "PASHS,NME,GSV,B,ON,1"), r.sent.map { it.second })
        assertEquals("\$PASHQ,PRT*" + "%02X".format(NmeaParser.checksum("PASHQ,PRT")) + "\r\n", PrecisionCommands.line("PASHQ,PRT"))
        assertEquals(1, r.maxInFlight)
        // 자연 대기 5 s 뒤 첫 명령, 다음 명령은 응답 뒤
        assertTrue(r.sent[0].first >= 5_000)
        assertTrue(r.sent[1].first >= r.sent[0].first + 100)
        assertTrue(r.sent[2].first >= r.sent[1].first + 100)
        assertNotNull(res.firstGstAtMs); assertNotNull(res.firstGsaAtMs)
        assertFalse(res.gstMissing)
    }

    @Test fun alreadyPresent_sendsNothing_andKeepsBudget() = runTest {
        val r = Rig(this) { error("보내면 안 됨") }
        r.seen += setOf("GGA", "GST", "GSA", "GSV")   // 0.1.36: GSV 까지 오면 아무것도 안 보냄
        val b = PrecisionBudget()
        val res = r.run(b)
        assertEquals(PrecisionOutcome.ALREADY_PRESENT, res.outcome)
        assertTrue(r.sent.isEmpty()); assertEquals(0, b.used)
    }

    @Test fun onlyMissingSentenceIsRequested() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(50L, prt("A"), null) else Triple(50L, ack(), "GST") }
        r.seen += setOf("GGA", "GSA", "GSV")
        val res = r.run()
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GST,A,ON,1"), r.sent.map { it.second })
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome)
    }

    @Test fun prtNoReply_fallsBackToC_after1500ms() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(0L, null, null) else Triple(50L, ack(), if ("GST" in b) "GST" else "GSA") }
        val res = r.run()
        assertEquals('C', res.port)
        assertTrue(res.portSource, res.portSource.contains("무응답"))
        assertTrue(r.sent[1].first - r.sent[0].first >= 1_500)
        assertEquals("PASHS,NME,GST,C,ON,1", r.sent[1].second)
    }

    @Test fun prtBadPort_or_badChecksum_or_nak_fallsBackToC() = runTest {
        for (reply in listOf(prt("Z"), prt("E"), "\$PASHR,PRT,B,6*00", nak())) {
            val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(10L, reply, null) else Triple(10L, ack(), if ("GST" in b) "GST" else "GSA") }
            val res = r.run()
            assertEquals(reply, 'C', res.port)
            assertTrue(r.sent.drop(1).all { it.second.contains(",C,ON,1") })
        }
    }

    @Test fun nak_failsWithoutThrowing_andNoticeNeeded() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(10L, prt("C"), null) else Triple(10L, nak(), null) }
        val res = r.run()
        assertEquals(PrecisionOutcome.FAILED_NAK, res.outcome)
        assertTrue(res.gstMissing); assertTrue(res.failed)
    }

    @Test fun ackButNoSentence_isTimeoutFailure_successJudgedBySentenceNotAck() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(10L, prt("C"), null) else Triple(10L, ack(), null) }
        val res = r.run()
        assertEquals(PrecisionOutcome.FAILED_TIMEOUT, res.outcome)
    }

    @Test fun onlyGstArrives_isPartial() = runTest {
        val r = Rig(this) { b -> if (b == PrecisionCommands.PRT_QUERY) Triple(10L, prt("C"), null) else Triple(10L, ack(), if ("GST" in b) "GST" else null) }
        assertEquals(PrecisionOutcome.PARTIAL_GST_ONLY, r.run().outcome)
    }

    @Test fun writeFailure_doesNotThrow() = runTest {
        val act = PrecisionActivator({ testScheduler.currentTime })
        val res = act.run({ throw java.io.IOException("Broken pipe") }, Channel(1), { setOf("GGA") }, PrecisionBudget())
        assertEquals(PrecisionOutcome.FAILED_WRITE, res.outcome)
    }

    @Test fun budget_twoRunsPerDevice_thenNothingSent() = runTest {
        val b = PrecisionBudget()
        repeat(2) { Rig(this) { Triple(10L, null, null) }.run(b) }
        val r3 = Rig(this) { error("세 번째는 보내면 안 됨") }
        val res = r3.run(b)
        assertEquals(PrecisionOutcome.BUDGET_USED, res.outcome); assertTrue(r3.sent.isEmpty())
        assertEquals(2, PrecisionBudget.MAX_RUNS)
        assertSame(PrecisionBudget.forDevice("00:11"), PrecisionBudget.forDevice("00:11"))
        assertNotSame(PrecisionBudget.forDevice("00:11"), PrecisionBudget.forDevice("00:22"))
    }

    @Test fun forbiddenCommandsCannotBeBuilt() {
        for (bad in listOf("PASHS,NME,ALL,C,OFF", "PASHS,PRT,C,9600", "PASHS,RST", "PASHS,INI", "PASHS,OUT,C,ON", "PASHS,DIF,C,ON",
            "PASHS,NME,GGA,C,ON,1", "PASHS,NME,GGA,C,OFF", "PASHS,NME,GST,C,ON,0.5", "PASHS,NME,GST,C,ON,0.2", "PASHS,NME,GST,X,ON,1"))
            assertFalse(bad, PrecisionCommands.isAllowed(bad))
        assertThrows(IllegalArgumentException::class.java) { PrecisionCommands.enable("GGA", 'C') }
        assertThrows(IllegalArgumentException::class.java) { PrecisionCommands.enable("GST", 'Z') }
        for (p in "ABCDI") for (s in listOf("GST", "GSA")) assertTrue(PrecisionCommands.isAllowed(PrecisionCommands.enable(s, p)))
    }
}

/** 응답 줄 모으기 · 정밀도 문장 해석 */
class PrecisionParsingTest {
    @Test fun assembler_splitChunks_interleavedWithNmeaAndRtcm() {
        val a = PashrLineAssembler()
        val prt = NmeaParser.build("PASHR,PRT,B,6")
        val stream = rtcmFrame(1077, 40).toList() + (gga() + prt.substring(0, 7)).toByteArray().toList()
        val out = ArrayList<String>()
        out += a.feed(stream.toByteArray())
        out += a.feed(prt.substring(7, 12).toByteArray())
        out += a.feed((prt.substring(12) + NmeaParser.build("PASHR,ACK")).toByteArray())
        assertEquals(listOf(prt.trim(), NmeaParser.build("PASHR,ACK").trim()), out)
        val p = PashReply.parse(out[0]) as PashReply.Prt
        assertEquals('B', p.port); assertTrue(p.checksumOk)
    }

    private fun drive(vararg lines: String): GnssSnapshot {
        val d = NmeaDriver(DeviceProfiles.SP60)
        val b = lines.joinToString("") { NmeaParser.build(it) }.toByteArray()
        d.onBytes(b, b.size, 1_000)
        return d.snapshot
    }

    @Test fun gst_withEmptyFields2to5_stillGivesSigma() {
        val s = drive("GNGST,092751.00,,,,,0.012,0.010,0.020")
        assertTrue("GST" in s.seenTypes)
        assertEquals(0.012, s.gst!!.sigmaLat!!, 1e-9); assertEquals(0.020, s.gst!!.sigmaAlt!!, 1e-9)
        assertNotNull(s.gst!!.sigmaHoriz)
    }

    @Test fun multiGsa_lastLineWins() {
        val s = drive("GNGSA,A,3,01,02,03,,,,,,,,,,2.5,1.2,2.1,1", "GNGSA,A,3,65,66,,,,,,,,,,,1.8,0.9,1.5,2")
        assertEquals(1.8, s.gsa!!.pdop!!, 1e-9)
    }

    @Test fun talkers_GP_GL_GA_GB_GN_allCountAsGstGsa() {
        for (t in listOf("GP", "GL", "GA", "GB", "GN")) {
            val s = drive("${t}GST,092751.00,1.2,0.01,0.01,0,0.01,0.01,0.02", "${t}GSA,A,3,01,02,03,,,,,,,,,,1.8,0.9,1.5")
            assertTrue(t, s.seenTypes.containsAll(setOf("GST", "GSA")))
        }
    }
}

/** 안내 문구: SP60 실패 1번, R10 은 명령 없이 10초 뒤 안내 1번 */
class PrecisionNoticeTest {
    private fun res(o: PrecisionOutcome, seen: Set<String>) = PrecisionResult(o, 'C', "-", emptyList(), emptyList(), emptyList(), null, null, seen, 0)

    @Test fun sp60FailNoticeOncePerConnection_withCommText() {
        val g = PrecisionNoticeGate()
        g.onUpdate(DeviceProfiles.SP60_ID, LinkState.STREAMING, setOf("GGA"), 0)
        assertEquals("SP60이 정밀도(GST)를 보내지 않아요. 터미널/SPace에서 켜 주세요", g.onActivation(DeviceProfiles.SP60_ID, res(PrecisionOutcome.FAILED_NAK, setOf("GGA"))))
        assertNull(g.onActivation(DeviceProfiles.SP60_ID, res(PrecisionOutcome.FAILED_TIMEOUT, setOf("GGA"))))
        // 재연결 → 다시 한 번 가능
        g.onUpdate(DeviceProfiles.SP60_ID, LinkState.RECONNECTING, emptySet(), 1)
        g.onUpdate(DeviceProfiles.SP60_ID, LinkState.STREAMING, setOf("GGA"), 2)
        assertNotNull(g.onActivation(DeviceProfiles.SP60_ID, res(PrecisionOutcome.BUDGET_USED, setOf("GGA"))))
    }

    @Test fun sp60Success_noNotice() {
        val g = PrecisionNoticeGate()
        g.onUpdate(DeviceProfiles.SP60_ID, LinkState.STREAMING, setOf("GGA"), 0)
        assertNull(g.onActivation(DeviceProfiles.SP60_ID, res(PrecisionOutcome.SUCCESS, setOf("GGA", "GST", "GSA"))))
    }

    @Test fun r10_noGstFor10s_noticeOnce_andNoSigmaHints() {
        val g = PrecisionNoticeGate()
        assertNull(g.onUpdate(DeviceProfiles.R10_ID, LinkState.STREAMING, setOf("GGA"), 0))
        assertNull(g.onUpdate(DeviceProfiles.R10_ID, LinkState.STREAMING, setOf("GGA"), 9_000))
        assertEquals(PrecisionGuide.R10_NO_GST, g.onUpdate(DeviceProfiles.R10_ID, LinkState.STREAMING, setOf("GGA"), 10_000))
        assertNull(g.onUpdate(DeviceProfiles.R10_ID, LinkState.STREAMING, setOf("GGA"), 20_000))
        assertTrue(PrecisionGuide.noSigmaHint(DeviceProfiles.R10_ID)!!.contains("GST"))
        assertTrue(PrecisionGuide.noSigmaHint(DeviceProfiles.SP60_ID)!!.contains("SPace"))
    }
}

/** LinkSupervisor + 가짜 소켓(실시간, 짧은 시간값) */
class PrecisionLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun sp60(chunk: Int = 64, gap: Long = 0) = DeviceProfiles.SP60.copy(staleAfterMs = 5_000, reconnectAfterMs = 60_000, nmeaHealAttempts = 0, noDataHoldMs = 0,
        rtcmWriteChunk = chunk, rtcmWriteGapMs = gap,
        reconnect = ReconnectPolicy(initialDelayMs = 100, maxDelayMs = 400, settleMs = 0, repairHintAfterFailures = 3, firstConnectDelayMs = 0))

    private class Rig(profile: DeviceProfile, scope: CoroutineScope, val budget: PrecisionBudget = PrecisionBudget(), val retry: Channel<Unit>? = null, factory: (Int) -> MockSocket) {
        val link = MutableStateFlow(LinkStatus())
        val gnss = MutableStateFlow(GnssSnapshot())
        val rtcm = Channel<ByteArray>(256)
        val sockets = CopyOnWriteArrayList<MockSocket>()
        val results = CopyOnWriteArrayList<PrecisionResult>()
        val clock = { System.nanoTime() / 1_000_000 }
        val sup = LinkSupervisor(profile, { factory(sockets.size).also { sockets.add(it) } }, clock, link, gnss, rtcm, Channel(Channel.CONFLATED), {}, {},
            watchdogPeriodMs = 50, onPrecisionResult = { results.add(it) }, precisionBudget = budget,
            precision = PrecisionActivator(clock, naturalWaitMs = 300, replyWaitMs = 200, verifyWaitMs = 500, pollMs = 10), precisionRetry = retry)
        val job = scope.launch { sup.run() }
        fun waitFor(ms: Long = 6000, what: String = "", cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
            fail("timeout: $what link=${link.value} results=$results")
        }
    }

    /** 응답하는 SP60 흉내: PRT→B, GST/GSA 켜기→ACK 뒤 그 문장을 보냄 */
    private fun respondingSp60() = MockSocket(writeBehavior = { s, b, o, n ->
        s.written.write(b, o, n)
        val txt = String(b, o, n, Charsets.US_ASCII)
        when {
            "PASHQ,PRT" in txt -> { val l = NmeaParser.build("PASHR,PRT,B,6"); s.push(l.substring(0, 9)); s.push(l.substring(9)) }
            "PASHS,NME,GST" in txt -> s.push(NmeaParser.build("PASHR,ACK") + gst())
            "PASHS,NME,GSA" in txt -> s.push(NmeaParser.build("PASHR,ACK") + NmeaParser.build("GNGSA,A,3,01,02,03,,,,,,,,,,1.8,0.9,1.5"))
            // 0.1.36: SkyPlot 용 GSV
            "PASHS,NME,GSV" in txt -> s.push(NmeaParser.build("PASHR,ACK") + NmeaParser.build("GPGSV,1,1,04,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45"))
        }
    })

    private fun pashLines(w: String) = Regex("""\$(PASH[^*\r\n]*)""").findAll(w).map { it.groupValues[1] }.toList()

    @Test fun sp60_ggaOnly_requestsGstGsa_onceOnPrtPort_andSigmaArrives() {
        val r = Rig(sp60(), scope) { respondingSp60() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        r.waitFor(what = "result") { r.results.size == 1 }
        val res = r.results[0]
        assertEquals(PrecisionOutcome.SUCCESS, res.outcome); assertEquals('B', res.port)
        val cmds = pashLines(r.sockets[0].written.toString("US-ASCII"))
        // 0.1.36(의도된 변경): GSV 하나 더
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GST,B,ON,1", "PASHS,NME,GSA,B,ON,1", "PASHS,NME,GSV,B,ON,1"), cmds)
        assertTrue(cmds.all { PrecisionCommands.isAllowed(it) })
        assertNotNull(r.gnss.value.gst)
        Thread.sleep(800)
        assertEquals("연결당 한 번", 1, r.results.size)
        assertEquals(4, pashLines(r.sockets[0].written.toString("US-ASCII")).size)
    }

    @Test fun r10_neverSendsAnything() {
        val prof = DeviceProfiles.R10.copy(staleAfterMs = 5_000, reconnectAfterMs = 60_000, noDataHoldMs = 0, nmeaHealAttempts = 0)
        val r = Rig(prof, scope) { MockSocket() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        r.waitFor { r.link.value.state == LinkState.STREAMING }
        repeat(15) { Thread.sleep(100); r.sockets[0].push(gga()) }
        assertEquals("R10 에는 아무것도 쓰지 않음", 0, r.sockets[0].written.size())
        assertTrue(r.results.isEmpty())
    }

    @Test fun commandsNeverInsideRtcmFrame() {
        val r = Rig(sp60(chunk = 16, gap = 3), scope) { respondingSp60() }
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        val frames = (0 until 60).map { rtcmFrame(1077 + (it % 3), 120, fill = it) }
        // 0.1.36: 명령 줄 바이트를 정확히(PRT + GST·GSA·GSV) — 다 쓰기 전에 읽으면 마지막 프레임이 잘림
        val cmdBytes = (listOf(PrecisionCommands.PRT_QUERY) + PrecisionCommands.SENTENCES.map { PrecisionCommands.enable(it, 'B') }).sumOf { PrecisionCommands.line(it).length }
        scope.launch { for (f in frames) { r.rtcm.send(f); delay(15) } }
        r.waitFor(ms = 10_000, what = "result") { r.results.isNotEmpty() }
        r.waitFor(ms = 10_000, what = "all frames") { r.sockets[0].written.size() >= frames.sumOf { it.size } + cmdBytes }
        // 쓴 바이트를 앞에서부터: RTCM 프레임(CRC 확인) 또는 $…CRLF 명령 줄만 있어야 한다
        val w = r.sockets[0].written.toByteArray()
        var i = 0; var nFrames = 0; val cmds = ArrayList<String>()
        while (i < w.size) {
            if ((w[i].toInt() and 0xFF) == 0xD3) {
                val len = ((w[i + 1].toInt() and 0x03) shl 8) or (w[i + 2].toInt() and 0xFF)
                val body = w.copyOfRange(i, i + 3 + len)
                val crc = ((w[i + 3 + len].toInt() and 0xFF) shl 16) or ((w[i + 4 + len].toInt() and 0xFF) shl 8) or (w[i + 5 + len].toInt() and 0xFF)
                assertEquals("프레임 $nFrames CRC(중간에 명령이 끼면 깨짐)", Crc24q.compute(body), crc)
                i += 6 + len; nFrames++
            } else {
                assertEquals('$'.code, w[i].toInt())
                val end = (i until w.size).first { w[it] == '\n'.code.toByte() }
                cmds += String(w, i, end - i + 1, Charsets.US_ASCII).trim(); i = end + 1
            }
        }
        assertEquals(60, nFrames)
        assertEquals(4, cmds.size)   // 0.1.36: PRT·GST·GSA·GSV
    }

    @Test fun reconnect_runsAgainOnce_thenBudgetStopsThirdConnection() {
        val budget = PrecisionBudget()
        val r = Rig(sp60(), scope, budget) { MockSocket() } // 응답 없는 SP60
        for (k in 0 until 3) {
            r.waitFor(what = "socket $k") { r.sockets.size == k + 1 }
            r.sockets[k].push(gga())
            r.waitFor(what = "result $k") { r.results.size == k + 1 }
            r.sockets[k].eof()
        }
        val perSocket = r.sockets.take(3).map { s -> pashLines(s.written.toString("US-ASCII")).count { it == "PASHQ,PRT" } }
        assertEquals(listOf(1, 1, 0), perSocket)
        assertEquals(PrecisionOutcome.BUDGET_USED, r.results[2].outcome)
        assertEquals(PrecisionOutcome.FAILED_TIMEOUT, r.results[0].outcome)
        assertEquals('C', r.results[0].port)
        // 연결은 실패해도 유지(끊은 것은 시험의 eof)
        assertTrue(r.sockets.take(3).flatMap { pashLines(it.written.toString("US-ASCII")) }.all { PrecisionCommands.isAllowed(it) })
    }

    /** 0.1.30 T23-b: 사용자 [자동 켜기 다시] → 자동 상한과 별도로 한 순서만, 같은 잠금·순서 */
    @Test fun userRetry_runsExactlyOneMoreSequence() {
        val retry = Channel<Unit>(Channel.CONFLATED)
        val budget = PrecisionBudget(); repeat(2) { budget.tryAcquire() } // 자동 상한을 이미 다 씀
        val r = Rig(sp60(), scope, budget, retry) { MockSocket() } // 응답 없는 SP60
        r.waitFor { r.sockets.size == 1 }
        r.sockets[0].push(gga())
        r.waitFor(what = "auto") { r.results.size == 1 }
        assertEquals(PrecisionOutcome.BUDGET_USED, r.results[0].outcome)
        assertEquals(0, pashLines(r.sockets[0].written.toString("US-ASCII")).size)
        retry.trySend(Unit)
        r.waitFor(what = "retry") { r.results.size == 2 }
        Thread.sleep(500)
        assertEquals(2, r.results.size)
        assertEquals(listOf("PASHQ,PRT", "PASHS,NME,GST,C,ON,1", "PASHS,NME,GSA,C,ON,1", "PASHS,NME,GSV,C,ON,1"), pashLines(r.sockets[0].written.toString("US-ASCII")))
        assertEquals(PrecisionOutcome.FAILED_TIMEOUT, r.results[1].outcome)
    }
}
