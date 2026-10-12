package kr.archsurvey.t0141

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.archsurvey.bt.*
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.t0140.FakeConn
import kr.archsurvey.t0140.FakePair
import kr.archsurvey.ui.ConnectionController
import kr.archsurvey.ui.PairController
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 0.1.41(측량) · 0.1.3(사진 기준점): 등록 없이 연결된 R10 이 GGA 를 안 보낼 때 */
@OptIn(ExperimentalCoroutinesApi::class)
class R10Unbonded0141Test {
    private val fast = R10AutoTiming(naturalWaitMs = 200, ackWaitMs = 100, probeWaitMs = 300, settleMs = 200, precisionCheckMs = 300)

    /** 스크립트 R10: 명령마다 응답, GGA 가 켜진 포트에 따라 문장 개수 증가 */
    private class Script(val ack: Boolean = true, val serial: String? = "5411457757", val ggaOnPorts: Set<Int> = emptySet(), val ggaOnlyWhenAll: Boolean = false) {
        val events = Channel<DcolEvent>(64)
        val counts = ConcurrentHashMap<String, AtomicLong>()
        val sent = mutableListOf<String>()
        val on = mutableSetOf<Int>()
        fun count(t: String) = counts[t]?.get() ?: 0L
        suspend fun send(b: ByteArray, label: String) {
            sent += label
            when {
                b.size == 1 && b[0].toInt() == TrimbleDcol.ENQ -> if (ack) events.send(DcolEvent.Ack)
                (b[2].toInt() and 0xFF) == TrimbleDcol.TYPE_GETSERIAL -> serial?.let { events.send(DcolEvent.Frame(0, TrimbleDcol.TYPE_RSERIAL, (it.take(8)).toByteArray() + ByteArray(20))) }
                (b[2].toInt() and 0xFF) == 0x64 -> {
                    if (ack) events.send(DcolEvent.Ack)
                    // 레코드: 07 04 TYPE PORT FREQ OFF (데이터 7바이트 뒤부터)
                    val d = b.copyOfRange(4, b.size - 2); var i = 7; val ports = mutableSetOf<Int>(); var gga = false; var onFlag = false
                    while (i < d.size) { if (d[i + 2].toInt() == TrimbleDcol.NMEA_GGA) { gga = true; ports += d[i + 3].toInt(); onFlag = d[i + 4].toInt() == 3 }; i += 6 }
                    if (gga && onFlag) on += ports
                    val emit = if (ggaOnlyWhenAll) on.containsAll(TrimbleDcol.BT_PORTS) && ports.size == 3 else ports.any { it in ggaOnPorts }
                    if (gga && onFlag && emit) counts.getOrPut("GGA") { AtomicLong() }.addAndGet(1)
                }
            }
        }
    }

    private suspend fun runAuto(s: Script, extended: Boolean): R10AutoResult {
        var tx = 0
        return R10AutoConfig({ b, l -> s.send(b, l) }, s.events, { s.count(it) }, { System.nanoTime() / 1_000_000 },
            object : R10PortStore { override fun port(): Int? = null; override fun save(port: Int) {} }, fast, { tx++ and 0xFF }, extended = extended).enableOutput()
    }

    @Test fun getSerialPacket_isReadOnly06h() {
        assertEquals("02 00 06 00 06 03", TrimbleDcol.hex(TrimbleDcol.getSerial()))
        assertEquals("5411457757".take(8), TrimbleDcol.serialText("54114577".toByteArray() + byteArrayOf(0, 1)))
        val p = TrimbleDcol.setOutputsPorts(0, listOf(17, 18, 19), listOf("GGA"), true)
        assertEquals(3 * 6 + 7, p[3].toInt())
        assertThrows(IllegalArgumentException::class.java) { TrimbleDcol.setOutputsPorts(0, listOf(17, 20), listOf("GGA"), true) }
    }

    @Test fun extended_ackButNoGga_reportsTwoWaySerial_triesAllPortsOnce_thenOff() = kotlinx.coroutines.runBlocking {
        val s = Script(ggaOnPorts = emptySet())
        val r = runAuto(s, true)
        assertEquals(R10AutoOutcome.NOT_FOUND, r.outcome)
        assertEquals("RSERIAL 54114577", r.twoWay)
        assertTrue(r.summary(), r.summary().contains("양방향=RSERIAL"))
        assertEquals(listOf("ENQ", "GETSERIAL", "64h GGA 1Hz 포트17", "64h GGA 끔 포트17", "64h GGA 1Hz 포트18", "64h GGA 끔 포트18",
            "64h GGA 1Hz 포트19", "64h GGA 끔 포트19", "64h GGA 1Hz 포트17·18·19", "64h GGA 끔 포트17·18·19"), s.sent)
    }

    @Test fun extended_noSerialReply_saysNoReply() = kotlinx.coroutines.runBlocking {
        val r = runAuto(Script(ack = false, serial = null), true)
        assertEquals("응답없음", r.twoWay); assertEquals("-", r.enq)
    }

    @Test fun extended_allPortsAtOnce_canFindGga() = kotlinx.coroutines.runBlocking {
        val s = Script(ggaOnlyWhenAll = true)
        val r = runAuto(s, true)
        assertEquals(R10AutoOutcome.PORT_FOUND, r.outcome); assertNull(r.port); assertTrue(r.gotGga)
        assertEquals("64h 4종 1Hz 포트17·18·19", s.sent.last())
    }

    @Test fun notExtended_oldSequenceUnchanged() = kotlinx.coroutines.runBlocking {
        val s = Script()
        val r = runAuto(s, false)
        assertEquals("-", r.twoWay)
        assertFalse(s.sent.contains("GETSERIAL")); assertFalse(s.sent.any { "17·18·19" in it })
    }

    @Test fun engineUsesExtendedOnlyForR10() {
        val st = object : R10PortStore { override fun port(): Int? = null; override fun save(port: Int) {} }
        assertTrue(R10AutoOptions.forProfile(DeviceProfiles.R10, st)!!.extended)
        assertNull(R10AutoOptions.forProfile(DeviceProfiles.SP60, st))
    }

    // ---- 등록 창: 등록 없이 연결됐지만 GGA 없음 → 자동으로 등록 → 그래도 안 되면 R10 웹 NMEA 안내 ----
    @Test fun directSocketButNoGga_fallsBackToBond_thenBondedConnect() = runTest(StandardTestDispatcher()) {
        val fc = FakeConn(); val fp = FakePair()
        val cc = ConnectionController(fc, backgroundScope)
        val pc = PairController(fp, cc, backgroundScope, retryGapMs = 1_000); cc.pair = pc
        val r10 = FoundDevice("R10, 5411457757: Trimble", "AA:11", false, -60)
        fp.scanResults = listOf(r10); pc.openSheet(); runCurrent()
        pc.tap(r10)
        assertTrue(fp.bondCalls.isEmpty())
        fc.link.value = LinkStatus(LinkState.CONNECTED_NO_DATA); advanceTimeBy(600); runCurrent()
        assertEquals("소켓만으로는 성공 아님", PairStep.CONNECTING_UNBONDED, pc.step)
        fc.link.value = LinkStatus(LinkState.STALE, error = BtError.NO_DATA); advanceTimeBy(600); runCurrent()
        assertEquals(PairStep.BONDING, pc.step)
        assertTrue(PairText.step(pc.step, pc.machine).contains("위치가 안 와"))
        assertTrue(fc.direct.isEmpty())
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf("AA:11"), fp.bondCalls)
        fc.link.value = LinkStatus()
        assertEquals("1234", fp.request("AA:11", PairConst.VARIANT_PIN)); fp.bonded("AA:11"); runCurrent()
        assertEquals(PairStep.CONNECTING, pc.step)
        fc.link.value = LinkStatus(LinkState.STREAMING); advanceTimeBy(600); runCurrent()
        assertEquals(PairStep.DONE, pc.step)
    }

    @Test fun directNoGga_andBondFails_showsR10WebNmeaCard() {
        val m = PairMachine(FoundDevice("R10, 5411457757: Trimble", "AA:11", false), PairList.pins("1234"))
        m.start(false)
        assertEquals(PairAction.CreateBond("AA:11"), m.onConnectResult(false, noData = true))
        m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1)
        m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1)
        assertEquals(PairFix.R10_WEB_NMEA, m.fix)
        val (t, b) = PairText.fix(m.fix, "R10")!!
        assertTrue(t.contains("위치")); assertTrue(b.contains("Maintain configuration") && b.contains("192.168.142.1") && b.contains("Bluetooth"))
    }
}
