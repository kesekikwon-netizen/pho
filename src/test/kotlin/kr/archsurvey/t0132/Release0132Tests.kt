package kr.archsurvey.t0132

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.device.*
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.t0130.rig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 메모리 저장소 플랫폼: AndroidConnectionPlatform 과 같은 규칙(기기별 주소·보정, 주소별 종류 기억, null 주소로 지우지 않음) */
internal class MemPlatform(var devices: List<Pair<String, String>>, link: LinkState = LinkState.IDLE) : ConnectionPlatform {
    override val link = MutableStateFlow(LinkStatus(link))
    override val ntrip = MutableStateFlow(NtripStatus())
    var deviceId: String? = null
    val addr = HashMap<String, String>()
    val pre = HashMap<String, Boolean>()
    val devNtrip = HashMap<String, NtripForm>()
    var globalNtrip = NtripForm(user = "u1", password = "pw")
    val types = HashMap<String, String>()
    var success = false
    var signature: String? = null
    val started = mutableListOf<Pair<String, String>>()
    val ntripOn = mutableListOf<Boolean>()
    var stops = 0
    var perm = true
    var btOn = true
    override var autoConnect = true // 실제 앱 기본값(설정 「다음 실행 때 자동 연결」 켬)
    override var correctionUserSet = false
    override fun bondedDevices() = devices
    override fun hasBluetoothPermission() = perm
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(perm)
    override fun bluetoothEnabled() = btOn
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) {
        deviceId = profile.id; addr[profile.id] = address; started += profile.id to address; ntripOn += ntripEnabled
        link.value = LinkStatus(LinkState.CONNECTING)
    }
    override fun stopReal() { stops++; link.value = LinkStatus() }
    override fun loadSaved() = SavedConnection(deviceId, deviceId?.let { addr[it] }, globalNtrip, deviceId?.let { pre[it] } ?: false)
    override fun save(s: SavedConnection) {
        s.deviceId?.let { id -> deviceId = id; s.address?.let { addr[id] = it }; pre[id] = s.preChecked; devNtrip[id] = s.ntrip }
        globalNtrip = s.ntrip
    }
    override fun hadSuccess() = success
    override fun savedFor(deviceId: String): SavedConnection? {
        val a = addr[deviceId]; val n = devNtrip[deviceId]
        if (a == null && n == null) return null
        return SavedConnection(deviceId, a, n ?: NtripForm(host = ""), pre[deviceId] ?: false)
    }
    override fun deviceTypeFor(address: String) = types[address]
    override fun markLinkOk(address: String, deviceId: String) { types[address] = deviceId }
    override fun receiverSignature() = signature
}

private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
private val R10 = "R10, 5432K12345" to "AA:01"
private val SP60 = "SP60_1234" to "BB:02"

class ReceiverDetect0132Tests {
    @Test fun nameDetection_modelBeforeMaker_unknownIgnored() {
        assertEquals(DeviceProfiles.SP60, ReceiverDetect.fromName("SP60_1234"))
        assertEquals(DeviceProfiles.SP60, ReceiverDetect.fromName("Spectra SP 60"))
        assertEquals(DeviceProfiles.SP60, ReceiverDetect.fromName("Spectra Geospatial"))
        assertEquals(DeviceProfiles.R10, ReceiverDetect.fromName("R10, 5432K12345"))
        assertEquals(DeviceProfiles.R10, ReceiverDetect.fromName("Trimble R10-1234"))
        assertEquals(DeviceProfiles.R10, ReceiverDetect.fromName("TRMB 1234"))
        assertNull("R100 은 R10 아님", ReceiverDetect.fromName("R100"))
        assertNull(ReceiverDetect.fromName("Galaxy Buds2"))
        assertNull("SPS(Trimble SPS) 처럼 제조사 둘 다 → 모름", ReceiverDetect.fromName("Trimble SPS985"))
        assertNull(ReceiverDetect.fromName(null))
    }

    @Test fun nmeaSignature() {
        assertEquals(DeviceProfiles.SP60_ID, ReceiverDetect.fromNmea("\$PASHR,ACK*3D\r\n\$GPGGA,..."))
        assertEquals(DeviceProfiles.R10_ID, ReceiverDetect.fromNmea("\$PTNL,GGK,...\r\n"))
        assertNull(ReceiverDetect.fromNmea("\$GNGGA,123519,4807.038,N"))
    }

    @Test fun decide_savedBonded_usesSaved_withOthersAsFallback() {
        val d = ReceiverDetect.decide(DeviceProfiles.R10_ID, R10.second, listOf(SP60, R10, "Buds" to "CC:03"))
        d as ReceiverDetect.Decision.UseSaved
        assertEquals(R10.second, d.target.address); assertEquals(DeviceProfiles.R10, d.target.profile)
        assertEquals(listOf(SP60.second), d.fallbacks.map { it.address })
    }

    @Test fun decide_rememberedTypeBeatsSavedIdAndName() {
        val d = ReceiverDetect.decide(DeviceProfiles.R10_ID, "DD:04", listOf("Rover1" to "DD:04"), remembered = { if (it == "DD:04") DeviceProfiles.SP60_ID else null })
        assertEquals(DeviceProfiles.SP60, (d as ReceiverDetect.Decision.UseSaved).target.profile)
        // 저장 주소의 이름을 몰라도(사용자가 고른 기기) 그대로 쓴다
        val d2 = ReceiverDetect.decide(DeviceProfiles.SP60_ID, "DD:04", listOf("Rover1" to "DD:04"))
        assertEquals(DeviceProfiles.SP60, (d2 as ReceiverDetect.Decision.UseSaved).target.profile)
    }

    @Test fun decide_noSavedOrUnpaired() {
        assertTrue(ReceiverDetect.decide(null, null, listOf("Buds" to "C")) == ReceiverDetect.Decision.None(false))
        assertEquals(ReceiverDetect.Decision.None(true), ReceiverDetect.decide(DeviceProfiles.R10_ID, "GONE", emptyList()))
        val one = ReceiverDetect.decide(DeviceProfiles.R10_ID, "GONE", listOf(SP60))
        assertTrue(one is ReceiverDetect.Decision.AutoPick && one.savedMissing && one.target.profile == DeviceProfiles.SP60)
        val many = ReceiverDetect.decide(null, null, listOf(SP60, R10))
        assertTrue(many is ReceiverDetect.Decision.Ambiguous && many.candidates.size == 2)
    }
}

class AutoConnect0132Tests {
    @Test fun ambiguous_noSaved_opensPicker_noConnect() {
        val p = MemPlatform(listOf(SP60, R10)); val c = ConnectionController(p, scope())
        assertFalse(c.autoConnectOnLaunch())
        assertTrue(p.started.isEmpty()); assertTrue(c.open)
        assertEquals(ConnectionController.AUTO_PICK_TITLE, c.notice!!.title)
    }

    @Test fun savedUnpaired_singleOther_autoPicksAndConnects() {
        val p = MemPlatform(listOf(SP60)); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = "GONE"
        val c = ConnectionController(p, scope())
        assertTrue(c.autoConnectOnLaunch())
        assertEquals(listOf(DeviceProfiles.SP60_ID to SP60.second), p.started)
        assertEquals(DeviceProfiles.SP60_ID, c.profile.id)
    }

    @Test fun blockedAtLaunch_saysWhy_insteadOfSilence() {
        val p = MemPlatform(listOf(R10)); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = R10.second
        p.btOn = false
        val c = ConnectionController(p, scope())
        assertFalse(c.autoConnectOnLaunch()); assertEquals(ConnectionController.AUTO_BT_OFF, c.ui.toast)
        p.btOn = true; p.perm = false
        val c2 = ConnectionController(p, scope())
        assertFalse(c2.autoConnectOnLaunch()); assertEquals(ConnectionController.AUTO_NO_PERMISSION, c2.ui.toast)
    }

    @Test fun autoConnectOff_respected() {
        val p = object : ConnectionPlatform by MemPlatform(listOf(R10)) { override var autoConnect: Boolean get() = false; set(_) {} }
        val c = ConnectionController(p, scope())
        assertFalse(c.autoConnectOnLaunch())
    }

    @Test fun engineAlreadyRunning_adoptsWithoutReconnect() {
        val p = MemPlatform(listOf(R10), LinkState.STREAMING); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = R10.second
        val c = ConnectionController(p, scope())
        assertFalse(c.autoConnectOnLaunch())
        assertTrue("소켓을 끊었다 다시 맺지 않음", p.started.isEmpty())
        assertTrue(c.connectRequested); assertEquals(ConnPhase.CONNECTED, c.phase())
    }

    @Test fun savedReceiverSilent_fallsBackToOtherPaired_thenBackOnce() {
        val p = MemPlatform(listOf(SP60, R10)); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = R10.second
        val c = ConnectionController(p, scope())
        assertTrue(c.autoConnectOnLaunch())
        assertEquals(DeviceProfiles.R10_ID to R10.second, p.started.last())
        // R10 전원 꺼짐: 한 바퀴 실패
        c.onStatus(LinkStatus(LinkState.RECONNECTING, 2, 1, BtError.CONNECT_TIMEOUT), NtripStatus())
        assertEquals(DeviceProfiles.SP60_ID to SP60.second, p.started.last())
        assertEquals(DeviceProfiles.SP60_ID, c.profile.id)
        assertTrue(c.ui.toast!!.contains("응답 없음"))
        // 앞 수신기의 늦은 실패 상태로는 또 넘어가지 않음
        c.onStatus(LinkStatus(LinkState.RECONNECTING, 3, 2, BtError.POWER_OR_RANGE), NtripStatus())
        assertEquals(2, p.started.size)
        // SP60 도 응답 없음 → 원래 R10 으로 한 번 돌아가고 끝
        c.onStatus(LinkStatus(LinkState.CONNECTING, 1, 0), NtripStatus())
        c.onStatus(LinkStatus(LinkState.RECONNECTING, 2, 1, BtError.CONNECT_TIMEOUT), NtripStatus())
        assertEquals(DeviceProfiles.R10_ID to R10.second, p.started.last()); assertEquals(3, p.started.size)
        c.onStatus(LinkStatus(LinkState.CONNECTING, 1, 0), NtripStatus())
        c.onStatus(LinkStatus(LinkState.RECONNECTING, 2, 1, BtError.CONNECT_TIMEOUT), NtripStatus())
        assertEquals("한 바퀴만", 3, p.started.size)
    }

    @Test fun manualConnect_neverSwitchesReceiver() {
        val p = MemPlatform(listOf(SP60, R10)); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = R10.second; p.pre[DeviceProfiles.R10_ID] = true
        val c = ConnectionController(p, scope())
        c.connect()
        c.onStatus(LinkStatus(LinkState.RECONNECTING, 2, 1, BtError.CONNECT_TIMEOUT), NtripStatus())
        assertEquals(listOf(DeviceProfiles.R10_ID to R10.second), p.started)
    }

    @Test fun streaming_remembersReceiverType_evenIfVrsFails_andNmeaSignatureCorrectsType() {
        val p = MemPlatform(listOf("Rover1" to "DD:04")); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = "DD:04"
        val c = ConnectionController(p, scope())
        assertTrue(c.autoConnectOnLaunch())
        c.onStatus(LinkStatus(LinkState.STREAMING), NtripStatus(NtripState.AUTH_FAILED, "401"))
        assertEquals(DeviceProfiles.R10_ID, p.types["DD:04"])
        p.signature = DeviceProfiles.SP60_ID // 수신기가 $PASH 를 보냄
        c.onStatus(LinkStatus(LinkState.STREAMING, 1, 0, message = "x"), NtripStatus(NtripState.AUTH_FAILED, "401"))
        assertEquals(DeviceProfiles.SP60_ID, p.types["DD:04"])
        assertTrue(c.ui.toast!!.contains("SP60"))
        // 다음 실행: 기억된 종류(SP60)로 연결
        val c2 = ConnectionController(p.also { it.link.value = LinkStatus() }, scope())
        assertTrue(c2.autoConnectOnLaunch()); assertEquals(DeviceProfiles.SP60_ID to "DD:04", p.started.last())
    }

    @Test fun notificationStop_releasesConnectingState() {
        val p = MemPlatform(listOf(R10)); p.deviceId = DeviceProfiles.R10_ID; p.addr[DeviceProfiles.R10_ID] = R10.second
        val c = ConnectionController(p, scope())
        assertTrue(c.autoConnectOnLaunch())
        p.link.value = LinkStatus()
        assertEquals("예전: 연결 중으로 남음", ConnPhase.CONNECTING, c.phase())
        c.onStoppedOutside()
        assertEquals(ConnPhase.IDLE, c.phase())
    }
}

class PerDevice0132Tests {
    @Test fun eachReceiverKeepsItsOwnAddressChecksAndCorrection() {
        val p = MemPlatform(listOf(R10, SP60)); p.success = true
        val c = ConnectionController(p, scope())
        c.selectAddress(R10.second); c.setCheck(0, true); c.setCheck(1, true)
        c.pickMount("VRS-RTCM34"); assertEquals("RTS1.ngii.go.kr", c.form.host)
        c.selectProfile(DeviceProfiles.SP60)
        assertNull(c.address)
        assertEquals("SP60 은 3.4 못 받음 → 기본 RTS2·VRS-RTCM32(아이디는 유지)", "VRS-RTCM32", c.form.mount); assertEquals("u1", c.form.user)
        c.selectAddress(SP60.second); c.pickMount("VRS-RTCM31")
        c.selectProfile(DeviceProfiles.R10)
        assertEquals(R10.second, c.address); assertTrue(c.checks.all { it })
        assertEquals("RTS1.ngii.go.kr", c.form.host); assertEquals("VRS-RTCM34", c.form.mount)
        c.selectProfile(DeviceProfiles.SP60)
        assertEquals(SP60.second, c.address); assertEquals("VRS-RTCM31", c.form.mount)
        // 다시 실행해도 마지막(SP60) 그대로
        val c2 = ConnectionController(p, scope())
        assertEquals(DeviceProfiles.SP60_ID, c2.profile.id); assertEquals(SP60.second, c2.address); assertEquals("VRS-RTCM31", c2.form.mount)
    }

    @Test fun inheritedCompatibleCorrection_kept_whenNewDeviceHasNone() {
        val p = MemPlatform(listOf(R10, SP60)); p.globalNtrip = NtripForm(host = "caster.example.com", mount = "M1", user = "u", password = "p")
        p.deviceId = DeviceProfiles.R10_ID
        val c = ConnectionController(p, scope())
        c.selectProfile(DeviceProfiles.SP60)
        assertEquals("caster.example.com", c.form.host); assertEquals("M1", c.form.mount)
    }
}

class Exit0132Tests {
    private fun setup(state: LinkState): Triple<SurveyController, MemPlatform, IntArray> {
        val (_, c) = rig(strict = false)
        val p = MemPlatform(listOf(SP60), state); p.deviceId = DeviceProfiles.SP60_ID; p.addr[DeviceProfiles.SP60_ID] = SP60.second
        p.devNtrip[DeviceProfiles.SP60_ID] = NtripForm(host = "RTS2.ngii.go.kr", mount = "VRS-RTCM32", user = "sp", password = "pw")
        p.globalNtrip = p.devNtrip[DeviceProfiles.SP60_ID]!! // save() 가 둘 다 쓰므로 실제로도 같다
        c.connection = ConnectionController(p, scope())
        val exits = IntArray(1)
        c.onExitApp = { exits[0]++ }
        return Triple(c, p, exits)
    }

    @Test fun connected_twoSteps_device_then_program() {
        val (c, p, exits) = setup(LinkState.STREAMING)
        c.requestExit()
        assertEquals(AppExit.TITLE_DEVICE, c.ui.confirm!!.title)
        assertTrue(c.ui.confirm!!.body.startsWith("수신기 연결을 끊을까요?"))
        assertTrue("전원은 수신기 버튼", c.ui.confirm!!.body.contains("수신기 버튼"))
        c.ui.answer(true)
        assertEquals(1, p.stops); assertEquals(0, exits[0])
        assertEquals(LinkState.IDLE, p.link.value.state)
        assertEquals(AppExit.TITLE_APP, c.ui.confirm!!.title); assertEquals("앱을 종료할까요? 측점은 이미 저장돼 있어요.", c.ui.confirm!!.body)
        c.ui.answer(true)
        assertEquals("두 번 끊지 않음", 1, p.stops); assertEquals(1, exits[0])
    }

    @Test fun deviceStep_cancel_keepsEverything() {
        val (c, p, exits) = setup(LinkState.STREAMING)
        c.requestExit(); c.ui.answer(false)
        assertNull(c.ui.confirm); assertEquals(0, p.stops); assertEquals(0, exits[0])
    }

    @Test fun programStep_cancel_staysInApp_disconnected() {
        val (c, p, exits) = setup(LinkState.STREAMING)
        c.requestExit(); c.ui.answer(true); c.ui.answer(false)
        assertNull(c.ui.confirm); assertEquals(1, p.stops); assertEquals(0, exits[0])
        assertEquals(ConnPhase.IDLE, c.connection!!.phase())
        // 다시 뒤로 → 이제 수신기 없음 → 「프로그램 종료」만
        c.requestExit(); assertEquals(AppExit.TITLE_APP, c.ui.confirm!!.title)
    }

    @Test fun notConnected_onlyProgramStep() {
        val (c, p, exits) = setup(LinkState.IDLE)
        c.requestExit()
        assertEquals(AppExit.TITLE_APP, c.ui.confirm!!.title)
        c.ui.answer(true); assertEquals(0, p.stops); assertEquals(1, exits[0])
    }

    @Test fun deviceExit_keepsSavedReceiver_nextLaunchAutoReconnects() {
        val (c, p, _) = setup(LinkState.STREAMING)
        c.requestExit(); c.ui.answer(true); c.ui.answer(true)
        assertEquals(DeviceProfiles.SP60_ID, p.deviceId); assertEquals(SP60.second, p.addr[DeviceProfiles.SP60_ID])
        assertEquals("VRS-RTCM32", p.devNtrip[DeviceProfiles.SP60_ID]!!.mount)
        // 다시 실행
        val c2 = ConnectionController(p, scope())
        assertEquals(DeviceProfiles.SP60_ID, c2.profile.id); assertEquals(SP60.second, c2.address)
        assertTrue(c2.autoConnectOnLaunch())
        assertEquals(DeviceProfiles.SP60_ID to SP60.second, p.started.single())
    }

    @Test fun noPowerOffCommand_anywhere() {
        val main = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(Regex("POW,OFF|POWER,OFF|\\\$PASHS,POW").containsMatchIn(main))
    }
}

/** 0.1.32 안정성 수정 — 소스 고정(Android 경로라 JVM 에서 실행 불가한 부분) */
class Stability0132SourceTests {
    private fun src(p: String) = File("src/main/kotlin/kr/archsurvey/$p").readText()

    @Test fun autoConnect_onEveryNewViewModel_notOnlyFirstLaunch() {
        val act = src("MainActivity.kt")
        val block = act.substringAfter("if (savedInstanceState == null) {\n            vm.controller.homeOpen").substringBefore("}")
        assertFalse("되살린 화면(savedInstanceState 있음)에서도 자동 연결", block.contains("autoConnectOnLaunch"))
        assertTrue(act.contains("vm.autoConnectOnLaunch()"))
        assertTrue(src("ui/SurveyViewModel.kt").contains("if (autoConnectTried) return"))
    }

    @Test fun engineRestart_waitsForOldSession() {
        assertTrue(src("engine/Engine.kt").contains("old?.join(); gnssLoop(profile, address)"))
    }

    @Test fun service_failurePaths_doNotLeaveConnectingForever() {
        val s = src("engine/SurveyConnectionService.kt")
        assertTrue(s.contains("ACTION_STOP_FROM_NOTIFICATION"))
        assertTrue(s.contains("engine.stoppedOutside.tryEmit"))
        assertTrue(s.contains("LinkStatus(LinkState.NEEDS_USER"))
        assertTrue(src("ui/SurveyViewModel.kt").contains("stoppedOutside.collect { cc.onStoppedOutside() }"))
    }

    @Test fun savedAddress_neverClearedByNull() {
        assertTrue(src("data/Settings.kt").contains("fun setBtAddress(deviceId: String, addr: String?) { if (addr != null)"))
    }
}

class Text0132Tests {
    @Test fun ngiiFaqTexts() {
        val auth = ErrorCards.ntripCard(NtripStatus(NtripState.AUTH_FAILED, "401"))!!
        assertTrue(auth.action.contains("공식 FAQ")); assertTrue(auth.action.contains("ngii")); assertTrue(auth.action.contains("이메일 형식"))
        assertFalse(auth.action.contains("확인하지 못했어요"))
        val dup = ErrorCards.ntripCard(NtripStatus(NtripState.AUTH_FAILED, "403"))!!
        assertTrue(dup.action.contains("기기마다 다른 통합 ID"))
        val help = File("src/main/kotlin/kr/archsurvey/ui/ConnectionScreen.kt").readText()
        assertTrue(help.contains("공식 FAQ") && help.contains("비밀번호는 ngii"))
        assertFalse(help.contains("공식 공지 원문은 확인하지 못했어요"))
    }
}
