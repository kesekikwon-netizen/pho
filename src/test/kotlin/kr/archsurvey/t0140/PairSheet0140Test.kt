package kr.archsurvey.t0140

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.bt.FoundDevice
import kr.archsurvey.bt.PairConst
import kr.archsurvey.bt.PairStep
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 0.1.40 주변 수신기 창(Robolectric — 실기기 아님) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class PairSheet0140Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/workspace/survey-app/build-reports/0.1.40/screens").apply { mkdirs() }
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        FileOutputStream(File(out, "p40_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun findList_tap_pinAuto_progress_failCard() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fc = FakeConn(); val fp = FakePair()
        fp.bondedList = listOf(FoundDevice("SP60-123456", "BB:22", true))
        fp.scanResults = listOf(FoundDevice("R10, 5411457757: Trimble", "AA:11", false, -58), FoundDevice("Galaxy Buds2", "CC:33", false, -45))
        val cc = ConnectionController(fc, scope)
        val pc = PairController(fp, cc, scope, retryGapMs = 0)
        cc.pair = pc
        rule.setContent { SurveyTheme(dark = false) { androidx.compose.foundation.layout.Box { PairFindButton(cc); if (pc.open) PairSheet(pc) } } }
        rule.onNodeWithTag("pair_find").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pair_dev_AA:11").assertExists(); rule.onNodeWithTag("pair_dev_BB:22").assertExists()
        shot("01_list")
        rule.onNodeWithTag("pair_dev_AA:11").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pair_step", useUnmergedTree = true).assertTextContains("등록 없이 바로", substring = true)
        rule.runOnIdle { assertTrue(fp.bondCalls.isEmpty()) }
        shot("02_direct_connecting")
        rule.runOnIdle { fc.link.value = kr.archsurvey.device.LinkStatus(kr.archsurvey.device.LinkState.NEEDS_USER) }
        rule.waitUntil(3_000) { pc.step == PairStep.BONDING }
        rule.runOnIdle { assertEquals("1234", fp.request("AA:11", PairConst.VARIANT_PIN)) }
        rule.onNodeWithTag("pair_step", useUnmergedTree = true).assertTextContains("PIN 1234", substring = true)
        shot("03_pin_fallback")
        rule.runOnIdle { fp.failed("AA:11") }; rule.waitForIdle()
        rule.runOnIdle { fp.failed("AA:11") }
        rule.waitUntil(3_000) { pc.step == PairStep.FAILED }
        rule.onNodeWithTag("pair_fix_title", useUnmergedTree = true).assertTextEquals("R10 전원을 껐다 켜고 다시 눌러 주세요")
        shot("04_fail_power_cycle")
        rule.onNodeWithTag("pair_adv").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pair_pin_0000").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("0000", pc.pin)
        shot("05_advanced_pin")
    }
}
