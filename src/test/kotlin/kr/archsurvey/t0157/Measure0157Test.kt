package kr.archsurvey.t0157

import kotlinx.coroutines.channels.Channel
import kr.archsurvey.bt.*
import kr.archsurvey.engine.PrefsJobFolderStore
import kr.archsurvey.job.JobFolderRegistry
import kr.archsurvey.job.JobFolderStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

/** 0.1.57 현장(2026-10-15 17:22) 「측량이 안된다」 회귀 */
class Measure0157Test {
    /** PrefsJobFolderStore 와 같은 인코딩을 쓰는 메모리 저장소 */
    private class Mem : JobFolderStore {
        var json = ""
        override fun all(): Map<String, String> { val o = JSONObject(json.ifBlank { "{}" }); return o.keys().asSequence().associateWith { o.getString(it) } }
        override fun put(job: String, rel: String) { json = PrefsJobFolderStore.encode(all() + mapOf(job to rel)) }
        override fun remove(job: String) { json = PrefsJobFolderStore.encode(all() - job) }
    }

    @Test fun oldPutExpression_wasClassCast() {
        val pair: Any = ("a" to "b")
        assertThrows(ClassCastException::class.java) { @Suppress("UNCHECKED_CAST") (pair as Map<*, *>).size }
    }

    @Test fun registry_assignAndFolderOf_noCrash_roundTrip() {
        val m = Mem(); val r = JobFolderRegistry(m)
        val rel = r.assign("1호 주거지", "유적A")
        assertEquals(rel, r.folderOf("1호 주거지"))
        assertNotNull(r.folderOf("옛 작업"))
        assertEquals(2, m.all().size)
        r.forget("옛 작업"); assertEquals(1, m.all().size)
        assertEquals("a/\"b\"", JSONObject(PrefsJobFolderStore.encode(mapOf("k" to "a/\"b\""))).getString("k"))
    }

    /** R10: 4종을 켜면 GGA 가 멈추는 수신기 → GGA 만 다시 켜서 위치가 온다 */
    @Test fun r10_fullSetStopsGga_fallsBackToGgaOnly() = kotlinx.coroutines.runBlocking {
        val events = Channel<DcolEvent>(64); val gga = AtomicLong(); val sent = mutableListOf<String>()
        var ggaOn = false; var fullOn = false
        val ticker = kotlinx.coroutines.GlobalScope.launchTicker { if (ggaOn && !fullOn) gga.incrementAndGet() }
        val timing = R10AutoTiming(naturalWaitMs = 200, ackWaitMs = 50, probeWaitMs = 400, settleMs = 300, precisionCheckMs = 300)
        val r = R10AutoConfig({ _, l ->
            sent += l; events.send(DcolEvent.Ack)
            when {
                l == "64h GGA 1Hz 포트18" -> ggaOn = true
                l.startsWith("64h GGA+GST") -> fullOn = true
                l.startsWith("64h GST+GSA+GSV 끔") -> fullOn = false
            }
        }, events, { if (it == "GGA") gga.get() else 0L }, { System.nanoTime() / 1_000_000 },
            object : R10PortStore { override fun port(): Int? = 18; override fun save(port: Int) {} }, timing, { 0 }).enableOutput()
        ticker.cancel()
        assertEquals(sent.toString(), R10AutoOutcome.GGA_ONLY_FALLBACK, r.outcome)
        assertTrue(r.gotGga); assertEquals(18, r.port)
        assertEquals(listOf("ENQ", "64h GGA 1Hz 포트18", "64h GGA+GST+GSA+GSV 1Hz 포트18", "64h GST+GSA+GSV 끔 포트18", "64h GGA 1Hz 포트18"), sent)
    }

    @Test fun r10_fullSetKeepsGga_noFallback() = kotlinx.coroutines.runBlocking {
        val events = Channel<DcolEvent>(64); val gga = AtomicLong(); val sent = mutableListOf<String>(); var on = false
        val ticker = kotlinx.coroutines.GlobalScope.launchTicker { if (on) gga.incrementAndGet() }
        val r = R10AutoConfig({ _, l -> sent += l; events.send(DcolEvent.Ack); if (l == "64h GGA 1Hz 포트18") on = true }, events,
            { if (it == "GGA") gga.get() else 0L }, { System.nanoTime() / 1_000_000 },
            object : R10PortStore { override fun port(): Int? = 18; override fun save(port: Int) {} },
            R10AutoTiming(naturalWaitMs = 200, ackWaitMs = 50, probeWaitMs = 400, settleMs = 300), { 0 }).enableOutput()
        ticker.cancel()
        assertEquals(R10AutoOutcome.PORT_FOUND, r.outcome); assertEquals(3, sent.size)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchTicker(tick: () -> Unit) = launch(kotlinx.coroutines.Dispatchers.Default) {
    while (true) { tick(); kotlinx.coroutines.delay(40) }
}
