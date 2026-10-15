package kr.archsurvey.t0133

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.*
import kr.archsurvey.comm.CommTestUtil
import kr.archsurvey.comm.MockSocket
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.NmeaDriver
import kr.archsurvey.core.NmeaParser
import kr.archsurvey.core.SatInfo
import kr.archsurvey.device.*
import kr.archsurvey.engine.LinkSupervisor
import kr.archsurvey.ntrip.Crc24q
import kr.archsurvey.ui.Constellation
import kr.archsurvey.ui.SkyModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

/** 0.1.33 R10 자동설정: 패킷 바이트 — Trimble 공식 예시 + 연구 단계 검증 출력(build-reports/r10-autoconfig/dcol_packets.out.txt) */
class TrimbleDcolPacket0133Tests {
    @Test fun genericPacket_reproducesOfficialTrimbleExamples() {
        // receiverhelp.trimble.com/oem-gnss config-receiver-gnss-setup-oem.html · AEh 예시
        for (e in listOf(
            "02 00 64 19 00 00 00 03 00 01 00 02 04 01 05 00 00 07 04 06 01 03 00 07 04 0D 01 03 00 BE 03",
            "02 00 64 0A 00 00 00 03 00 01 01 0A 01 01 7F 03",
            "02 00 AE 01 00 AF 03",
            "02 00 AE 02 0E 16 D4 03",
        )) {
            val b = hex(e)
            assertArrayEquals(e, b, TrimbleDcol.packet(b[2].toInt() and 0xFF, b.copyOfRange(4, b.size - 2)))
        }
    }

    @Test fun appFileOutputs_matchVerifiedBuilderBytes() {
        assertArrayEquals(byteArrayOf(0x05), TrimbleDcol.enq())
        val v = mapOf(
            TrimbleDcol.setOutputs(0, 17, listOf("GGA"), true) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 11 03 00 9A 03",
            TrimbleDcol.setOutputs(0, 17, listOf("GGA"), false) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 11 00 00 97 03",
            TrimbleDcol.setOutputs(0, 18, listOf("GGA"), true) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 12 03 00 9B 03",
            TrimbleDcol.setOutputs(0, 18, listOf("GGA"), false) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 12 00 00 98 03",
            TrimbleDcol.setOutputs(0, 19, listOf("GGA"), true) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 13 03 00 9C 03",
            TrimbleDcol.setOutputs(0, 19, listOf("GGA"), false) to "02 00 64 0D 00 00 00 03 00 01 00 07 04 06 13 00 00 99 03",
            TrimbleDcol.setOutputs(0, 17, R10AutoConfig.FULL_SET, true) to
                "02 00 64 1F 00 00 00 03 00 01 00 07 04 06 11 03 00 07 04 0D 11 03 00 07 04 26 11 03 00 07 04 12 11 03 00 4E 03",
            TrimbleDcol.setOutputs(0, 18, R10AutoConfig.FULL_SET, true) to
                "02 00 64 1F 00 00 00 03 00 01 00 07 04 06 12 03 00 07 04 0D 12 03 00 07 04 26 12 03 00 07 04 12 12 03 00 52 03",
            TrimbleDcol.setOutputs(0, 19, R10AutoConfig.FULL_SET, true) to
                "02 00 64 1F 00 00 00 03 00 01 00 07 04 06 13 03 00 07 04 0D 13 03 00 07 04 26 13 03 00 07 04 12 13 03 00 56 03",
            TrimbleDcol.setOutputs(0, 18, listOf("GGA", "GST"), true) to "02 00 64 13 00 00 00 03 00 01 00 07 04 06 12 03 00 07 04 0D 12 03 00 CE 03",
        )
        for ((got, want) in v) assertEquals(want, TrimbleDcol.hex(got))
    }

    @Test fun transmissionNumber_changesByteAndChecksum() {
        val p0 = TrimbleDcol.setOutputs(0, 18, listOf("GGA"), true)
        val p5 = TrimbleDcol.setOutputs(5, 18, listOf("GGA"), true)
        assertEquals(5, p5[4].toInt())
        assertEquals(((p0[p0.size - 2].toInt() and 0xFF) + 5) and 0xFF, p5[p5.size - 2].toInt() and 0xFF)
        assertEquals(0x2C, TrimbleDcol.setOutputs(300, 18, listOf("GGA"), true)[4].toInt() and 0xFF) // 300 mod 256
    }

    @Test fun dangerousRecordsCannotBeBuilt() {
        for (t in listOf(0xFF, 0x00, 0x0A /*GSOF*/, 0x01)) assertThrows(IllegalArgumentException::class.java) { TrimbleDcol.outputRecord(t, 18, 3) }
        for (p in listOf(0, 1, 15, 16, 20, 0xFF)) assertThrows(IllegalArgumentException::class.java) { TrimbleDcol.outputRecord(TrimbleDcol.NMEA_GGA, p, 3) }
        for (f in listOf(0xFF, 1, 2, 4)) assertThrows(IllegalArgumentException::class.java) { TrimbleDcol.outputRecord(TrimbleDcol.NMEA_GGA, 18, f) }
        val p = TrimbleDcol.setOutputs(1, 18, R10AutoConfig.FULL_SET, true)
        assertEquals("즉시 적용", 0x01, p[9].toInt()); assertEquals("공장값 리셋 안 함", 0x00, p[10].toInt())
        assertEquals(0x64, p[2].toInt())
    }

    @Test fun scanner_findsAckNakAndFrames_ignoresNmea_rejectsBadChecksum() {
        val s = DcolScanner()
        val nmea = CommTestUtil.gga().toByteArray()
        val good = TrimbleDcol.packet(0x40, byteArrayOf(1, 2, 3))
        val bad = good.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        val ev = s.feed(nmea + byteArrayOf(0x06) + nmea + byteArrayOf(0x15) + good + nmea + bad + byteArrayOf(0x06))
        assertEquals(listOf("ACK", "NAK", "FRAME 40h len=3", "ACK"), ev.map { it.toString() })
        assertEquals(1, s.badFrames)
        // 바이트 단위로 쪼개 넣어도 같다
        val s2 = DcolScanner(); val all = byteArrayOf(0x06) + good
        val ev2 = all.flatMap { s2.feed(byteArrayOf(it)) }
        assertEquals(listOf("ACK", "FRAME 40h len=3"), ev2.map { it.toString() })
    }

    @Test fun nmeaParser_ignoresAckNakBytesAndDcolFrames() {
        val p = NmeaParser()
        val frame = TrimbleDcol.packet(0x57, ByteArray(40) { (it * 7).toByte() }) // 바이너리(0x24 '$'·0x0A 포함 가능)
        val garbage = byteArrayOf(0x24, 0x47, 0x0A, 0x0D, 0x00, 0x7F, 0x2A)
        var stream = ByteArray(0)
        repeat(20) { i ->
            stream += CommTestUtil.gga("0927%02d.00".format(i)).toByteArray()
            stream += byteArrayOf(0x06); stream += frame; stream += byteArrayOf(0x15); stream += garbage
            stream += CommTestUtil.gst().toByteArray()
        }
        val msgs = stream.toList().chunked(37).flatMap { c -> p.feed(c.toByteArray()) }
        assertEquals(20, msgs.count { it is kr.archsurvey.core.GgaMessage })
        assertEquals(20, msgs.count { it is kr.archsurvey.core.GstMessage })
        assertEquals("바이너리 때문에 체크섬 오류를 세지 않는다", 0, p.stats.checksumErrors)
    }

    @Test fun engineEnablesAutoConfigOnlyForR10() {
        val st = object : R10PortStore { override fun port(): Int? = null; override fun save(port: Int) {} }
        assertNotNull(R10AutoOptions.forProfile(DeviceProfiles.R10, st))
        assertNull("SP60 경로는 그대로", R10AutoOptions.forProfile(DeviceProfiles.SP60, st))
        assertTrue("SP60 문자열 명령 후보 그대로", NmeaActivation.candidates(DeviceProfiles.SP60).isNotEmpty())
        assertTrue(NmeaActivation.candidates(DeviceProfiles.R10).isEmpty())
    }

    @Test fun r10HelpText_autoFirst_webUiFallback() {
        val r = DeviceProfiles.R10
        assertTrue(r.nmeaSetupTitle.contains("자동"))
        assertTrue(r.nmeaSetupSteps.first().contains("자동"))
        assertTrue(r.nmeaSetupSteps.any { it.contains("실패") && it.contains("192.168.142.1") })
        assertTrue(r.noDataHint.contains("자동으로 켜 보았지만") && r.noDataHint.contains("웹 UI"))
        assertTrue(r.preConnectChecks.first().contains("자동"))
    }
}

/** Trimble GSV(위성군을 이어 번호 매김) — SkyPlot·보이는 위성 수 */
class TrimbleGsv0133Tests {
    private val doc = listOf( // receiverhelp.trimble.com/oem-gnss/nmea0183-messages-gsv.html 예시 그대로
        "\$GPGSV,8,1,25,21,44,141,47,15,14,049,44,6,31,255,46,3,25,280,44*75",
        "\$GPGSV,8,2,25,18,61,057,48,22,68,320,52,27,34,268,47,24,32,076,45*76",
        "\$GPGSV,8,3,25,14,51,214,49,19,23,308,46*7E",
        "\$GPGSV,8,4,25,51,44,183,49,46,41,169,43,48,36,220,45*47",
        "\$GLGSV,8,5,25,82,49,219,52,76,22,051,41,83,37,316,51,67,57,010,51*6C",
        "\$GLGSV,8,6,25,77,24,108,44,81,10,181,46,78,1,152,34,66,18,060,45*50",
        "\$GLGSV,8,7,25,68,37,284,50*5C",
    )

    @Test fun trimbleCombinedGsv_satsInViewNotDoubleCounted_andAllSatsParsed() {
        val d = NmeaDriver(DeviceProfiles.R10)
        val b = doc.joinToString("") { it + "\r\n" }.toByteArray()
        d.onBytes(b, b.size, 1_000)
        assertEquals("문서 예시는 전체 25개(GP+GL 을 더해 50 이 되면 안 됨)", 25, d.snapshot.satsInView)
        assertEquals("문서 예시는 8개 중 7개 문장만 실려 있어 위성 22개", 22, d.snapshot.satsDetail.size)
        // 다음 주기도 그대로
        d.onBytes(b, b.size, 2_000)
        assertEquals(25, d.snapshot.satsInView)
        val gl = d.snapshot.satsDetail.filter { SkyModel.constellation(it) == Constellation.GLONASS }
        assertEquals(9, gl.size)
        assertEquals("R18", SkyModel.label(SatInfo("GL", 82, 49, 219, 52)))
        assertEquals("C05", SkyModel.label(SatInfo("GB", 105, 10, 10, 40)))
        assertEquals(Constellation.SBAS, SkyModel.constellation(SatInfo("GP", 51, 44, 183, 49)))
    }

    @Test fun perConstellationGsv_stillSummed() {
        val d = NmeaDriver(DeviceProfiles.R10)
        val b = (NmeaParser.build("GPGSV,1,1,09,01,10,100,40") + NmeaParser.build("GLGSV,1,1,06,65,10,100,40")).toByteArray()
        d.onBytes(b, b.size, 1); d.onBytes(b, b.size, 2)
        assertEquals(15, d.snapshot.satsInView)
    }
}

/**
 * 가짜 R10: 받은 바이트를 RTCM(D3)·DCOL(02)·ENQ(05)로 나눠 해석하고, 폰이 붙은 [btPort] 에 켜진 문장을 [periodMs] 마다 보낸다.
 * 끼어 쓰기(RTCM 프레임 중간에 명령)나 쓰레기 바이트가 있으면 [violations] 에 남는다.
 */
class FakeR10(
    val btPort: Int = 18,
    val mode: Mode = Mode.ACCEPT,
    val enqAck: Boolean = true,
    val natural: Set<String> = emptySet(),
    val binaryNoise: Boolean = false,
    val periodMs: Long = 100,
) {
    enum class Mode { ACCEPT, NAK_ALL, SILENT }
    data class AppFile(val tx: Int, val records: List<Triple<String, Int, Int>>) // (문장, 포트, 주기)

    val socket = MockSocket(writeBehavior = { s, b, o, n -> s.written.write(b, o, n); onWrite(b, o, n) })
    val enabled = HashMap<Int, MutableSet<String>>()
    val appFiles = CopyOnWriteArrayList<AppFile>()
    val violations = CopyOnWriteArrayList<String>()
    val enqCount = AtomicInteger(0)
    val rtcmFrames = AtomicInteger(0)
    val ggaSent = AtomicInteger(0)
    private val buf = java.io.ByteArrayOutputStream()
    private val running = AtomicBoolean(true)
    private val names = mapOf(0x06 to "GGA", 0x0D to "GST", 0x26 to "GSA", 0x12 to "GSV")

    init {
        Thread {
            var i = 0
            while (running.get() && !socket.closed.get()) {
                Thread.sleep(periodMs)
                val on = synchronized(enabled) { natural + (enabled[btPort] ?: emptySet()) }
                if (mode == Mode.SILENT || on.isEmpty()) continue
                val t = "0927%02d.00".format(i++ % 60)
                val sb = java.io.ByteArrayOutputStream()
                fun noise() { if (binaryNoise) sb.write(byteArrayOf(0x06, 0x15) + TrimbleDcol.packet(0x40, byteArrayOf(0x24, 0x0A, 0x2A)) + byteArrayOf(0x00, 0x7F)) }
                if ("GGA" in on) { sb.write(CommTestUtil.gga(t).replace("GNGGA", "GPGGA").let { NmeaParser.build(it.substring(1, it.indexOf('*'))) }.toByteArray()); ggaSent.incrementAndGet(); noise() }
                if ("GST" in on) { sb.write(NmeaParser.build("GPGST,$t,1.2,0.01,0.01,0,0.010,0.010,0.020").toByteArray()); noise() }
                if ("GSA" in on) { sb.write(NmeaParser.build("GNGSA,A,3,01,02,03,04,05,06,,,,,,,1.5,0.9,1.2").toByteArray()); noise() }
                if ("GSV" in on) {
                    sb.write(NmeaParser.build("GPGSV,2,1,05,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45").toByteArray())
                    sb.write(NmeaParser.build("GLGSV,2,2,05,70,12,034,40").toByteArray()); noise()
                }
                socket.push(sb.toByteArray())
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() = running.set(false)

    private fun reply(b: Int) = socket.push(byteArrayOf(b.toByte()))

    @Synchronized private fun onWrite(b: ByteArray, o: Int, n: Int) {
        buf.write(b, o, n)
        var a = buf.toByteArray()
        var p = 0
        loop@ while (p < a.size) {
            when (a[p].toInt() and 0xFF) {
                0x05 -> { enqCount.incrementAndGet(); if (enqAck && mode != Mode.SILENT) reply(0x06); p++ }
                0xD3 -> {
                    if (a.size - p < 3) break@loop
                    val len = ((a[p + 1].toInt() and 0x03) shl 8) or (a[p + 2].toInt() and 0xFF)
                    if (a.size - p < len + 6) break@loop
                    val crc = Crc24q.compute(a, p, len + 3)
                    val got = ((a[p + len + 3].toInt() and 0xFF) shl 16) or ((a[p + len + 4].toInt() and 0xFF) shl 8) or (a[p + len + 5].toInt() and 0xFF)
                    if (crc != got) violations += "RTCM CRC 깨짐(끼어 쓰기?) at $p"
                    rtcmFrames.incrementAndGet(); p += len + 6
                }
                0x02 -> {
                    if (a.size - p < 4) break@loop
                    val len = a[p + 3].toInt() and 0xFF
                    if (a.size - p < len + 6) break@loop
                    val pk = a.copyOfRange(p, p + len + 6); p += len + 6
                    handleDcol(pk)
                }
                else -> { violations += "쓰레기 바이트 %02X at $p".format(a[p]); p++ }
            }
        }
        buf.reset(); if (p < a.size) buf.write(a, p, a.size - p)
    }

    private fun handleDcol(pk: ByteArray) {
        var cs = 0; for (i in 1 until pk.size - 2) cs += pk[i].toInt() and 0xFF
        if ((cs and 0xFF) != (pk[pk.size - 2].toInt() and 0xFF) || pk.last().toInt() != 0x03) { violations += "DCOL 체크섬/ETX"; return }
        val type = pk[2].toInt() and 0xFF
        if (type != 0x64) { violations += "허용 안 된 명령 %02Xh".format(type); return }
        val d = pk.copyOfRange(4, pk.size - 2)
        if (d[1].toInt() != 0 || d[2].toInt() != 0) violations += "페이지"
        if (d[3].toInt() != 3 || d[5].toInt() != 1 || d[6].toInt() != 0) violations += "파일제어블록(즉시적용/공장값) 이상"
        val recs = ArrayList<Triple<String, Int, Int>>()
        var i = 7
        while (i < d.size) {
            if (d[i].toInt() != 0x07 || d[i + 1].toInt() != 0x04) { violations += "출력 레코드 아님"; return }
            val t = d[i + 2].toInt() and 0xFF; val port = d[i + 3].toInt() and 0xFF; val f = d[i + 4].toInt() and 0xFF
            val name = names[t]
            if (name == null) violations += "위험한 메시지 종류 %02X".format(t)
            if (port !in 17..19) violations += "블루투스 아닌 포트 $port"
            if (f != 0 && f != 3) violations += "주기 $f"
            recs += Triple(name ?: "?%02X".format(t), port, f); i += 6
        }
        appFiles += AppFile(d[0].toInt() and 0xFF, recs)
        when (mode) {
            Mode.SILENT -> {}
            Mode.NAK_ALL -> reply(0x15)
            Mode.ACCEPT -> {
                synchronized(enabled) {
                    for ((name, port, f) in recs) { val s = enabled.getOrPut(port) { HashSet() }; if (f == 3) s += name else s -= name }
                }
                reply(0x06)
            }
        }
    }
}

class R10AutoConfigStateMachine0133Tests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakes = CopyOnWriteArrayList<FakeR10>()
    @After fun tearDown() { scope.cancel(); fakes.forEach { it.stop() } }

    private val fast = R10AutoTiming(naturalWaitMs = 400, ackWaitMs = 200, probeWaitMs = 600, settleMs = 400, precisionCheckMs = 900)

    private class Store(var p: Int? = null) : R10PortStore {
        val saves = CopyOnWriteArrayList<Int>()
        override fun port(): Int? = p
        override fun save(port: Int) { p = port; saves += port }
    }

    private inner class Rig(val fake: FakeR10, val store: Store = Store(), chunk: Int = 256, gap: Long = 20, dead: Long = 6_000, probeWhenNatural: Boolean = false) {
        val link = MutableStateFlow(LinkStatus())
        val gnss = MutableStateFlow(GnssSnapshot())
        val rtcm = Channel<ByteArray>(256)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val results = CopyOnWriteArrayList<R10AutoResult>()
        val profile = DeviceProfiles.R10.copy(
            staleAfterMs = 3_000, reconnectAfterMs = dead, noDataHoldMs = 0, nmeaHealAttempts = 0,
            rtcmWriteChunk = chunk, rtcmWriteGapMs = gap,
            reconnect = ReconnectPolicy(initialDelayMs = 100, maxDelayMs = 400, settleMs = 0, repairHintAfterFailures = 3),
        )
        val sup = LinkSupervisor(profile, { fake.socket }, { System.nanoTime() / 1_000_000 }, link, gnss, rtcm, wake, {}, {},
            watchdogPeriodMs = 50, r10Auto = R10AutoOptions(store, fast, probeWhenNatural), onR10Auto = { results += it })
        val job = scope.launch { sup.run() }
        init { fakes += fake }
        fun waitFor(ms: Long = 8_000, what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
            fail("timeout: $what link=${link.value} results=${results.map { it.summary() }} appfiles=${fake.appFiles} viol=${fake.violations}")
        }
        fun stopAll() { job.cancel(); fake.stop() }
    }

    private fun assertTxIncreasing(f: FakeR10) {
        val tx = f.appFiles.map { it.tx }
        for ((a, b) in tx.zipWithNext()) assertEquals("전송 번호는 1씩 증가: $tx", (a + 1) and 0xFF, b)
    }

    @Test fun acceptsOnPort18_probes17Off_thenEnablesFullSetOn18_andSaves() {
        val r = Rig(FakeR10(btPort = 18))
        r.waitFor(what = "enable+precision results") { r.results.size >= 2 }
        val a = r.results[0]
        assertEquals(a.summary(), R10AutoOutcome.PORT_FOUND, a.outcome); assertEquals(18, a.port)
        assertEquals("ACK", a.enq)
        assertEquals(R10AutoOutcome.PRECISION_OK, r.results[1].outcome)
        assertEquals(listOf(18), r.store.saves)
        assertEquals(1, r.fake.enqCount.get())
        assertEquals(
            listOf(
                listOf(Triple("GGA", 17, 3)), listOf(Triple("GGA", 17, 0)), listOf(Triple("GGA", 18, 3)),
                listOf(Triple("GST", 18, 3)), listOf(Triple("GSA", 18, 3)), listOf(Triple("GSV", 18, 3)), // 0.1.58 B1: 한 개씩
            ),
            r.fake.appFiles.map { it.records },
        )
        assertTrue(r.fake.violations.toString(), r.fake.violations.isEmpty())
        assertTxIncreasing(r.fake)
        assertTrue("포트 18 은 끄지 않음", r.fake.appFiles.flatMap { it.records }.none { it.second == 18 && it.third == 0 })
        assertEquals(LinkState.STREAMING, r.link.value.state)
        assertTrue(r.gnss.value.seenTypes.containsAll(setOf("GGA", "GST", "GSA", "GSV")))
        assertTrue("GSV → SkyPlot 위성", r.gnss.value.satsDetail.isNotEmpty())
        assertEquals(5, r.gnss.value.satsInView)
        assertTrue(a.seenAfter.containsAll(setOf("GGA", "GST", "GSA", "GSV")))
        r.stopAll()
    }

    @Test fun savedPortTriedFirst_noOtherPortsTouched() {
        val r = Rig(FakeR10(btPort = 18), Store(18))
        r.waitFor(what = "result") { r.results.isNotEmpty() }
        assertEquals(R10AutoOutcome.PORT_FOUND, r.results[0].outcome)
        assertEquals(18, r.fake.appFiles.first().records.single().second)
        assertTrue(r.fake.appFiles.flatMap { it.records }.all { it.second == 18 })
        r.stopAll()
    }

    @Test fun savedPortWrong_fallsBackAndOverwritesSavedPort() {
        val r = Rig(FakeR10(btPort = 19), Store(18))
        r.waitFor(what = "result") { r.results.isNotEmpty() }
        assertEquals(R10AutoOutcome.PORT_FOUND, r.results[0].outcome); assertEquals(19, r.results[0].port)
        assertEquals(listOf(18, 17, 19), r.fake.appFiles.filter { it.records.size == 1 && it.records[0].first == "GGA" && it.records[0].third == 3 }.map { it.records[0].second })
        assertEquals(19, r.store.p)
        r.stopAll()
    }

    @Test fun allNak_noOffPackets_webUiHelpShown() {
        val r = Rig(FakeR10(mode = FakeR10.Mode.NAK_ALL), dead = 2_500)
        r.waitFor(what = "result") { r.results.isNotEmpty() }
        assertEquals(R10AutoOutcome.ALL_NAK, r.results[0].outcome)
        assertEquals(9, r.fake.appFiles.size) // 0.1.58 B1: 포트 3개 × (1 + NAK 재시도 2)
        assertTrue("NAK 이면 켜진 게 없으니 끄지 않는다", r.fake.appFiles.flatMap { it.records }.all { it.third == 3 })
        assertTrue(r.results[0].tried.all { it.endsWith(":NAK") })
        assertTrue(r.store.saves.isEmpty())
        r.waitFor(what = "needs user") { r.link.value.state == LinkState.NEEDS_USER }
        assertEquals(kr.archsurvey.bt.BtError.NO_DATA, r.link.value.error)
        assertTrue(r.link.value.message, r.link.value.message.contains("웹 UI") && r.link.value.message.contains("자동으로 켜 보았지만"))
        r.stopAll()
    }

    @Test fun silentReceiver_triesAllPorts_offOnlyWhatItTurnedOn() {
        val r = Rig(FakeR10(mode = FakeR10.Mode.SILENT))
        r.waitFor(what = "result") { r.results.isNotEmpty() }
        val a = r.results[0]
        assertEquals(R10AutoOutcome.NOT_FOUND, a.outcome); assertEquals("-", a.enq)
        assertEquals(
            listOf(17 to 3, 17 to 0, 18 to 3, 18 to 0, 19 to 3, 19 to 0),
            r.fake.appFiles.map { it.records.single().let { x -> x.second to x.third } },
        )
        assertTrue(r.fake.appFiles.all { it.records.single().first == "GGA" })
        assertTxIncreasing(r.fake)
        r.stopAll()
    }

    /** 0.1.34(사용자 SmartTopo 분석): 자연 NMEA 가 오면 64h 를 보내지 않는다 — GST/GSA 가 없어도 기록·안내만 */
    @Test fun naturalGgaWithoutGst_default_sendsNothing_reportsMissing() {
        val r = Rig(FakeR10(btPort = 18, natural = setOf("GGA")))
        r.waitFor(what = "precision result") { r.results.size >= 2 }
        assertEquals(R10AutoOutcome.NATURAL, r.results[0].outcome)
        assertEquals(R10AutoOutcome.PRECISION_MISSING_NATURAL, r.results[1].outcome)
        Thread.sleep(300)
        assertEquals("자연 NMEA 면 아무 바이트도 쓰지 않음", 0, r.fake.socket.written.size())
        assertTrue(r.fake.appFiles.isEmpty()); assertEquals(0, r.fake.enqCount.get())
        assertTrue(r.store.saves.isEmpty())
        r.stopAll()
    }

    /** 선택 기능(probeWhenNatural=true, 기본 꺼짐): 0.1.33 의 GST 포트 탐색 보충 — GGA 는 절대 안 건드림 */
    @Test fun naturalGgaWithoutGst_optIn_neverTouchesGga_addsPrecisionOnFoundPort() {
        val r = Rig(FakeR10(btPort = 18, natural = setOf("GGA")), probeWhenNatural = true)
        r.waitFor(what = "precision result") { r.results.any { it.outcome != R10AutoOutcome.NATURAL } }
        assertEquals(R10AutoOutcome.NATURAL, r.results[0].outcome)
        val b = r.results.last()
        assertEquals(b.summary(), R10AutoOutcome.PRECISION_ADDED, b.outcome); assertEquals(18, b.port)
        assertEquals("자연 NMEA 면 ENQ 도 안 보냄", 0, r.fake.enqCount.get())
        assertTrue("GGA 레코드는 하나도 보내지 않음", r.fake.appFiles.flatMap { it.records }.none { it.first == "GGA" })
        assertEquals(
            listOf(listOf(Triple("GST", 17, 3)), listOf(Triple("GST", 17, 0)), listOf(Triple("GST", 18, 3)),
                listOf(Triple("GST", 18, 3)), listOf(Triple("GSA", 18, 3)), listOf(Triple("GSV", 18, 3))),
            r.fake.appFiles.map { it.records },
        )
        val g0 = r.fake.ggaSent.get()
        r.waitFor(what = "gga keeps flowing") { r.fake.ggaSent.get() > g0 + 3 }
        assertTrue(r.gnss.value.seenTypes.containsAll(setOf("GST", "GSA", "GSV")))
        assertEquals(listOf(18), r.store.saves)
        assertTrue(r.fake.violations.isEmpty())
        r.stopAll()
    }

    @Test fun naturalFullNmea_sendsNothing() {
        val r = Rig(FakeR10(natural = setOf("GGA", "GST", "GSA")))
        r.waitFor(what = "precision ok") { r.results.size >= 2 }
        assertEquals(R10AutoOutcome.NATURAL, r.results[0].outcome)
        assertEquals(R10AutoOutcome.PRECISION_OK, r.results[1].outcome)
        assertEquals(0, r.fake.socket.written.size())
        r.stopAll()
    }

    @Test fun commandsNeverInterleaveWithRtcmFrames() {
        // RTCM 을 처음부터 계속 밀어 넣는 동안 포트 탐색(17·18 실패 후 19)·4종 켜기가 일어난다
        val r = Rig(FakeR10(btPort = 19), chunk = 32, gap = 3)
        val pump = scope.launch {
            var n = 0
            while (isActive) { r.rtcm.send(CommTestUtil.rtcmFrame(1077 + (n++ % 3), 200 + n % 50)); delay(15) }
        }
        r.waitFor(ms = 12_000, what = "port found") { r.results.any { it.outcome == R10AutoOutcome.PORT_FOUND } }
        pump.cancel()
        Thread.sleep(300)
        assertTrue(r.fake.violations.toString(), r.fake.violations.isEmpty())
        assertTrue("RTCM 이 실제로 섞여 흘렀다: ${r.fake.rtcmFrames.get()}", r.fake.rtcmFrames.get() > 20)
        assertTrue(r.fake.appFiles.size >= 4)
        r.stopAll()
    }

    @Test fun binaryBytesInStream_doNotBreakNmea() {
        val r = Rig(FakeR10(btPort = 18, binaryNoise = true))
        r.waitFor(what = "results") { r.results.size >= 2 }
        assertEquals(R10AutoOutcome.PORT_FOUND, r.results[0].outcome)
        assertEquals(R10AutoOutcome.PRECISION_OK, r.results[1].outcome)
        Thread.sleep(500)
        val s = r.gnss.value
        assertEquals("바이너리·ACK/NAK 바이트로 체크섬 오류 없음", 0L, s.checksumErrors)
        assertTrue(s.seenTypes.containsAll(setOf("GGA", "GST", "GSA", "GSV")))
        assertTrue(r.fake.violations.isEmpty())
        r.stopAll()
    }
}
