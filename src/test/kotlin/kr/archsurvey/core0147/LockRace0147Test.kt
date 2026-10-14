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

/** 0.1.47 · 0.1.9: 현장 10-14 10:37~10:42 두 앱이 R10 을 서로 빼앗던 문제 — 방송이 늦게 오는 경우(비동기) */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRace0147Test {
    private var now = 0L
    private val mac = "AA:11"
    /** 실제 방송처럼 보낸 뒤 나중에(deliver) 도착 */
    class AsyncWire {
        val q = ArrayDeque<Pair<Bus, LockMsg>>(); val buses = mutableListOf<Bus>()
        inner class Bus : LockBus { override var onMessage: ((LockMsg) -> Unit)? = null
            init { buses += this }
            override fun send(m: LockMsg) { for (b in buses.toList()) if (b !== this) q.addLast(b to m) } }
        fun deliver() { while (q.isNotEmpty()) { val (b, m) = q.removeFirst(); b.onMessage?.invoke(m) } }
    }

    @Test fun autoConnect_waitsForQueryAnswer_doesNotStealFromHolder() = runTest(StandardTestDispatcher()) {
        val w = AsyncWire()
        val a = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now }); a.hold(mac); w.deliver()
        val fb = FakeConn(); fb.devices = listOf("R10, 5411457757: Trimble" to mac)
        val b = ConnectionController(fb, backgroundScope)
        b.lock = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }, settleMs = 1_500L)
        b.selectAddress(mac); b.changeAutoConnect(true)
        assertFalse("답 오기 전엔 연결 안 함", b.autoConnectOnLaunch()); assertNull(fb.started)
        w.deliver() // QUERY → a 가 HOLD 로 답
        now += 1_500; advanceTimeBy(1_600); runCurrent()
        assertNull("다른 앱이 쓰는 중이면 자동 연결로 빼앗지 않음", fb.started)
        assertEquals("발굴 측량", b.lockedBy?.label)
    }

    @Test fun autoConnect_noOtherHolder_connectsAfterSettle() = runTest(StandardTestDispatcher()) {
        val w = AsyncWire()
        val fb = FakeConn(); fb.devices = listOf("R10, 5411457757: Trimble" to mac)
        val b = ConnectionController(fb, backgroundScope)
        b.lock = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }, settleMs = 1_500L)
        b.selectAddress(mac); b.changeAutoConnect(true)
        b.autoConnectOnLaunch(); w.deliver()
        now += 1_500; advanceTimeBy(1_600); runCurrent()
        assertEquals(mac, fb.started?.second)
    }

    @Test fun backgroundStreamingApp_yieldsWhenForegroundAppHolds() = runTest(StandardTestDispatcher()) {
        val w = AsyncWire()
        val fa = FakeConn(); fa.devices = listOf("R10, 5411457757: Trimble" to mac)
        val a = ConnectionController(fa, backgroundScope)
        a.lock = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now })
        a.connectPicked(mac, "R10"); w.deliver()
        fa.link.value = LinkStatus(LinkState.STREAMING)
        a.onAppBackground()
        val b = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }); w.deliver()
        b.hold(mac); w.deliver()
        assertFalse("뒤에 있는 앱은 위치를 받던 중이라도 양보", a.connectRequested)
        assertTrue(fa.stopped >= 1); assertTrue(a.bgStopped)
    }
}
