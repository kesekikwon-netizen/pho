package kr.archsurvey.t0161

import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.TmProjection
import kr.archsurvey.imports.SmartTopoImport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmartTopoImport0161Test {
    private val head = "포인트명,X(N),Y(E),Z(h),코드명\r\n"

    @Test fun csvOnlyPointGetsRealLatLonNotZero() {
        val tm = TmProjection.forward(37.35267, 127.07880, KoreanTmZone.CENTRAL)
        val c = SmartTopoImport.fromCsv(head + "A,${tm.northing},${tm.easting},50.0,없음\r\n")
        val p = SmartTopoImport.toEntities(c, "j", KoreanTmZone.CENTRAL, emptySet(), 1L).points.single()
        assertEquals(37.35267, p.lat, 1e-8); assertEquals(127.07880, p.lon, 1e-8)
        assertEquals(tm.northing, p.northing, 0.0); assertNotNull(p.geoidSeparation)
    }

    @Test fun inverseRoundTripAllZones() {
        for (z in KoreanTmZone.entries) {
            val lon = when (z) { KoreanTmZone.WEST -> 125.3; KoreanTmZone.CENTRAL -> 127.4; KoreanTmZone.EAST -> 129.1; else -> 130.9 }
            val t = TmProjection.forward(36.1, lon, z)
            val (la, lo) = SmartTopoImport.inverse(t.northing, t.easting, z)!!
            assertEquals(36.1, la, 1e-9); assertEquals(lon, lo, 1e-9)
        }
    }

    @Test fun zeroOrBadLatLonFallsBackToCsvNe() {
        val d = listOf(SmartTopoImport.StPoint("B", lat = 0.0, lon = 0.0, n = 528000.0, e = 206000.0, z = 10.0))
        val p = SmartTopoImport.toEntities(d, "j", KoreanTmZone.CENTRAL, emptySet(), 1L).points.single()
        assertEquals(528000.0, p.northing, 1e-6); assertTrue(p.lat in 32.0..40.0)
        // 좌표 아무것도 없으면 건너뜀
        assertEquals(1, SmartTopoImport.toEntities(listOf(SmartTopoImport.StPoint("C", lat = 0.0, lon = 0.0)), "j", KoreanTmZone.CENTRAL, emptySet(), 1L).skippedNoPosition)
    }

    @Test fun utf8AndCp949CsvBothDecode() {
        val t = head + "토층1,528000,206000,1,담장\r\n"
        assertEquals("토층1", SmartTopoImport.fromCsv(SmartTopoImport.decodeText(t.toByteArray(Charsets.UTF_8))).single().name)
        assertEquals("토층1", SmartTopoImport.fromCsv(SmartTopoImport.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + t.toByteArray(Charsets.UTF_8))).single().name)
        assertEquals("토층1", SmartTopoImport.fromCsv(SmartTopoImport.decodeText(t.toByteArray(SmartTopoImport.CP949))).single().name)
    }

    @Test fun quotedCsvFieldsAndDuplicatesCaseInsensitive() {
        val c = SmartTopoImport.fromCsv(head + "\"P,1\",528000,206000,1,\"벽 \"\"A\"\"\"\r\np,1,528001,206001,1,x\r\n")
        assertEquals("P,1", c[0].name); assertEquals("벽 \"A\"", c[0].code)
        val dup = SmartTopoImport.fromCsv(head + "Q1,528000,206000,1,\r\nq1,528001,206001,1,\r\n")
        assertEquals(1, SmartTopoImport.toEntities(dup, "j", KoreanTmZone.CENTRAL, emptySet(), 1L).skippedDuplicate)
    }

    @Test fun largeZipStreamsAndSkipsOtherEntries() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("job/photos/big.jpg")); z.write(ByteArray(8 * 1024 * 1024)); z.closeEntry()
            z.putNextEntry(ZipEntry("job/Export/job_Data.csv")); z.write((head + "1,528000,206000,1,\r\n").toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        val (_, c) = SmartTopoImport.readZip(bos.toByteArray().inputStream())
        assertEquals(1, c.size)
    }

    @Test(expected = java.io.IOException::class) fun corruptZipThrowsNotCrashSilently() {
        val b = ByteArrayOutputStream().also { ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("a_Data.csv")); z.write(ByteArray(1000) { 7 }); z.closeEntry() } }.toByteArray()
        for (i in 35 until b.size - 40) b[i] = 0x55
        SmartTopoImport.readZip(b)
    }
}
