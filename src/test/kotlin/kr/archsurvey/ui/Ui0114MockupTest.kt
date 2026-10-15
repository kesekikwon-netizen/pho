package kr.archsurvey.ui

import kr.archsurvey.settingsTab

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.FakeTiltPlatform
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.tiltReading
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 0.1.14 목업 01~23 대응 화면을 하나씩 열어 캡처(build-reports/v0114/mockup_XX_*.png)하고 핵심 태그를 확인한다.
 * 01 로그인은 앱 정책(계정 없음)으로 건너뜀. Robolectric(JVM) — 실기기·에뮬레이터 아님.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0114MockupTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val out = File(REPORTS_ROOT + "/v0114").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Jobs : JobPlatform {
        override var current = "발굴-3지구"; val names = linkedSetOf("발굴-3지구")
        override suspend fun list() = names.map { JobInfo(it, 3, 1, 0, null, null) }
        override suspend fun info(name: String) = JobInfo(name, 3, 1, 0, null, null)
        override suspend fun create(name: String) { names += name; current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String): String { names -= name; return "b.zip" }
    }
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }
    private class Exp : ExportPlatform {
        var lastFormats: Set<kr.archsurvey.export.ExportFormat>? = null
        override suspend fun summary() = ExportSummary("발굴-3지구", 3, 1, 0, 0)
        override suspend fun export(includePhotos: Boolean, includeVoices: Boolean) = ExportOutput("발굴-3지구_20261005.zip", 52_000, "/x/a.zip", 3, 1, 0)
        override suspend fun exportFormats(formats: Set<kr.archsurvey.export.ExportFormat>, includePhotos: Boolean, includeVoices: Boolean): ExportOutput {
            lastFormats = formats; return export(includePhotos, includeVoices)
        }
        var shared = 0
        override fun share(out: ExportOutput) { shared++ }
        override fun saveAs(out: ExportOutput, onDone: (Boolean) -> Unit) = onDone(true)
    }
    private class Bak : BackupPlatform {
        override suspend fun list() = listOf(BackupItem("backup_20261005_101000.zip", "/b/1", 1_791_100_000_000L, 3, 1, 0, 1_200_000))
        override suspend fun create() = BackupItem("backup_new.zip", "/b/2", 1_791_100_100_000L, 3, 1, 0, 1_200_100)
        override suspend fun restore(item: BackupItem) = item
        override suspend fun importPicked(): BackupItem? = null; override suspend fun delete(item: BackupItem) {}
        override fun share(item: BackupItem) {}; override fun saveAs(item: BackupItem, onDone: (Boolean) -> Unit) = onDone(true); override fun restartApp() {}
    }
    private class Store : PointStore {
        val saved = ArrayList<SavedPoint>()
        override suspend fun loadAll() = saved.toList(); override suspend fun maxSeq() = saved.size
        override suspend fun save(p: SavedPoint): Long { saved.add(p); return saved.size.toLong() }
        override suspend fun hideLast() {}
    }
    private class Media : MediaPlatform {
        val ph = ArrayList<PhotoItem>(); val vo = ArrayList<VoiceItem>()
        override suspend fun photos(pointRowId: Long) = ph.toList(); override suspend fun voices(pointRowId: Long) = vo.toList()
        override fun takePhoto(pointId: String, pointRowId: Long, onDone: (Long?) -> Unit) { val id = ph.size + 1L; ph.add(PhotoItem(id, "x.jpg")); onDone(id) }
        override fun pickPhotos(pointId: String, pointRowId: Long, onDone: (Int) -> Unit) = onDone(0)
        override suspend fun deletePhoto(id: Long) { ph.removeAll { it.id == id } }
        override fun thumb(rel: String): androidx.compose.ui.graphics.ImageBitmap? = null
        override fun hasMicPermission() = true; override fun requestMicPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun startRecording(pointId: String): String? = null
        override suspend fun finishRecording(pointRowId: Long): VoiceItem? = null
        override fun cancelRecording() {}; override fun play(rel: String, onDone: () -> Unit) = true; override fun stopPlayback() {}
        override suspend fun deleteVoice(id: Long) {}
    }
    private class Conn : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        override fun bondedDevices() = listOf("Trimble R10-5512" to "00:11:22:33:44:55", "SP60-6021" to "00:11:22:33:44:66")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection(null, null, NtripForm())
        override fun save(s: SavedConnection) {}
    }

    private lateinit var c: SurveyController
    private lateinit var f: FakeGnssSource
    private lateinit var cc: ConnectionController
    private lateinit var media: Media
    private val exp = Exp()
    private val shots = ArrayList<String>()

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name
    }

    private fun setup(points: Int = 3) {
        TileImages.networkEnabled = false
        TapHaptics.reset()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        f = FakeGnssSource(FakeScenarios.allFixed()); media = Media()
        c = SurveyController(f, db, scope, epochsPerPoint = 3, store = Store())
        c.ui.toastMs = 600_000L
        val jobs = Jobs(); c.projectName = jobs.current
        c.media = media
        c.settingsState = SettingsState(c, Sett())
        c.jobs = JobController(c, jobs, scope, MemoryJobConfigPlatform())
        c.vworld = VWorldSettingsState(MemoryMapKeyPlatform(), scope, Dispatchers.Unconfined)
        c.exporter = ExportController(exp, scope)
        c.backup = BackupController(Bak(), scope)
        cc = ConnectionController(Conn(), scope)
        c.connection = cc
        c.bg = kr.archsurvey.ui.bg.BackgroundController({ c.projection }, kr.archsurvey.georef.JsonFileBgLayerStore(tmp.newFolder()))
        val tp = FakeTiltPlatform()
        c.tilt = TiltController(tp).also { it.resume(); repeat(60) { _ -> it.onSample(tiltReading(0.6, 0.3)) } }
        repeat(2) { f.emitNext() }
        repeat(points) { c.onMeasure(); repeat(7) { f.emitNext() } }
        c.dismissResult(); c.ui.hideToast()
        c.homeOpen = true
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
    }

    private fun ui(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }
    private fun reset() = ui { c.goToSurvey(); c.homeOpen = false; c.ui.hideToast() }

    @Test fun all23MockupScreens() {
        setup()
        assertEquals(3, c.points.size)
        // 02 홈
        rule.onNodeWithTag("home_screen").assertExists(); shot("mockup_02_home")
        // 03 환경설정 (목업 순서)
        // 0.1.35: 환경설정 목록 → 설정 한 화면(꼭 필요한 네 가지 + 측점 + 발굴 + 고급)
        ui { c.homeOpen = false; c.hub.open(ToolScreen.SETTINGS) }
        for (tag in listOf("set_receiver", "set_vrs", "set_pole", "zone_toggle")) rule.settingsTab(1).onNodeWithTag(tag).performScrollTo().assertExists()
        for (tag in listOf("epochs_toggle", "set_strict_quality")) rule.settingsTab(0).onNodeWithTag(tag).performScrollTo().assertExists()
        shot("mockup_03_settings")
        // 03→RTK 설정
        reset(); ui { c.hub.open(ToolScreen.RTK) }; rule.onNodeWithTag("rtk_compat").assertExists(); shot("mockup_03b_rtk")
        // 04 장비 연결
        reset(); ui { cc.openScreen() }
        rule.onNodeWithTag("conn_model_other").assertExists(); rule.onNodeWithTag("conn_refresh").assertExists(); shot("mockup_04_device")
        // 05 GNSS 정보
        reset(); ui { cc.close(); c.gnssInfoOpen = true }; rule.onNodeWithTag("gnss_info").assertExists(); shot("mockup_05_gnss_info")
        // 06 SkyPlot
        ui { c.skyPlotOpen = true }; rule.onNodeWithTag("skyplot_chip_J").assertExists(); rule.onNodeWithTag("skyplot_mask").assertExists(); shot("mockup_06_skyplot")
        // 07 VRS
        reset(); ui { cc.openVrs() }; shot("mockup_07_vrs")
        // 08 일반 측량 메뉴
        reset(); ui { cc.close(); c.hub.open(ToolScreen.SURVEY_HUB) }; rule.onNodeWithTag("hub_survey").assertExists(); shot("mockup_08_survey_hub")
        // 09 작업관리
        ui { c.hub.open(ToolScreen.JOB_HUB) }; rule.onNodeWithTag("jobhub_new").assertExists(); shot("mockup_09_job_hub")
        // 10 새 작업
        ui { c.jobs!!.openNew(); c.jobs!!.newDetailsOpen = true }; rule.onNodeWithTag("job_zone_note").performScrollTo().assertExists() /* 0.1.39: 자동 토글 → 추천 타일 미리 고름 */; rule.onNodeWithTag("job_codes").assertDoesNotExist() /* 0.1.38: 위치 종류(코드 라이브러리)는 발굴 종류로 대체돼 숨김 */; rule.onNodeWithTag("job_name").assertExists(); shot("mockup_10_new_job")
        // 11 작업 전송 양식
        reset(); ui { c.exporter!!.openScreen() }
        rule.onNodeWithTag("export_options").performScrollTo().performClick(); rule.waitForIdle() // 0.1.56 S4 접힘
        rule.onNodeWithTag("export_fmt_shp").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("export_more").performScrollTo().performClick(); rule.waitForIdle() // 0.1.43 「다른 형식」 접기
        rule.onNodeWithTag("export_fmt_kml").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("export_screen").performTouchInput { swipeDown() }
        shot("mockup_11_export_format")
        // 12 전송 방식
        // 0.1.43 시안 ⑨: 기본 = 도면 DXF · Shape · 점 목록 · 야장 (+사진·음성 묶음) → Shape 빼고 KML 넣기 = 파일 5개
        rule.onNodeWithTag("export_options_text", useUnmergedTree = true).assertTextContains("5개", substring = true); rule.onNodeWithTag("export_run").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("export_saved_path").assertExists()
        assertEquals(setOf(kr.archsurvey.export.ExportFormat.DRAWING_DXF, kr.archsurvey.export.ExportFormat.FIELD_CSV, kr.archsurvey.export.ExportFormat.CSV, kr.archsurvey.export.ExportFormat.KML), exp.lastFormats)
        rule.onNodeWithTag("export_share").assertExists(); rule.onNodeWithTag("export_save").assertExists()
        shot("mockup_12_export_method")
        rule.onNodeWithTag("export_share").performClick(); rule.waitForIdle()
        assertEquals(2, exp.shared) /* 0.1.55: 만들기 = 바로 공유 1번 + 다시 공유 1번 */; rule.onNodeWithTag("export_notice").assertExists() /* 0.1.56 M5 토스트 대신 화면 안 */
        // 13 배경맵 설정
        reset(); ui { c.exporter!!.close(); c.bg!!.openManage() }
        rule.onNodeWithTag("bg_tab_0").assertExists(); shot("mockup_13_background")
        rule.onNodeWithTag("bg_tab_0").performClick(); rule.waitForIdle(); rule.onNodeWithTag("bg_tab_range_note").assertExists(); shot("mockup_13b_background_range")
        // 14 백업/복구 창
        reset(); ui { c.bg!!.close(); c.backupQuickOpen = true }; rule.onNodeWithTag("backup_quick").assertExists(); shot("mockup_14_backup_restore")
        // 15 측량 지도
        reset(); for (tag in listOf("tool_points", "now_row", "pole_chip", "status_pill", "measure_button")) rule.onNodeWithTag(tag).assertExists()
        shot("mockup_15_survey_map")
        // 16 측량 설정
        ui { c.setupOpen = true }
        rule.onNodeWithTag("setup_memo_manual").performScrollTo().performClick(); rule.waitForIdle()
        for (tag in listOf("setup_code", "setup_memo", "setup_antenna", "setup_ref", "setup_autolink", "setup_volume_key", "setup_link_line", "setup_link_area", "setup_link_point", "setup_new_link", "setup_tilt", "setup_apply", "setup_cancel")) rule.onNodeWithTag(tag).assertExists()
        // 0.1.58 B8: 측량 설정에서 볼륨 키 측정 켜고 끄기(기본 꺼짐)
        assertFalse(c.dig.volumeKey)
        rule.onNodeWithTag("setup_volume_key").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle(); assertTrue(c.dig.volumeKey)
        rule.onNodeWithTag("setup_volume_key").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle(); assertFalse(c.dig.volumeKey)
        shot("mockup_16_survey_settings")
        ui { c.setupOpen = false }
        // 17 측량 결과
        ui { c.onMeasure(); repeat(7) { f.emitNext() } }
        assertEquals(Phase.RESULT, c.phase)
        shot("mockup_17a_result_strip")
        // 0.1.35: 「계속 측량할까요」 패널 없음 — 장부 카드 + 측점 단추가 「다음 점」(X3)
        ui { c.ui.hideToast(); c.openPointDetail() }
        rule.onNodeWithTag("point_confirm").assertExists()
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("측량")
        shot("mockup_17_survey_result")
        // 18 0.1.59: 찍으면 바로 저장 + 되돌리기 알림(전체 화면 확인 없음)
        rule.onNodeWithTag("result_photo").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("photo_review").assertDoesNotExist()
        if (media.ph.isEmpty()) ui { c.addPhotoCamera() } // 점 확인 창이 단추를 덮는 경우(이전 시험도 직접 열었다)
        rule.waitForIdle()
        assertEquals(1, media.ph.size); assertEquals("되돌리기", c.ui.toastActionLabel); shot("mockup_18_photo_capture")
        // 19 점 목록
        reset(); ui { c.hub.open(ToolScreen.POINTS) }
        rule.onNodeWithTag("points_tab_0").assertExists(); shot("mockup_19_point_manager")
        // 20 길게 누름 메뉴
        val p0 = c.points.first()
        rule.onNodeWithTag("point_row_${p0.name}").performTouchInput { longClick() }; rule.waitForIdle()
        rule.onNodeWithTag("point_menu").assertExists(); shot("mockup_20_point_longpress")
        ui { c.pointMenuFor = null }
        // 21 점 상세
        ui { c.hub.openDetail(c.points[1]) }
        rule.onNodeWithTag("detail_measure").performScrollTo().assertExists(); rule.onNodeWithTag("detail_map_bar").assertExists()
        shot("mockup_21_point_detail")
        // 22 위치 모양
        reset(); ui { c.markerStyleOpen = true }; rule.onNodeWithTag("marker_style").assertExists(); shot("mockup_22_marker_style")
        rule.onNodeWithTag("ms_apply").performClick(); rule.waitForIdle(); assertFalse(c.markerStyleOpen)
        // 23 IMU · 기울기
        reset(); ui { c.hub.open(ToolScreen.IMU) }
        rule.onNodeWithTag("imu_receiver_state").assertExists(); rule.onNodeWithTag("imu_phone_note").performScrollTo().assertExists()
        shot("mockup_23_imu_tilt")
        // 22개 목업(01 로그인 제외) + 보조 3장(03b RTK · 13b 범위 탭 · 17a 결과 띠)
        assertEquals(25, shots.size)
    }

    /** 홈 큰 카드 → 일반 측량 메뉴 → 측량(지도) · 홈 작업 관리 → 작업관리 메뉴 */
    @Test fun homeCardsFollowMockupFlow() {
        // 0.1.35: 홈 「측량 시작」 → 바로 지도, 「작업」 → 작업 정보 → 적용 → 지도
        setup()
        rule.onNodeWithTag("home_survey").performClick(); rule.waitForIdle()
        assertEquals("survey", c.visibleRoute())
        ui { c.homeOpen = true }
        rule.onNodeWithTag("home_job_btn").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("tool:JOB_INFO", c.visibleRoute())
        rule.onNodeWithTag("job_apply").performClick(); rule.waitForIdle()
        assertEquals("survey", c.visibleRoute())
    }

    /** 수신기 IMU 와 폰 기포를 문구로 구분: 수신기 쪽은 보정 불가 안내, 폰 쪽은 참고용·좌표 미보정 */
    @Test fun imuCopySeparatesReceiverImuFromPhoneBubble() {
        setup()
        ui { c.homeOpen = false; c.hub.open(ToolScreen.IMU) }
        rule.onNodeWithTag("imu_receiver_note").assertTextContains("수신기", substring = true)
        rule.onNodeWithTag("imu_phone_note").performScrollTo().assertTextContains("좌표", substring = true)
        rule.onNodeWithTag("imu_receiver_cal").performClick(); rule.waitForIdle()
        assertEquals(ToastKind.ERROR, c.ui.toastKind)
    }

    /** 마커 스타일 저장 콜백 + 지도 상태 반영 */
    @Test fun markerStyleAppliesAndPersists() {
        setup()
        var saved: String? = null
        ui { c.homeOpen = false; c.onMarkerStyleChanged = { saved = it.encode() }; c.markerStyleOpen = true }
        rule.onNodeWithTag("ms_apply").performClick(); rule.waitForIdle()
        assertNotNull(saved)
        assertEquals(kr.archsurvey.map.MarkerStyle.decode(saved), c.markerStyle)
        assertEquals(ToastKind.OK, c.ui.toastKind)
    }
}
