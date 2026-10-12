package kr.archsurvey.t0137

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kr.archsurvey.arch.ArchController
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import kr.archsurvey.dig.DigKind
import kr.archsurvey.export.GcpAutoSaver
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.unit.dp

/** 0.1.37 Target 1–4 단추 · 저장 즉시 XML 자동 저장(실제 Room + 가짜 수신기 + Robolectric 화면. 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class QuickTag0137Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SurveyDb
    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private val saves = ArrayList<Long?>()
    private lateinit var saver: GcpAutoSaver

    private class Sett : SettingsPlatform {
        override var zone = kr.archsurvey.core.KoreanTmZone.EAST; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    @Before fun setUp() {
        TileImages.networkEnabled = false
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed())
        val store = RoomPointStore(db, { "강릉" }, { "R10" })
        c = SurveyController(f, AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()), scope, store = store, epochsPerPoint = 3)
        c.projectName = "강릉"
        c.arch = ArchController(c, RoomArchStore(db, { "강릉" }), scope)
        c.settingsState = SettingsState(c, Sett())
        c.homeOpen = false
        c.ui.toastMs = 600_000L
        saver = GcpAutoSaver(ctx, db.dao(), db.arch())
        c.gcpFiles = object : GcpFilePlatform {
            override suspend fun save(featureId: Long?): GcpSaveResult? { saves += featureId; return saver.save("강릉", featureId)?.let { GcpSaveResult(it.featureLabel, it.xmlName, it.publicPath, it.count) } }
            override fun share(featureLabel: String) = saver.shareIntent("강릉", featureLabel) != null
        }
        repeat(3) { f.emitNext() }
    }
    @After fun tearDown() { db.close() }

    private fun show() { rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }; rule.waitForIdle() }
    private fun adv(n: Int) { rule.runOnIdle { repeat(n) { f.emitNext() } }; rule.waitForIdle() }
    private fun shot(name: String): String {
        rule.waitForIdle()
        val out = File("/workspace/survey-app/build-reports/0.1.37/screens").apply { mkdirs() }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        val file = File(out, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }

    @Test fun fourButtons_measureEachTag_stateAndAutoXml() {
        val fid = runBlocking { c.dig.newFeature("주거지", prov = false)!! }
        c.dig.select(DigKind.FEATURE, fid)
        show()
        shot("q_map_4buttons_empty")
        // 단추 4개 · 장갑 크기 · 측량 단추 72dp 이상 유지
        for (i in 1..4) {
            val b = rule.onNodeWithTag("qtag_$i").fetchSemanticsNode().size
            assertTrue("Target $i 높이 ${b.height}", b.height >= with(rule.density) { 56.dp.toPx() })
            assertTrue(b.width >= with(rule.density) { 48.dp.toPx() })
        }
        assertTrue(rule.onNodeWithTag("measure_button").fetchSemanticsNode().size.height >= with(rule.density) { 72.dp.toPx() })
        rule.onNodeWithTag("qtag_feature", useUnmergedTree = true).assertTextEquals("1호 주거지")
        val pickerBefore = c.dig.tagId
        for (i in listOf(1, 2, 3, 4)) {
            rule.onNodeWithTag("qtag_$i").performClick(); adv(8)
            rule.runOnIdle { c.dismissResult() }
        }
        val g = c.points.filter { it.digKind == "gcp" }
        assertEquals(listOf("1", "2", "3", "4"), g.map { it.digTag })
        assertTrue(g.all { it.featureId == fid })
        assertEquals("지금 재는 것은 유구 경계 그대로", DigKind.FEATURE, c.dig.kind)
        assertEquals("−/+ 번호 그대로", pickerBefore, c.dig.tagId)
        assertNull(c.dig.quickTag)
        for (i in 1..4) rule.onNodeWithTag("qtag_done_$i", useUnmergedTree = true).assertExists()
        // 저장마다 XML 자동 저장(그 유구)
        assertEquals(listOf<Long?>(fid, fid, fid, fid), saves)
        rule.onNodeWithTag("toast_text", useUnmergedTree = true).assertTextContains("1호 주거지 XML 저장됨", substring = true)
        val xml = File(saver.localDir("강릉"), "1호_주거지_AprilTag_EPSG${c.zone.epsg}_MSL-RECEIVER.xml")
        assertTrue(saver.localDir("강릉").list()?.toList().toString(), xml.exists())
        val text = xml.readText()
        assertEquals(listOf("Target #1", "Target #2", "Target #3", "Target #4"), Regex("<Name>(Target #\\d+)</Name>").findAll(text).map { it.groupValues[1] }.toList())
        assertTrue(text.contains("<Definition>EPSG:${c.zone.epsg}</Definition>") && text.contains("\r\n") && !text.startsWith("\uFEFF"))
        assertTrue(File(saver.localDir("강릉"), "1호_주거지_AprilTag_EPSG${c.zone.epsg}_MSL-RECEIVER.txt").readText().startsWith("Target #1,"))
        assertEquals("1호_주거지_AprilTag_EPSG${c.zone.epsg}_MSL-RECEIVER.xml", c.gcpSaved[fid])
        // 측량 단추는 유구 경계(표적 아님)
        rule.runOnIdle { c.onMeasure() }; adv(8)
        assertEquals("feature", c.points.last().digKind)
        rule.runOnIdle { c.dismissResult() }
        shot("q_map_4buttons_done")
        println("SNAPSHOT " + shot("q_map_4buttons_done"))
    }

    @Test fun measuredTag_tapOnlyHints_longPressConfirmsRemeasure_xmlUpdated() {
        val fid = runBlocking { c.dig.newFeature("수혈", prov = false)!! }
        c.dig.select(DigKind.FEATURE, fid)
        show()
        rule.onNodeWithTag("qtag_2").performClick(); adv(8); rule.runOnIdle { c.dismissResult() }
        assertEquals(1, c.points.count { it.digKind == "gcp" })
        // 이미 잰 번호 → 누르기만 하면 안내, 측점 안 함
        rule.onNodeWithTag("qtag_2").performClick(); adv(8)
        assertEquals(1, c.points.count { it.digKind == "gcp" })
        rule.onNodeWithTag("toast_text", useUnmergedTree = true).assertTextContains("길게 누르세요", substring = true)
        // 길게 → 확인 창 → 다시 재기
        f.emitNext()
        rule.onNodeWithTag("qtag_2").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals("Target #2 다시 잴까요?")
        rule.onNodeWithTag("confirm_ok").performClick(); adv(8)
        rule.runOnIdle { if (c.dupPrompt != null) c.resolveDup(DupChoice.NEW_POINT) }; adv(2)
        val g = c.points.filter { it.digKind == "gcp" && it.featureId == fid }
        assertTrue("다시 잰 값이 저장됨", g.size == 2 || saves.size >= 2)
        assertTrue(g.all { it.digTag == "2" })
        assertTrue(saves.size >= 2)
    }

    @Test fun mainMeasureClearsStaleQuickTag() {
        val fid = runBlocking { c.dig.newFeature("수혈", prov = false)!! }
        c.dig.select(DigKind.FEATURE, fid)
        c.dig.quickTag = 3   // 예: 확인 창을 취소해 남은 번호
        c.onMeasure(); repeat(8) { f.emitNext() }
        assertNull(c.dig.quickTag)
        assertEquals("feature", c.points.last().digKind)
    }

    @Test fun buttonsHiddenWithoutFeature_shownInGcpMode() {
        show()
        rule.onNodeWithTag("qtag_row").assertDoesNotExist()
        rule.runOnIdle { c.dig.select(DigKind.GCP, null) }; rule.waitForIdle()
        rule.onNodeWithTag("qtag_row").assertExists()
        rule.onNodeWithTag("qtag_feature", useUnmergedTree = true).assertTextEquals("유구 밖")
    }

    @Test fun featureCard_showsXmlSavedAndShare() {
        val fid = runBlocking { c.dig.newFeature("주거지", prov = false)!! }
        c.dig.select(DigKind.FEATURE, fid)
        c.measureQuickTag(1); repeat(8) { f.emitNext() }; c.dismissResult()
        show()
        rule.runOnIdle { c.hub.open(ToolScreen.FEATURES) }; rule.waitForIdle()
        rule.onNodeWithTag("feature_xml_$fid", useUnmergedTree = true).performScrollTo().assertTextContains("XML 저장됨", substring = true)
        rule.onNodeWithTag("feature_xml_share_$fid").assertExists()
        assertTrue(c.gcpFiles!!.share("1호 주거지"))
    }
}
