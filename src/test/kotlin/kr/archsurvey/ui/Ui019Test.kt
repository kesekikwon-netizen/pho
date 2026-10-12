package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.MountInfo
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

/** 0.1.9 화면 시험: 흰 테마(시스템 다크여도), 마운트 선택 창, 연결 진행 창·성공 저장, 3회 측정 진행·편차, 위성 오래됨. 가짜 플랫폼, 실기기 아님 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-night-xxhdpi")
class Ui019Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/v019").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class P(val mountsResult: () -> List<MountInfo>) : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var started = 0; var marked: NtripForm? = null; var saved: SavedConnection? = null
        var success = false; override var autoConnect = true
        val profiles = HashMap<String, NtripForm>()
        override fun bondedDevices() = listOf("Trimble R10-5512" to "00:11:22:33:44:55")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
        override fun stopReal() {}
        override fun loadSaved() = SavedConnection("trimble_r10", "00:11:22:33:44:55", NtripForm(user = "myid", password = "pw"), preChecked = true)
        override fun save(s: SavedConnection) { saved = s }
        override suspend fun fetchMounts(cfg: NtripConfig) = mountsResult()
        override fun hostProfile(host: String) = profiles[host.lowercase()]
        override fun markSuccess(form: NtripForm) { marked = form; success = true; profiles[form.host.lowercase()] = form }
        override fun hadSuccess() = success
    }

    private val table = listOf(
        MountInfo("VRS-RTCM31", "VRS", "RTCM 3.1", "GPS+GLO", true, "1004(1),1012(1)", 37.0, 127.0, "B", 9600, "NGII"),
        MountInfo("VRS-RTCM32", "VRS", "RTCM 3.2", "GPS+GLO+GAL+BDS", true, "MSM4", 37.0, 127.0, "B", 9600, "NGII"),
        MountInfo("FKP-RTCM31", "FKP", "RTCM 3.1", "GPS+GLO", true, "", 37.0, 127.0, "B", 4800, "NGII"),
        MountInfo("SUWN-RTCM32", "Suwon", "RTCM 3.2", "GPS+GLO+GAL+BDS", false, "MSM7", 37.275, 127.054, "B", 4800, "NGII"),
        MountInfo("SSR-SSRG", "SSR", "SSR", "GPS", false, "", null, null, "B", null, "NGII"),
        MountInfo("VRS-CMRx", "VRS", "CMRx", "GPS+GLO", true, "", 37.0, 127.0, "B", null, "NGII"),
    )

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun bitmap(): Bitmap {
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        return bmp
    }

    private fun setup(src: GnssSource? = null, mounts: () -> List<MountInfo> = { table }, epochs: Int = 3): Triple<SurveyController, ConnectionController, P> {
        val p = P(mounts)
        val s = src ?: FakeGnssSource(FakeScenarios.fieldWalk())
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), epochsPerPoint = epochs)
        c.resultStripMs = Long.MAX_VALUE
        c.ui.toastMs = 600_000L
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        cc.ui = c.ui
        c.connection = cc
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 82) } }   // 시스템 다크(night)여도 흰 테마
        rule.waitForIdle()
        return Triple(c, cc, p)
    }

    @Test fun connectionScreenIsWhiteEvenWhenSystemIsDark() {
        val (_, cc, _) = setup()
        rule.runOnUiThread { cc.openVrs() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_service").assertExists()
        rule.onNodeWithTag("conn_mount_pick").assertExists()
        rule.onNodeWithTag("conn_auto_row").assertExists()
        val b = bitmap()
        val px = b.getPixel(4, b.height / 2)
        // 0.1.11: 아이보리/오프화이트 바탕(F7F6F2) — 시스템 다크여도 밝은 바탕
        assertTrue("가장자리 배경 밝음(빨강 ${android.graphics.Color.red(px)})", android.graphics.Color.red(px) >= 240)
        assertTrue(android.graphics.Color.green(px) >= 240); assertTrue(android.graphics.Color.blue(px) >= 235)
        shot("01_conn_white")
    }

    @Test fun mountPickerListsSourcetableWithSearchAndSaves() {
        val (c, cc, p) = setup()
        rule.runOnUiThread { cc.openVrs() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_mount_pick").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mount_picker").assertExists()
        rule.onNodeWithTag("mount_ok").assertTextContains("6개", substring = true)
        for (m in table) rule.onNodeWithTag("mount_item_${m.mountPoint}").assertExists()
        shot("02_mount_picker")
        rule.onNodeWithTag("mount_search").performTextInput("rtcm32"); rule.waitForIdle()
        rule.onNodeWithTag("mount_item_VRS-RTCM32").assertExists()
        rule.onNodeWithTag("mount_item_SUWN-RTCM32").assertExists()
        rule.onNodeWithTag("mount_item_FKP-RTCM31").assertDoesNotExist()
        rule.onNodeWithTag("mount_item_SUWN-RTCM32").performClick(); rule.waitForIdle()
        assertEquals("SUWN-RTCM32", cc.form.mount)
        assertFalse(cc.mountPickerOpen)
        assertEquals("SUWN-RTCM32", p.saved!!.ntrip.mount)
        assertTrue(c.ui.toast!!.contains("마운트 포인트 저장됨"))
        rule.onNodeWithTag("toast").assertExists()
    }

    @Test fun mountPickerFallsBackToKnownListAndManualInput() {
        val (_, cc, _) = setup(mounts = { throw java.io.IOException("소스테이블 응답이 없어요(시간 초과)") })
        rule.runOnUiThread { cc.openVrs() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_mount_pick").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mount_failed").assertExists()
        rule.onNodeWithTag("mount_known_note").assertTextContains("미확인", substring = true)
        rule.onNodeWithTag("mount_item_VRS-RTCM31").assertExists()
        rule.onNodeWithTag("mount_item_RTK-RTCM32").assertExists()
        shot("03_mount_fallback")
        rule.onNodeWithTag("mount_manual").performTextReplacement("MY-MOUNT"); rule.waitForIdle()
        rule.onNodeWithTag("mount_apply").performClick(); rule.waitForIdle()
        assertEquals("MY-MOUNT", cc.form.mount)
    }

    @Test fun serviceChoiceRecallsLastSuccessfulProfileOfThatServer() {
        val (c, cc, p) = setup()
        p.profiles["rts2.ngii.go.kr"] = NtripForm("RTS2.ngii.go.kr", "2102", "VRS-RTCM32", "id2", "pw2")
        rule.runOnUiThread { cc.openVrs() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_service").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("choice_sheet").assertExists()
        rule.onNodeWithTag("service_1").performClick(); rule.waitForIdle()
        assertEquals("RTS2.ngii.go.kr", cc.form.host); assertEquals("2102", cc.form.port); assertEquals("id2", cc.form.user); assertEquals("pw2", cc.form.password)
        assertTrue(c.ui.toast!!.contains("마지막 성공 설정"))
    }

    @Test fun connectShowsProgressStepsAndSavesOnceOnSuccess() {
        val (c, cc, p) = setup()
        rule.runOnUiThread { cc.openScreen() }; rule.waitForIdle()
        rule.onNodeWithTag("conn_connect").performClick(); rule.waitForIdle()
        assertEquals(1, p.started)
        rule.onNodeWithTag("conn_progress").assertExists()
        rule.onNodeWithTag("conn_progress_elapsed").assertExists()
        rule.runOnUiThread { p.link.value = LinkStatus(LinkState.CONNECTING) }; rule.waitForIdle()
        rule.onNodeWithTag("conn_progress_step_0").assertTextContains("진행 중", substring = true)
        rule.runOnUiThread { p.link.value = LinkStatus(LinkState.STREAMING); p.ntrip.value = NtripStatus(NtripState.CONNECTED) }; rule.waitForIdle()
        rule.onNodeWithTag("conn_progress_step_1").assertTextContains("수신기 위치 데이터", substring = true)
        rule.onNodeWithTag("conn_progress_step_3").assertTextContains("진행 중", substring = true)
        shot("04_conn_progress")
        assertNull("보정 수신 전에는 저장 알림 없음", p.marked)
        rule.runOnUiThread { p.ntrip.value = NtripStatus(NtripState.RECEIVING) }; rule.waitForIdle()
        assertNotNull(p.marked); assertEquals("myid", p.marked!!.user)
        assertTrue(c.ui.toast!!.startsWith("연결 설정 저장됨"))
        val first = p.marked
        rule.runOnUiThread { p.ntrip.value = NtripStatus(NtripState.RECEIVING, "x") }; rule.waitForIdle()
        assertSame("한 번만 저장", first, p.marked)
        rule.onNodeWithTag("conn_progress_close").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("conn_progress").assertDoesNotExist()
        // 자동 연결: 다음 실행 흉내(0.1.32: 엔진이 아직 돌면 다시 맺지 않고 이어받으므로 프로세스가 끝난 상태로)
        rule.runOnUiThread { p.link.value = LinkStatus() }
        val cc2 = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertTrue(cc2.autoConnectOnLaunch()); assertEquals(2, p.started)
        p.autoConnect = false
        val cc3 = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(cc3.autoConnectOnLaunch())
    }

    @Test fun threeEpochMeasurementShowsProgressAndSamplesInDetail() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val (c, _, _) = setup(src = f)
        fun adv(n: Int) { rule.runOnIdle { repeat(n) { f.emitNext() } }; rule.waitForIdle() }
        adv(3)
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertEquals(Phase.ACQUIRING, c.phase)
        // 0.1.35 X4: 진행은 측점 단추 안(지도·내 위치를 가리지 않음)
        rule.onNodeWithTag("measure_progress", useUnmergedTree = true).assertExists()
        var guard = 0
        while (c.measureCount < 1 && guard++ < 5) adv(1)
        assertEquals(1, c.measureCount)
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("재는 중 1/3")
        shot("05_measure_1of3")
        adv(1)
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("재는 중 2/3")
        adv(1)
        assertEquals(Phase.RESULT, c.phase)
        val p = c.points.last()
        assertEquals(3, p.epochs); assertEquals(3, p.samples.size)
        assertTrue(c.ui.toast!!.contains("3회 평균"))
        rule.onNodeWithTag("toast").assertExists()
        shot("06_saved_toast")
        rule.runOnUiThread { c.ui.hideToast(); c.hub.open(ToolScreen.POINTS) }; rule.waitForIdle()
        rule.onNodeWithTag("point_row_${p.name}").performScrollTo().performClick(); rule.waitForIdle()
        for (i in 0 until 3) rule.onNodeWithTag("sample_$i").performScrollTo().assertExists()
        rule.onNodeWithTag("samples_spread").performScrollTo().assertTextContains("mm", substring = true)
        shot("07_point_samples")
    }

    @Test fun stopDuringMeasurementCancels() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk())
        val (c, _, _) = setup(src = f)
        rule.runOnIdle { repeat(3) { f.emitNext() } }; rule.waitForIdle()
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        // 0.1.35: 재는 중 측점 단추 다시 누름 = 중지
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        assertNotEquals(Phase.ACQUIRING, c.phase); assertEquals(0, c.points.size)
    }

    private class Late(val f: FakeGnssSource) : GnssSource by f { var extra = 0L; override fun clockMs() = f.clockMs() + extra }

    @Test fun satelliteCountShowsUsedAndMarksStale() {
        val f = FakeGnssSource(FakeScenarios.fieldWalk()); val s = Late(f)
        val (c, _, _) = setup(src = s)
        rule.runOnIdle { repeat(3) { f.emitNext() } }; rule.waitForIdle()
        assertTrue(bandDetail(c).contains("위성 ${c.gv.satellites}"))
        assertFalse(bandDetail(c).contains("오래됨"))
        s.extra = 5_000L
        assertTrue(bandDetail(c), bandDetail(c).contains("5초 전(오래됨)"))
    }
}
