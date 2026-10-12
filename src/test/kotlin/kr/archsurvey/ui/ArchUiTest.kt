package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.arch.*
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 고고학 화면(구역·그리드·외곽·중복 경고·재측량 이력·위치 종류 편집)을 Robolectric 으로 조작해 본다. 실기기 아님. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ArchUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/arch").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    class MemPoints : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = saved.toList()
        override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }
    class MemArch : ArchStore {
        val areas = ArrayList<Area>(); val grids = ArrayList<GridDef>(); val outlines = ArrayList<Outline>()
        var revs = emptyList<Revision>()
        private var seq = 0L
        override suspend fun load() = ArchSnapshot(areas.toList(), grids.toList(), outlines.toList(), emptyMap())
        override suspend fun addArea(name: String, colorIdx: Int): Long { areas.add(Area(++seq, name, colorIdx)); return seq }
        override suspend fun renameArea(id: Long, name: String) {}
        override suspend fun deleteArea(id: Long) { areas.removeAll { it.id == id }; grids.removeAll { it.areaId == id } }
        override suspend fun addGrid(g: GridDef): Long { grids.add(g.copy(id = ++seq)); return seq }
        override suspend fun deleteGrid(id: Long) { grids.removeAll { it.id == id } }
        override suspend fun startOutline(areaId: Long?, label: String): Long { outlines.add(Outline(++seq, label, areaId, false, false, emptyList())); return seq }
        override suspend fun addVertex(outlineId: Long, pointRowId: Long) {}
        override suspend fun removeVertex(outlineId: Long, pointRowId: Long) {}
        override suspend fun finishOutline(id: Long, closed: Boolean) {}
        override suspend fun deleteOutline(id: Long) { outlines.removeAll { it.id == id } }
        override suspend fun revisions(pointRowId: Long) = revs
    }

    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private lateinit var arch: MemArch

    private fun start(points: Int = 0) {
        f = FakeGnssSource(FakeScenarios.allFixed())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        c = SurveyController(f, db, scope, store = MemPoints())
        arch = MemArch()
        c.arch = ArchController(c, arch, scope)
        repeat(2) { f.emitNext() }
        repeat(points) { rule.runOnUiThread { c.onMeasure(); repeat(7) { f.emitNext() } } }
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, name)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun ui(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }

    @Test fun areaAndGridCanBeCreatedFromScreens() {
        start()
        ui { c.hub.open(ToolScreen.AREAS) }
        rule.onNodeWithTag("areas_empty").assertExists()
        rule.onNodeWithTag("area_pick_A").performClick(); rule.waitForIdle() // 0.1.39: 칩 한 번에 만들기
        assertEquals(listOf("A"), c.arch.areas.map { it.name })
        rule.onNodeWithTag("grids_empty").assertExists()
        shot("a1_areas")
        rule.onNodeWithTag("grid_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("grid_wizard").assertExists()
        rule.onNodeWithTag("grid_mode_az").performClick()
        rule.onNodeWithTag("grid_n").performTextInput("412000"); rule.onNodeWithTag("grid_e").performTextInput("238000")
        rule.onNodeWithTag("grid_az").performTextInput("90"); rule.onNodeWithTag("grid_sp_2 m").performScrollTo().performClick()
        rule.onNodeWithTag("grid_save").performClick(); rule.waitForIdle()
        assertEquals(1, c.arch.grids.size)
        assertEquals(2.0, c.arch.grids[0].spacingM, 1e-9)
        assertEquals(ToolScreen.AREAS, c.hub.screen)
        rule.onNodeWithTag("grid_${c.arch.grids[0].id}").assertExists()
        shot("a2_grid_listed")
    }

    @Test fun wizardRefusesWithoutNumbers() {
        start()
        ui { c.arch.addArea("B"); c.hub.open(ToolScreen.GRID_WIZ) }
        rule.onNodeWithTag("grid_mode_az").performClick()
        rule.onNodeWithTag("grid_save").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("grid_problem").assertExists()
        assertTrue(c.arch.grids.isEmpty())
    }

    @Test fun typesScreenAddRenameMoveDelete() {
        start()
        ui { c.hub.open(ToolScreen.TYPES) }
        val n0 = c.types.size
        rule.onNodeWithTag("type_new_field").performTextInput("토층점"); rule.onNodeWithTag("type_add").performClick(); rule.waitForIdle()
        assertEquals(n0 + 1, c.types.size); assertEquals("토층점", c.types.last())
        rule.onNodeWithTag("type_new_field").performTextInput("아주아주긴이름이다아"); rule.onNodeWithTag("type_add").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("types_msg").assertExists()
        assertEquals(n0 + 1, c.types.size)
        rule.onNodeWithTag("type_up_$n0").performClick(); rule.waitForIdle()
        assertEquals("토층점", c.types[n0 - 1])
        rule.onNodeWithTag("type_del_${n0 - 1}").performClick(); rule.waitForIdle()
        assertFalse(c.types.contains("토층점"))
        shot("a3_types")
    }

    @Test fun duplicateSheetOffersThreeChoicesAndDiscardKeepsCount() {
        start(1)
        val p = c.points.single()
        ui { c.dupPrompt = DupPrompt(p.copy(name = "새"), p, 0.012, SurveyStatus.FIXED) }
        rule.onNodeWithTag("dup_sheet").assertExists()
        rule.onNodeWithTag("dup_text").assertTextContains("0.012", substring = true)
        rule.onNodeWithTag("dup_resurvey").assertExists(); rule.onNodeWithTag("dup_new").assertExists()
        shot("a4_duplicate")
        rule.onNodeWithTag("dup_discard").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dup_sheet").assertDoesNotExist()
        assertEquals(1, c.points.size)
    }

    @Test fun pointDetailShowsResurveyButtonAndHistory() {
        start(1)
        val p = c.points.single()
        arch.revs = listOf(Revision(1, 1_000_000L, p.northing, p.easting, 1.0, 0.01, 4, true, 5, "R10", "A-1"), Revision(2, 2_000_000L, p.northing + 0.01, p.easting, 1.0, 0.012, 4, true, 5, "R10", "A-1"))
        ui { c.hub.openDetail(p) }
        rule.onNodeWithTag("detail_resurvey").assertExists()
        rule.onNodeWithTag("revision_title").assertTextContains("2건", substring = true)
        rule.onNodeWithTag("revision_2").assertExists()
        rule.onNodeWithTag("detail_resurvey").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(p.name, c.resurveyTarget?.name)
        rule.onNodeWithTag("resurvey_banner").assertExists()
        rule.onNodeWithTag("resurvey_cancel").performClick(); rule.waitForIdle()
        assertNull(c.resurveyTarget)
    }

    @Test fun outlineModeBarAndQuickSheetEntries() {
        // 0.1.35: 윤곽 모드 막대 → 「지금 재는 것」 고르기 · 유구 경계는 「면 닫기」
        start()
        rule.onNodeWithTag("now_row").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dig_picker").assertExists()
        rule.onNodeWithTag("kind_feature").performClick(); rule.waitForIdle()
        assertEquals(kr.archsurvey.dig.DigKind.FEATURE, c.dig.kind)
        rule.onAllNodesWithTag("dig_picker").fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.runOnUiThread { c.dig.pickerOpen = false } }
        rule.waitForIdle()
        rule.onNodeWithTag("tool_close_area").performClick(); rule.waitForIdle()
        assertTrue("점이 없으면 닫기 안 됨 안내", c.message.orEmpty().contains("유구"))
        assertFalse(c.dig.closeArmed)
    }
    @Test fun setupSheetFromLongPressAppliesMemoTypeAndLinkMode() {
        start()
        rule.onNodeWithTag("measure_button").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("setup_sheet").assertExists()
        rule.onNodeWithTag("setup_name", useUnmergedTree = true).assertTextEquals(c.nextName)
        rule.onNodeWithTag("setup_memo").performTextInput("북동 모서리")
        rule.onNodeWithTag("setup_link_area").performClick(); rule.waitForIdle()
        shot("a8_setup_sheet")
        rule.onNodeWithTag("setup_apply").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("setup_sheet").assertDoesNotExist()
        assertTrue(c.arch.outlineMode); assertTrue(c.arch.preferClosed)
        assertEquals("북동 모서리", c.pendingMemo)
        rule.runOnUiThread { c.onMeasure(); repeat(7) { f.emitNext() } }; rule.waitForIdle()
        assertEquals("북동 모서리", c.points.last().memo)
        assertEquals("비고는 한 번만 붙는다", "", c.pendingMemo)
        // 취소는 아무것도 바꾸지 않음
        rule.onNodeWithTag("measure_button").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("setup_link_point").performClick(); rule.onNodeWithTag("setup_cancel").performClick(); rule.waitForIdle()
        assertTrue(c.arch.outlineMode)
    }
}
