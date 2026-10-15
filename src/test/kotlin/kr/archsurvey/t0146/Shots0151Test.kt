package kr.archsurvey.t0146

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.51 새 작업 쉽게: 홈 → 새 작업 → 만들고 측량 시작(2탭, 글자 입력 없음) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0151Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "/workspace/survey-app/build-reports/0.1.51/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private class Jobs : JobPlatform {
        override var current = "정밀발굴 1지구"; val m = linkedMapOf("정밀발굴 1지구" to 3)
        override suspend fun list() = m.map { JobInfo(it.key, it.value, 2, 1, 1_791_000_000_000L, 1_791_090_000_000L, 0) }
        override suspend fun info(name: String) = list().first { it.name == name }
        override suspend fun create(name: String) { m[name] = 0; current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String): String { m.remove(name); return "x.zip" }
    }
    @Test fun newJob_flow() {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID, Mem())
        c.projectName = "정밀발굴 1지구"; c.zone = KoreanTmZone.EAST; c.ui.toastMs = 1L
        s.push(FixQuality.RTK_FIXED, 0.014); c.homeOpen = true
        c.jobs = JobController(c, Jobs(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle()
        shot("survey_1_home")
        var taps = 0
        rule.onNodeWithTag("home_new_job").performClick(); taps++; rule.waitForIdle()
        shot("survey_2_new_job")
        rule.onNodeWithTag("job_name").performTextInput("새 트렌치") // 0.1.55 사용자 정책: 이름은 직접 입력
        rule.onNodeWithTag("job_create").performClick(); taps++; rule.waitForIdle()
        assertFalse("측량 지도로", c.homeOpen); assertNull(c.hub.screen)
        assertNotEquals("정밀발굴 1지구", c.projectName)
        File(out, "survey_taps.txt").writeText("$taps\n")
    }
}
