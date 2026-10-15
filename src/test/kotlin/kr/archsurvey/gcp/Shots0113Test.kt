package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.13 새 대상 쉽게: 대상 목록 → 새 대상 → 만들고 표적 재기(글자 입력 없음) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xxhdpi")
class Shots0113Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getenv("SHOT_DIR") ?: "build-reports/0.1.13/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun newObj_flow() {
        TileImages.networkEnabled = false
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        while (c.screen != Screen.OBJECTS && c.stack.size > 1) c.back()
        rule.setContent { SurveyTheme { GcpRoot(c, GcpEnv()) } }; rule.waitForIdle()
        shot("gcp_1_objects")
        var taps = 0
        rule.onNodeWithTag("obj_new").performClick(); taps++; rule.waitForIdle()
        shot("gcp_2_new_obj")
        rule.onNodeWithTag("newobj_title").assertIsFocused().performTextInput("2호 주거지") // 0.1.17 이름은 직접 입력
        rule.onNodeWithTag("newobj_ok").performClick(); taps++; rule.waitForIdle()
        if (c.screen == Screen.OBJ) { rule.onNodeWithTag("obj_measure").performClick(); taps++; rule.waitForIdle() }
        assertEquals(Screen.TARGETS, c.screen)
        shot("gcp_3_targets")
        File(out, "gcp_taps.txt").writeText("$taps\n")
    }
}
