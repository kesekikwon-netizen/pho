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
        rule.runOnIdle { c.go(Screen.MAP); c.fitAll() }
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

    /** 0.1.5: 시안 번호(02–10)와 같은 이름으로 남겨 나란히 비교한다 */
    @Test fun s02_folder_list() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.back() }
        show(c)
        rule.onNodeWithTag("objects").assertIsDisplayed(); rule.onNodeWithTag("obj_001호_수혈").assertIsDisplayed()
        rule.onNodeWithTag("folder_count", useUnmergedTree = true).assertTextContains("폴더 3개", substring = true)
        shot("m02-folder-list")
        rule.onNodeWithTag("stage_photo").performClick(); shot("m02b-folder-list-filter-photo")
    }

    @Test fun s03_new_object_and_number() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.back() }
        show(c)
        rule.onNodeWithTag("obj_new").performClick(); rule.onNodeWithTag("newobj").assertIsDisplayed()
        rule.onNodeWithTag("newobj_name", useUnmergedTree = true).assertTextEquals("2호 주거지")   // 마지막 종류 · 다음 번호
        shot("m03-new-object")
        rule.onNodeWithTag("objno_display").performClick()
        rule.onNodeWithTag("col0_1").performClick(); rule.onNodeWithTag("col1_2").performClick(); rule.onNodeWithTag("col2_7").performClick()
        rule.onNodeWithTag("numpad_value", useUnmergedTree = true).assertTextEquals("127"); shot("m04-number-127")
        rule.onNodeWithTag("numpad_ok").performClick(); rule.onNodeWithTag("newobj_name", useUnmergedTree = true).assertTextEquals("127호 주거지")
        rule.onNodeWithTag("kind_section").performScrollTo().performClick(); shot("m03b-new-object-section-letters")
    }

    @Test fun s05_folder_inside_stages() {
        val r = busy(); val c = r.c
        show(c)
        rule.onNodeWithTag("obj_screen").assertIsDisplayed(); rule.onNodeWithTag("obj_title", useUnmergedTree = true).assertTextEquals("1호 수혈")
        shot("m05-folder-inside-measure")
        rule.runOnIdle { c.openObj(c.book!!.objs[1].id) }; shot("m05b-folder-inside-photo")
        rule.runOnIdle { c.openObj(c.book!!.objs[2].id) }; shot("m05c-folder-inside-send")
        rule.onNodeWithTag("tool_session").performClick(); shot("m05d-session-picker")
    }

    @Test fun s06_targets_and_measuring() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs[1].id); c.openTargets() }
        show(c)
        rule.onNodeWithTag("targets").assertIsDisplayed(); rule.onNodeWithTag("qtag_done_1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("표적 4 재기")
        shot("m06-targets")
        rule.runOnIdle { val (la, lo) = Geo.latLon(194890.0, 573975.0); r.gnss.emit(q = kr.archsurvey.core.FixQuality.RTK_FLOAT, lat = la, lon = lo) }
        rule.mainClock.advanceTimeBy(1500); rule.onNodeWithTag("measure_label", useUnmergedTree = true).assertTextEquals("FIX 기다리는 중"); shot("m06b-targets-float-blocked")
        rule.runOnIdle { r.prefs.epochs = 10; r.gnss.emitAt(194890.0, 573975.0); c.onTargetTap(4) }
        rule.runOnIdle { repeat(7) { r.gnss.emitAt(194890.0, 573975.0) } }
        rule.onNodeWithTag("measuring").assertIsDisplayed(); shot("m07-measuring")
        rule.onNodeWithTag("measure_stop").performClick(); rule.onNodeWithTag("targets").assertIsDisplayed()
        rule.runOnIdle { c.onTargetLong(0) }; shot("m06c-remeasure-confirm")
        rule.runOnIdle { c.ui.answer(false); c.openAddSlot() }; shot("m06d-add-target-number")
    }

    @Test fun s08_id_card_with_qr() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.openObj(c.book!!.objs[1].id); c.openIdCard() }
        show(c)
        rule.onNodeWithTag("idcard_folder", useUnmergedTree = true).assertTextEquals("A_토층_1차")
        rule.onNodeWithTag("idcard_qr").assertIsDisplayed()
        shot("m08-id-card")
    }

    @Test fun s09_handoff() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.back() }
        show(c)
        rule.onNodeWithTag("to_handoff").performClick(); rule.onNodeWithTag("handoff_zip", useUnmergedTree = true).assertExists(); shot("m09-handoff")
        rule.onNodeWithTag("handoff_all").performClick(); shot("m09b-handoff-all")
        rule.onNodeWithTag("handoff_send").performClick()
        assertEquals(1, r.files.zips.size); assertEquals(HandoffScope.ALL, c.handoffScope)
    }

    @Test fun s10_sites_and_new_site_picker() {
        val r = ControllerTest.rig(kr.archsurvey.gcp.model.AddressParts("강원특별자치도", "강릉시", "성남동", "중앙동"))
        show(r.c)
        rule.onNodeWithTag("sites").assertIsDisplayed(); shot("m10a-sites-empty")
        rule.runOnIdle { r.gnss.emit(lat = 37.7519, lon = 128.8961) }
        rule.onNodeWithTag("site_new").performClick()
        shot("m10b-new-site")
        rule.onNodeWithTag("site_sug_0").assertExists(); rule.onNodeWithTag("district_2").performScrollTo().performClick()
        rule.onNodeWithTag("newsite_ok").performClick(); rule.onNodeWithTag("objects").assertIsDisplayed()
        rule.onNodeWithTag("site_name", useUnmergedTree = true).assertTextEquals("강릉 성남동 유적 2지구")
        rule.runOnIdle { r.c.openSitePicker() }; shot("m10-sites")
    }

    @Test fun s11_map_menu_pole_settings() {
        val r = busy(); val c = r.c
        rule.runOnIdle { c.go(Screen.MAP) }
        show(c)
        rule.onNodeWithTag("site_map").assertIsDisplayed(); rule.onNodeWithTag("qtag_1").assertIsDisplayed(); shot("m11-map-offline")
        rule.onNodeWithTag("menu").performClick(); shot("m12-menu")
        rule.runOnIdle { c.menuOpen = false; c.poleOpen = true }; shot("m13-pole")
        rule.runOnIdle { c.poleOpen = false; c.go(Screen.SETTINGS) }
        rule.onNodeWithTag("pole_value").assertTextEquals("1.6 m"); shot("m14-settings")
        rule.onNodeWithTag("exit").performScrollTo().performClick(); shot("m15-exit-confirm")
    }
}
