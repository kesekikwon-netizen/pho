package kr.archsurvey.gcp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.archsurvey.gcp.ControllerTest.Companion.measure
import kr.archsurvey.gcp.ControllerTest.Companion.siteWithObj
import kr.archsurvey.gcp.model.HandoffScope
import kr.archsurvey.gcp.model.ObjKind
import kr.archsurvey.gcp.ui.*
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.TileImages
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

/**
 * JVM(Robolectric)에서 실제 Compose 화면을 그려 PNG 로 남긴다. 실기기 아님(글꼴·터치·해상도 차이는 못 봄). 360×800 dp, xxhdpi.
 * V월드 타일은 끔(네트워크 없음) → 지도는 회색 격자 + 표적만.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class SnapshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build-reports/screens").apply { mkdirs() }

    @Before fun noNet() { TileImages.networkEnabled = false }

    private var ui: kr.archsurvey.ui.FeedbackHub? = null
    private fun show(c: GcpController) { ui = c.ui; rule.setContent { SurveyTheme { GcpRoot(c, GcpEnv()) } }; rule.waitForIdle() }

    private fun shot(name: String, keepToast: Boolean = false) {
        if (!keepToast) rule.runOnIdle { ui?.hideToast() }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 대상 셋(측점 필요 · 사진 필요 · 전달 필요) 있는 유적 */
    private fun busy(): ControllerTest.Rig {
        val r = ControllerTest.rig().siteWithObj(); val c = r.c
        r.measure(1, 194884.215, 573986.096, 4.192); r.measure(2, 194880.652, 573989.204, 2.883)
        c.back(); c.openNewObj(ObjKind.SECTION); c.submitNewObj()
        r.measure(1, 194950.0, 574010.0); r.measure(2, 194951.0, 574010.0); r.measure(3, 194952.0, 574011.0)
        c.back(); c.openNewObj(ObjKind.FEATURE); c.submitNewObj()
        r.measure(1, 194900.0, 573950.0); r.measure(2, 194902.0, 573950.0); r.measure(3, 194901.0, 573953.0); r.measure(4, 194899.0, 573952.0)
        c.markPhotoDone(); c.back()
        repeat(3) { r.gnss.emitAt(194890.0, 573975.0) }
        return r
    }

    @Test fun s01_sites_empty_and_new_site() {
        val r = ControllerTest.rig(); show(r.c)
        rule.onNodeWithTag("sites").assertIsDisplayed(); shot("01-sites-empty")
        rule.onNodeWithTag("new_site").performClick()
        rule.runOnIdle { r.c.newSite = r.c.newSite!!.copy(name = "강릉 ○○유적", code = "GN", epsg = 5187) }
        shot("02-new-site")
    }

    @Test fun s03_board_counts_rows_and_filter() {
        val r = busy(); show(r.c)
        rule.onNodeWithTag("board").assertIsDisplayed()
        rule.onNodeWithTag("row_GN-001").assertIsDisplayed()
        shot("03-board")
        rule.onNodeWithTag("count_PHOTO").performClick(); shot("04-board-filter-photo")
        rule.onNodeWithTag("row_GN-002").assertIsDisplayed(); rule.onNodeWithTag("row_GN-001").assertDoesNotExist()
        rule.onNodeWithTag("count_PHOTO").performClick()
        rule.onNodeWithTag("search_toggle").performClick(); rule.onNodeWithTag("search_field").performTextInput("003"); shot("05-board-search")
        rule.onNodeWithTag("row_GN-003").assertIsDisplayed(); rule.onNodeWithTag("row_GN-001").assertDoesNotExist()
    }

    @Test fun s06_new_feature_and_section_sheets() {
        val r = busy(); show(r.c)
        rule.onNodeWithTag("add_feature").performClick(); rule.onNodeWithTag("new_obj_id").assertIsDisplayed(); shot("06-add-feature")
        rule.runOnIdle { r.c.newObj = null }
        rule.onNodeWithTag("add_section").performClick(); shot("07-add-section")
    }

    @Test fun s08_measure_screen_states() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs[0].id) }
        show(c)
        rule.onNodeWithTag("measure").assertIsDisplayed(); rule.onNodeWithTag("target_1").assertIsDisplayed()
        shot("08-measure-feature-fix")
        rule.runOnIdle { val (la, lo) = Geo.latLon(194890.0, 573975.0); r.gnss.emit(q = kr.archsurvey.core.FixQuality.RTK_FLOAT, lat = la, lon = lo) }
        rule.mainClock.advanceTimeBy(1500); rule.onNodeWithTag("gate_banner").assertIsDisplayed(); shot("09-measure-float-blocked")
        rule.runOnIdle { r.gnss.emitAt(194890.0, 573975.0); c.onTargetLong(2) }
        shot("10-measure-remeasure-confirm")
        rule.runOnIdle { c.ui.answer(false) }
        rule.runOnIdle { r.measure(1, 194884.215, 573986.140, 4.192) }
        shot("11-target-moved")
        rule.runOnIdle { c.ui.answer(false); c.openObj(c.book!!.objs[1].id) }
        shot("12-measure-section-photo")
        rule.runOnIdle { c.openNewSession() }; shot("13-new-session")
    }

    @Test fun s14_id_card_with_qr() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs[1].id); c.openIdCard() }
        show(c)
        rule.onNodeWithTag("idcard_code").assertTextEquals("GN-002")
        rule.onNodeWithTag("idcard_qr").assertIsDisplayed()
        shot("14-id-card")
    }

    @Test fun s15_handoff_sheet() {
        val r = busy(); show(r.c)
        rule.onNodeWithTag("open_handoff").performClick()
        rule.onNodeWithTag("zip_name").assertIsDisplayed(); shot("15-handoff-pending")
        rule.onNodeWithTag("scope_ALL").performClick(); rule.onNodeWithTag("handoff_nophoto").assertIsDisplayed(); shot("16-handoff-all")
        rule.onNodeWithTag("handoff_send").performClick()
        assertEquals(1, r.files.zips.size); assertEquals(HandoffScope.ALL, r.c.handoffScope)
    }

    @Test fun s17_site_map_and_sites_list() {
        val r = busy(); show(r.c)
        rule.onNodeWithTag("open_map").performClick(); rule.onNodeWithTag("map_canvas").assertIsDisplayed(); shot("17-site-map")
        rule.onNodeWithTag("back").performClick(); rule.onNodeWithTag("back").performClick()
        rule.onNodeWithTag("site_GN").assertIsDisplayed(); shot("18-sites")
    }

    @Test fun s19_settings_and_advanced() {
        val r = busy(); show(r.c)
        rule.runOnIdle { r.c.go(Screen.SETTINGS) }
        rule.onNodeWithTag("pole_value").assertTextEquals("1.6 m"); shot("19-settings")
        rule.onNodeWithTag("advanced_toggle").performClick(); rule.onNodeWithTag("set_vworld").performScrollTo(); shot("20-settings-advanced")
        rule.onNodeWithTag("exit").performScrollTo().performClick(); shot("21-exit-confirm")
    }
}
