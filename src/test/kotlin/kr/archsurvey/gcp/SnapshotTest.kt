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
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
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
        c.openNewObj(); c.pickKind(ObjKind.SECTION); c.submitNewObj()
        r.measure(1, 194950.0, 574010.0); r.measure(2, 194951.0, 574010.0); r.measure(3, 194952.0, 574011.0)
        c.openNewObj(); c.pickKind(ObjKind.DWELLING); c.submitNewObj()
        r.measure(1, 194900.0, 573950.0); r.measure(2, 194902.0, 573950.0); r.measure(3, 194901.0, 573953.0); r.measure(4, 194899.0, 573952.0)
        c.markPhotoDone()
        repeat(3) { r.gnss.emitAt(194890.0, 573975.0) }
        c.openObj(c.book!!.objs[0].id)
        return r
    }

    /** 내장 V월드 키(릴리스 때 tools/inject-vworld-key.sh 로 넣은 것). 없으면 빈 키 → 지도 시험은 건너뜀 */
    private val builtinKey: String get() = runCatching { kr.archsurvey.map.VWorldDefaults.BUILTIN_API_KEY }.getOrDefault("")

    /** 맨 마지막(z): 실제 V월드 타일을 받는 동안 다른 화면 시험이 안 멈추게 */
    @Test fun z01_main_map_screen_real_satellite_cadastral() {
        val key = builtinKey
        org.junit.Assume.assumeTrue("V월드 키 없음", key.length >= 20)
        TileImages.networkEnabled = true
        val r = busy(); val c = r.c; c0 = c
        rule.runOnIdle { c.fitAll() }
        ui = c.ui
        rule.setContent { SurveyTheme { GcpRoot(c, GcpEnv(mapAccess = { kr.archsurvey.map.VWorldAccess(key, "") })) } }
        // 타일은 IO 에서 받는다 — 몇 초 기다리며 다시 그린다
        settleTiles()
        rule.onNodeWithTag("map_area").assertIsDisplayed(); rule.onNodeWithTag("measure_button").assertIsDisplayed()
        rule.onNodeWithTag("map_attribution").assertIsDisplayed()
        shot("01-main-map-satellite-cadastral")
        // 위성은 실패 없어야 함. 지적(2d.vworld.kr:8895)은 이 상자에서 포트가 막혀 시간 초과 — 측량 앱과 같은 주소·코드(폰에서는 됨)
        assertTrue("타일 오류: ${TileImages.lastError}", TileImages.lastError == null || TileImages.lastError!!.contains("지적"))
        rule.runOnIdle { c.zoomOut(); c.zoomOut() }
        settleTiles()
        shot("02-main-map-zoomed-out")
    }

    /**
     * Robolectric 은 화면을 실제로 그릴 때(view.draw)만 지도 그리기가 돌고, 그때 타일을 요청한다.
     * 그래서 버릴 그림을 반복해 그리며 타일 그림 수(version)가 12초 동안 안 바뀔 때까지(최대 90초 · 상자에서는 지적 서버 8895 포트가 막혀 위성이 그 뒤에 줄 선다) 기다린다.
     */
    private fun settleTiles() {
        val view = rule.activity.window.decorView
        var last = -1; var still = 0; var n = 0
        while ((still < 24 || last <= 0) && n < 180) {
            rule.runOnUiThread { val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.invalidate(); view.draw(android.graphics.Canvas(b)); b.recycle() }
            Thread.sleep(500); rule.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }; rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
            val v = TileImages.version; if (v == last) still++ else { still = 0; last = v }; n++
        }
        rule.runOnIdle { c0?.let { it.panBy(0.001, 0.0); it.panBy(-0.001, 0.0) } }; rule.waitForIdle()
    }
    private var c0: GcpController? = null

    @Test fun s03_map_screen_states_offline() {
        val r = busy(); val c = r.c
        show(c)
        rule.onNodeWithTag("site_map").assertIsDisplayed(); rule.onNodeWithTag("qtag_1").assertIsDisplayed()
        rule.onNodeWithTag("now_obj", useUnmergedTree = true).assertTextEquals("1호 수혈")
        shot("03-map-measure-fix")
        rule.runOnIdle { val (la, lo) = Geo.latLon(194890.0, 573975.0); r.gnss.emit(q = kr.archsurvey.core.FixQuality.RTK_FLOAT, lat = la, lon = lo) }
        rule.mainClock.advanceTimeBy(1500); rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("FIX 기다리는 중"); shot("04-map-float-blocked")
        rule.runOnIdle { r.gnss.emitAt(194890.0, 573975.0); c.onTargetLong(1) }
        shot("05-remeasure-confirm")
        rule.runOnIdle { c.ui.answer(false); r.measure(1, 194884.215, 573986.140, 4.192) }
        shot("06-target-moved")
        rule.runOnIdle { c.ui.answer(false); c.openObj(c.book!!.objs[2].id) }
        shot("07-map-photo-stage")
    }

    @Test fun s08_pickers_obj_kind_number_session_target() {
        val r = busy(); val c = r.c
        show(c)
        rule.onNodeWithTag("now_row").performClick(); rule.onNodeWithTag("obj_sheet").assertIsDisplayed(); shot("08-picker-objects")
        rule.onNodeWithTag("obj_new").performClick(); rule.onNodeWithTag("kind_dwelling").assertIsDisplayed(); shot("09-picker-kind-tiles")
        rule.onNodeWithTag("kind_dwelling").performClick(); rule.onNodeWithTag("objno_value", useUnmergedTree = true).assertTextEquals("2"); shot("10-picker-number")
        rule.onNodeWithTag("objno_display").performClick(); rule.onNodeWithTag("key_1").performClick(); rule.onNodeWithTag("key_2").performClick(); rule.onNodeWithTag("key_7").performClick()
        rule.onNodeWithTag("numpad_value", useUnmergedTree = true).assertTextEquals("127"); shot("11-picker-numpad-127")
        rule.onNodeWithTag("numpad_ok").performClick(); rule.onNodeWithTag("objno_value", useUnmergedTree = true).assertTextEquals("127")
        rule.runOnIdle { c.newObj = null; c.newObj = NewObjForm(); c.pickKind(ObjKind.SECTION) }; shot("12-picker-section-letters")
        rule.runOnIdle { c.newObj = null }
        rule.onNodeWithTag("tool_session").performClick(); shot("13-picker-session")
        rule.onNodeWithTag("session_new").performClick(); shot("14-picker-new-session")
        rule.runOnIdle { c.newSession = null }
        rule.onNodeWithTag("add_slot").performClick(); rule.onNodeWithTag("numpad_value", useUnmergedTree = true).assertTextEquals("5"); shot("15-picker-target-number")
    }

    @Test fun s16_sites_and_new_site_picker() {
        val r = ControllerTest.rig(kr.archsurvey.gcp.model.AddressParts("강원특별자치도", "강릉시", "성남동", "중앙동"))
        show(r.c)
        rule.onNodeWithTag("sites").assertIsDisplayed(); shot("16-sites-empty")
        rule.runOnIdle { r.gnss.emit(lat = 37.7519, lon = 128.8961) }
        rule.onNodeWithTag("site_new").performClick()
        shot("17-picker-new-site")
        rule.onNodeWithTag("site_sug_0").assertExists(); rule.onNodeWithTag("district_2").performScrollTo().performClick()
        rule.onNodeWithTag("epsg_5187").performScrollTo(); shot("17b-picker-new-site-district-epsg")
        rule.onNodeWithTag("newsite_ok").performClick(); rule.onNodeWithTag("site_map").assertIsDisplayed()
        rule.onNodeWithTag("header_title", useUnmergedTree = true).assertTextEquals("강릉 성남동 유적 2지구")
        rule.runOnIdle { r.c.back() }; shot("18-sites-one")
    }

    @Test fun s19_id_card_with_qr() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs[1].id); c.openIdCard() }
        show(c)
        rule.onNodeWithTag("idcard_folder", useUnmergedTree = true).assertTextEquals("A_토층_1차")
        rule.onNodeWithTag("idcard_qr").assertIsDisplayed()
        shot("19-id-card")
    }

    @Test fun s20_menu_handoff_pole() {
        val r = busy(); show(r.c)
        rule.onNodeWithTag("menu").performClick(); shot("20-menu")
        rule.onNodeWithTag("menu_handoff").performClick(); rule.onNodeWithTag("handoff_zip", useUnmergedTree = true).assertExists(); shot("21-handoff")
        rule.onNodeWithTag("handoff_all").performClick()
        assertEquals(1, r.files.zips.size); assertEquals(HandoffScope.ALL, r.c.handoffScope)
        rule.onNodeWithTag("pole_chip").performClick(); shot("22-pole")
    }

    @Test fun s23_settings_and_advanced() {
        val r = busy(); show(r.c)
        rule.runOnIdle { r.c.go(Screen.SETTINGS) }
        rule.onNodeWithTag("pole_value").assertTextEquals("1.6 m"); shot("23-settings")
        rule.onNodeWithTag("advanced_toggle").performClick(); rule.onNodeWithTag("set_vworld").performScrollTo(); shot("24-settings-advanced")
        rule.onNodeWithTag("exit").performScrollTo().performClick(); shot("25-exit-confirm")
    }
}
