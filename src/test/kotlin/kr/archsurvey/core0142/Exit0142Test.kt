package kr.archsurvey.core0142

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.archsurvey.core0140.FakeConn
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.42 · 0.1.4: 「프로그램 종료」가 완전히 닫고, 다음 실행은 시작 화면부터 */
@OptIn(ExperimentalCoroutinesApi::class)
class Exit0142Test {
    @Test fun cleanExitFlag_consumedOnceOnNextLaunch() {
        val st = object : CleanExitStore { override var exited = false }
        val c = CleanExit(st)
        assertFalse(c.consumeOnLaunch())
        c.mark()
        assertTrue("종료 뒤 첫 실행 = 처음부터", c.consumeOnLaunch())
        assertFalse("그다음 실행은 평소대로", c.consumeOnLaunch())
    }

    @Test fun exitDisconnect_stopsServiceAndAutoReconnect_releasesCrossAppLock() = runTest(StandardTestDispatcher()) {
        val w = Wire()
        val fc = FakeConn(); fc.devices = listOf("R10" to "AA:11")
        val cc = ConnectionController(fc, backgroundScope)
        cc.lock = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { 0L })
        val other = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { 0L })
        cc.connectPicked("AA:11", "R10")
        assertNotNull(other.otherHolder("AA:11"))
        cc.stopForExit()
        assertFalse(cc.connectRequested); assertTrue(fc.stopped >= 1)
        assertNull("다른 앱이 바로 쓸 수 있음", other.otherHolder("AA:11"))
        cc.onAppBackground(); cc.onAppForeground()
        assertFalse("종료 뒤 자동 재연결 없음", cc.connectRequested)
    }

    @Test fun exitWithoutLink_stillReleasesLock() = runTest(StandardTestDispatcher()) {
        val w = Wire(); val fc = FakeConn()
        val cc = ConnectionController(fc, backgroundScope)
        cc.lock = ReceiverLock(w.Bus(), "kr.archsurvey.field", "발굴 측량", { 0L })
        val other = ReceiverLock(w.Bus(), "kr.archsurvey.gcp", "사진 기준점", { 0L })
        cc.lock!!.hold("AA:11")
        assertNotNull(other.otherHolder("AA:11"))
        cc.onStoppedOutside()
        assertNull(other.otherHolder("AA:11")); assertFalse(cc.connectRequested)
    }

    @Test fun mainActivity_exitOrder_andFreshStart() {
        val src = java.io.File("src/main/kotlin/kr/archsurvey/MainActivity.kt").readText()
        val body = src.substringAfter("private fun exitCompletely()").substringBefore("\n    }\n")
        val idx = listOf("cleanExit.mark()", "finishAndRemoveTask()", "cancel(kr.archsurvey.engine.SurveyConnectionService.NOTI_ID)", "killProcess").map { body.indexOf(it) }
        assertTrue("종료 순서 $idx", idx.all { it >= 0 } && idx.zipWithNext().all { (a, b) -> a < b })
        assertTrue(src.contains("onExitApp = { exitCompletely() }"))
        assertTrue("종료 뒤 실행은 저장 상태 버림", src.contains("freshAfterExit = cleanExit.consumeOnLaunch()") && src.contains("if (freshAfterExit) null else savedInstanceStateRaw"))
        val ctl = listOf("src/main/kotlin/kr/archsurvey/ui/SurveyController.kt", "src/main/kotlin/kr/archsurvey/gcp/ui/GcpController.kt").map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue("종료는 늘 연결·잠금 정리", ctl.contains("if (connection?.linkActive() == true) disconnectDevice() else runCatching { connection?.onStoppedOutside() }"))
        assertTrue("서비스 정지 먼저", body.indexOf("stopSafely(") in 0 until body.indexOf("finishAndRemoveTask()"))
    }
}
