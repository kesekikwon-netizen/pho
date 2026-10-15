package kr.archsurvey.core0120

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kr.archsurvey.bt.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.58 B1: R10 명령은 한 개씩 · ACK 대기 · 200 ms 간격 · NAK 재시도 2번 */
class R10Serial0120Test {
    private val store = object : R10PortStore { override fun port(): Int? = 18; override fun save(port: Int) {} }

    @Test fun defaults() {
        val t = R10AutoTiming()
        assertTrue(t.ackWaitMs <= 1_000); assertEquals(200L, t.minGapMs); assertEquals(2, t.nakRetries)
    }

    @Test fun commandsSpacedAtLeastGap_andOnePerSentence() = runBlocking {
        val events = Channel<DcolEvent>(64); val times = mutableListOf<Long>(); val sent = mutableListOf<String>()
        var gga = 0L; var on = false
        val clock = { System.nanoTime() / 1_000_000 }
        val r = R10AutoConfig({ _, l -> times += clock(); sent += l; events.send(DcolEvent.Ack); if (l == "64h GGA 1Hz 포트18") on = true }, events,
            { if (it == "GGA" && on) ++gga else gga }, clock, store,
            R10AutoTiming(naturalWaitMs = 100, ackWaitMs = 100, probeWaitMs = 300, settleMs = 100, minGapMs = 200), { 0 }).enableOutput()
        assertEquals(R10AutoOutcome.PORT_FOUND, r.outcome)
        assertTrue(sent.none { it.contains("+") || it.contains("4종") })
        for ((a, b) in times.zipWithNext()) assertTrue("간격 ${b - a}ms $sent", b - a >= 195)
    }

    @Test fun nakRetriedTwice_thenGivesUp() = runBlocking {
        val events = Channel<DcolEvent>(64); val sent = mutableListOf<String>()
        val r = R10AutoConfig({ _, l -> sent += l; events.send(if (l == "ENQ") DcolEvent.Ack else DcolEvent.Nak) }, events, { 0L },
            { System.nanoTime() / 1_000_000 }, store,
            R10AutoTiming(naturalWaitMs = 50, ackWaitMs = 50, probeWaitMs = 50, settleMs = 50, minGapMs = 10), { 0 }).enableOutput()
        assertEquals(R10AutoOutcome.ALL_NAK, r.outcome)
        assertEquals(3, sent.count { it == "64h GGA 1Hz 포트18" })
        assertEquals(1 + 9, sent.size)
    }

    @Test fun nakThenAck_succeedsOnRetry() = runBlocking {
        val events = Channel<DcolEvent>(64); val sent = mutableListOf<String>(); var n = 0; var gga = 0L; var on = false
        val r = R10AutoConfig({ _, l -> sent += l
            if (l == "64h GGA 1Hz 포트18") { n++; if (n == 1) events.send(DcolEvent.Nak) else { events.send(DcolEvent.Ack); on = true } } else events.send(DcolEvent.Ack)
        }, events, { if (it == "GGA" && on) ++gga else gga }, { System.nanoTime() / 1_000_000 }, store,
            R10AutoTiming(naturalWaitMs = 50, ackWaitMs = 50, probeWaitMs = 200, settleMs = 50, minGapMs = 10), { 0 }).enableOutput()
        assertEquals(18, r.port); assertEquals(2, n)
    }
}
