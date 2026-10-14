package kr.archsurvey.ui

import kr.archsurvey.settingsTab

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.FakeTiltPlatform
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.device.*
import kr.archsurvey.tiltReading
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 전자 기포: 지도 위 작은 기포, 확인 창, 설정. Robolectric 그림(실기기 아님). 스크린샷: REPORTS_ROOT/tilt/ */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class TiltScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(REPORTS_ROOT + "/tilt").apply { mkdirs() }
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())
    private class Sett : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var vibration = true; override var keepScreenOn = true
    }

    private fun shot(name: String) {
        ButtonCount.record(rule, name)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setup(p: FakeTiltPlatform = FakeTiltPlatform()): Pair<SurveyController, TiltController> {
        val f = FakeGnssSource(FakeScenarios.allFixed())
        val c = SurveyController(f, db, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val tc = TiltController(p, { 0L })
        c.tilt = tc; c.settingsState = SettingsState(c, Sett())
        repeat(2) { f.emitNext() }
        rule.setContent { SurveyTheme(dark = false) { SurveyMapScreen(c, batteryPct = 82) } }
        rule.waitForIdle()
        return c to tc
    }

    @Test fun bubbleShowsAngleAndWarnsOverLimitWithoutAddingButtons() {
        val (c, tc) = setup()
        rule.runOnUiThread { repeat(60) { tc.onSample(tiltReading(0.8, 0.4)) } }; rule.waitForIdle()
        rule.onNodeWithTag("tilt_bubble", useUnmergedTree = true).assertExists() // 0.1.46: 「다음 점」 줄(누르면 무엇을 잴까요) 안에 합쳐짐 — 기포 표시 자체는 단추가 아님
        rule.onNodeWithTag("tilt_angle", useUnmergedTree = true).assertTextContains("0.9°", substring = true)
        shot("k1_bubble_ok")
        rule.runOnUiThread { repeat(80) { tc.onSample(tiltReading(2.6, 1.2)) } }; rule.waitForIdle()
        assertTrue(tc.isOver)
        rule.onNodeWithTag("tilt_angle", useUnmergedTree = true).assertTextContains("2.9°", substring = true)
        shot("k2_bubble_over")
        // 확인 정책: 측점 → 확인 창
        rule.runOnUiThread { tc.changePolicy(TiltPolicy.CONFIRM) }
        rule.onNodeWithTag("measure_button").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tilt_sheet").assertExists()
        shot("k3_tilt_confirm")
        rule.onNodeWithTag("tilt_cancel").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tilt_sheet").assertDoesNotExist(); assertTrue(c.points.isEmpty())
    }

    @Test fun settingsSectionAdjustsLimitPolicyAndCalibrates() {
        val p = FakeTiltPlatform()
        val (c, tc) = setup(p)
        rule.runOnUiThread { repeat(60) { tc.onSample(tiltReading(3.0)) }; c.hub.open(ToolScreen.SETTINGS) }; rule.waitForIdle()
        rule.settingsTab(1).onNodeWithTag("tilt_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tilt_plus").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(2.5, p.limitDeg, 0.0)
        rule.onNodeWithTag("tilt_policy_block").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("block", p.policyId)
        rule.onNodeWithTag("tilt_calibrate").performScrollTo().performClick(); rule.waitForIdle()
        assertNotNull(p.reference)
        rule.onNodeWithTag("tilt_note").assertTextContains("영점을 잡았어요", substring = true)
        rule.settingsTab(1).onNodeWithTag("tilt_toggle").performScrollTo()
        shot("k4_settings_tilt")
    }

    @Test fun noSensorPhoneShowsNoticeAndNoBubble() {
        val (c, _) = setup(FakeTiltPlatform(available = false))
        rule.onNodeWithTag("tilt_bubble", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnUiThread { c.hub.open(ToolScreen.SETTINGS) }; rule.waitForIdle()
        rule.settingsTab(1).onNodeWithTag("tilt_no_sensor").performScrollTo().assertExists()
    }
}
