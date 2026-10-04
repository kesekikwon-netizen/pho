package kr.archsurvey

import kotlinx.coroutines.*
import kr.archsurvey.bt.BtError
import kr.archsurvey.data.PointEntity
import kr.archsurvey.export.Formats
import kr.archsurvey.ntrip.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

class NtripUnitTest {
    @Test fun base64MatchesJdk() {
        for (s in listOf("a:b", "user:pass", "ngiiuser:ngii", "한글:pw!", "x:")) {
            val expect = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
            assertEquals(expect, NtripClient.android_free_base64(s.toByteArray()))
        }
    }
    @Test fun requestFormat() {
        val v1 = NtripClient.buildRequest(NtripConfig(host = "h", mountPoint = "M", username = "u", password = "p"), "/M")
        assertTrue(v1.startsWith("GET /M HTTP/1.0\r\n")); assertTrue(v1.contains("Authorization: Basic dTpw")); assertTrue(v1.endsWith("\r\n\r\n"))
        assertFalse(v1.contains("Ntrip-Version"))
        val v2 = NtripClient.buildRequest(NtripConfig(host = "h", mountPoint = "M", username = "u", useV2 = true), "/M")
        assertTrue(v2.contains("Ntrip-Version: Ntrip/2.0"))
    }
    @Test fun chunkedDecodeAcrossBoundaries() {
        val body = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray()
        val d = ChunkedDecoder(); val out = ByteArrayOutputStream()
        var i = 0
        while (i < body.size) { val n = minOf(3, body.size - i); d.decode(body.copyOfRange(i, i + n), n) { b, l -> out.write(b, 0, l) }; i += n }
        assertEquals("hello world", out.toString())
    }
    @Test fun sourcetableParse() {
        val t = "SOURCETABLE 200 OK\r\nSTR;VRS-RTCM32;Seoul;RTCM 3.2;1004(1),1019(1);2;GPS+GLO;NGII;KOR;37.5;127.0;1;0;gnsmart;none;B;N;9600;\r\nSTR;X;a;b\r\nENDSOURCETABLE\r\n"
        val l = Sourcetable.parse(t)
        assertEquals(1, l.size); assertEquals("VRS-RTCM32", l[0].mountPoint); assertTrue(l[0].needsNmea)
    }

    private fun mockCaster(response: ByteArray, gga: AtomicReference<String?>, reqHolder: AtomicReference<String?>): ServerSocket {
        val ss = ServerSocket(0)
        Thread {
            try {
                val s = ss.accept()
                val ins = s.getInputStream(); val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(ins.read().toChar())
                reqHolder.set(sb.toString())
                s.getOutputStream().apply { write(response); flush() }
                // GGA 수신 대기
                s.soTimeout = 3000
                val line = StringBuilder()
                try { while (true) { val c = ins.read(); if (c < 0) break; line.append(c.toChar()); if (line.endsWith("\n")) { gga.set(line.toString()); break } } } catch (_: Exception) {}
                Thread.sleep(300); s.close()
            } catch (_: Exception) {}
        }.start()
        return ss
    }

    @Test fun clientReceivesRtcmAndSendsGga() = runBlocking {
        val gga = AtomicReference<String?>(null); val req = AtomicReference<String?>(null)
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x04, 1, 2, 3, 4, 9, 9, 9)
        val ss = mockCaster("ICY 200 OK\r\n".toByteArray() + rtcm, gga, req)
        val got = ByteArrayOutputStream(); val states = ArrayList<NtripState>()
        val cfg = NtripConfig(host = "127.0.0.1", port = ss.localPort, mountPoint = "VRS", username = "u", password = "p")
        val sent = NmeaParser_gga()
        val r = runCatching {
            withTimeout(8000) {
                NtripClient().run(cfg, 1000, 5000, { sent }, { states += it }, { b, n -> synchronized(got) { got.write(b, 0, n) } })
            }
        }
        ss.close()
        assertNotNull(r.exceptionOrNull())
        assertArrayEquals(rtcm, got.toByteArray())
        assertTrue(states.contains(NtripState.RECEIVING))
        assertTrue(req.get()!!.startsWith("GET /VRS HTTP/1.0"))
        assertEquals(sent, gga.get())
    }

    @Test fun auth401() = runBlocking {
        val ss = mockCaster("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray(), AtomicReference(null), AtomicReference(null))
        val cfg = NtripConfig(host = "127.0.0.1", port = ss.localPort, mountPoint = "VRS", username = "u", password = "bad")
        val r = runCatching { withTimeout(8000) { NtripClient().run(cfg, 1000, 5000, { null }, {}, { _, _ -> }) } }
        ss.close()
        assertTrue(r.exceptionOrNull() is NtripAuthException)
        assertEquals(401, (r.exceptionOrNull() as NtripAuthException).httpCode)
    }

    @Test fun mountNotFoundReturnsSourcetable() = runBlocking {
        val ss = mockCaster("SOURCETABLE 200 OK\r\n\r\nENDSOURCETABLE\r\n".toByteArray(), AtomicReference(null), AtomicReference(null))
        val cfg = NtripConfig(host = "127.0.0.1", port = ss.localPort, mountPoint = "NOPE", username = "u")
        val r = runCatching { withTimeout(8000) { NtripClient().run(cfg, 1000, 5000, { null }, {}, { _, _ -> }) } }
        ss.close()
        assertTrue(r.exceptionOrNull() is NtripMountException)
    }

    @Test fun v2ChunkedResponse() = runBlocking {
        val rtcm = byteArrayOf(0xD3.toByte(), 0, 2, 7, 7)
        val resp = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: gnss/data\r\n\r\n5\r\n").toByteArray() + rtcm + "\r\n0\r\n\r\n".toByteArray()
        val ss = mockCaster(resp, AtomicReference(null), AtomicReference(null))
        val got = ByteArrayOutputStream()
        val cfg = NtripConfig(host = "127.0.0.1", port = ss.localPort, mountPoint = "V", username = "u", useV2 = true)
        runCatching { withTimeout(5000) { NtripClient().run(cfg, 1000, 2000, { null }, {}, { b, n -> got.write(b, 0, n) }) } }
        ss.close()
        assertArrayEquals(rtcm, got.toByteArray())
    }

    @Test fun noDataTimeout() = runBlocking {
        val ss = mockCaster("ICY 200 OK\r\n".toByteArray(), AtomicReference(null), AtomicReference(null))
        val cfg = NtripConfig(host = "127.0.0.1", port = ss.localPort, mountPoint = "V", username = "u")
        val r = runCatching { withTimeout(9000) { NtripClient().run(cfg, 1000, 1500, { null }, {}, { _, _ -> }) } }
        ss.close()
        val ex = r.exceptionOrNull()
        assertTrue(ex.toString(), ex is NtripNoDataException || ex is NtripProtocolException)
    }

    private fun NmeaParser_gga(): String = kr.archsurvey.core.GgaRelay.forCaster(kr.archsurvey.core.NmeaParser.build("GNGGA,092751.00,3733.9900,N,12658.6800,E,4,12,0.9,45.5,M,25.3,M,1.0,0001"))!!
}

typealias IOException_or_Timeout = Throwable

class BtErrorTest {
    @Test fun classify() {
        assertEquals(BtError.PERMISSION_NEARBY, BtError.classify(SecurityException()))
        assertEquals(BtError.CONNECT_REFUSED, BtError.classify(java.io.IOException("Service discovery failed")))
        assertEquals(BtError.CONNECT_TIMEOUT, BtError.classify(java.io.IOException("Connect timeout")))
        assertEquals(BtError.SOCKET_CLOSED, BtError.classify(java.io.IOException("read failed, socket might closed or timeout, read ret: -1")))
        assertEquals(BtError.UNKNOWN, BtError.classify(null))
    }
    @Test fun codesUniqueAndInRange() {
        val codes = BtError.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { Regex("BT-1\\d\\d").matches(it) })
        assertTrue(BtError.BT_OFF.needsUserAction && BtError.NOT_PAIRED.needsUserAction)
        assertFalse(BtError.CONNECT_TIMEOUT.needsUserAction)
    }
}

class FormatsTest {
    private fun pt(memo: String = "a,b \"q\"", id: String = "P-0001") = PointEntity(
        rowId = 1, project = "발굴-1", pointId = id, code = "유구", memo = memo,
        lat = 37.5665, lon = 126.978, ellipsoidalHeight = 100.0, altitudeMsl = 75.0, geoidSeparation = 25.0,
        northing = 551885.0306, easting = 198056.3667, crsEpsg = 5186, fixQuality = 4, allEpochsFixed = true,
        satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.012, sigmaH = 0.0156, sigmaU = 0.02,
        correctionAgeSec = 1.0, baseStationId = "0001", epochs = 5, antennaHeight = 1.8, antennaModel = "TRIMBLE_R10", antennaRefPoint = "bottom_of_antenna_mount", antennaInputMode = "vertical", antennaPcoV = 0.1491, antennaPcoH = 0.0,
        antennaInputValue = 1.8, antennaReceiverApplied = false, groundEllipsoidalHeight = 98.2, groundMsl = 73.2,
        device = "Trimble R10", measuredAtUtcMs = 0L,
    )
    @Test fun csvHasAllAntennaAndHeights() {
        val csv = Formats.pointsCsv(listOf(pt()), mapOf(1L to listOf("photos/a.jpg", "photos/b.jpg")), TimeZone.getTimeZone("Asia/Seoul"))
        assertTrue(csv.startsWith("\uFEFFpoint_id,"))
        val lines = csv.trim().split("\r\n")
        assertEquals(2, lines.size)
        val header = lines[0].removePrefix("\uFEFF").split(",")
        val row = lines[1]
        assertEquals(Formats.POINT_HEADER, header)
        assertTrue(row.contains("98.200")); assertTrue(row.contains("100.000")); assertTrue(row.contains("1.800")); assertTrue(row.contains("bottom_of_antenna_mount")); assertTrue(row.contains("0.1491")); assertTrue(row.contains("TRIMBLE_R10"))
        assertTrue(row.contains("\"a,b \"\"q\"\"\""))
        assertTrue(row.contains("photos/a.jpg;photos/b.jpg"))
        assertTrue(row.contains("1970-01-01T00:00:00Z")); assertTrue(row.contains("1970-01-01 09:00:00"))
        assertTrue(row.contains("551885.031"))
    }
    @Test fun csvInjectionGuard() {
        assertEquals("\"'=cmd\"", Formats.csvEscape("=cmd"))
        assertEquals("plain", Formats.csvEscape("plain"))
    }
    @Test fun noCommaDecimalsEvenInKoreanLocale() {
        val old = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(Formats.pointsCsv(listOf(pt()), emptyMap()).contains("98.200"))
        } finally { java.util.Locale.setDefault(old) }
    }
    @Test fun geoJsonStructure() {
        val j = org.json.JSONObject(Formats.pointsGeoJson(listOf(pt("line\nbreak \"x\"")), mapOf(1L to listOf("photos/a.jpg"))))
        val f = j.getJSONArray("features").getJSONObject(0)
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(126.978, c.getDouble(0), 1e-9); assertEquals(37.5665, c.getDouble(1), 1e-9); assertEquals(100.0, c.getDouble(2), 1e-9)
        val p = f.getJSONObject("properties")
        assertEquals(98.2, p.getDouble("height_ground_ellipsoidal"), 1e-9)
        assertEquals(100.0, p.getDouble("height_apc_ellipsoidal"), 1e-9)
        assertEquals(1.8, p.getDouble("antenna_height_m"), 1e-9)
        assertEquals("bottom_of_antenna_mount", p.getString("antenna_ref_point")); assertEquals(0.1491, p.getDouble("pco_v_m"), 1e-9)
        assertEquals("line\nbreak \"x\"", p.getString("memo"))
        assertEquals("photos/a.jpg", p.getJSONArray("photos").getString(0))
    }
}
