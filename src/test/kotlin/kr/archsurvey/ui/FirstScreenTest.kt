package kr.archsurvey.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 첫 화면(수신기 없음)과 수신 중 화면을 Robolectric 으로 그려 본다. 실기기 아님. 스크린샷: build-reports/real/ */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class FirstScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/real").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Stub : GnssSource {
        override val link = MutableStateFlow(LinkStatus())
        override val gnss = MutableStateFlow(GnssSnapshot())
        override fun clockMs() = 10_000L
        override fun stop() {}
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, name)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun firstScreenSaysReceiverNotConnected() {
        val s = Stub()
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
        // 0.1.35: 상태 알약 + 원인 한 줄(누르면 연결)
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("수신기 연결 안 됨")
        rule.onNodeWithTag("cause_line").assertExists()
        rule.onNodeWithTag("measure_disabled_reason", useUnmergedTree = true).assertExists()
        shot("r1_not_connected")
        rule.onNodeWithTag("measure_button").performClick()
        rule.waitForIdle()
        assertEquals(0, c.points.size)
        shot("r2_measure_refused")
    }

    @Test fun streamingScreenShowsFixSatellitesAndCentimeterAccuracy() {
        val s = Stub()
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        val gga = GgaMessage("", "000000", 37.0, 127.0, FixQuality.RTK_FIXED, 21, 0.8, 50.0, 25.0, 1.0, "0001")
        rule.runOnUiThread {
            s.link.value = LinkStatus(state = LinkState.STREAMING)
            s.gnss.value = GnssSnapshot(gga, GstMessage("", 0.01, 0.006, 0.006, 0.012), lastGgaAtMs = 10_000L)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("cause_line").assertDoesNotExist()
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextEquals("측량 가능")
        rule.onNodeWithTag("pill_sigma", useUnmergedTree = true).assertTextEquals("±0.8/1.2")
        // 자세한 값(위성·고정)은 알약을 눌러 「지금 상태」에서
        rule.onNodeWithTag("status_pill").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dig_status").assertExists()
        rule.onNodeWithText("21", substring = true).assertExists()
        rule.onNodeWithTag("light_2").assertExists()
        shot("r3_streaming")
    }

    private fun streaming(): Pair<Stub, SurveyController> {
        val s = Stub()
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        val gga = GgaMessage("", "000000", 37.0, 127.0, FixQuality.RTK_FIXED, 21, 0.8, 50.0, 25.0, 1.0, "0001")
        rule.runOnUiThread {
            s.link.value = LinkStatus(state = LinkState.STREAMING)
            s.gnss.value = GnssSnapshot(gga, GstMessage("", 0.01, 0.006, 0.006, 0.012), lastGgaAtMs = 10_000L)
        }
        rule.waitForIdle()
        return s to c
    }

    @Test fun startsDirectlyOnSurveyMap() {
        val (_, c) = streaming()
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        rule.onNodeWithTag("status_pill").assertExists()
        rule.onNodeWithTag("measure_button").assertExists()
        rule.onNodeWithTag("tab_points").assertDoesNotExist()
        rule.onNodeWithTag("pole_strip").assertDoesNotExist()
        shot("c_start_streaming")
    }

    @Test fun startsOnMapWhenNotConnected() {
        val s = Stub()
        val c = SurveyController(s, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
        rule.onNodeWithTag("cause_line").assertExists()
        rule.onNodeWithTag("pill_fix", useUnmergedTree = true).assertTextContains("연결 안 됨", substring = true)
        shot("c_start_not_connected")
    }

    @Test fun quickSheetAndDrawerAndVersion() {
        val (_, c) = streaming()
        // 0.1.35: 위치 종류 칩 → 「지금 재는 것」 줄 → 고르기 시트
        rule.onNodeWithTag("now_row").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dig_picker").assertExists()
        shot("c_quick_sheet")
        rule.runOnUiThread { c.dig.select(kr.archsurvey.dig.DigKind.ARTIFACT, null); c.dig.pickerOpen = false }; rule.waitForIdle()
        rule.onNodeWithTag("dig_picker").assertDoesNotExist()
        assertEquals(kr.archsurvey.dig.DigKind.ARTIFACT, c.dig.kind)
        rule.runOnUiThread { c.drawerOpen = true }
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_version", useUnmergedTree = true).assertExists()
        shot("c_drawer")
    }
}
