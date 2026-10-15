package kr.archsurvey.t0159

import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.TmProjection
import kr.archsurvey.imports.SmartTopoImport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 합성 SmartTopo 표본(실제 현장 자료는 저장소에 넣지 않는다) */
class SmartTopoImport0159Test {
    private val cols = listOf("Pointname", "Pointcode", "Pointinfo", "Pointcount", "Pointmemo", "Antenah", "Coordinate", "Haccuracy", "Vaccuracy",
        "Latitude", "Longitude", "Starttime", "Endtime", "PDOP", "Epoch", "Satellites", "Elevation", "Device", "MeasureTo")

    private fun dbf(rows: List<List<String>>, deletedRow: Int = -1): ByteArray {
        val len = 30; val cs = SmartTopoImport.CP949
        val out = ByteArrayOutputStream()
        val headerLen = 32 + 32 * cols.size + 1; val recLen = 1 + len * cols.size
        val h = ByteArray(32); h[0] = 3
        fun le(a: ByteArray, o: Int, v: Int, n: Int) { for (i in 0 until n) a[o + i] = (v shr (8 * i)).toByte() }
        le(h, 4, rows.size, 4); le(h, 8, headerLen, 2); le(h, 10, recLen, 2); out.write(h)
        for (c in cols) { val f = ByteArray(32); c.toByteArray().copyInto(f); f[11] = 'C'.code.toByte(); f[16] = len.toByte(); out.write(f) }
        out.write(0x0D)
        rows.forEachIndexed { i, r ->
            out.write(if (i == deletedRow) '*'.code else ' '.code)
            for (v in r) { val b = ByteArray(len) { ' '.code.toByte() }; v.toByteArray(cs).copyInto(b); out.write(b) }
        }
        out.write(0x1A); return out.toByteArray()
    }

    private fun row(name: String, info: String = "점", memo: String = "", lat: String = "37.35267", lon: String = "127.07880") =
        listOf(name, "없음", info, "1", memo, "1.6", "3", "0.025", "0.051", lat, lon, "20250424081525", "20250424081530", "3.770", "5", "10", "123.252", "GPS", "퀵릴리즈 하단")

    private val csv = "포인트명 ,X(N) ,Y(E) ,Z(h) ,코드명 \r\n1,528155.514,206982.164,100.0416,없음\r\n9,528000.0,206000.0,90.5,담장\r\n"

    @Test fun readsDbfCp949AndSkipsStarDeleted() {
        val rows = SmartTopoImport.readDbf(dbf(listOf(row("1", memo = "1호 석곽"), row("X"), row("2")), deletedRow = 1))
        assertEquals(2, rows.size)
        assertEquals("1호 석곽", rows[0]["Pointmemo"]); assertEquals("퀵릴리즈 하단", rows[0]["MeasureTo"])
    }

    @Test fun mergesAndConvertsToJobCrs() {
        val d = SmartTopoImport.fromDbf(SmartTopoImport.readDbf(dbf(listOf(row("1", memo = "석곽"), row("2", info = "삭제점"), row("3")))))
        val c = SmartTopoImport.fromCsv(csv)
        val all = SmartTopoImport.merge(d, c)
        assertEquals(listOf("1", "2", "3", "9"), all.map { it.name })
        val r = SmartTopoImport.toEntities(all, "작업", KoreanTmZone.CENTRAL, existing = setOf("3"), nowMs = 1L)
        assertEquals(1, r.skippedDeleted); assertEquals(1, r.skippedDuplicate)
        assertEquals(listOf("1", "9"), r.points.map { it.pointId })
        val p1 = r.points[0]
        val tm = TmProjection.forward(37.35267, 127.07880, KoreanTmZone.CENTRAL)
        assertEquals(tm.northing, p1.northing, 1e-6); assertEquals(5186, p1.crsEpsg)
        assertEquals(100.0416, p1.groundMsl!!, 1e-9); assertEquals(123.252, p1.groundEllipsoidalHeight!!, 1e-9)
        assertEquals(0.025, p1.sigmaH!!, 1e-9); assertEquals(0.051, p1.sigmaU!!, 1e-9)
        assertEquals(1.6, p1.antennaHeight, 1e-9); assertEquals("퀵릴리즈 하단", p1.antennaRefPoint)
        assertEquals("석곽", p1.memo); assertTrue(p1.device.startsWith("SmartTopo"))
        // 2025-04-24 08:15:30 KST
        assertEquals(1745450130000L, p1.measuredAtUtcMs)
        // CSV 만 있는 점: N/E 그대로, 코드명은 메모로
        assertEquals(528000.0, r.points[1].northing, 0.0); assertEquals("담장", r.points[1].memo)
        assertNotNull(r.maxNeDiffM)
    }

    @Test fun readsZipIgnoringStakeOut() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, SmartTopoImport.CP949).use { z ->
            z.putNextEntry(ZipEntry("job/없음_point.dbf")); z.write(dbf(listOf(row("1")))); z.closeEntry()
            z.putNextEntry(ZipEntry("job/UserFile/job_StakeOutList_point.dbf")); z.write(dbf(listOf(row("S1")))); z.closeEntry()
            z.putNextEntry(ZipEntry("job/Export/job_Data.csv")); z.write(csv.toByteArray(SmartTopoImport.CP949)); z.closeEntry()
        }
        val (d, c) = SmartTopoImport.readZip(bos.toByteArray())
        assertEquals(listOf("1"), d.map { it.name }); assertEquals(2, c.size)
    }
}
