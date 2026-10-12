package kr.archsurvey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kr.archsurvey.bt.BtError
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.feedback.FeedbackPlayer
import kr.archsurvey.feedback.bindEngineFeedback
import kr.archsurvey.ntrip.MountInfo
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ui.*
import kr.archsurvey.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 0.1.19 효과음·연결 피드백 배선. JVM/Robolectric — 실기기 미디어 볼륨·체감 음량 미시험 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundFeedback0119Tests {

    private class P(
        saved: SavedConnection = SavedConnection(DeviceProfiles.R10_ID, "AA:BB", NtripForm(user = "u", password = "p"), true),
    ) : ConnectionPlatform {
        override val link = MutableStateFlow(LinkStatus())
        override val ntrip = MutableStateFlow(NtripStatus())
        var stopped = 0; var started = 0
        private val s = saved
        override fun bondedDevices() = listOf("R10" to "AA:BB")
        override fun hasBluetoothPermission() = true
        override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun bluetoothEnabled() = true
        override fun openBluetoothSettings() {}
        override fun openAppSettings() {}
        override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started++ }
        override fun stopReal() { stopped++ }
        override fun loadSaved() = s
        override fun save(s: SavedConnection) {}
    }

    private fun ctl(p: P = P()) = ConnectionController(p).also { c -> for (i in c.checks.indices) c.setCheck(i, true) }

    private fun player(sound: Boolean = true, vib: Boolean = true): FeedbackPlayer {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("settings", 0).edit().clear().putBoolean("sound", sound).putBoolean("vibration", vib).commit()
        return FeedbackPlayer(ctx, SettingsStore(ctx))
    }

    @Test fun bindEngineEvents_playsThroughPlayer() = runBlocking {
        val events = MutableSharedFlow<FeedbackEvent>(extraBufferCapacity = 8)
        val played = ArrayList<FeedbackEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = bindEngineFeedback(scope, events) { played.add(it) }
        assertTrue(events.tryEmit(FeedbackEvent.FIX_ACQUIRED))
        assertTrue(events.tryEmit(FeedbackEvent.LINK_LOST))
        assertTrue(events.tryEmit(FeedbackEvent.NTRIP_OK))
        yield()
        assertEquals(listOf(FeedbackEvent.FIX_ACQUIRED, FeedbackEvent.LINK_LOST, FeedbackEvent.NTRIP_OK), played)
        job.cancel()
    }

    @Test fun soundOff_skipsBeep_keepsVibrationWhenOn() {
        val fp = player(sound = false, vib = true)
        fp.play(FeedbackEvent.SURVEY_OK)
        assertNull("소리 OFF → beep 스킵", fp.lastBeep)
        assertEquals(FeedbackEvent.SURVEY_OK, fp.lastVibrate)
        assertEquals(1, fp.playCount)
    }

    @Test fun soundOn_beepsSurveyOk() {
        val fp = player(sound = true, vib = false)
        fp.play(FeedbackEvent.SURVEY_FAIL)
        assertEquals(FeedbackEvent.SURVEY_FAIL, fp.lastBeep)
        assertNull(fp.lastVibrate)
    }

    @Test fun connectOk_fail_disconnect_events_noDoubleWithLink() {
        val p = P(); val c = ctl(p)
        val got = ArrayList<FeedbackEvent>()
        c.onFeedback = { got.add(it) }
        // 성공 전 실패 → CONNECT_FAIL (LINK_LOST 아님)
        c.connect(); p.link.value = LinkStatus(LinkState.NEEDS_USER, error = BtError.CONNECT_TIMEOUT)
        c.onStatus(p.link.value, NtripStatus())
        assertEquals(listOf(FeedbackEvent.CONNECT_FAIL), got)
        // 다시 연결 → STREAMING 성공 → CONNECT_OK
        got.clear()
        c.connect(); p.link.value = LinkStatus(LinkState.STREAMING)
        c.onStatus(p.link.value, NtripStatus(NtripState.RECEIVING))
        assertEquals(listOf(FeedbackEvent.CONNECT_OK), got)
        assertTrue(c.savedThisRun)
        // 성공 뒤 같은 실패 상태 → CONNECT_FAIL 안 냄(LINK_* 경로만)
        got.clear()
        p.link.value = LinkStatus(LinkState.NEEDS_USER, error = BtError.SOCKET_CLOSED)
        c.onStatus(p.link.value, NtripStatus())
        assertTrue(got.isEmpty())
        // 사용자 해제 → DISCONNECT
        c.disconnect()
        assertEquals(listOf(FeedbackEvent.DISCONNECT), got)
    }

    @Test fun cancelConnect_noDisconnectSound() {
        val p = P(); val c = ctl(p)
        val got = ArrayList<FeedbackEvent>()
        c.onFeedback = { got.add(it) }
        c.connect(); c.cancelConnect()
        assertTrue(got.isEmpty())
    }

    @Test fun keepHint_usesQueueWording() {
        assertFalse(ConnectionController.KEEP_HINT.contains("상태 띠"))
        assertTrue(ConnectionController.KEEP_HINT.contains("대기열"))
    }

    @Test fun startNow_clearsNotice() {
        val p = P(); val c = ctl(p)
        // 권한/기기 없이 notice 를 직접 만들기 어렵다면 체크 미완으로
        for (i in c.checks.indices) c.setCheck(i, false)
        c.connect()
        assertNotNull(c.notice)
        for (i in c.checks.indices) c.setCheck(i, true)
        c.connect()
        assertNull("startNow 후 notice 지움", c.notice)
        assertTrue(c.connectRequested)
    }

    @Test fun selectedMountHidden_byCompatFilter() {
        val sp = ctl(P(SavedConnection(DeviceProfiles.SP60_ID, "AA:BB", NtripForm(user = "u", password = "p", mount = "VRS-RTCM34"), true)))
        val list = listOf(
            MountInfo("VRS-RTCM31", "", "RTCM 3.1", "GPS", true),
            MountInfo("VRS-RTCM34", "", "RTCM 3.4", "GPS", true),
        )
        val f = ConnectionController::class.java.getDeclaredField("mounts\$delegate"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") (f.get(sp) as androidx.compose.runtime.MutableState<List<MountInfo>>).value = list
        assertEquals("VRS-RTCM34", sp.form.mount)
        assertTrue(sp.mountCompatOnly)
        assertTrue(sp.selectedMountHiddenByFilter())
        sp.mountCompatOnly = false
        assertFalse(sp.selectedMountHiddenByFilter())
    }

    @Test fun connectEvents_haveDistinctPatternsFromLinkLost() {
        // CONNECT_FAIL ≠ LINK_LOST 패턴(이중 비프 시 구분 가능)
        assertFalse(FeedbackEvent.CONNECT_FAIL.pattern.contentEquals(FeedbackEvent.LINK_LOST.pattern))
        assertFalse(FeedbackEvent.CONNECT_OK.pattern.contentEquals(FeedbackEvent.LINK_RESTORED.pattern))
        assertEquals(3, FeedbackEvent.LINK_LOST.pattern.toList().drop(1).filterIndexed { i, _ -> i % 2 == 0 }.size)
    }
}
