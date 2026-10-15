package kr.archsurvey.t0138

import kr.archsurvey.data.PointEntity
import kr.archsurvey.dig.DigKind
import kr.archsurvey.export.DigExport
import kr.archsurvey.export.ExportFormat
import kr.archsurvey.ui.DrawerMenu
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.38 사진 실측(AprilTag·iTwin) 제거 + 옛 점은 그대로 열림 */
class Removal0138Test {
    private fun pt(kind: String, type: String, tag: String = "") = PointEntity(
        rowId = 1, project = "강릉", pointId = "P-0001", positionType = type, memo = "",
        lat = 37.5, lon = 127.0, ellipsoidalHeight = 80.0, altitudeMsl = 52.0, geoidSeparation = 28.0,
        northing = 573986.0, easting = 194884.0, crsEpsg = 5187, fixQuality = 4, allEpochsFixed = true,
        satellites = 20, hdop = 0.8, sigmaE = 0.01, sigmaN = 0.012, sigmaH = 0.008, sigmaU = 0.015,
        correctionAgeSec = 1.0, baseStationId = "0001", epochs = 3, antennaHeight = 1.799, antennaModel = "TRIMBLE_R10", antennaRefPoint = "bottom_of_quick_release", antennaInputMode = "vertical", antennaPcoV = 0.199, antennaPcoH = 0.0,
        antennaInputValue = 1.6, antennaReceiverApplied = false, groundEllipsoidalHeight = 32.0, groundMsl = 4.192,
        heightModel = "RECEIVER", device = "R10", measuredAtUtcMs = 1_760_000_000_000L,
        digKind = kind, featureId = 7, artifactNo = null, levelKind = "", digTag = tag, cellName = "",
    )

    @Test fun noPhotoControlPointKind_noItwinFormat() {
        assertTrue(DigKind.entries.none { it.id == "gcp" || "AprilTag" in it.labelKo || "사진 기준점" in it.labelKo })
        assertTrue(ExportFormat.entries.none { "itwin" in it.id.lowercase() || "iTwin" in it.labelKo })
    }

    @Test fun legacyGcpPoint_opensAsNormalPoint_exportsToOtherLayer() {
        val old = pt("gcp", "사진 기준점(AprilTag)", tag = "3")
        assertNull("옛 종류는 알 수 없는 종류 = 이름 그대로 보이는 보통 점", DigKind.resolve(old.digKind, old.positionType))
        assertEquals("기타", DigExport.layerOf(old))
        val csv = DigExport.fieldCsv(listOf(old), emptyList())
        assertTrue("점이 야장에서 빠지지 않음", csv.contains("P-0001"))
        val dxf = DigExport.drawing(listOf(old), emptyList(), emptyList())
        assertNotNull(dxf)
    }

    @Test fun sourceHasNoPhotogrammetryLeftovers() {
        val main = File("src/main/kotlin")
        val hits = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TargetNames.kt" /* 0.1.56 M7 세 앱 이름 규칙 */ }.filter { f ->
            val t = f.readText(); listOf("AprilTag", "QuickTag", "GcpAutoSaver", "ItwinGcpExport", "ItwinCaptureXml", "ITWIN_XML", "Target #").any { it in t }
        }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun singleMenu_noDuplicateDestinations() {
        val items = DrawerMenu.allItems
        assertEquals("메뉴 한 단계 10칸", 10, items.size)
        assertEquals("같은 곳으로 가는 칸이 두 개 없음", items.size, items.map { it.action }.toSet().size)
        assertEquals(items.size, items.map { it.label }.toSet().size)
        assertTrue(DrawerMenu.sections.none { it.title == "즐겨찾기" })
        val labels = items.map { it.label }
        for (gone in listOf("점 목록", "계산기", "위치 종류", "안테나 높이", "작업 정보", "측량")) assertFalse(gone, gone in labels)
        assertTrue(labels.first() == "+ 새 작업") // 0.1.39: 새 작업이 메뉴 첫 칸
        assertTrue(labels.any { "구글 드라이브" in it })
    }

    @Test fun appIdentity_nameIconPackage() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"app_name\">발굴 측량</string>"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"kr.archsurvey.field\""))
        val bg = File("src/main/res/drawable-v26/ic_launcher_background.xml").readText()
        // 짙은 바탕(밝기 낮음)
        val colors = Regex("#FF([0-9A-Fa-f]{6})").findAll(bg).map { it.groupValues[1] }.toList()
        assertTrue(colors.isNotEmpty())
        for (c in colors) {
            val r = c.substring(0, 2).toInt(16); val g = c.substring(2, 4).toInt(16); val b = c.substring(4, 6).toInt(16)
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            assertTrue("바탕 $c 이 어두움(lum=$lum)", lum < 60)
        }
        assertTrue(File("src/main/res/drawable-v26/ic_launcher_foreground.xml").readText().contains("#FFFFFF"))
    }
}
