package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.MountInfo
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.11 전문가 메모 반영: GSV 상세(SkyPlot)·타원체고·VRS 계측·권장 마운트·전자 기포 문구 */
class Gnss0111Test {
    @Test fun gsvParsesSatellitesWithAndWithoutSignalId() {
        val p = NmeaParser()
        val a = p.parseLine(NmeaParser.build("GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00")) as GsvMessage
        assertEquals(3, a.totalMsgs); assertEquals(1, a.msgNum); assertEquals(11, a.satsInView)
        assertEquals(4, a.sats.size)
        assertEquals(SatInfo("GP", 4, 15, 270, 0), a.sats[1])
        // NMEA 4.10: 끝에 신호 ID, 빈 SNR
        val b = p.parseLine(NmeaParser.build("GLGSV,1,1,02,65,45,120,38,66,12,300,,1")) as GsvMessage
        assertEquals(2, b.sats.size); assertNull(b.sats[1].snr); assertEquals(300, b.sats[1].azDeg)
    }

    @Test fun driverKeepsRecentSatellitesOnly() {
        val d = NmeaDriver(DeviceProfiles.R10); d.reset(0)
        val b1 = (NmeaParser.build("GPGSV,1,1,02,03,40,111,45,04,15,270,30") + "\r\n").toByteArray()
        d.onBytes(b1, b1.size, 1_000)
        assertEquals(2, d.snapshot.satsDetail.size)
        val b2 = (NmeaParser.build("GAGSV,1,1,01,11,70,010,48") + "\r\n").toByteArray()
        d.onBytes(b2, b2.size, 20_000)
        assertEquals("10초 넘게 안 보인 위성은 뺀다", listOf("GA:11"), d.snapshot.satsDetail.map { it.talker + ":" + it.prn })
    }

    @Test fun skyModelProjectsAndClassifies() {
        SkyModel.project(90, 0)!!.let { assertEquals(0f, it.first, 1e-6f); assertEquals(0f, it.second, 1e-6f) }
        val e = SkyModel.project(0, 90)!!; assertEquals(1f, e.first, 1e-4f); assertEquals(0f, e.second, 1e-4f)
        assertNull(SkyModel.project(null, 10))
        assertEquals(Constellation.GLONASS, SkyModel.constellation(SatInfo("GN", 70, 10, 10, 30)))
        assertEquals(Constellation.SBAS, SkyModel.constellation(SatInfo("GP", 40, 10, 10, 30)))
        assertEquals("E11", SkyModel.label(SatInfo("GA", 11, 1, 1, 1)))
        val list = listOf(SatInfo("GP", 1, 10, 0, 1), SatInfo("GP", 2, 80, 0, 1), SatInfo("GL", 65, 5, 0, 1))
        assertEquals(listOf(2, 1), SkyModel.table(list, setOf(Constellation.GLONASS), 0).map { it.prn })
        assertEquals(listOf(2), SkyModel.table(list, emptySet(), 15).map { it.prn })
    }

    @Test fun ellipsoidalHeightNeedsBothParts() {
        assertEquals(75.0, GnssView(altM = 50.0, geoidSepM = 25.0).ellipsoidalM!!, 1e-9)
        assertNull(GnssView(altM = 50.0).ellipsoidalM)
    }

    @Test fun ntripMeterShowsRateDelayElapsed() {
        val m = NtripMeter()
        m.update(NtripStatus(NtripState.CONNECTING), 0)
        assertNull(m.elapsedText(0))
        m.update(NtripStatus(NtripState.RECEIVING, rtcmBytes = 0, lastRtcmAtMs = 1_000), 1_000)
        m.update(NtripStatus(NtripState.RECEIVING, rtcmBytes = 2_048, lastRtcmAtMs = 3_000), 3_000)
        assertEquals(1.0, m.kbps!!, 1e-9)
        assertEquals("00:00:02", m.elapsedText(3_000))
        val line = m.line(NtripStatus(NtripState.RECEIVING, rtcmBytes = 2_048, lastRtcmAtMs = 3_000), 3_500)
        assertTrue(line, line.contains("1.0 kB/s") && line.contains("지연 0.5 s") && line.contains("경과 00:00:02"))
        m.update(NtripStatus(NtripState.ERROR), 4_000)
        assertNull("끊기면 경과·속도 초기화", m.kbps); assertNull(m.elapsedText(4_000))
    }

    @Test fun recommendedMountForSp60PicksVrs32() {
        val list = listOf(
            MountInfo("VRS-RTCM31", "", "RTCM 3.1", "", true), MountInfo("VRS-RTCM32", "", "RTCM 3.2", "", true),
            MountInfo("VRS-RTCM34", "", "RTCM 3.4", "", true), MountInfo("RTK-RTCM32", "", "RTCM 3.2", "", false),
        )
        assertEquals("VRS-RTCM32", RecommendedMount.pick(DeviceProfiles.SP60, list, "VRS-RTCM34"))
        assertNull(RecommendedMount.pick(DeviceProfiles.SP60, emptyList(), "VRS-RTCM34"))
    }

    @Test fun tiltCopySaysPhoneSensorNotReceiverImu() {
        assertTrue(TiltCopy.NOTE.contains("폰") && TiltCopy.NOTE.contains("IMU") && TiltCopy.NOTE.contains("보정하지 않"))
        assertEquals(3.49, TiltCopy.offsetCm(1.0), 0.01)
        assertTrue(TiltCopy.offsetLine(2.0).contains("7.0 cm"))
    }
}
