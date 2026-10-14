package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
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

/** 0.1.14 세계 수준 개선: 접힘/펼침(Fold7 안쪽 근사 750x832dp)/햇빛 고대비 + 상태 모양 + 접힘선과 측량 단추 거리 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0114Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "build-reports/0.1.14/screens").apply { mkdirs() }
    private val after = System.getenv("SHOT_BEFORE") == null
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "gcp_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sun(v: Boolean) { if (after) { val k = Class.forName("kr.archsurvey.ui.Sunlight"); k.getMethod("set", android.content.Context::class.java, Boolean::class.javaPrimitiveType).invoke(k.getField("INSTANCE").get(null), RuntimeEnvironment.getApplication(), v) } }
    @After fun off() = sun(false)
    private fun targets(float: Boolean = false): ControllerTest.Rig {
        TileImages.networkEnabled = false
        val r = ControllerTest.rig().siteWithObj()
        r.measure(1, 194884.0, 573986.0)
        repeat(3) { r.gnss.emitAt(194882.0, 573987.0) }
        if (float) r.gnss.emit(q = kr.archsurvey.core.FixQuality.RTK_FLOAT)
        if (r.c.screen != Screen.TARGETS) r.c.openTargets()
        rule.setContent { SurveyTheme { GcpRoot(r.c, GcpEnv()) } }; rule.waitForIdle()
        return r
    }
    @Test fun folded_fix() { targets(); shot("folded_targets_fix"); if (after) rule.onNodeWithTag("pill_mark_fix", useUnmergedTree = true).assertExists() }
    @Test fun folded_float() { targets(true); shot("folded_targets_float"); if (after) rule.onNodeWithTag("pill_mark_float", useUnmergedTree = true).assertExists() }
    @Test @Config(qualifiers = "w750dp-h832dp-notnight-xhdpi") fun unfolded_targets() {
        targets(); shot("unfolded_targets")
        val b = rule.onNodeWithTag("measure_button").fetchSemanticsNode().boundsInRoot; val d = rule.density.density
        assertTrue("측량 단추 왼쪽 ${b.left / d}dp ≥ 접힘선(375dp)+48dp", b.left >= (375f + 48f) * d)
        File(out, "gcp_unfolded_hinge.txt").writeText("hinge 375dp, measure_button left ${b.left / d}dp right ${b.right / d}dp\n")
    }
    @Test fun sunlight() {
        sun(true)
        val r = targets(); val c = r.c
        shot("sun_targets")
        while (c.screen != Screen.SITES && c.stack.size > 1) c.back()
        rule.waitForIdle(); shot("sun_sites")
        if (after) {
            val sp = RuntimeEnvironment.getApplication().getSharedPreferences("ui_prefs", 0)
            rule.onNodeWithTag("home_sun").performClick(); rule.waitForIdle(); assertFalse(sp.getBoolean("sun_high_contrast", true))
            rule.onNodeWithTag("home_sun").performClick(); rule.waitForIdle(); assertTrue(sp.getBoolean("sun_high_contrast", false))
        }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
