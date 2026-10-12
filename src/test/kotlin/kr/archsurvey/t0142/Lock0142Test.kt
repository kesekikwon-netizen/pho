package kr.archsurvey.t0142

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.device.ReconnectPolicy
import kr.archsurvey.t0140.FakeConn
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.42(측량) · 0.1.4(사진 기준점) 앱 사이 수신기 잠금 · 뒤로 가면 멈춤 · 재연결 상한 — 같은 시험 */
class Wire {
    val buses = mutableListOf<Bus>()
    inner class Bus : LockBus {
        override var onMessage: ((LockMsg) -> Unit)? = null
        init { buses += this }
        override fun send(m: LockMsg) { for (b in buses.toList()) if (b !== this) b.onMessage?.invoke(m) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Lock0142Test {
    private var now = 0L
    private val mac = "AA:11"

    @Test fun holdSeenByOther_releaseClears_staleExpires() {
        val w = Wire()
        val a = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now })
        val b = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now })
        a.hold(mac)
        assertEquals("발굴 측량", b.otherHolder(mac)?.label)
        assertNull("자기 잠금은 막지 않음", a.otherHolder(mac))
        assertNull("다른 수신기는 자유", b.otherHolder("BB:22"))
        a.release(); assertNull(b.otherHolder(mac))
        a.hold(mac); now += 59_000; assertNotNull(b.otherHolder(mac))
        now += 2_000; assertNull("60초 소식 없음(앱 죽음) = 잠금 없음", b.otherHolder(mac))
        a.heartbeat(); assertNotNull(b.otherHolder(mac))
    }

    @Test fun lateStarter_queries_andLearnsExistingHolder() {
        val w = Wire()
        val a = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { now })
        a.hold(mac)
        val b = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { now }) // 시작할 때 QUERY → a 가 HOLD 로 답
        assertEquals("kr.archsurvey.field", b.otherHolder(mac)?.pkg)
    }

    private fun ctl(w: Wire, pkg: String, label: String, scope: kotlinx.coroutines.CoroutineScope): Pair<FakeConn, ConnectionController> {
        val fc = FakeConn(); fc.devices = listOf("R10, 5411457757: Trimble" to mac)
        val cc = ConnectionController(fc, scope)
        cc.lock = ReceiverLock(w.Bus(), pkg, label, { now })
        return fc to cc
    }

    @Test fun otherAppHolds_noConnect_card_takeOver_handsOff() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (fa, a) = ctl(w, "kr.archsurvey.field", "발굴 측량", backgroundScope)
        val (fb, b) = ctl(w, "kr.archsurvey.gcp", "사진 기준점", backgroundScope)
        a.connectPicked(mac, "R10, 5411457757: Trimble")
        assertTrue(a.connectRequested); assertEquals(mac, fa.started?.second)
        // 사진 기준점에서 같은 수신기 → 연결 안 함, 카드
        b.connectPicked(mac, "R10, 5411457757: Trimble")
        assertNull("두 앱이 동시에 붙지 않음", fb.started)
        assertFalse(b.connectRequested)
        assertEquals("다른 앱(발굴 측량)이 수신기를 쓰는 중", b.notice?.title)
        assertEquals("발굴 측량", b.lockedBy?.label)
        // 여기서 쓰기 → 발굴 측량이 끊고 놓음 → 사진 기준점이 연결
        b.takeOver(); runCurrent()
        assertFalse(a.connectRequested); assertTrue(fa.stopped >= 1)
        assertEquals(mac, fb.started?.second); assertTrue(b.connectRequested); assertNull(b.lockedBy)
        // 이제 반대로 발굴 측량이 막힘
        a.connectPicked(mac, "R10")
        assertEquals("다른 앱(사진 기준점)이 수신기를 쓰는 중", a.notice?.title)
    }

    @Test fun takeOver_otherDeadNoAnswer_connectsAfterWait() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (_, b) = ctl(w, "kr.archsurvey.gcp", "사진 기준점", backgroundScope)
        // 죽은 앱이 남긴 잠금(응답 없음)
        b.lock!!.handle(LockMsg(LockOp.HOLD, "kr.archsurvey.field", "발굴 측량", mac))
        b.connectPicked(mac, "R10")
        assertNotNull(b.lockedBy)
        b.takeOver(); runCurrent(); assertFalse(b.connectRequested)
        advanceTimeBy(6_100); runCurrent()
        assertTrue("6초 답 없으면 여기서 연결", b.connectRequested)
    }

    @Test fun autoConnectOnLaunch_blockedByOtherApp_doesNotStart() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (fb, b) = ctl(w, "kr.archsurvey.gcp", "사진 기준점", backgroundScope)
        b.lock!!.handle(LockMsg(LockOp.HOLD, "kr.archsurvey.field", "발굴 측량", mac))
        b.selectAddress(mac)
        assertFalse(b.autoConnectOnLaunch())
        assertNull(fb.started)
    }

    @Test fun background_withoutPosition_stopsAtOnce_foregroundResumes() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (fa, a) = ctl(w, "kr.archsurvey.field", "발굴 측량", backgroundScope)
        a.connectPicked(mac, "R10")
        a.onAppBackground()
        assertFalse("위치를 못 받은 자동 재연결은 뒤에서 멈춤", a.connectRequested); assertTrue(a.bgStopped); assertTrue(fa.stopped >= 1)
        assertNull("잠금도 놓음", ReceiverLock(w.Bus(), "x", "x", { now }).otherHolder(mac))
        fa.started = null
        a.onAppForeground()
        assertTrue(a.connectRequested); assertEquals(mac, fa.started?.second)
    }

    @Test fun background_whileSurveying_keeps_thenStopsAfterGraceIfLost() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (fa, a) = ctl(w, "kr.archsurvey.field", "발굴 측량", backgroundScope)
        a.connectPicked(mac, "R10")
        fa.link.value = LinkStatus(LinkState.STREAMING)
        a.onStatus(fa.link.value, fa.ntrip.value)
        a.onAppBackground(); runCurrent()
        assertTrue("측량 중(위치 받는 중)이면 화면 꺼져도 유지", a.connectRequested)
        advanceTimeBy(300_000); runCurrent()
        assertTrue(a.connectRequested)
        fa.link.value = LinkStatus(LinkState.RECONNECTING, failures = 3)
        advanceTimeBy(a.backgroundGraceMs + 6_000); runCurrent()
        assertFalse("뒤에서 2분 넘게 끊겨 있으면 멈춤", a.connectRequested)
    }

    @Test fun backgroundApp_yieldsWhenForegroundAppClaimsSameReceiver() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val (fa, a) = ctl(w, "kr.archsurvey.field", "발굴 측량", backgroundScope)
        a.connectPicked(mac, "R10"); fa.link.value = LinkStatus(LinkState.STREAMING); a.onStatus(fa.link.value, fa.ntrip.value)
        a.onAppBackground(); fa.link.value = LinkStatus(LinkState.RECONNECTING)
        a.lock!!.handle(LockMsg(LockOp.HOLD, "kr.archsurvey.gcp", "사진 기준점", mac))
        assertFalse("뒤에 있고 위치 없는 쪽이 양보", a.connectRequested)
    }

    @Test fun reconnectCap() {
        val p = ReconnectPolicy(1_000, 15_000, 500, 4)
        assertFalse(p.shouldGiveUp(11)); assertTrue(p.shouldGiveUp(12))
        assertFalse(ReconnectPolicy(1_000, 15_000, 500, 4, giveUpAfterFailures = 0).shouldGiveUp(1000))
        val src = java.io.File("src/main/kotlin/kr/archsurvey/engine/LinkSupervisor.kt").readText()
        assertTrue(src.contains("policy.shouldGiveUp(failures)"))
    }

    @Test fun manifestDeclaresSignatureLockPermission() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:name=\"kr.archsurvey.permission.RECEIVER_LOCK\" android:protectionLevel=\"signature\""))
        assertTrue(m.contains("<uses-permission android:name=\"kr.archsurvey.permission.RECEIVER_LOCK\""))
    }
}
