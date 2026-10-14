package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.8: 글자 130% · 접힘(360dp) · 펼침(Fold 안쪽 ≈ 700dp)에서 표적 재기 화면이 깨지지 않는지 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LayoutBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun noNet() { TileImages.networkEnabled = false }
    private val out = File("build-reports/0.1.8/screens").apply { mkdirs() }

    fun targets(): GcpController {
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194880.0, 573989.0)
        repeat(3) { r.gnss.emitAt(194882.0, 573987.0) }
        c.openTargets()
        rule.setContent { SurveyTheme { GcpRoot(c, GcpEnv()) } }; rule.waitForIdle()
        return c
    }
    fun check(name: String) {
        rule.onNodeWithTag("targets").assertIsDisplayed()
        rule.onNodeWithTag("map_area").assertIsDisplayed()
        rule.onNodeWithTag("measure_button").assertIsDisplayed().assertHeightIsAtLeast(96.dp)
        for (t in 1..4) rule.onNodeWithTag("qtag_$t").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag("pole_chip").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("표적 #3 측량")
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Config(sdk = [34], qualifiers = "w360dp-h780dp-notnight-xxhdpi", fontScale = 1.3f)
class LayoutFolded130Test : LayoutBase() {
    @Test fun targets_folded_font130() { targets(); rule.onNodeWithTag("targets_unfolded").assertDoesNotExist(); check("g03-folded-font130") }
}

@Config(sdk = [34], qualifiers = "w720dp-h800dp-notnight-xhdpi", fontScale = 1.3f)
class LayoutUnfolded130Test : LayoutBase() {
    @Test fun targets_unfolded_font130() { targets(); rule.onNodeWithTag("targets_unfolded").assertIsDisplayed(); check("g03-unfolded-font130") }
}

@Config(sdk = [34], qualifiers = "w360dp-h780dp-notnight-xxhdpi")
class LayoutFolded100Test : LayoutBase() {
    @Test fun targets_folded_font100() { targets(); check("g03-folded-font100") }
}
