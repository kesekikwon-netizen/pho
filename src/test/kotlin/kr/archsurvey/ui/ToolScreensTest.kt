package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** 서랍에서 여는 도구 화면들을 Robolectric(JVM)으로 그려 본다. 실기기/에뮬레이터 실행이 아니며 파일·카메라·마이크는 가짜 플랫폼이다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ToolScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/tools").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Store : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = saved.toList(); override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }
    private class Media : MediaPlatform {
        val ph = ArrayList<PhotoItem>(); val vo = ArrayList<VoiceItem>()
        override suspend fun photos(pointRowId: Long) = ph.toList(); override suspend fun voices(pointRowId: Long) = vo.toList()
        override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Boolean) -> Unit) { ph.add(PhotoItem(ph.size + 1L, "x.jpg")); onDone(true) }
        override fun pickPhotos(pointId: String, pointRowId: Long, remaining: Int, onDone: (Int) -> Unit) = onDone(0)
        override suspend fun deletePhoto(id: Long) { ph.removeAll { it.id == id } }
        override fun thumb(rel: String): androidx.compose.ui.graphics.ImageBitmap? = null
        override fun hasMicPermission() = true; override fun requestMicPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun startRecording(pointId: String): String? = null
        override suspend fun finishRecording(pointRowId: Long): VoiceItem? = VoiceItem(vo.size + 1L, "v.m4a", 7000).also { vo.add(it) }
        override fun cancelRecording() {}; override fun play(rel: String, onDone: () -> Unit) = true; override fun stopPlayback() {}
        override suspend fun deleteVoice(id: Long) { vo.removeAll { it.id == id } }
    }
    private class Exp : ExportPlatform {
        override suspend fun summary() = ExportSummary("작업1", 3, 2, 1, 3)
        override suspend fun export(includePhotos: Boolean, includeVoices: Boolean) = ExportOutput("작업1_20261004_213000.zip", 48_200, "/x/a.zip", 3, 2, 1)
        override fun share(out: ExportOutput) {}; override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
    }
    private class Bak : BackupPlatform {
        val items = arrayListOf(BackupItem("backup_20261004_210000.zip", "/b/1", 1_791_100_000_000L, 3, 2, 1, 1_500_000))
        override suspend fun list() = items.toList()
        override suspend fun create() = BackupItem("backup_new.zip", "/b/2", 1_791_100_100_000L, 3, 2, 1, 1_500_100).also { items.add(0, it) }
        override suspend fun restore(item: BackupItem) = BackupItem("before_restore_1.zip", "/b/3", 1L, 3, 2, 1, 1)
        override suspend fun importPicked(): BackupItem? = null; override suspend fun delete(item: BackupItem) { items.remove(item) }
        override fun share(item: BackupItem) {}; override fun saveAs(item: BackupItem, onDone: (Boolean) -> Unit) = onDone(true); override fun restartApp() {}
    }
    private class Jobs : JobPlatform {
        override var current = "작업1"; val m = linkedMapOf("작업1" to 3, "3지구-A트렌치" to 12)
        override suspend fun list() = m.map { JobInfo(it.key, it.value, 2, 1, 1_791_000_000_000L, 1_791_090_000_000L, if (it.key == "작업1") 3 else 0) }
        override suspend fun info(name: String) = list().first { it.name == name }
        override suspend fun create(name: String) { m[name] = 0; current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String): String { m.remove(name); return "before_delete_1.zip" }
    }
    private class Sett : SettingsPlatform {
        override var zone = kr.archsurvey.core.KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, name)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private lateinit var c: SurveyController
    private lateinit var f: FakeGnssSource
    private lateinit var media: Media

    private fun setup(points: Int = 3) {
        f = FakeGnssSource(FakeScenarios.allFixed()); media = Media()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        c = SurveyController(f, db, scope, store = Store())
        c.media = media
        c.exporter = ExportController(Exp(), scope)
        c.backup = BackupController(Bak(), scope)
        c.settingsState = SettingsState(c, Sett())
        c.jobs = JobController(c, Jobs(), scope)
        repeat(2) { f.emitNext() }
        repeat(points) { c.onMeasure(); repeat(7) { f.emitNext() } }
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
    }

    @Test fun pointsListDetailEditAndGoto() {
        setup()
        rule.runOnUiThread { c.hub.open(ToolScreen.POINTS) }; rule.waitForIdle()
        rule.onNodeWithTag("points_screen").assertExists()
        rule.onNodeWithTag("points_count").assertExists()
        shot("t1_points")
        rule.onNodeWithTag("point_row_P-0002").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("point_detail").assertExists()
        rule.onNodeWithTag("edit_type_toggle").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("edit_type_기준점").performClick()
        rule.onNodeWithTag("edit_memo").performTextInput("북동 모서리")
        shot("t2_point_detail")
        rule.onNodeWithTag("point_save").performClick(); rule.waitForIdle()
        assertEquals("기준점", c.points.first { it.name == "P-0002" }.type)
        assertEquals("북동 모서리", c.points.first { it.name == "P-0002" }.memo)
        rule.onNodeWithTag("detail_goto").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("goto_screen").assertExists()
        rule.onNodeWithTag("goto_distance").assertExists()
        shot("t3_goto")
        rule.onNodeWithTag("tool_back").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("point_detail").assertExists()
    }

    @Test fun calcScreens() {
        setup()
        rule.runOnUiThread { c.hub.open(ToolScreen.CALC) }; rule.waitForIdle()
        rule.onNodeWithTag("calc_pick_a").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("calc_list_P-0001").performClick()
        rule.onNodeWithTag("calc_pick_b").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("calc_list_P-0003").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("calc_dist").assertExists()
        shot("t4_calc_distance")
        rule.onNodeWithTag("calc_mode_AREA").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("calc_area_add").performClick(); rule.waitForIdle()
        for (n in listOf("P-0001", "P-0002", "P-0003")) rule.onNodeWithTag("calc_list_$n").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("calc_area").assertExists()
        shot("t5_calc_area")
    }

    @Test fun settingsAndJobs() {
        setup()
        rule.runOnUiThread { c.hub.open(ToolScreen.SETTINGS) }; rule.waitForIdle()
        rule.onNodeWithTag("zone_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("zone_" + kr.archsurvey.core.KoreanTmZone.entries.indexOfFirst { it.epsg == 5187 }).performClick(); rule.waitForIdle()
        assertEquals(5187, c.zone.epsg)
        shot("t6_settings")
        rule.runOnUiThread { c.jobs!!.openList() }; rule.waitForIdle()
        rule.onNodeWithTag("job_item_0").assertExists()
        shot("t7_jobs_list")
        rule.onNodeWithTag("job_delete_1").performScrollTo().performClick(); rule.waitForIdle()
        shot("t8_job_delete_confirm")
        rule.onNodeWithTag("confirm_cancel").performClick(); rule.waitForIdle()
        rule.runOnUiThread { c.jobs!!.openNew() }; rule.waitForIdle()
        rule.onNodeWithTag("job_name").performTextInput("4지구"); rule.onNodeWithTag("job_create").performClick(); rule.waitForIdle()
        assertEquals("4지구", c.projectName)
        rule.onNodeWithTag("job_info_name").assertExists()
        shot("t9_job_info")
    }

    @Test fun exportAndBackupScreens() {
        setup()
        rule.runOnUiThread { c.exporter!!.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("export_fake_warning").assertExists()
        rule.onNodeWithTag("export_run").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("export_filename").assertExists()
        shot("t10_export_done")
        rule.runOnUiThread { c.exporter!!.close(); c.backup!!.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("backup_item_0").assertExists()
        shot("t11_backup")
        rule.onNodeWithTag("backup_restore_0").performClick(); rule.waitForIdle()
        shot("t12_restore_confirm")
        rule.onNodeWithTag("confirm_cancel").performClick(); rule.waitForIdle()
    }

    @Test fun photosAndVoiceInExpandedResult() {
        setup()
        rule.runOnUiThread { c.toggleExpand() }; rule.waitForIdle()
        rule.onNodeWithTag("photo_slot_0").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("photo_slot_1").performClick(); rule.waitForIdle()
        assertEquals(2, c.photos.size)
        rule.onNodeWithTag("photo_count").assertExists()
        rule.onNodeWithTag("voice_big").performClick(); rule.waitForIdle()
        assertEquals(Phase.RECORDING, c.phase)
        shot("t13_recording")
        rule.onNodeWithTag("record_stop").performClick(); rule.waitForIdle()
        assertEquals(1, c.voices.size)
        rule.runOnUiThread { c.toggleExpand() }; rule.waitForIdle()
        shot("t14_expanded_with_media")
    }
}
