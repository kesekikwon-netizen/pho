package kr.archsurvey.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.archsurvey.core.*
import kr.archsurvey.data.AntennaDb
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.device.FakeGnssSource
import kr.archsurvey.device.FakeScenarios
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.1.16: 버튼 탭 햅틱 — 설정 기본 ON, Vibrator 직접 펄스(시스템 터치진동 OFF 대비) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xxhdpi")
class Ui0116HapticTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val db = AntennaDb.parse(File("src/main/assets/antenna_db.json").readText())

    private class Jobs : JobPlatform {
        override var current = "te1"
        override suspend fun list() = listOf(JobInfo(current, 0, 0, 0, null, null))
        override suspend fun info(name: String) = JobInfo(name, 0, 0, 0, null, null)
        override suspend fun create(name: String) { current = name }
        override suspend fun open(name: String) { current = name }
        override suspend fun delete(name: String) = "b.zip"
    }
    private class Sett(override var vibration: Boolean = true) : SettingsPlatform {
        override var zone = KoreanTmZone.CENTRAL; override var poleRight = true; override var sound = true; override var keepScreenOn = true
    }

    private lateinit var c: SurveyController

    private fun setup(vib: Boolean = true) {
        TileImages.networkEnabled = false
        TapHaptics.reset()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = FakeGnssSource(FakeScenarios.allFixed())
        c = SurveyController(f, db, scope, epochsPerPoint = 3)
        c.ui.toastMs = 600_000L
        val jobs = Jobs(); c.projectName = jobs.current
        c.settingsState = SettingsState(c, Sett(vib))
        c.jobs = JobController(c, jobs, scope, MemoryJobConfigPlatform())
        c.homeOpen = true
        rule.setContent { SurveyTheme { SurveyMapScreen(c, batteryPct = 80) } }
        repeat(2) { f.emitNext() }
        rule.waitForIdle()
    }

    @Test fun vibrationSettingDefaultsOnInPrefs() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        val store = SettingsStore(ctx)
        assertTrue("SharedPreferences 기본 ON", store.vibrationOn)
    }

    @Test fun majorButtonsPulseWhenVibrationOn() {
        setup(true)
        rule.onNodeWithTag("home_settings2").performScrollTo().performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        assertTrue("탭 카운트", TapHaptics.taps >= 1)
        // Robolectric 에서는 performHapticFeedback 가 보통 false → Vibrator 경로
        assertTrue("직접 진동기 펄스(시스템 터치진동 OFF 대비)", TapHaptics.directPulses >= 1)
        rule.onNodeWithTag("zone_toggle").performClick(); rule.waitForIdle()
        assertTrue(TapHaptics.taps >= 2)
        assertTrue(TapHaptics.directPulses >= 2)
    }

    @Test fun vibrationOffSkipsTapPulse() {
        setup(true)
        rule.runOnUiThread { c.settingsState!!.changeVibration(false) }; rule.waitForIdle()
        TapHaptics.reset()
        // 홈「환경설정」도 hClickable — 진동 OFF 면 탭 카운트 증가 없음
        rule.onNodeWithTag("home_settings2").performScrollTo().performClick() /* 0.1.38: 머리줄 ≡ = 메뉴, 설정은 아래 바로가기 */; rule.waitForIdle()
        assertEquals(0, TapHaptics.taps)
        assertEquals(0, TapHaptics.directPulses)
    }

    @Test fun vibrateMsDoesNotThrow() {
        TapHaptics.reset()
        val ctx = RuntimeEnvironment.getApplication()
        TapHaptics.vibrateMs(ctx, 18L)
        TapHaptics.vibrateMs(ctx, 40L)
        // Robolectric 진동기 유무와 무관하게 예외 없어야 함
        assertTrue(TapHaptics.directPulses >= 0)
    }
}
