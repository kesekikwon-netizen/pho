package kr.archsurvey.t0136

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kr.archsurvey.arch.ArchController
import kr.archsurvey.core.FixQuality
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import kr.archsurvey.device.*
import kr.archsurvey.dig.DigKind
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.export.ExportFormat
import kr.archsurvey.export.ExportService
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.photo.PhotoStore
import kr.archsurvey.t0130.rig
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
import androidx.compose.ui.unit.dp
import java.util.zip.ZipFile

/** 연결 플랫폼: 끊기·다시 잇기 횟수를 센다 */
internal class TrackConn(state: LinkState = LinkState.STREAMING) : ConnectionPlatform {
    var stops = 0; var starts = 0
    override val link = MutableStateFlow(LinkStatus(state))
    override val ntrip = MutableStateFlow(NtripStatus())
    override fun bondedDevices() = listOf("R10_5411457757" to "00:11:22:33:44:66")
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { starts++; link.value = LinkStatus(LinkState.STREAMING) }
    override fun stopReal() { stops++; link.value = LinkStatus() }
    override fun loadSaved() = SavedConnection(DeviceProfiles.R10_ID, "00:11:22:33:44:66", NtripForm(user = "u", password = "p"), true)
    override fun save(s: SavedConnection) {}
}

/** 0.1.36 화면 시험(Robolectric — 실기기 아님): 종료 창(현장 보고 재현) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class ExitUi0136Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 재현: R10 연결 중 홈에서 뒤로 → 1단계 창의 확인 단추.
     * 0.1.35: 제목 「기기 종료」·단추 「예」 → 누르면 블루투스만 끊기고 R10 전원은 그대로(앱엔 전원 끄기 명령이 없음) = 「안 꺼진다」.
     * 0.1.36: 제목·단추가 「수신기 연결 끊기」(본문: 앱은 전원을 끌 수 없음), 누르면 연결이 정말 끊기고(다시 잇지 않음) 이어서 프로그램 종료.
     */
    @Test fun homeBack_disconnectReallyStops_noReconnect_thenExit() {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        val conn = TrackConn()
        var exits = 0
        c.ui.toastMs = 600_000L
        c.homeOpen = true
        s.push(FixQuality.RTK_FIXED, 0.005)
        c.connection = ConnectionController(conn, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.onExitApp = { exits++ }
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals("수신기 연결 끊기")
        rule.onNodeWithTag("confirm_ok").assertTextContains("연결 끊기", substring = true)
        rule.onNodeWithTag("confirm_dialog").assertTextContains("전원을 끌 수 없어요", substring = true)
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals("다시 잇지 않음", 0, conn.starts)
        assertFalse(c.connection!!.linkActive())
        rule.onNodeWithTag("confirm_title", useUnmergedTree = true).assertTextEquals(AppExit.TITLE_APP)
        rule.onNodeWithTag("confirm_ok").performClick(); rule.waitForIdle()
        assertEquals(1, conn.stops); assertEquals(1, exits); assertEquals(0, conn.starts)
    }

    @Test fun longJobName_headerShowsTwoLines_pillStillVisible() {
        kr.archsurvey.ui.TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID)
        c.projectName = "제주 애월읍 광령리 1234번지 유적 시굴조사"
        c.homeOpen = false
        s.push(FixQuality.RTK_FIXED, 0.005)
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        rule.onNodeWithTag("header_title", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("status_pill").assertIsDisplayed()
        val lines = rule.onNodeWithTag("header_title", useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()
        val oneLine = with(rule.density) { 22.dp.toPx() }
        assertTrue("두 줄로 보임(높이 $lines > 한 줄 $oneLine)", lines > oneLine)
    }
}

/** 0.1.36 사진 기준점 흐름: 실제 Room + 가짜 수신기(실기기 아님) */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [34])
class GcpFlow0136Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: SurveyDb
    private lateinit var f: FakeGnssSource
    private lateinit var c: SurveyController
    private lateinit var store: RoomPointStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed())
        store = RoomPointStore(db, { "광령리" }, { "R10" })
        c = SurveyController(f, AntennaDb.parse(File("src/main/assets/antenna_db.json").readText()), scope, store = store)
        c.projectName = "광령리"
        c.arch = ArchController(c, RoomArchStore(db, { "광령리" }), scope)
        repeat(2) { f.emitNext() }
    }
    @After fun tearDown() { db.close() }
    private fun measure() { c.onMeasure(); repeat(12) { f.emitNext() }; c.dismissResult() }

    @Test fun tagsPerFeature_autoNext_savedAsAttributes_exportedPerFeature() = runBlocking {
        val a = c.dig.newFeature("수혈", prov = false)!!   // 1호 수혈
        val b = c.dig.newFeature("주거지", prov = false)!!  // 1호 주거지
        c.dig.select(DigKind.GCP, a)
        assertEquals(1, c.dig.tagId)
        assertEquals("사진 기준점 · #1" to "1호 수혈", c.dig.nowLine())
        measure(); measure()
        c.dig.stepTag(+3); assertEquals(6, c.dig.tagId)   // 장갑 −/+ : 3 → 6
        c.dig.stepTag(-2); assertEquals(4, c.dig.tagId)
        measure()
        val g = c.points.filter { it.digKind == "gcp" }
        assertEquals(listOf("1", "2", "4"), g.map { it.digTag })
        assertTrue(g.all { it.featureId == a })
        assertEquals(listOf("P-0001", "P-0002", "P-0003"), g.map { it.name })   // 점 번호는 그대로
        assertEquals(5, c.dig.tagId)
        // 디스크에도
        val disk = store.loadAll().filter { it.digKind == "gcp" }
        assertEquals(listOf("1", "2", "4"), disk.map { it.digTag }); assertTrue(disk.all { it.featureId == a })
        // 장부 제목 = 표적 번호, 무엇 줄 = AprilTag #n · 유구
        val l = c.dig.ledger(g.last())
        assertEquals("#4", l.title); assertTrue(l.line, l.line.contains("AprilTag #4") && l.line.contains("1호 수혈"))
        // 다른 유구로 바꾸면 같은 표적 묶음 다시 쓰기 → 1부터
        c.dig.chooseFeature(b)
        assertEquals(1, c.dig.tagId); assertFalse(c.dig.tagAlreadyMeasured)
        measure()
        // 같은 번호 다시 고르면 「다시 재기」
        c.dig.chooseFeature(a); c.dig.setTag(2); assertTrue(c.dig.tagAlreadyMeasured)
        assertTrue(c.dig.measureSub().contains("다시 재기"))
        // 측량한 것 › 유구 카드
        assertEquals(listOf(1, 2, 4), c.dig.featureCards().single { it.id == a }.gcpTags)
        assertEquals(listOf(1), c.dig.featureCards().single { it.id == b }.gcpTags)
        // 저녁 도면 묶음 → 유구마다 따로 파일
        val r = ExportService(ctx, db.dao(), PhotoStore(ctx, db.dao()), null, db.arch()).export("광령리", includePhotos = false, formats = ExportFormat.EVENING)
        val names = ZipFile(r.zip).use { z -> z.entries().toList().map { it.name } }
        val epsg = c.zone.epsg
        assertTrue(names.toString(), names.any { it.startsWith("itwin_gcp/1호_수혈_AprilTag_EPSG$epsg") && it.endsWith(".txt") })
        assertTrue(names.any { it.startsWith("itwin_gcp/1호_주거지_AprilTag_EPSG$epsg") && it.endsWith(".xml") })
        assertTrue("두 유구가 #1 을 같이 써서 전체 파일 없음", names.none { it.startsWith("itwin_gcp/전체_") })
        val txt = ZipFile(r.zip).use { z -> z.getInputStream(z.entries().toList().first { it.name.startsWith("itwin_gcp/1호_수혈") && it.name.endsWith(".txt") }).readBytes().toString(Charsets.UTF_8) }
        assertEquals(listOf("AprilTag #1", "AprilTag #2", "AprilTag #4"), txt.lines().filter { it.isNotBlank() }.map { it.substringBefore(',') })
    }

    @Test fun noFeatureSelected_isOutsideFeature() {
        c.dig.select(DigKind.GCP, null)
        assertEquals(DigKind.GCP, c.dig.kind)
        assertEquals("AprilTag #1 · 유구 밖", c.dig.measureSub())
        measure()
        assertNull(c.points.single().featureId); assertEquals("1", c.points.single().digTag)
    }
}
