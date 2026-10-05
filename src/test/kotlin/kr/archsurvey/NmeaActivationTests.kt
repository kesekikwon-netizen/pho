package kr.archsurvey

import kotlinx.coroutines.runBlocking
import kr.archsurvey.bt.NmeaActOutcome
import kr.archsurvey.bt.NmeaActivation
import kr.archsurvey.core.NmeaParser
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.ntrip.RtcmMountCompat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NmeaActivationTests {
    @Test fun sp60CandidatesCoverPortsABandCAndQuery() {
        val c = NmeaActivation.candidates(DeviceProfiles.SP60)
        assertTrue(c.any { it.body.startsWith("PASHQ,PRT") })
        assertTrue(c.any { it.body.startsWith("PASHQ,NMO") })
        assertTrue(c.any { it.body == "PASHS,OUT,C,ON" })
        assertTrue(c.any { it.body == "PASHS,OUT,A,ON" })
        assertTrue(c.any { it.body == "PASHS,OUT,B,ON" })
        assertTrue(c.any { "DIF,PRT,C,RT3" in it.body || "DIF,PRT,C,ALL" in it.body })
        assertTrue(c.any { "GGA,C,ON" in it.body })
        assertTrue(c.any { "GGA,A,ON" in it.body })
        assertTrue(c.any { "GGA,B,ON" in it.body })
        // GSV: 학습 캐시 명시 근거 없음 → 후보에 넣지 않음
        assertTrue(c.none { "GSV" in it.body })
        assertTrue(c.all { it.line().startsWith("$") && "*" in it.line() })
        assertEquals(NmeaParser.build("PASHS,NME,GGA,C,ON,1"), c.first { "GGA,C,ON,1" in it.body }.line())
        assertTrue(DeviceProfiles.SP60.nmeaHealAttempts >= 4)
        assertTrue(DeviceProfiles.SP60.noDataHoldMs >= 15_000)
        assertTrue(DeviceProfiles.SP60.reconnectAfterMs >= 40_000)
    }

    @Test fun r10HasNoAutomaticCommands() {
        assertTrue(NmeaActivation.candidates(DeviceProfiles.R10).isEmpty())
        assertTrue(NmeaActivation.fieldChecklist(DeviceProfiles.R10).contains("웹 UI"))
        assertTrue(NmeaActivation.fieldChecklist(DeviceProfiles.SP60).contains("위성"))
        assertTrue(NmeaActivation.fieldChecklist(DeviceProfiles.SP60).contains("도난경보") || NmeaActivation.fieldChecklist(DeviceProfiles.SP60).contains("시작잠금"))
        assertTrue(NmeaActivation.fieldChecklist(DeviceProfiles.R10).contains("다른") || NmeaActivation.fieldChecklist(DeviceProfiles.R10).contains("도난"))
    }

    @Test fun runReportsNaturalGgaWithoutSending() = runBlocking {
        val r = NmeaActivation.run(
            cmds = NmeaActivation.candidates(DeviceProfiles.SP60),
            write = { fail("쓰면 안 됨") },
            hasGga = { true },
            clock = { 0L },
            naturalWaitMs = 100,
        )
        assertEquals(NmeaActOutcome.NATURAL_GGA, r.outcome)
        assertTrue(r.tried.isEmpty())
    }

    @Test fun runSendsThenSeesGga() = runBlocking {
        val clock = AtomicLong(0)
        val gga = AtomicBoolean(false)
        val written = ArrayList<String>()
        val cmds = NmeaActivation.candidates(DeviceProfiles.SP60).take(2)
        // 자연 대기 0, 첫 쓰기 직후 GGA
        val r = NmeaActivation.run(
            cmds, {
                written.add(String(it))
                gga.set(true)
                clock.addAndGet(50)
            },
            { gga.get() }, { clock.get() },
            naturalWaitMs = 0, perCmdWaitMs = 200, afterAllWaitMs = 50,
        )
        assertEquals(NmeaActOutcome.GGA_SEEN, r.outcome)
        assertTrue(written.isNotEmpty())
        assertTrue(written[0].startsWith("\$PASH"))
    }

    @Test fun r10SkipStillWaits() = runBlocking {
        val r = NmeaActivation.run(
            emptyList(), { fail("no write") }, { false }, { System.currentTimeMillis() },
            naturalWaitMs = 50, afterAllWaitMs = 50,
        )
        assertEquals(NmeaActOutcome.SKIPPED, r.outcome)
    }

    @Test fun isNmeaIssueOnlyForDataErrors() {
        assertTrue(NmeaActivation.isNmeaIssue(kr.archsurvey.bt.BtError.NO_DATA))
        assertTrue(NmeaActivation.isNmeaIssue(kr.archsurvey.bt.BtError.NO_GGA))
        assertFalse(NmeaActivation.isNmeaIssue(kr.archsurvey.bt.BtError.BT_OFF))
        assertFalse(NmeaActivation.isNmeaIssue(null))
    }

    @Test fun sp60RejectsRtcm34Mounts_r10Allows() {
        assertEquals(2, RtcmMountCompat.maxMinor(DeviceProfiles.SP60))
        assertEquals(4, RtcmMountCompat.maxMinor(DeviceProfiles.R10))
        assertEquals(4, RtcmMountCompat.detectMinor("VRS-RTCM34"))
        assertEquals(4, RtcmMountCompat.detectMinor("RTCM 3.4"))
        assertEquals(2, RtcmMountCompat.detectMinor("VRS-RTCM32", "RTCM 3.2"))
        assertTrue(RtcmMountCompat.isTooNew(DeviceProfiles.SP60, "VRS-RTCM34"))
        assertFalse(RtcmMountCompat.isTooNew(DeviceProfiles.R10, "VRS-RTCM34"))
        assertFalse(RtcmMountCompat.isTooNew(DeviceProfiles.SP60, "VRS-RTCM32"))
        val w = RtcmMountCompat.warnIfIncompatible(DeviceProfiles.SP60, "VRS-RTCM34")!!
        assertTrue(w.contains("3.2") && w.contains("3.1") && w.contains("SP60"))
        assertNull(RtcmMountCompat.warnIfIncompatible(DeviceProfiles.R10, "VRS-RTCM34"))
    }

    @Test fun fixQualityNineIsSbasNotNone() {
        assertEquals(kr.archsurvey.core.FixQuality.SBAS, kr.archsurvey.core.FixQuality.fromCode(9))
        assertTrue(kr.archsurvey.core.FixQuality.SBAS.isCorrected)
        assertFalse(kr.archsurvey.core.FixQuality.SBAS.isRtk)
        assertNotEquals(kr.archsurvey.core.FixQuality.NONE, kr.archsurvey.core.FixQuality.fromCode(9))
        val gga = kr.archsurvey.core.NmeaParser().parseLine(
            kr.archsurvey.core.NmeaParser.build("GNGGA,092751.00,3733.9900,N,12658.6800,E,9,12,0.9,45.5,M,25.3,M,,")
        ) as kr.archsurvey.core.GgaMessage
        assertEquals(kr.archsurvey.core.FixQuality.SBAS, gga.quality)
        assertTrue(gga.hasPosition)
    }
}
