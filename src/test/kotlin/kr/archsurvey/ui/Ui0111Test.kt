package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 0.1.11 목업 반영 화면 시험(Robolectric, JVM). 실기기·에뮬레이터 아님.
 * 홈 → 측량 지도(머리글·상태 알약·하단 도구·측량하기) → 환경설정 → 장비 연결 / VRS 설정 → GNSS 정보 → 점 목록.
 * 캡처: build-reports/v0111/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0111Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/v0111").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Stub : GnssSource {
        override val link = MutableStateFlow(LinkStatus())
        override val gnss = MutableStateFlow(GnssSnapshot())
        var now = 10_000L
        override fun clockMs() = now
        override fun stop() {}
    }

    private class P : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var started = 0; var stopped = 0
        override var autoConnect = false
        override fun bondedDevices() = listOf("Trimble R10-5512" to "00:11:22:33:44:55", "SP60-7781" to "AA:BB:CC:DD:EE:01")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
        override fun stopReal() { stopped++ }
        override fun loadSaved() = SavedConnection("trimble_r10", "00:11:22:33:44:55", NtripForm(user = "myid", password = "pw"), preChecked = true)
        override fun save(s: SavedConnection) {}
        var opts = true to true
        override fun ntripOptions() = opts
        override fun applyNtripOptions(sendGga: Boolean, autoRetry: Boolean) { opts = sendGga to autoRetry }
        var retried = 0
        override fun retryNtrip() { retried++ }
        override fun diagnostics() = "=== NMEA 활성화 spectra_sp60: ACK_ONLY · 4200ms · 시도 2"
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, "v0111_$name")
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setup(streaming: Boolean = true, home: Boolean = true): Triple<SurveyController, ConnectionController, Pair<Stub, P>> {
        val s = Stub(); val p = P()
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        c.projectName = "20261005-1040"
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui
        c.connection = cc
        c.homeOpen = home
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 82) } }
        if (streaming) {
            val gga = GgaMessage("", "000000", 37.5, 127.0, FixQuality.RTK_FIXED, 23, 0.8, 50.0, 25.0, 1.0, "0001")
            rule.runOnUiThread {
                s.link.value = LinkStatus(state = LinkState.STREAMING)
                s.gnss.value = GnssSnapshot(gga, GstMessage("", 0.01, 0.006, 0.006, 0.018), GsaMessage("", 3, 1.8, 0.9, 1.5), lastGgaAtMs = 10_000L)
                p.link.value = LinkStatus(state = LinkState.STREAMING)
                p.ntrip.value = NtripStatus(state = NtripState.RECEIVING, rtcmBytes = 12_800, framesOk = 42, lastRtcmAtMs = android.os.SystemClock.elapsedRealtime())
            }
        }
        rule.waitForIdle()
        return Triple(c, cc, s to p)
    }

    private fun assertTouchTargets(label: String) {
        val small = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { it.size.width / 3f < 47.5f || it.size.height / 3f < 47.5f }
        assertTrue("$label 48dp 미만: " + small.map { "${it.size.width / 3f}x${it.size.height / 3f}" }, small.isEmpty())
    }

    @Test fun a_homeShowsPillJobAndConnectionBar() {
        // 0.1.35: 홈 = 「오늘 준비」(작업명 명조 · 상태 알약 · 장부 · 5단계). PDOP·연결 문자열은 숨김(지금 상태 시트 / 설정 › 고급)
        setup()
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
        rule.onNodeWithTag("home_title").assertTextEquals("발굴 측량")
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측량 가능")
        rule.onNodeWithTag("home_job").assertTextContains("20261005-1040", substring = true)
        for (id in listOf("receiver", "vrs", "pole", "crs", "bm")) rule.onNodeWithTag("prep_$id").performScrollTo().assertExists()
        rule.onNodeWithTag("prep_count").assertTextContains("/ 5", substring = true)
        rule.onAllNodesWithText("[앱 이름]", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("PDOP", substring = true).assertCountEquals(0)
        assertTouchTargets("홈")
        shot("01_home")
    }

    @Test fun b_homeToSurveyMapAndBack() {
        val (c, _, _) = setup()
        // 0.1.35: 「측량 시작」 → 바로 지도(허브 없음)
        rule.onNodeWithTag("home_survey").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("survey_hub").assertDoesNotExist()
        assertFalse(c.homeOpen)
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측량 가능")
        rule.onNodeWithTag("measure_button").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("measure_button").getBoundsInRoot().let { it.bottom - it.top }.value >= 72f)
        for (tag in listOf("tool_locate", "tool_fit", "tool_zoom_in", "tool_zoom_out", "now_row", "pole_chip", "status_pill", "gear", "tool_undo")) rule.onNodeWithTag(tag).assertExists()
        val map = rule.onNodeWithTag("map_area").getBoundsInRoot(); val root = rule.onRoot().getBoundsInRoot()
        assertTrue("지도 비율", (map.bottom - map.top).value / (root.bottom - root.top).value >= 0.6f)
        assertTouchTargets("측량 지도")
        shot("02_survey_map")
        // 유구를 고르면 「면 닫기」가 생긴다
        rule.runOnUiThread { c.dig.select(kr.archsurvey.dig.DigKind.FEATURE, null) }; rule.waitForIdle()
        rule.onNodeWithTag("tool_close_area").assertExists()
        rule.onNodeWithTag("survey_back").performClick(); rule.waitForIdle()
        assertTrue(c.homeOpen)
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test fun c_settingsMenuToVrsOnlyAndDevice() {
        val (c, cc, _) = setup()
        // 0.1.35: 설정 한 화면 「꼭 필요한 네 가지」에서 VRS · 수신기
        rule.onNodeWithTag("home_settings2").performScrollTo().performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        assertTouchTargets("설정")
        shot("03_settings_menu")
        rule.onNodeWithTag("set_vrs").performClick(); rule.waitForIdle()
        assertTrue(cc.open); assertEquals(ConnSection.VRS, cc.section)
        rule.onAllNodesWithTag("header_title").assertAny(hasText("VRS 설정"))
        rule.onNodeWithTag("conn_host").assertExists()
        rule.onNodeWithTag("conn_port").assertExists()
        rule.onNodeWithTag("conn_mount_pick").assertExists()
        rule.onNodeWithTag("conn_vrs_status").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("conn_model_trimble_r10").assertDoesNotExist()
        // 0.1.11 통신 P1: GGA 전송·자동 재접속 스위치, kB/s·지연·경과
        rule.onNodeWithTag("conn_gga_row").assertExists()
        rule.onNodeWithTag("conn_vrs_meter", useUnmergedTree = true).assertTextContains("kB/s", substring = true)
        rule.onNodeWithTag("conn_vrs_meter", useUnmergedTree = true).assertTextContains("경과", substring = true)
        rule.onNodeWithTag("conn_save").assertExists()
        assertTouchTargets("VRS 설정")
        shot("05_vrs_settings")
        rule.onNodeWithTag("conn_autoretry_row").performScrollTo().performClick(); rule.waitForIdle()
        assertFalse(cc.autoRetry)
        rule.onNodeWithTag("conn_autoretry_note", useUnmergedTree = true).assertTextContains("다시 시도", substring = true)
        rule.onNodeWithTag("conn_back").performClick(); rule.waitForIdle()
        assertFalse(cc.open)
        rule.onNodeWithTag("set_receiver").performClick(); rule.waitForIdle()
        assertEquals(ConnSection.ALL, cc.section)
        rule.onAllNodesWithTag("header_title").assertAny(hasText("장비 연결"))
        rule.onNodeWithTag("conn_model_trimble_r10").assertIsDisplayed()
        rule.onNodeWithTag("conn_bonded_00:11:22:33:44:55").assertIsDisplayed()
        rule.onNodeWithTag("conn_connect").assertIsDisplayed()
        assertTouchTargets("장비 연결")
        shot("04_device_connect")
        rule.onNodeWithTag("conn_diag_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("conn_diag_text", useUnmergedTree = true).assertTextContains("NMEA 활성화", substring = true)
        // 시스템 뒤로 = 연결 화면 닫기 → 환경설정 닫기 → (홈)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle(); assertFalse(cc.open)
        rule.runOnUiThread { backTarget(c)!!.invoke() }; rule.waitForIdle(); assertNull(c.hub.screen)
        // 0.1.31 T27: 홈에서 뒤로 = 종료 확인 창(예전: null = 앱 기본 나가기 → 연결 서비스가 남았다)
        assertNotNull("홈에서는 종료 확인 창", backTarget(c))
    }

    @Test fun d_gnssInfoFromPill() {
        val (c, _, sp) = setup()
        val s = sp
        // 0.1.35: 알약 → 「지금 상태」(신호등 3칸) → GNSS 자세히
        rule.onNodeWithTag("status_pill").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dig_status").assertIsDisplayed()
        rule.onNodeWithTag("status_more").performClick(); rule.waitForIdle()
        assertTrue(c.gnssInfoOpen)
        rule.onNodeWithTag("gnss_info").assertIsDisplayed()
        shot("06_gnss_info")
        rule.onNodeWithText("75.000 m").assertExists()  // 타원체고 = 50 + 25
        // SkyPlot: GSV 없으면 빈 상태(가짜 위성 없음)
        rule.onNodeWithTag("gnss_info_sky").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("skyplot").assertIsDisplayed()
        rule.onNodeWithTag("skyplot_empty", useUnmergedTree = true).assertExists()
        val sats = listOf(SatInfo("GP", 3, 62, 40, 46), SatInfo("GP", 7, 25, 210, 38), SatInfo("GL", 66, 45, 120, 41), SatInfo("GA", 11, 70, 300, 48), SatInfo("GB", 23, 15, 90, 33))
        rule.runOnUiThread { sp.first.gnss.value = sp.first.gnss.value.copy(satsDetail = sats) }
        rule.waitForIdle()
        rule.onNodeWithTag("skyplot_empty", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("E11").assertExists()
        assertTouchTargets("SkyPlot")
        shot("06b_skyplot")
        rule.onNodeWithTag("skyplot_chip_G").performClick(); rule.waitForIdle()
        rule.onNodeWithText("G03").assertDoesNotExist()
        rule.onNodeWithTag("skyplot_close").performClick(); rule.waitForIdle()
        assertFalse(c.skyPlotOpen)
        rule.onNodeWithTag("gnss_info_ok").performClick(); rule.waitForIdle()
        assertFalse(c.gnssInfoOpen)
    }

    @Test fun f_sp60Rtcm34ShowsRecommendedMount() {
        val (c, cc, _) = setup()
        rule.runOnUiThread {
            cc.selectProfile(kr.archsurvey.device.DeviceProfiles.SP60)
            cc.updateForm(cc.form.copy(host = "RTS2.ngii.go.kr", mount = "VRS-RTCM34"))
            cc.openVrs()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("conn_rtcm_warn").performScrollTo().assertIsDisplayed()
        shot("05b_vrs_rtcm34_sp60")
        rule.onNodeWithTag("conn_rtcm_fix").performClick(); rule.waitForIdle()
        assertEquals("VRS-RTCM32", cc.form.mount)
        rule.onNodeWithTag("conn_rtcm_warn").assertDoesNotExist()
        assertNotNull(c)
    }

    @Test fun e_pointsAndResultAndNotConnected() {
        val (c, _, sp) = setup(home = false)
        val (s, _) = sp
        // 측점 3회 평균 → 결과 띠
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        repeat(4) { i ->
            rule.runOnUiThread {
                s.now += 1000
                val gga = GgaMessage("", "00000$i", 37.5, 127.0, FixQuality.RTK_FIXED, 23, 0.8, 50.0, 25.0, 1.0, "0001")
                s.gnss.value = GnssSnapshot(gga, GstMessage("", 0.01, 0.006, 0.006, 0.018), GsaMessage("", 3, 1.8, 0.9, 1.5), lastGgaAtMs = s.now)
            }
            rule.waitForIdle()
        }
        shot("07_after_measure")
        rule.runOnUiThread { c.hub.open(ToolScreen.POINTS) }; rule.waitForIdle()
        rule.onNodeWithTag("points_screen").assertIsDisplayed()
        rule.onAllNodesWithTag("header_title").assertAny(hasText("점 목록"))
        assertTrue(rule.onAllNodesWithTag("status_pill").fetchSemanticsNodes().size >= 2)
        shot("08_points")
        // 미연결: 알약 회색 「수신기 없음」(0.1.30), 측점 불가 유지
        rule.runOnUiThread { c.hub.closeAll(); s.link.value = LinkStatus(state = LinkState.IDLE); s.gnss.value = GnssSnapshot() }
        rule.waitForIdle()
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("수신기 연결 안 됨")
        rule.onNodeWithTag("cause_line").assertExists()
        rule.onNodeWithTag("measure_disabled_reason", useUnmergedTree = true).assertExists()
        shot("09_not_connected")
    }
}
