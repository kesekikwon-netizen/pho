package kr.archsurvey.t0146

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.io.AtomicFiles
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.StubSrc
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.46 디자인 시스템 반영 — 글자 130% 에서 접힘(412×892)·펼침(884×1076) 화면. Robolectric 그림(실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xhdpi")
class Release0146Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/workspace/survey-app/build-reports/0.1.46/screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setup(fontScale: Float = 1.3f, home: Boolean = false): Pair<StubSrc, SurveyController> {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.SP60_ID, Mem())
        c.projectName = "정밀발굴 1지구"
        c.zone = KoreanTmZone.EAST
        c.ui.toastMs = 1L
        s.push(FixQuality.RTK_FIXED, 0.014)
        c.homeOpen = home
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) }
            }
        }
        rule.waitForIdle()
        return s to c
    }

    private fun measureOnce(s: StubSrc, c: SurveyController, lat: Double = 36.5, lon: Double = 127.0) {
        rule.runOnIdle { s.now += 2000; s.push(FixQuality.RTK_FIXED, 0.014, lat = lat, lon = lon); c.onMeasure() }
        rule.waitForIdle()
    }

    @Test fun statusBadge_isFixCm_andHeaderShowsEpsgZone() {
        setup()
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("FIX")
        rule.onNodeWithTag("pill_sigma", useUnmergedTree = true).assertTextEquals("1.4 cm")
        rule.onNodeWithTag("header_job", useUnmergedTree = true).assertTextContains("EPSG:5187 동부", substring = true)
        shot("s03_measure_map-folded-fs130")
    }

    @Test fun measureButton_96dp_inThumbZone_atFontScale130() {
        setup()
        val b = rule.onNodeWithTag("measure_button").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val h = with(rule.density) { b.height.toDp() }
        assertTrue("측정 단추 높이 $h", h >= 95.5.dp)
        assertTrue("엄지 영역(아래 40%)", b.top >= root.height * 0.6f)
    }

    @Test fun duplicatePoint_inlineDecisionBar_keepsMapVisible_noScrim() {
        val (s, c) = setup()
        measureOnce(s, c)
        rule.runOnIdle { c.dismissResult() }
        measureOnce(s, c) // 같은 자리
        assertNotNull("같은 자리 판정", c.dupPrompt)
        rule.onNodeWithTag("map_area").assertIsDisplayed()
        rule.onNodeWithTag("dup_sheet").assertIsDisplayed()
        rule.onNodeWithTag("measure_button").assertIsDisplayed()
        for (t in listOf("dup_resurvey", "dup_new", "dup_discard")) {
            val h = with(rule.density) { rule.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot.height.toDp() }
            assertTrue("$t 높이 $h", h >= 55.5.dp)
        }
        // 결정 바는 지도 아래(측정 패널 안)에 있어 지도를 덮지 않는다
        val map = rule.onNodeWithTag("map_area").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("dup_sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("지도와 겹치지 않음", bar.top >= map.bottom - 1f)
        shot("s03_dup_decision_bar-folded-fs130")
        val n = c.points.size
        rule.onNodeWithTag("dup_discard").performClick(); rule.waitForIdle()
        assertNull(c.dupPrompt); assertEquals(n, c.points.size)
    }

    @Test fun savedNotice_3s_withUndo() {
        val (s, c) = setup()
        val n = c.points.size
        measureOnce(s, c, 36.5003, 127.0006)
        assertEquals(n + 1, c.points.size)
        rule.onNodeWithTag("saved_toast").assertIsDisplayed()
        shot("s03b_saved_notice-folded-fs130")
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        rule.onNodeWithTag("saved_toast").assertIsDisplayed() // 2초엔 아직
        rule.onNodeWithTag("saved_toast_undo").performClick(); rule.waitForIdle()
        assertEquals("되돌리기로 점 숨김", n, c.points.size)
    }

    @Test fun savedNotice_disappearsAfter3s() {
        val (s, c) = setup()
        measureOnce(s, c, 36.5004, 127.0007)
        rule.mainClock.advanceTimeBy(3500); rule.waitForIdle()
        rule.onNodeWithTag("saved_toast").assertDoesNotExist()
    }

    @Test fun header_epsg_notTruncated_412dp_fs130() {
        setup()
        val res = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("header_job", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(res) }
        assertFalse("머리줄 EPSG 잘림", res.first().hasVisualOverflow)
    }

    @Test fun measureRow_pointsButton_and_compactSecondary() {
        val (_, c) = setup()
        rule.onNodeWithTag("tool_points").assertIsDisplayed()
        rule.onNodeWithTag("tool_photo").assertIsDisplayed(); rule.onNodeWithTag("tool_memo").assertIsDisplayed()
        rule.onNodeWithTag("tool_points").performClick(); rule.waitForIdle()
        assertEquals(ToolScreen.POINTS, c.hub.screen)
    }

    @Test fun screens_folded_fontScale130() {
        val (s, c) = setup(home = true)
        rule.runOnIdle { c.homeOpen = false }
        measureOnce(s, c, 36.5001, 127.0001); rule.runOnIdle { c.dismissResult() }
        measureOnce(s, c, 36.5003, 127.0004); rule.runOnIdle { c.dismissResult(); c.homeOpen = true }
        shot("s01_home-folded-fs130")
        rule.runOnIdle { c.homeOpen = false; c.jobs?.openNew() ?: Unit }; shot("s02_new_job-folded-fs130")
        rule.runOnIdle { c.hub.closeAll(); c.hub.open(ToolScreen.POINTS) }; shot("s04_point_list-folded-fs130")
        rule.runOnIdle { c.pointMenuFor = c.points.first() }; shot("s04b_point_sheet-folded-fs130")
        rule.onNodeWithTag("pm_type_0").assertExists(); rule.runOnIdle { c.pointMenuFor = null }
        rule.runOnIdle { c.hub.closeAll(); c.antennaSheetOpen = true }; shot("s06_pole_sheet-folded-fs130")
        rule.onNodeWithTag("step_plus").assertIsDisplayed(); rule.onNodeWithTag("sheet_close").assertExists()
        rule.runOnIdle { c.antennaSheetOpen = false; c.hub.open(ToolScreen.SETTINGS) }; shot("s07_settings-folded-fs130")
        rule.onNodeWithTag("settings_tab_1").performClick(); rule.waitForIdle(); rule.onNodeWithTag("set_receiver").assertExists()
        rule.onNodeWithTag("settings_tab_2").performClick(); rule.waitForIdle(); rule.onNodeWithTag("mapbase_toggle").assertExists()
        rule.runOnIdle { c.settingsTab = 0; c.hub.closeAll(); c.connection = ConnectionController(kr.archsurvey.t0140.FakeConn(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)); c.connection?.openScreen() }
        shot("s05_receiver-folded-fs130")
        rule.runOnIdle { c.connection?.leaveScreen() }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Config(qualifiers = "w884dp-h1076dp-notnight-xhdpi")
    @Test fun screens_unfolded_fontScale130() {
        val (_, c) = setup(home = true)
        shot("s01_home-unfolded-fs130")
        rule.runOnIdle { c.homeOpen = false }; shot("s03_measure_map-unfolded-fs130")
        rule.onNodeWithTag("measure_button").assertIsDisplayed()
        rule.onNodeWithTag("map_area").assertIsDisplayed()
    }

    @Test fun errorChip_hidesExceptionNames() {
        assertEquals("배경지도를 받지 못했어요", UserError.scrub("배경지도를 받지 못했어요 (SocketTimeoutException)"))
        assertFalse(UserError.of("t", RuntimeException("x"), "내보내기에 실패했어요", "다시 시도하세요").contains("Exception"))
        assertFalse(kr.archsurvey.map.VWorldTileResponse.networkErrorKo("https://api.vworld.kr/req/wms?LAYERS=lp_pa_cbnd_bubun", "UnknownHostException").contains("Exception"))
        rule.setContent { SurveyTheme(dark = false) { ErrorChip("지적도를 잠시 받지 못했어요 (IOException)", onRetry = {}) } }
        rule.onNodeWithTag("error_chip_text", useUnmergedTree = true).assertTextEquals("지적도를 잠시 받지 못했어요")
        rule.onNodeWithTag("error_chip_retry").assertExists()
        shot("s08_error_chip-folded-fs130")
    }
}

/** 순수 JVM 시험 */
class Release0146UnitTest {
    @Test fun atomicWrite_replacesWhole_noTempLeft() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        val f = File(d, "points.csv")
        AtomicFiles.writeText(f, "old")
        AtomicFiles.writeText(f, "new,1\n")
        assertEquals("new,1\n", f.readText())
        assertEquals(listOf("points.csv"), d.list()!!.toList())
    }

    @Test fun atomicWrite_failureKeepsOriginal() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        val f = File(d, "a.dxf"); AtomicFiles.writeText(f, "complete")
        runCatching { AtomicFiles.write(f) { it.write("half".toByteArray()); throw java.io.IOException("배터리 방전 흉내") } }
        assertEquals("complete", f.readText())
        assertEquals(1, d.list()!!.size)
    }

    @Test fun atomicZip_isReadable() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        val z = File(d, "x.zip")
        AtomicFiles.zip(z) { zo -> zo.putNextEntry(java.util.zip.ZipEntry("a.txt")); zo.write("hi".toByteArray()); zo.closeEntry() }
        java.util.zip.ZipFile(z).use { assertEquals("hi", it.getInputStream(it.getEntry("a.txt")).readBytes().decodeToString()) }
    }

    @Test fun epsgDisplay_matchesZone_displayOnly() {
        assertEquals("EPSG:5187 동부", KoreanTmZone.EAST.displayKo)
        assertEquals("EPSG:5186 중부", KoreanTmZone.CENTRAL.displayKo)
        assertEquals(KoreanTmZone.EAST, KoreanTmZone.fromEpsg(5187))
    }

    @Test fun tokens_matchApprovedDesignSystem() {
        fun argb(c: androidx.compose.ui.graphics.Color) = (c.value shr 32).toLong().toInt()
        assertEquals(0xFF1F7A3A.toInt(), argb(FieldIvory.fix)); assertEquals(0xFF1F7A3A.toInt(), argb(D.fix))
        assertEquals(0xFFA86400.toInt(), argb(FieldIvory.float)); assertEquals(0xFFB42318.toInt(), argb(FieldIvory.error))
        assertEquals(56f, FieldIvory.minTouch.value); assertEquals(96f, FieldIvory.measureButton.value); assertEquals(8f, FieldIvory.gap.value)
        assertTrue(DTy.tiny.fontSize.value >= 16f && DTy.small.fontSize.value >= 16f && Ty.small.fontSize.value >= 16f && DTy.monoS.fontSize.value >= 16f)
    }

    /** 화면 글자 16sp 미만 금지(시험용 투명 표지 1.sp 제외) */
    @Test fun noFontBelow16spInUi() {
        val bad = File("src/main/kotlin/kr/archsurvey").walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""fontSize = ([0-9.]+)\.sp""").findAll(f.readText()).map { it.groupValues[1].toDouble() }.filter { it in 1.5..15.99 }.map { "${f.name}:$it" }
        }.toList()
        assertTrue(bad.toString(), bad.isEmpty())
    }
}

/** 두 앱 FieldIvory.kt 는 글자 하나 다르지 않아야 한다 */
class SharedTokensTest {
    @Test fun fieldIvoryIdenticalToGcpApp() {
        val g = File("/workspace/gcp-app/src/main/kotlin/kr/archsurvey/ui/FieldIvory.kt")
        org.junit.Assume.assumeTrue(g.exists())
        assertEquals(g.readText(), File("src/main/kotlin/kr/archsurvey/ui/FieldIvory.kt").readText())
    }
}
