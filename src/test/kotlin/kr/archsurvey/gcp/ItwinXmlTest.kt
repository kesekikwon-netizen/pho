package kr.archsurvey.gcp

import kr.archsurvey.gcp.model.ItwinXml
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 사용자가 준 실제 iTwin Capture 파일(강릉 1호 · EPSG:5187 · Target #1–4)에서 Measurement(사진 위 점, iTwin 안에서 생김)만 빼면
 * 이 앱 출력과 바이트 단위로 같아야 한다(측량 앱 0.1.37 ItwinXml0137Test 와 같은 비교).
 */
class ItwinXmlTest {
    private val sampleBytes = File("src/test/resources/itwin/gangneung-1ho.xml").readBytes()
    private val stripped = String(sampleBytes, Charsets.UTF_8).replace(Regex("\t\t\t<Measurement>\r\n.*?\t\t\t</Measurement>\r\n", RegexOption.DOT_MATCHES_ALL), "")
    // 표본 좌표. 잰 순서를 섞어도 파일은 1,2,3,4 순
    private val shots = listOf(
        shot(3, 1, 3, 194877.654, 573994.437, 2.87), shot(1, 1, 1, 194884.215, 573986.096, 4.192),
        shot(4, 1, 4, 194874.974, 573993.995, 3.982), shot(2, 1, 2, 194880.652, 573989.204, 2.883),
    )

    @Test fun sampleIsUtf8NoBomCrlfTabs() {
        assertFalse(sampleBytes[0] == 0xEF.toByte())
        assertTrue(stripped.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<SurveysData>\r\n\t<SpatialReferenceSystems>"))
        assertEquals(4, Regex("<ControlPoint>").findAll(stripped).count())
        assertFalse(stripped.contains("<Measurement>"))
    }

    @Test fun ourXmlEqualsUserSampleWithoutMeasurements_byteForByte() {
        val ours = ItwinXml.write(shots, 5187)
        assertEquals(stripped, ours)
        assertArrayEquals(stripped.toByteArray(Charsets.UTF_8), ours.toByteArray(Charsets.UTF_8))
        File("build-reports").mkdirs(); File("build-reports/sample-output-GN-A001_1호_1차_EPSG5187.xml").writeText(ours)
    }

    @Test fun txtCustomFormat() {
        assertEquals("Target #1,194884.215,573986.096,4.192\r\nTarget #2,194880.652,573989.204,2.883\r\nTarget #3,194877.654,573994.437,2.870\r\nTarget #4,194874.974,573993.995,3.982\r\n", ItwinXml.txt(shots))
    }

    @Test fun srsNamesForAllKoreanBelts() {
        for ((e, n) in listOf(5185 to "West Belt 2010", 5186 to "Central Belt 2010", 5187 to "East Belt 2010", 5188 to "East Sea Belt 2010")) {
            assertTrue(ItwinXml.write(shots, e).contains("<Name>Korea 2000 / $n (EPSG:$e)</Name>\r\n\t\t\t<Definition>EPSG:$e</Definition>"))
        }
    }

    @Test fun reMeasuredUsesLatestActive_andRealTagNumbers() {
        val again = shots.map { if (it.tag == 2) it.copy(active = false) else it } + shot(9, 1, 2, 194880.66, 573989.21, 2.89, at = 50) + shot(10, 1, 17, 1.0, 2.0, 3.0)
        val x = ItwinXml.write(again, 5187)
        assertTrue(x.contains("<x>194880.66</x>") && x.contains("<z>2.89</z>")); assertFalse(x.contains("194880.652"))
        assertTrue(x.contains("<Name>Target #17</Name>"))
        assertEquals(listOf("Target #1", "Target #2", "Target #3", "Target #4", "Target #17"), Regex("<Name>(Target #\\d+)</Name>").findAll(x).map { it.groupValues[1] }.toList())
        assertTrue(x.contains("<Id>4</Id>"))
        assertEquals(5, Regex("<CheckPoint>false</CheckPoint>").findAll(x).count())
        assertEquals(10, Regex("Accuracy>0.01</").findAll(x).count())
    }
}
