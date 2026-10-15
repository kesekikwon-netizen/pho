package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.model.ObjKind
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
import kr.archsurvey.ui.ToastAnchor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.8: 목업 g01–g09 과 같은 상태를 그려 build-reports/0.1.8/render/g*.png 로 남긴다(비교 PNG 는 tools/compare.py) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class GBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun noNet() { TileImages.networkEnabled = false }
    private val out = File("build-reports/0.1.8/render").apply { mkdirs() }
    var ui: kr.archsurvey.ui.FeedbackHub? = null

    fun shot(name: String, keepToast: Boolean = false) {
        if (!keepToast) rule.runOnIdle { ui?.hideToast() }
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    fun show(c: GcpController) { ui = c.ui; rule.setContent { SurveyTheme { GcpRoot(c, GcpEnv()) } }; rule.waitForIdle() }

    /** 목업과 비슷한 유적: 1호 수혈(표적 4 · 사진 필요) · A–A′ 토층(보내기 준비) · 1호 주거지(측량 중) · 2호 주거지(바닥, 시작 전) */
    fun site(): ControllerTest.Rig {
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.0, 573986.0); r.measure(2, 194880.0, 573989.0); r.measure(3, 194882.0, 573992.0); r.measure(4, 194886.0, 573990.0)
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); c.submitNewObj()
        r.measure(1, 194900.0, 573950.0); r.measure(2, 194902.0, 573950.0)
        c.openNewObj(); c.pickKind(ObjKind.SECTION); c.submitNewObj()
        r.measure(1, 194950.0, 574010.0); r.measure(2, 194951.0, 574010.0); r.measure(3, 194952.0, 574011.0); r.measure(4, 194953.0, 574012.0)
        c.markPhotoDone()
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); c.pickLabel("바닥"); c.submitNewObj()
        repeat(3) { r.gnss.emitAt(194890.0, 573975.0) }
        return r
    }
}

@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class GScreensFoldedTest : GBase() {
    @Test fun g01_home_sites() { val r = site(); rule.runOnIdle { while (r.c.screen != Screen.OBJECTS) r.c.back() }; show(r.c); rule.onNodeWithTag("objects").assertIsDisplayed(); rule.onNodeWithTag("stage_row").assertIsDisplayed(); shot("g01_home_sites") }
    @Test fun g02_new_target() { val r = site(); rule.runOnIdle { r.c.back(); r.c.openNewObj() }; show(r.c); rule.onNodeWithTag("newobj_title").assertIsFocused(); rule.onNodeWithTag("newobj_title").performTextInput("2호 수혈"); rule.onNodeWithTag("newobj_name", useUnmergedTree = true).assertTextEquals("만들기"); shot("g02_new_target") }
    @Test fun g03_measure_map() { val r = site(); val c = r.c; rule.runOnIdle { c.openObj(c.book!!.objs.first { it.kind == ObjKind.DWELLING && it.no == 1 }.id); c.openTargets() }; show(c); shot("g03_measure_map") }
    @Test fun g04_measuring_avg() {
        val r = site(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs.first { it.kind == ObjKind.DWELLING && it.no == 1 }.id); c.openTargets() }
        show(c)
        rule.runOnIdle { r.prefs.epochs = 30; r.gnss.emitAt(194890.0, 573975.0); c.onTargetTap(3) }
        rule.runOnIdle { repeat(12) { r.gnss.emitAt(194890.0, 573975.0) } }
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextContains("남은", substring = true)
        shot("g04_measuring_avg"); rule.runOnIdle { c.cancelMeasure() }
    }
    @Test fun g05_target_folder() { val r = site(); val c = r.c; rule.runOnIdle { c.openObj(c.book!!.objs.first { it.kind == ObjKind.PIT }.id) }; show(c); rule.onNodeWithTag("obj_progress", useUnmergedTree = true).assertExists(); shot("g05_target_folder") }
    @Test fun g06_export() { val r = site(); rule.runOnIdle { r.c.back(); r.c.handoffOpen = true }; show(r.c); rule.onNodeWithTag("handoff_send").assertExists(); shot("g06_export") }
    @Test fun g07_settings() { val r = site(); rule.runOnIdle { r.c.go(Screen.SETTINGS) }; show(r.c); rule.onNodeWithTag("set_tab_general").assertIsDisplayed(); shot("g07_settings")
        rule.onNodeWithTag("set_tab_receiver").performClick(); shot("g07b_settings_receiver"); rule.onNodeWithTag("set_tab_advanced").performClick(); shot("g07c_settings_advanced") }
    @Test fun g08_error_chip() { val r = site(); val c = r.c; rule.runOnIdle { c.go(Screen.MAP) }; show(c)
        rule.onNodeWithTag("map_notice").assertIsDisplayed(); rule.onNodeWithTag("map_notice_what", useUnmergedTree = true).assertTextContains("V월드", substring = true); shot("g08_error_chip") }
    @Test fun g09_pole_sheet() { val r = site(); val c = r.c; rule.runOnIdle { c.go(Screen.MAP); c.poleOpen = true }; show(c); rule.onNodeWithTag("pole_value", useUnmergedTree = true).assertTextEquals("1.60"); shot("g09_pole_sheet") }
}

@Config(sdk = [34], qualifiers = "w720dp-h800dp-notnight-xhdpi")
class GScreensUnfoldedTest : GBase() {
    /** 펼침: 저장 알림은 지도 아래쪽 안에, 오른쪽 판과 겹치지 않는다 */
    @Test fun g03_unfolded_toast_over_map_only() {
        val r = site(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs.first { it.kind == ObjKind.DWELLING && it.no == 1 }.id); c.openTargets() }
        show(c)
        rule.runOnIdle { r.measure(3, 194901.0, 573953.0) }
        rule.waitForIdle()
        val toast = rule.onNodeWithTag("toast").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val map = rule.onNodeWithTag("map_area").fetchSemanticsNode().boundsInRoot
        assertTrue("toast $toast inside map $map", toast.left >= map.left - 1 && toast.right <= map.right + 1 && toast.bottom <= map.bottom + 1 && toast.top >= map.top - 1)
        assertTrue("toast in lower half", toast.center.y > map.center.y)
        assertNotNull(ToastAnchor.mapBounds)
        shot("g03_measure_map-unfolded", keepToast = true)
    }
}
