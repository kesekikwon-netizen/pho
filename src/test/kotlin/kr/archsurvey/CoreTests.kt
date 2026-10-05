package kr.archsurvey

import kr.archsurvey.core.*
import kr.archsurvey.data.PointIds
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.ReconnectPolicy
import org.junit.Assert.*
import org.junit.Test

private fun gga(q: Int, time: String = "092751.00", lat: String = "3733.9900", lon: String = "12658.6800", alt: String = "45.5", sep: String = "25.3"): String =
    NmeaParser.build("GNGGA,$time,$lat,N,$lon,E,$q,12,0.9,$alt,M,$sep,M,1.0,0001").trim()

class NmeaParserTest {
    @Test fun canonicalGgaChecksumAndFields() {
        val p = NmeaParser()
        val m = p.parseLine("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47") as GgaMessage
        assertEquals(48.1173, m.lat!!, 1e-5)
        assertEquals(11.516667, m.lon!!, 1e-5)
        assertEquals(FixQuality.SINGLE, m.quality)
        assertEquals(8, m.satellites)
        assertEquals(545.4, m.altitudeMsl!!, 1e-9)
        assertEquals(46.9, m.geoidSeparation!!, 1e-9)
        assertEquals(545.4 + 46.9, m.ellipsoidalHeight!!, 1e-9)
    }

    @Test fun rtkFixedAndFloatMapping() {
        val p = NmeaParser()
        assertEquals(FixQuality.RTK_FIXED, (p.parseLine(gga(4)) as GgaMessage).quality)
        assertEquals(FixQuality.RTK_FLOAT, (p.parseLine(gga(5)) as GgaMessage).quality)
        val g = p.parseLine(gga(4)) as GgaMessage
        assertEquals(1.0, g.correctionAgeSec!!, 1e-9)
        assertEquals("0001", g.baseStationId)
    }

    @Test fun badChecksumIsDropped() {
        val p = NmeaParser()
        val bad = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"
        assertNull(p.parseLine(bad))
        assertEquals(1, p.stats.checksumErrors)
    }

    @Test fun missingChecksumIsRejected() {
        assertNull(NmeaParser().parseLine("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test fun streamingSplitAcrossChunksAndGarbage() {
        val p = NmeaParser()
        val line = gga(4) + "\r\n"
        val bytes = ("xx\u0001garbage" + line + line).toByteArray(Charsets.ISO_8859_1)
        val out = ArrayList<NmeaMessage>()
        var i = 0
        while (i < bytes.size) { val n = minOf(7, bytes.size - i); out += p.feed(bytes.copyOfRange(i, i + n), n); i += n }
        assertEquals(2, out.size)
    }

    @Test fun binaryNoiseDoesNotCrashAndOverflowResets() {
        val p = NmeaParser(maxLine = 128)
        val noise = ByteArray(5000) { (it * 31).toByte() }
        p.feed(noise)
        val longLine = "\$" + "A".repeat(500)
        p.feed(longLine.toByteArray())
        val out = p.feed((gga(4) + "\r\n").toByteArray())
        assertEquals(1, out.size)
    }

    @Test fun gstSigma() {
        val line = NmeaParser.build("GPGST,172814.0,0.006,0.023,0.020,273.6,0.023,0.020,0.031").trim()
        val g = NmeaParser().parseLine(line) as GstMessage
        assertEquals(0.023, g.sigmaLat!!, 1e-9); assertEquals(0.031, g.sigmaAlt!!, 1e-9)
        assertEquals(Math.sqrt(0.023 * 0.023 + 0.020 * 0.020), g.sigmaHoriz!!, 1e-9)
    }

    @Test fun southWestHemisphere() {
        assertEquals(-33.5, NmeaParser.parseCoord("3330.0000", "S", 2)!!, 1e-9)
        assertEquals(-100.25, NmeaParser.parseCoord("10015.0000", "W", 3)!!, 1e-9)
        assertNull(NmeaParser.parseCoord("3399.0000", "N", 2))
    }

    @Test fun noFixGgaHasNoPosition() {
        val m = NmeaParser().parseLine(NmeaParser.build("GPGGA,000000,,,,,0,00,,,M,,M,,").trim()) as GgaMessage
        assertFalse(m.hasPosition)
    }
}

class GgaRelayTest {
    @Test fun gnToGpWithValidChecksum() {
        val out = GgaRelay.forCaster(gga(4))!!
        assertTrue(out.startsWith("\$GPGGA,"))
        assertTrue(out.endsWith("\r\n"))
        assertNotNull(NmeaParser().parseLine(out))
    }
    @Test fun rejectsNonGga() { assertNull(GgaRelay.forCaster(NmeaParser.build("GPGST,1,2,3,4,5,6,7,8"))); assertNull(GgaRelay.forCaster(null)) }
}

class TmProjectionTest {
    // 기준값: pyproj(EPSG:4326 -> EPSG:5185/5186/5187), 허용오차 1 mm
    private fun chk(z: KoreanTmZone, lat: Double, lon: Double, n: Double, e: Double) {
        val r = TmProjection.forward(lat, lon, z)
        assertEquals("N $z $lat,$lon", n, r.northing, 0.001)
        assertEquals("E $z $lat,$lon", e, r.easting, 0.001)
    }
    @Test fun origin() { chk(KoreanTmZone.CENTRAL, 38.0, 127.0, 600000.0, 200000.0) }
    @Test fun central() {
        chk(KoreanTmZone.CENTRAL, 37.5665, 126.9780, 551885.0306, 198056.3667)
        chk(KoreanTmZone.CENTRAL, 35.1796, 129.0756, 288993.7560, 389076.8036)
        chk(KoreanTmZone.CENTRAL, 37.0, 125.5, 490064.9433, 66478.2477)
    }
    @Test fun westAndEast() {
        chk(KoreanTmZone.WEST, 37.5665, 126.9780, 553724.3689, 374759.2927)
        chk(KoreanTmZone.EAST, 35.1796, 129.0756, 287022.7228, 206886.2728)
    }
}

class DeviceProfileTest {
    @Test fun bothModelsRegistered() {
        assertEquals(setOf("trimble_r10", "spectra_sp60"), DeviceProfiles.all.map { it.id }.toSet())
        assertSame(DeviceProfiles.R10, DeviceProfiles.byId("trimble_r10"))
        assertNull(DeviceProfiles.byId("nope")); assertNull(DeviceProfiles.byId(null))
    }
    @Test fun nameFilters() {
        assertTrue(DeviceProfiles.R10.matchesName("R10-5123456789"))
        assertTrue(DeviceProfiles.R10.matchesName("Trimble GNSS"))
        assertFalse(DeviceProfiles.R10.matchesName("SP60"))
        assertTrue(DeviceProfiles.SP60.matchesName("SP60-0123"))
        assertTrue(DeviceProfiles.SP60.matchesName("Spectra Precision"))
        assertFalse(DeviceProfiles.SP60.matchesName(null))
    }
    @Test fun sortPutsMatchesFirst() {
        val l = DeviceProfiles.SP60.sortByRelevance(listOf("JBL", "SP60-1", "Car")) { it }
        assertEquals("SP60-1", l.first())
    }
    @Test fun modelSpecificDifferences() {
        val r = DeviceProfiles.R10; val s = DeviceProfiles.SP60
        assertEquals(0L, r.reconnect.firstConnectDelayMs)
        assertTrue(s.reconnect.firstConnectDelayMs >= 3000)
        assertTrue(s.reconnect.repairHintAfterFailures < r.reconnect.repairHintAfterFailures)
        assertTrue(s.reconnect.maxDelayMs > r.reconnect.maxDelayMs)
        assertTrue(r.nmeaSetupSteps.any { it.contains("Bluetooth 1") && it.contains("2, 3") })
        assertTrue(s.pairingSteps.any { it.contains("위성") })
        assertTrue(s.preConnectChecks.any { it.contains("위성") })
        assertTrue(r.requiredSentences.contains("GGA") && s.requiredSentences.contains("GGA"))
        assertFalse(r.verifiedOnRealDevice || s.verifiedOnRealDevice)
    }
}

class ReconnectPolicyTest {
    @Test fun exponentialBackoffCapped() {
        val p = ReconnectPolicy(1000, 15000, 500, 4)
        assertEquals(0L, p.delayBeforeAttempt(1))
        assertEquals(1500L, p.delayBeforeAttempt(2))
        assertEquals(2500L, p.delayBeforeAttempt(3))
        assertEquals(4500L, p.delayBeforeAttempt(4))
        assertEquals(8500L, p.delayBeforeAttempt(5))
        assertEquals(15500L, p.delayBeforeAttempt(6))
        assertEquals(15500L, p.delayBeforeAttempt(500))
    }
    @Test fun repairHint() {
        val p = DeviceProfiles.SP60.reconnect
        assertFalse(p.shouldShowRepairHint(2)); assertTrue(p.shouldShowRepairHint(3))
    }
    @Test fun firstConnectDelay() {
        assertEquals(3000L, DeviceProfiles.SP60.reconnect.delayBeforeAttempt(1))
    }
}

class NmeaDriverTest {
    @Test fun healthTransitions() {
        // R10: staleAfterMs=5s, reconnectAfterMs=20s (0.1.13)
        val d = NmeaDriver(DeviceProfiles.R10); d.reset(0)
        assertEquals(DataHealth.NO_DATA_YET, d.health(1000))
        assertEquals(DataHealth.STALE, d.health(5500))
        assertEquals(DataHealth.DEAD, d.health(20500))
        val b = (gga(4) + "\r\n").toByteArray()
        d.onBytes(b, b.size, 30000)
        assertEquals(DataHealth.OK, d.health(31000))
        assertEquals(DataHealth.STALE, d.health(36000))
        assertEquals(DataHealth.DEAD, d.health(51000))
        assertEquals(FixQuality.RTK_FIXED, d.snapshot.quality)
        assertTrue(d.missingRequired().isEmpty())
    }
    @Test fun sp60HasLongerThresholds() {
        val d = NmeaDriver(DeviceProfiles.SP60); d.reset(0)
        assertEquals(DataHealth.NO_DATA_YET, d.health(5500))
        assertEquals(DataHealth.STALE, d.health(6500))
    }
    @Test fun missingGgaDetected() {
        val d = NmeaDriver(DeviceProfiles.R10); d.reset(0)
        val b = (NmeaParser.build("GPGST,1.0,0.1,0.2,0.3,10,0.02,0.02,0.04")).toByteArray()
        d.onBytes(b, b.size, 100)
        assertEquals(setOf("GGA"), d.missingRequired())
    }
}

class PointAveragerTest {
    private fun g(t: String, lat: String, q: Int) = NmeaParser().parseLine(gga(q, time = t, lat = lat)) as GgaMessage
    @Test fun averagesAndTracksWorstQuality() {
        val a = PointAverager(3)
        assertTrue(a.add(g("1.00", "3733.9900", 4), null))
        assertFalse("duplicate epoch ignored", a.add(g("1.00", "3733.9900", 4), null))
        a.add(g("2.00", "3733.9902", 5), null)
        assertFalse(a.done)
        a.add(g("3.00", "3733.9904", 4), null)
        assertTrue(a.done)
        val r = a.result()!!
        assertEquals(FixQuality.RTK_FLOAT, r.worstQuality)
        assertFalse(r.allFixed)
        assertEquals(37 + 33.9902 / 60.0, r.lat, 1e-9)
        assertEquals(3, r.epochs)
    }
    @Test fun noPositionIgnored() {
        val a = PointAverager(1)
        val nf = NmeaParser().parseLine(NmeaParser.build("GPGGA,000000,,,,,0,00,,,M,,M,,").trim()) as GgaMessage
        assertFalse(a.add(nf, null)); assertNull(a.result())
    }
}

class PointIdsTest {
    @Test fun increments() {
        assertEquals("P-0043", PointIds.next("P-0042", "P-"))
        assertEquals("A10", PointIds.next("A9", "A"))
        assertEquals("P-0001", PointIds.next(null, "P-"))
        assertEquals("X1", PointIds.next("X", "P-"))
    }
}
