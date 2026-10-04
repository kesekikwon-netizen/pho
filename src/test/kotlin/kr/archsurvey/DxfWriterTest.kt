package kr.archsurvey

import kr.archsurvey.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DxfWriterTest {
    private val pts = listOf(
        DxfPoint("P-0001", 200101.234, 550201.567, 41.235),
        DxfPoint("P-0002", 200103.456, 550201.890, 41.198),
        DxfPoint("경계점 3", 200103.789, 550204.012, 41.172),
        DxfPoint("P-0004", 200101.012, 550204.321, null),
    )
    private fun pairs(dxf: String): List<Pair<Int, String>> {
        val l = dxf.split("\n"); val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i + 1 < l.size) { l[i].trim().toIntOrNull()?.let { out.add(it to l[i + 1]) }; i += 2 }
        return out
    }

    @Test fun headerHasVersionUnitsCodepage() {
        val d = DxfWriter.build(pts)
        assertTrue(d.contains("\$ACADVER\n  1\nAC1015\n"))
        assertTrue(d.contains("\$INSUNITS\n 70\n6\n"))
        assertTrue(d.contains("\$DWGCODEPAGE\n  3\nANSI_949\n"))
        assertTrue(d.trimEnd().endsWith("EOF"))
    }

    @Test fun groupCodePairsAreWellFormed() {
        val d = DxfWriter.build(pts, DxfOptions(closedOutline = true))
        val lines = d.split("\n").dropLast(1) // 마지막 개행 뒤 빈 줄
        assertEquals(0, lines.size % 2)
        for (i in lines.indices step 2) assertNotNull("코드 줄 $i", lines[i].trim().toIntOrNull())
    }

    @Test fun onlyAsciiLayerNamesAndExpectedEntities() {
        val d = DxfWriter.build(pts, DxfOptions(closedOutline = true))
        val layers = pairs(d).filter { it.first == 8 }.map { it.second }.toSet()
        assertTrue(layers.all { it.all { c -> c.code < 128 } })
        assertEquals(setOf("0", "PT", "PT_NO", "PT_ELEV", "PT_LINE"), layers)
        val types = pairs(d).filter { it.first == 0 }.map { it.second }
        assertEquals(4, types.count { it == "POINT" })
        assertEquals(4 + 3, types.count { it == "TEXT" }) // 번호 4 + 표고 3(표고 없는 점 제외)
        assertEquals(1, types.count { it == "LWPOLYLINE" })
        val i = pairs(d).indexOfFirst { it.second == "LWPOLYLINE" }
        assertTrue(pairs(d).drop(i).take(12).contains(70 to "1")) // 폐합
    }

    @Test fun axisOrderSwapsXY() {
        fun firstPoint(d: String): Triple<String, String, String> {
            val p = pairs(d); val i = p.indexOfFirst { it.second == "POINT" }
            val sub = p.drop(i)
            return Triple(sub.first { it.first == 10 }.second, sub.first { it.first == 20 }.second, sub.first { it.first == 30 }.second)
        }
        assertEquals(Triple("200101.2340", "550201.5670", "41.2350"), firstPoint(DxfWriter.build(pts)))
        assertEquals(Triple("550201.5670", "200101.2340", "41.2350"), firstPoint(DxfWriter.build(pts, DxfOptions(axis = DxfAxisOrder.NORTH_X))))
    }

    @Test fun koreanIsRawCp949InFile() {
        val bytes = DxfWriter.write(pts)
        val cp = DxfWriter.cp949()
        assertEquals("CP949 로 되돌려 한글이 같아야 함", true, String(bytes, cp).contains("경계점 3"))
        val hangulBytes = "경계점".toByteArray(cp)
        assertTrue(indexOf(bytes, hangulBytes) >= 0)
        // UTF-8 로는 같은 바이트열이 없어야 함(= 정말 CP949)
        assertEquals(-1, indexOf(bytes, "경계점".toByteArray(Charsets.UTF_8)))
    }

    @Test fun unencodableCharsAreEscaped() {
        val d = DxfWriter.build(listOf(DxfPoint("A\u2603B", 1.0, 2.0, 3.0)))
        assertTrue(d.contains("A\\U+2603B"))
        val ctl = DxfWriter.build(listOf(DxfPoint("a\nb", 1.0, 2.0, 3.0)))
        assertTrue(ctl.contains("\na b\n"))
    }

    @Test fun extentsCoverPoints() {
        val p = pairs(DxfWriter.build(pts))
        val i = p.indexOfFirst { it.second == "\$EXTMIN" }
        assertEquals("200101.0120", p[i + 1].second); assertEquals("550201.5670", p[i + 2].second)
        val j = p.indexOfFirst { it.second == "\$EXTMAX" }
        assertEquals("200103.7890", p[j + 1].second)
    }

    @Test fun emptyListStillValid() {
        val d = DxfWriter.build(emptyList())
        assertTrue(d.contains("EOF")); assertEquals(0, pairs(d).count { it.second == "POINT" })
    }

    /** 외부 검증용 파일 생성 (build/dxf-samples). 박스에서 tools/validate_dxf.py 가 ezdxf/GDAL 로 읽는다 */
    @Test fun writeSamplesForExternalValidation() {
        val dir = File("build/dxf-samples").apply { mkdirs() }
        File(dir, "r2000_cp949_EN.dxf").writeBytes(DxfWriter.write(pts))
        File(dir, "r2000_cp949_NE_closed.dxf").writeBytes(DxfWriter.write(pts, DxfOptions(axis = DxfAxisOrder.NORTH_X, closedOutline = true)))
        File(dir, "r2000_cp949_EN_closed.dxf").writeBytes(DxfWriter.write(pts, DxfOptions(closedOutline = true)))
        assertTrue(File(dir, "r2000_cp949_EN.dxf").length() > 1000)
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return -1
    }
}
