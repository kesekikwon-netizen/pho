package kr.archsurvey.core0144

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kr.archsurvey.engine.SurveyConnectionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 0.1.44: ForegroundServiceDidNotStartInTimeException 방지 — 모든 시작 경로가 맨 먼저 startForeground */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fgs0144Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun intent(action: String? = null) = Intent(ctx, SurveyConnectionService::class.java).also { if (action != null) it.action = action }

    @Test fun onCreatePromotesImmediately() {
        val c = Robolectric.buildService(SurveyConnectionService::class.java).create()
        assertNotNull("onCreate 에서 이미 포그라운드", shadowOf(c.get()).lastForegroundNotification)
        assertEquals(SurveyConnectionService.NOTI_ID, shadowOf(c.get()).lastForegroundNotificationId)
        assertFalse(SurveyConnectionService.pendingStart)
    }

    @Test fun stopActionStillPromotesThenStops() {
        for (a in listOf(SurveyConnectionService.ACTION_STOP, SurveyConnectionService.ACTION_STOP_FROM_NOTIFICATION)) {
            val c = Robolectric.buildService(SurveyConnectionService::class.java, intent(a)).create().startCommand(0, 7)
            val sh = shadowOf(c.get())
            assertEquals("$a: onCreate+명령 모두 startForeground", 2, c.get().promoteCount)
            assertTrue("$a: stopForeground", sh.isForegroundStopped)
            assertEquals("$a: stopSelf(startId)", 7, sh.stopSelfId)
        }
    }

    @Test fun missingDeviceStillPromotesThenStops() {
        val c = Robolectric.buildService(SurveyConnectionService::class.java, intent()).create().startCommand(0, 3)
        val sh = shadowOf(c.get())
        assertEquals(2, c.get().promoteCount)
        assertTrue(sh.isForegroundStopped)
        assertEquals(3, sh.stopSelfId)
    }

    @Test fun stopWithoutServiceDoesNotSpawn() {
        Robolectric.buildService(SurveyConnectionService::class.java).create().destroy()
        SurveyConnectionService.stop(ctx)
        assertNull("안 떠 있으면 정지하려고 서비스를 깨우지 않음", shadowOf(ctx).nextStartedService)
    }

    @Test fun startUsesForegroundServiceAndMarksPending() {
        SurveyConnectionService.start(ctx, "dev", "00:11:22:33:44:55")
        assertTrue(SurveyConnectionService.pendingStart)
        assertNotNull(shadowOf(ctx).nextStartedService)
        // 종료 중 시작 대기 → stopService 대신 줄 세운 정지 명령
        SurveyConnectionService.stopSafely(ctx)
        assertEquals(SurveyConnectionService.ACTION_STOP, shadowOf(ctx).nextStartedService?.action)
    }

    @Test fun sourceOrderAndManifest() {
        val src = File("src/main/kotlin/kr/archsurvey/engine/SurveyConnectionService.kt").readText()
        val body = src.substringAfter("override fun onStartCommand").substringBefore("return START_NOT_STICKY")
        assertTrue("promote 가 첫 줄", body.trimStart('(', ')').lines().drop(1).first { it.isNotBlank() && !it.trim().startsWith("//") }.contains("promote()"))
        assertTrue("시작 거부 예외 잡음", src.substringAfter("fun start(ctx").substringBefore("fun stop(").contains("catch (e: Exception)"))
        assertFalse("인자 없는 stopSelf() 없음", Regex("stopSelf\\(\\)").containsMatchIn(src))
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(m.contains("android:foregroundServiceType=\"connectedDevice\""))
        val main = File("src/main/kotlin/kr/archsurvey/MainActivity.kt").readText()
        assertFalse("종료에서 직접 stopService 안 함", main.contains("stopService(android.content.Intent(this, kr.archsurvey.engine.SurveyConnectionService"))
    }
}
