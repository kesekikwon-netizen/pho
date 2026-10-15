package kr.archsurvey.t0158

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kr.archsurvey.comm.CommTestUtil.rtcmFrame
import kr.archsurvey.engine.*
import kr.archsurvey.ntrip.*
import kr.archsurvey.ui.DeviceCorrectionDefaults
import kr.archsurvey.device.DeviceProfiles
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NtripBaseChange0158Test {
    private val cfg = NtripConfig(host = "h", port = 1, mountPoint = "VRS-RTCM34", username = "u", password = "p")

    private class Runner(val now: () -> Long) : NtripRunner {
        val calls = ArrayList<Long>()
        override suspend fun run(cfg: NtripConfig, ggaIntervalMs: Long, noDataTimeoutMs: Long, ggaProvider: () -> String?, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit): Nothing {
            calls += now(); onState(NtripState.CONNECTED)
            val f = rtcmFrame(1005)
            // 처음 접속은 6 s 동안 보정을 보내다가 멈춘다(기준국 변경 뒤 서버가 조용해진 상황)
            if (calls.size == 1) { repeat(6) { onRtcm(f, f.size); delay(1_000) } }
            else while (true) { onRtcm(f, f.size); delay(1_000) }
            awaitCancellation()
        }
    }

    @Test fun baseChangeThenSilence_reconnectsFast_beforeNoDataTimeout() = runTest {
        val clock = { testScheduler.currentTime + 1_000 }
        var base: String? = "0009"
        val st = MutableStateFlow(NtripStatus())
        val runner = Runner(clock)
        val sup = NtripSupervisor(backgroundScope, st, clock, MutableStateFlow(true), { "\$GPGGA,x" }, {}, {}, runner,
            noDataTimeoutMs = 30_000, watchdogPeriodMs = 200, baseIdProvider = { base }, baseChangeGapMs = 3_000)
        sup.start(cfg, true, 5000)
        advanceTimeBy(5_500); base = "0012"
        advanceTimeBy(6_000)
        assertEquals("기준국 변경 뒤 재접속", 2, runner.calls.size)
        assertTrue(runner.calls[1] - runner.calls[0] < 15_000)
    }

    @Test fun baseChangeWithDataContinuing_noReconnect() = runTest {
        val clock = { testScheduler.currentTime + 1_000 }
        var base: String? = "0009"
        val runner = object : NtripRunner {
            var n = 0
            override suspend fun run(cfg: NtripConfig, ggaIntervalMs: Long, noDataTimeoutMs: Long, ggaProvider: () -> String?, onState: (NtripState) -> Unit, onRtcm: (ByteArray, Int) -> Unit): Nothing {
                n++; onState(NtripState.CONNECTED); val f = rtcmFrame(1005); while (true) { onRtcm(f, f.size); delay(500) }
            }
        }
        val sup = NtripSupervisor(backgroundScope, MutableStateFlow(NtripStatus()), clock, MutableStateFlow(true), { null }, {}, {}, runner,
            watchdogPeriodMs = 200, baseIdProvider = { base })
        sup.start(cfg, true, 5000); advanceTimeBy(3_000); base = "0012"; advanceTimeBy(10_000)
        assertEquals(1, runner.n)
    }

    @Test fun kindClassified() = assertEquals(NtripFailureKind.BASE_CHANGED, NtripFailureKind.classify(NtripBaseChangeException("x")))

    @Test fun r10NewSetupDefaultMount34_sp60Unchanged() {
        assertEquals("VRS-RTCM34", DeviceCorrectionDefaults.forProfile(DeviceProfiles.R10_ID)!!.mount)
        assertEquals("VRS-RTCM32", DeviceCorrectionDefaults.forProfile(DeviceProfiles.SP60_ID)!!.mount)
    }
}
