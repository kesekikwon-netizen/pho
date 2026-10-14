package kr.archsurvey.t0146

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.52 세계 수준 개선: 접힘/펼침(Fold7 안쪽 근사 750x832dp)/햇빛 고대비 화면 + 상태 모양 + 접힘선과 측량 단추 거리 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0152Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "/workspace/survey-app/build-reports/0.1.52/screens").apply { mkdirs() }
    private val after = System.getenv("SHOT_BEFORE") == null
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "survey_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun off() { if (after) setSun(false) }
    private fun setSun(v: Boolean) { if (after) Class.forName("kr.archsurvey.ui.Sunlight").getMethod("set", android.content.Context::class.java, Boolean::class.javaPrimitiveType).invoke(Class.forName("kr.archsurvey.ui.Sunlight").getField("INSTANCE").get(null), RuntimeEnvironment.getApplication(), v) }
    private fun start(q: FixQuality, sigma: Double, home: Boolean): SurveyController {
        TileImages.networkEnabled = false
        val (s, c) = rig(false, DeviceProfiles.R10_ID, Mem())
        c.projectName = "정밀발굴 1지구"; c.zone = KoreanTmZone.EAST; c.ui.toastMs = 1L
        s.push(q, sigma); c.homeOpen = home
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 80) } }
        rule.waitForIdle(); return c
    }
    @Test fun folded_fix() { start(FixQuality.RTK_FIXED, 0.014, false); shot("folded_map_fix")
        if (after) rule.onNodeWithTag("pill_mark_fix", useUnmergedTree = true).assertExists() }
    @Test fun folded_float() { start(FixQuality.RTK_FLOAT, 0.052, false); shot("folded_map_float")
        if (after) rule.onNodeWithTag("pill_mark_float", useUnmergedTree = true).assertExists() }
    @Test fun folded_home() { start(FixQuality.RTK_FIXED, 0.014, true); shot("folded_home") }
    @Test @Config(qualifiers = "w750dp-h832dp-notnight-xhdpi") fun unfolded_map() {
        start(FixQuality.RTK_FIXED, 0.014, false); shot("unfolded_map")
        rule.onNodeWithTag("side_pane").assertExists()
        // Fold7 안쪽: 접힘선 = 가로 가운데(375dp). 측량 단추는 접힘선에서 48dp 이상 떨어져 오른쪽(엄지) 칸에
        val b = rule.onNodeWithTag("measure_button").fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        val hinge = 375f * d
        assertTrue("측량 단추 왼쪽 ${b.left / d}dp ≥ 접힘선+48dp", b.left >= hinge + 48f * d)
        File(out, "survey_unfolded_hinge.txt").writeText("hinge 375dp, measure_button left ${b.left / d}dp right ${b.right / d}dp\n")
    }
    @Test fun sunlight_home_and_map() {
        if (!after) { start(FixQuality.RTK_FIXED, 0.014, true); shot("sun_home"); return }
        val c = start(FixQuality.RTK_FIXED, 0.014, true)
        rule.onNodeWithTag("home_sun").performClick(); rule.waitForIdle()
        shot("sun_home")
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("ui_prefs", 0).getBoolean("sun_high_contrast", false))
        c.homeOpen = false; rule.waitForIdle(); shot("sun_map_fix")
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
