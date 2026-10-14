package kr.archsurvey.core0147

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.core0140.FakeConn
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.48 · 0.1.10: 한 앱이 켜지면 다른 앱은 완전히 닫힘(방송은 늦게 도착) */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseOther0148Test {
    private var now = 0L
    private val mac = "AA:11"
    private val dev = listOf("R10, 5411457757: Trimble" to mac)

    @Test fun launchingApp_closesOther_waitsForAck_thenConnects_withNotice() = runTest(StandardTestDispatcher()) {
        val w = LockRace0147Test.AsyncWire()
        val fa = FakeConn(); fa.devices = dev
        val a = ConnectionController(fa, backgroundScope)
        a.lock = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now })
        val order = mutableListOf<String>()
        a.onExitForOtherApp = { order += "exit(stopped=${fa.stopped >= 1}, connect=${a.connectRequested})" }
        a.connectPicked(mac, "R10"); w.deliver(); fa.link.value = LinkStatus(LinkState.STREAMING)
        a.onAppBackground()

        val fb = FakeConn(); fb.devices = dev
        val b = ConnectionController(fb, backgroundScope)
        b.lock = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }, settleMs = 1_500L)
        b.selectAddress(mac); b.changeAutoConnect(true)
        assertFalse("닫기 답 전엔 연결 안 함", b.autoConnectOnLaunch()); assertNull(fb.started)
        w.deliver() // CLOSE_APP 도착 → a 닫힘 → CLOSED 보냄
        assertTrue(a.closedForOther); assertFalse(a.connectRequested); assertTrue("블루투스·VRS·서비스 멈춤", fa.stopped >= 1)
        assertEquals(listOf("exit(stopped=true, connect=false)"), order)
        w.deliver() // (같은 큐에서 CLOSED 도 이미 도착) → b 연결
        runCurrent()
        assertEquals("답이 오면 바로 연결(2초 안 기다림)", mac, fb.started?.second)
        assertEquals("발굴 측량을 닫고 연결했어요", b.ui.toast)
        assertNull("닫힌 앱의 잠금은 남지 않음", b.lockedBy)
    }

    @Test fun noOtherApp_connectsAfterTimeout_withoutNotice() = runTest(StandardTestDispatcher()) {
        val w = LockRace0147Test.AsyncWire()
        val fb = FakeConn(); fb.devices = dev
        val b = ConnectionController(fb, backgroundScope)
        b.lock = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }, settleMs = 1_500L)
        b.selectAddress(mac); b.changeAutoConnect(true)
        b.autoConnectOnLaunch(); w.deliver()
        advanceTimeBy(1_900); runCurrent(); assertNull(fb.started)
        now += 2_000; advanceTimeBy(200); runCurrent()
        assertEquals(mac, fb.started?.second)
        assertFalse((b.ui.toast ?: "").contains("닫고"))
    }

    @Test fun closeRequest_ignoredFromSelf_andHandledOnce() = runTest(StandardTestDispatcher()) {
        val w = LockRace0147Test.AsyncWire()
        val fa = FakeConn(); fa.devices = dev
        val a = ConnectionController(fa, backgroundScope)
        a.lock = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now })
        var exits = 0; a.onExitForOtherApp = { exits++ }
        a.lock!!.handle(LockMsg(LockOp.CLOSE_APP, "kr.archsurvey.field", "발굴 측량"))
        assertEquals(0, exits)
        a.lock!!.handle(LockMsg(LockOp.CLOSE_APP, "kr.archsurvey.gcp", "사진 기준점"))
        a.lock!!.handle(LockMsg(LockOp.CLOSE_APP, "kr.archsurvey.gcp", "사진 기준점"))
        assertEquals(1, exits)
    }
}
