package kr.archsurvey.t0131

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.core.*
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.t0130.Mem
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.31 시험용 연결 플랫폼: stopReal 횟수만 센다 */
internal class CountingConn(state: LinkState = LinkState.STREAMING) : ConnectionPlatform {
    var stops = 0
    override val link = MutableStateFlow(LinkStatus(state))
    override val ntrip = MutableStateFlow(NtripStatus())
    override fun bondedDevices() = listOf("SP60_1" to "00:11:22:33:44:55")
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {}
    override fun stopReal() { stops++; link.value = LinkStatus() }
    override fun loadSaved() = SavedConnection(DeviceProfiles.SP60_ID, "00:11:22:33:44:55", NtripForm(user = "u", password = "p"), true)
    override fun save(s: SavedConnection) {}
}

internal fun pt(sigmaH: Double?, sigmaU: Double?, q: Int = 4, caution: Boolean = sigmaH == null, name: String = "P-1") = SavedPoint(
    name = name, type = "유구", lat = 37.0, lon = 127.0, northing = 1.0, easting = 2.0, crsEpsg = 5186,
    groundHeightM = 10.0, sigmaHM = sigmaH, status = if (caution) SurveyStatus.CAUTION else SurveyStatus.FIXED, cautionFlag = caution, epochs = 3,
    antenna = AntennaRecord("X", "ARP", "ARP", 1.8, "VERTICAL", 0.0, 0.0, 1.8), fixQuality = q, satellites = 30, measuredAtMs = 0L,
    correctionAgeSec = 1.0, sigmaU = sigmaU)

/** 0.1.31 #1 측량 가능 = 초록(σ 없음 고정 포함), 엄격 규칙은 0.1.30 그대로 */
class Green0131Tests {
    private fun isGreen(c: androidx.compose.ui.graphics.Color) = c.green > c.red + 0.1f && c.green > c.blue + 0.1f

    @Test fun fixedToken_isGreen_and_highContrast() {
        assertTrue("고정(측량 가능) 색은 초록", isGreen(LightTokens.fixed))
        assertTrue(isGreen(statusColors(LightTokens, SurveyStatus.FIXED).first))
        assertFalse(isGreen(LightTokens.caution)); assertFalse(isGreen(LightTokens.bad))
    }

    @Test fun fixWithSigma_and_fixNoSigma_bothGreen_strictNoSigmaNot() {
        val (s, c) = rig(strict = false); s.push(FixQuality.RTK_FIXED, 0.01)
        assertEquals(SurveyStatus.FIXED, c.gv.displayStatus)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, null)
        assertEquals(SurveyStatus.FIXED, c.gv.displayStatus); assertTrue(c.gv.sigmaMissingOnFix)
        assertEquals(Tone.FIXED, HeaderModel.pill(c.gv, null, false, 10_000).fixTone)
        assertTrue("σ 없음 작은 표시는 남음", HeaderModel.metrics(c.gv).endsWith("· σ 없음"))
        val (s2, c2) = rig(strict = true); s2.push(FixQuality.RTK_FIXED, null)
        assertEquals(SurveyStatus.CAUTION, c2.gv.displayStatus)
    }

    @Test fun strictNoSigma_stillBlocked_withRetryPath() {
        val store = Mem(); val (s, c) = rig(strict = true, store = store)
        c.precisionRetry = { true }
        s.push(FixQuality.RTK_FIXED, null)
        c.onMeasure()
        assertEquals(PrecisionGuide.RETRY_LABEL, c.ui.choice!!.options.first())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun normalNoSigma_savedPointKeepsReason_andListGreen() {
        val store = Mem(); val (s, c) = rig(strict = false, store = store)
        s.push(FixQuality.RTK_FIXED, null)
        c.onMeasure(); c.ui.answer(true)
        s.now += 1000; s.push(FixQuality.RTK_FIXED, null)
        val p = store.saved.single()
        assertEquals("σ 없음(고정)", PointCaution.reason(p))
        assertEquals(SurveyStatus.FIXED, PointCaution.displayStatus(p))
        assertTrue(isGreen(statusColors(LightTokens, PointCaution.displayStatus(p)).first))
    }

    @Test fun bandTitle_and_drawerChip_useDisplayStatus() {
        assertEquals("측량 가능 · 양호", SigmaText.READY_MAIN)
        val band = File("src/main/kotlin/kr/archsurvey/ui/AppHeader.kt").readText().substringAfter("internal fun StatusBand(").substringBefore("\n}\n")
        assertTrue(band.contains("SurveyStatus.FIXED -> SigmaText.READY_MAIN"))
        assertFalse("비엄격 σ 없음도 같은 제목(따로 「고정 · σ 없음」 제목 없음)", band.contains("else SigmaText.FIX_NO_SIGMA"))
        val drawer = File("src/main/kotlin/kr/archsurvey/ui/SurveyMapScreen.kt").readText().substringAfter("fun DrawerOverlay(").substringBefore("\n}\n")
        assertTrue(drawer.contains("statusColors(t, shownSt)")); assertFalse(drawer.contains("statusColors(t, st.status)"))
    }
}

/** 0.1.31 #2 점마다 H/V 오차 */
class PointHv0131Tests {
    @Test fun hv_values_and_missing() {
        assertEquals("H 0.012 · V 0.020 m", PointCaution.hv(pt(0.012, 0.020)))
        assertEquals("H — · V —", PointCaution.hv(pt(null, null)))
        assertEquals("H 0.008 · V — m", PointCaution.hv(pt(0.008, null, caution = false)))
        assertEquals("오차 H 0.012 · V 0.020 m", PointCaution.hvLine(pt(0.012, 0.020)))
    }

    @Test fun savedPoint_carriesVerticalSigma_fromGst() {
        val store = Mem(); val (s, c) = rig(strict = false, store = store)
        s.push(FixQuality.RTK_FIXED, 0.01)
        c.onMeasure()
        val p = store.saved.single()
        assertNotNull(p.sigmaHM); assertNotNull(p.sigmaU)
        assertEquals(String.format(java.util.Locale.US, "H %.3f · V %.3f m", p.sigmaHM, p.sigmaU), PointCaution.hv(p))
    }

    @Test fun listRow_detail_menu_resultStrip_showHv() {
        val tools = File("src/main/kotlin/kr/archsurvey/ui/ToolScreens.kt").readText()
        assertTrue(tools.substringAfter("private fun PointRow(").substringBefore("\n}\n").contains("PointCaution.hv(p)"))
        assertTrue(tools.substringAfter("fun PointDetailScreen(").substringBefore("\n}\n").contains("PointCaution.hvLine(p)"))
        assertTrue(File("src/main/kotlin/kr/archsurvey/ui/MockupScreens.kt").readText().substringAfter("fun PointMenuSheet(").substringBefore("\n}\n").contains("PointCaution.hvLine(p)"))
        assertTrue(File("src/main/kotlin/kr/archsurvey/ui/SurveyMapScreen.kt").readText().substringAfter("private fun ResultStrip(").substringBefore("\n}\n").contains("PointCaution.hv(p)"))
    }
}

/** 0.1.31 #3 T27 종료: 확인 창 → 보정·블루투스 끊고 서비스 멈춤 → 앱 닫기. 전원 끄기 명령 없음 */
class Exit0131Tests {
    private fun setup(state: LinkState = LinkState.STREAMING): Triple<SurveyController, CountingConn, IntArray> {
        val (_, c) = rig(strict = false)
        val p = CountingConn(state)
        val cc = ConnectionController(p, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        c.connection = cc
        val exits = IntArray(1)
        c.onExitApp = { exits[0]++ }
        return Triple(c, p, exits)
    }

    // 0.1.32(의도된 변경): 한 창 → 「기기 종료」 → 「프로그램 종료」 두 단계(t0132/Exit0132Tests 에 자세히)
    @Test fun homeBack_asksExit_yes_disconnectsAndFinishes() {
        val (c, p, exits) = setup()
        c.homeOpen = true
        backTarget(c)!!.invoke()
        val r = c.ui.confirm!!
        assertEquals(AppExit.TITLE_DEVICE, r.title)
        // 0.1.36(의도된 변경): 현장 「기기 종료 눌러도 안 꺼진다」 → 제목 「수신기 연결 끊기」·단추 「연결 끊기」
        assertEquals("연결 끊기", r.okLabel); assertEquals("취소", r.cancelLabel)
        assertTrue(r.body.contains("수신기 버튼")) // 전원은 앱이 끄지 않음을 알림
        assertEquals(0, p.stops); assertEquals(0, exits[0])
        c.ui.answer(true)
        assertEquals(1, p.stops); assertEquals(0, exits[0])
        assertEquals(AppExit.TITLE_APP, c.ui.confirm!!.title)
        c.ui.answer(true)
        assertEquals(1, p.stops); assertEquals(1, exits[0])
        assertNull("끊김 알림 토스트 없이 닫힘", c.ui.toast)
    }

    @Test fun cancel_keepsConnection_andApp() {
        val (c, p, exits) = setup()
        c.homeOpen = true
        backTarget(c)!!.invoke(); c.ui.answer(false)
        assertEquals(0, p.stops); assertEquals(0, exits[0])
    }

    @Test fun notConnected_idleWording() {
        val (c, _, exits) = setup(LinkState.IDLE)
        c.requestExit()
        assertEquals(AppExit.TITLE_APP, c.ui.confirm!!.title)
        c.ui.answer(true); assertEquals(1, exits[0])
    }

    @Test fun duringAcquire_stopsMeasureFirst_noDialog() {
        val (c, p, exits) = setup()
        c.phase = Phase.ACQUIRING
        c.requestExit()
        assertEquals(Phase.IDLE, c.phase); assertNull(c.ui.confirm); assertEquals(0, p.stops); assertEquals(0, exits[0])
    }

    @Test fun surveyBack_stillGoesHome_first() {
        val (c, _, _) = setup()
        c.homeOpen = false
        backTarget(c)!!.invoke()
        assertTrue(c.homeOpen); assertNull(c.ui.confirm)
    }

    @Test fun activity_finishesTask_noPowerOffCommand() {
        val act = File("src/main/kotlin/kr/archsurvey/MainActivity.kt").readText()
        assertTrue(act.contains("finishAndRemoveTask()"))
        assertTrue(act.contains("exitRequested && isFinishing"))
        val main = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("수신기 전원 끄기 명령은 보내지 않음", Regex("POW,OFF|POWER,OFF|\\\$PASHS,POW").containsMatchIn(main))
    }
}

/** 0.1.31 #4 시작 모션 */
class Splash0131Tests {
    @Test fun phases_and_spec() {
        assertEquals(3_000, SplashSpec.DURATION_MS)
        assertEquals(0f, splashPhase(0f, 0.3f, 0.6f)); assertEquals(1f, splashPhase(1f, 0.3f, 0.6f)); assertEquals(0.5f, splashPhase(0.45f, 0.3f, 0.6f), 1e-4f)
    }

    @Test fun colors_fromBrief_inThemeOnly() {
        val t = LightTokens
        assertEquals(0xFFFAF9F5.toInt(), argb(t.splashBg)); assertEquals(0xFF141413.toInt(), argb(t.splashInk))
        assertEquals(0xFFD97757.toInt(), argb(t.splashLine)); assertEquals(0xFFC6613F.toInt(), argb(t.splashLineDeep))
        val src = File("src/main/kotlin/kr/archsurvey/ui/SplashScreen.kt").readText()
        assertFalse(src.contains("painterResource")); assertFalse(src.contains("FontWeight.Bold"))
    }

    @Test fun mainActivity_firstLaunchOnly_thenSurvey() {
        val act = File("src/main/kotlin/kr/archsurvey/MainActivity.kt").readText()
        assertTrue(act.contains("val firstLaunch = savedInstanceState == null"))
        assertTrue(act.contains("rememberSaveable { androidx.compose.runtime.mutableStateOf(firstLaunch) }"))
        // 0.1.12 직전 작업 이어 열기 · 자동 연결은 그대로(시작 모션과 무관하게 onCreate 에서)
        assertTrue(act.contains("vm.controller.homeOpen = !vm.resumeJob")); assertTrue(act.contains("vm.autoConnectOnLaunch()"))
    }

    private fun argb(c: androidx.compose.ui.graphics.Color) = ((c.alpha * 255 + 0.5f).toInt() shl 24) or ((c.red * 255 + 0.5f).toInt() shl 16) or ((c.green * 255 + 0.5f).toInt() shl 8) or (c.blue * 255 + 0.5f).toInt()
}
