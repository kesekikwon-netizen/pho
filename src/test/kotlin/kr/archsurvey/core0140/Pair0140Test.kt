package kr.archsurvey.core0140

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.archsurvey.bt.*
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 0.1.40(측량) · 0.1.2(사진 기준점) 앱 안 수신기 등록 — 같은 시험 파일 */
class FakeConn : ConnectionPlatform {
    override val link = MutableStateFlow(LinkStatus())
    override val ntrip = MutableStateFlow(NtripStatus())
    var devices = listOf<Pair<String, String>>()
    var started: Pair<String, String>? = null
    var stopped = 0
    var saved: SavedConnection? = null
    val direct = mutableListOf<Pair<String, String>>()
    override fun rememberDirect(name: String, address: String) { direct += name to address }
    override fun bondedDevices() = devices
    override fun hasBluetoothPermission() = true
    override fun requestBluetoothPermission(onResult: (Boolean) -> Unit) = onResult(true)
    override fun bluetoothEnabled() = true
    override fun openBluetoothSettings() {}
    override fun openAppSettings() {}
    override fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean) { started = profile.id to address }
    override fun stopReal() { stopped++ }
    override fun loadSaved() = SavedConnection(null, null, NtripForm(user = "", password = ""))
    override fun save(s: SavedConnection) { saved = s }
}

class FakePair : PairPlatform {
    override var events: PairEvents? = null
    var perm = true; var grant = true; var btOn = true
    val bondedSet = mutableSetOf<String>()
    var bondedList = listOf<FoundDevice>()
    var scanResults = listOf<FoundDevice>()
    val bondCalls = mutableListOf<String>()
    var createOk = true
    override fun hasPermission() = perm
    override fun requestPermission(onResult: (Boolean) -> Unit) { if (grant) perm = true; onResult(grant) }
    override fun bluetoothEnabled() = btOn
    override fun bonded() = bondedList
    override fun startScan(onFound: (FoundDevice) -> Unit, onDone: () -> Unit): Boolean { scanResults.forEach(onFound); onDone(); return true }
    override fun stopScan() {}
    override fun createBond(address: String): Boolean { bondCalls += address; return createOk }
    override fun isBonded(address: String) = address in bondedSet
    /** 수신기 흉내: 등록 요청 → 결과 */
    fun request(addr: String, variant: Int) = events!!.onPairingRequest(addr, variant)
    fun bonded(addr: String) { bondedSet += addr; events!!.onBondState(addr, PairConst.BOND_BONDED, PairConst.BOND_BONDING, null) }
    fun failed(addr: String, reason: Int? = PairConst.REASON_AUTH_FAILED) = events!!.onBondState(addr, PairConst.BOND_NONE, PairConst.BOND_BONDING, reason)
}

@OptIn(ExperimentalCoroutinesApi::class)
class Pair0140Test {
    private val r10 = FoundDevice("R10, 5411457757: Trimble", "AA:11", false, -60)
    private val sp60 = FoundDevice("SP60-123456", "BB:22", false, -70)

    // ---------- 순수 상태 기계 ----------
    @Test fun pinVariant_setsPin_thenBondedConnects() {
        val m = PairMachine(r10, PairList.pins("1234"), directFirst = false)
        assertEquals(PairAction.CreateBond("AA:11"), m.start(false))
        assertEquals("1234", m.onPairingRequest(PairConst.VARIANT_PIN))
        assertEquals(PairStep.BONDING, m.step)
        assertEquals(PairAction.Connect("AA:11", true), m.onBondState(PairConst.BOND_BONDED, PairConst.BOND_BONDING))
        m.onConnectResult(true); assertEquals(PairStep.DONE, m.step)
    }
    @Test fun consentOrPasskeyConfirm_leftToSystem_waitUser() {
        val m = PairMachine(sp60); m.start(false)
        assertNull(m.onPairingRequest(PairConst.VARIANT_CONSENT)); assertEquals(PairStep.WAIT_USER, m.step)
        assertNull(m.onPairingRequest(PairConst.VARIANT_PASSKEY_CONFIRMATION)); assertEquals(PairStep.WAIT_USER, m.step)
        assertTrue(PairText.step(PairStep.WAIT_USER, m).contains("「등록」"))
    }
    @Test fun r10_directInsecureFirst_noBond_thenBondOnlyIfDirectFails() {
        val m = PairMachine(r10, PairList.pins("1234"))
        assertEquals(PairAction.Connect("AA:11", false), m.start(false))
        assertEquals(PairStep.CONNECTING_UNBONDED, m.step)
        assertTrue(PairText.step(m.step, m).contains("등록 없이 바로"))
        m.onConnectResult(true); assertEquals(PairStep.DONE, m.step)
        // 실패하면 그때 PIN 등록(1234 → 0000) → 그래도 안 되면 전원 안내(등록 없이 연결을 또 하지는 않음)
        val f = PairMachine(r10, PairList.pins("1234"))
        f.start(false)
        assertEquals(PairAction.CreateBond("AA:11"), f.onConnectResult(false))
        assertEquals("1234", f.onPairingRequest(PairConst.VARIANT_PIN))
        assertEquals(PairAction.CreateBond("AA:11"), f.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1))
        assertEquals("0000", f.onPairingRequest(PairConst.VARIANT_PIN))
        assertEquals(PairAction.None, f.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1))
        assertEquals(PairFix.POWER_CYCLE, f.fix)
    }
    @Test fun sp60_bondsFirst() { assertEquals(PairAction.CreateBond("BB:22"), PairMachine(sp60).start(false)) }
    @Test fun unbondedPlan_insecureOnly_bondedPlanUnchanged() {
        val u = kr.archsurvey.bt.planAttempts(emptyList(), null, emptySet(), insecureFirst = true, unbonded = true).map { it.strategy }
        assertEquals(listOf(Strategy.INSECURE_SPP, Strategy.INSECURE_BASE, Strategy.REFLECT_CH1_INSECURE), u)
        val b = kr.archsurvey.bt.planAttempts(emptyList(), null, emptySet(), insecureFirst = true).map { it.strategy }
        assertEquals(listOf(Strategy.INSECURE_SPP, Strategy.SECURE_SPP, Strategy.BASE_SPP, Strategy.REFLECT_CH1), b)
        val src = java.io.File("src/main/kotlin/kr/archsurvey/bt/BtCompat.kt").readText()
        assertTrue(src.contains("createInsecureRfcommSocketToServiceRecord(BtCompat.BASE_UUID)") && src.contains("\"createInsecureRfcommSocket\""))
    }
    @Test fun bondFailure_retriesWith0000_thenUnbonded_thenPowerCycle_thenClearRecord() {
        val m = PairMachine(r10, PairList.pins("1234"), directFirst = false)
        m.start(false); m.onPairingRequest(PairConst.VARIANT_PIN)
        assertEquals(PairAction.CreateBond("AA:11"), m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, PairConst.REASON_AUTH_FAILED))
        assertEquals("0000", m.onPairingRequest(PairConst.VARIANT_PIN))
        assertEquals(PairAction.Connect("AA:11", false), m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, PairConst.REASON_AUTH_FAILED))
        assertEquals(PairStep.CONNECTING_UNBONDED, m.step)
        m.onConnectResult(false)
        assertEquals(PairStep.FAILED, m.step); assertEquals(PairFix.POWER_CYCLE, m.fix)
        assertEquals("R10 전원을 껐다 켜고 다시 눌러 주세요", PairText.fix(m.fix, r10.name)!!.first)
        // 다시(전원 껐다 켠 뒤) — 또 실패하면 R10 웹 화면 안내
        m.retry(false); m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1); m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, 1); m.onConnectResult(false)
        assertEquals(PairFix.CLEAR_RECORD, m.fix)
        assertTrue(PairText.fix(m.fix, r10.name)!!.second.contains("192.168.142.1"))
    }
    @Test fun userCancel_noAutoRetry() {
        val m = PairMachine(r10, directFirst = false); m.start(false)
        assertEquals(PairAction.None, m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, PairConst.REASON_AUTH_CANCELED))
        assertEquals(PairFix.CANCELLED, m.fix)
    }
    @Test fun remoteDown_saysComeCloser_noUnbondedAttempt() {
        val m = PairMachine(r10, directFirst = false); m.start(false)
        assertEquals(PairAction.None, m.onBondState(PairConst.BOND_NONE, PairConst.BOND_BONDING, PairConst.REASON_REMOTE_DEVICE_DOWN))
        assertEquals(PairFix.NEAR, m.fix)
    }
    @Test fun alreadyBonded_skipsPairing() {
        val m = PairMachine(r10.copy(bonded = true))
        assertEquals(PairAction.Connect("AA:11", true), m.start(true))
    }
    @Test fun timeout_countsAsFailure() {
        val m = PairMachine(r10, PairList.pins("0000"), directFirst = false) // 설정이 0000 이면 PIN 하나
        m.start(false)
        assertEquals(PairAction.Connect("AA:11", false), m.onTimeout())
    }
    @Test fun pins_defaultThenFactory_validated() {
        assertEquals(listOf("1234", "0000"), PairList.pins(null))
        assertEquals(listOf("1234", "0000"), PairList.pins("12a"))
        assertEquals(listOf("5678", "0000"), PairList.pins("5678"))
        assertEquals(listOf("0000"), PairList.pins("0000"))
    }
    @Test fun list_receiversFirst_bondedFirst_dedup() {
        val other = FoundDevice("Galaxy Buds", "CC:33", false, -40)
        val l = PairList.sort(listOf(other, sp60, r10, r10.copy(bonded = true, rssi = null), FoundDevice("", "DD:44", false)))
        assertEquals(listOf("AA:11", "BB:22", "CC:33"), l.map { it.address })
        assertTrue(l[0].bonded); assertEquals(-60, l[0].rssi)
        assertEquals(DeviceProfiles.R10_ID, l[0].model?.id); assertEquals(DeviceProfiles.SP60_ID, l[1].model?.id)
    }

    // ---------- 컨트롤러 + 기존 연결 흐름 ----------
    private fun rig(scope: kotlinx.coroutines.CoroutineScope): Triple<FakeConn, FakePair, PairController> {
        val fc = FakeConn(); val fp = FakePair()
        val cc = ConnectionController(fc, scope)
        var pin = "1234"
        val pc = PairController(fp, cc, scope, pinSetting = { pin }, pinSave = { pin = it }, bondTimeoutMs = 45_000, retryGapMs = 1_000)
        cc.pair = pc
        return Triple(fc, fp, pc)
    }

    @Test fun tapNewR10_connectsWithoutBond_savesAndRemembers() = runTest(StandardTestDispatcher()) {
        val (fc, fp, pc) = rig(backgroundScope)
        fp.scanResults = listOf(sp60, r10)
        pc.openSheet(); runCurrent()
        assertEquals(listOf("AA:11", "BB:22"), pc.devices.map { it.address }) // 신호 센 R10 먼저
        pc.tap(pc.devices.first { it.address == "AA:11" })
        assertTrue("R10 은 등록(createBond) 없이", fp.bondCalls.isEmpty())
        assertEquals(DeviceProfiles.R10_ID to "AA:11", fc.started)
        assertEquals(PairStep.CONNECTING_UNBONDED, pc.step)
        assertEquals("AA:11", fc.saved?.address); assertEquals(DeviceProfiles.R10_ID, fc.saved?.deviceId); assertTrue(fc.saved!!.preChecked)
        fc.link.value = LinkStatus(LinkState.STREAMING); advanceTimeBy(600); runCurrent()
        assertEquals(PairStep.DONE, pc.step)
        assertEquals(listOf(r10.name to "AA:11"), fc.direct)
    }

    @Test fun sp60New_pinAuto_bonded_connects() = runTest(StandardTestDispatcher()) {
        val (fc, fp, pc) = rig(backgroundScope)
        fp.scanResults = listOf(sp60); pc.openSheet(); runCurrent()
        pc.tap(sp60)
        assertEquals(listOf("BB:22"), fp.bondCalls)
        assertEquals("1234", fp.request("BB:22", PairConst.VARIANT_PIN))
        fp.bonded("BB:22"); runCurrent()
        assertEquals(DeviceProfiles.SP60_ID to "BB:22", fc.started); assertEquals(PairStep.CONNECTING, pc.step)
    }

    @Test fun alreadyBondedSp60_noBond_connectsAsSp60() = runTest(StandardTestDispatcher()) {
        val (fc, fp, pc) = rig(backgroundScope)
        fp.bondedList = listOf(sp60.copy(bonded = true)); fp.bondedSet += "BB:22"; fc.devices = listOf(sp60.name to "BB:22")
        pc.openSheet(); runCurrent()
        pc.tap(pc.devices.first())
        assertTrue(fp.bondCalls.isEmpty())
        assertEquals(DeviceProfiles.SP60_ID to "BB:22", fc.started)
    }

    @Test fun r10DirectFails_thenBond1234_then0000_thenFixCard() = runTest(StandardTestDispatcher()) {
        val (fc, fp, pc) = rig(backgroundScope)
        fp.scanResults = listOf(r10); pc.openSheet(); runCurrent()
        pc.tap(r10)
        assertEquals(DeviceProfiles.R10_ID to "AA:11", fc.started)
        fc.link.value = LinkStatus(LinkState.RECONNECTING, failures = 1); advanceTimeBy(1_100); runCurrent()
        assertTrue(fc.stopped >= 1) // 등록 없이 연결 멈춤
        assertEquals(PairStep.BONDING, pc.step)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(1, fp.bondCalls.size)
        assertEquals("1234", fp.request("AA:11", PairConst.VARIANT_PIN)); fp.failed("AA:11")
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, fp.bondCalls.size)
        assertEquals("0000", fp.request("AA:11", PairConst.VARIANT_PIN)); fp.failed("AA:11"); runCurrent()
        assertEquals(PairStep.FAILED, pc.step); assertEquals(PairFix.POWER_CYCLE, pc.fix)
        assertTrue(fc.direct.isEmpty())
    }

    @Test fun permissionExplainedFirst_thenScan() = runTest(StandardTestDispatcher()) {
        val (_, fp, pc) = rig(backgroundScope)
        fp.perm = false; fp.scanResults = listOf(r10)
        pc.openSheet(); assertTrue(pc.explainPermission); assertTrue(pc.devices.none { it.address == "AA:11" })
        pc.allowPermission(); assertFalse(pc.explainPermission)
        assertEquals("AA:11", pc.devices.first().address)
        fp.perm = false; fp.grant = false; pc.openSheet(); pc.allowPermission(); assertEquals(PairFix.PERMISSION, pc.fix)
    }

    @Test fun pinEditedOnlyInAdvanced_digitsOnly_used() = runTest(StandardTestDispatcher()) {
        val (_, fp, pc) = rig(backgroundScope)
        pc.changePin("98a76"); assertEquals("9876", pc.pin)
        pc.tap(sp60); assertEquals("9876", fp.request("BB:22", PairConst.VARIANT_PIN))
    }

    @Test fun sourceUsesPublicApis_andAbortsOnlyOrdered() {
        val src = java.io.File("src/main/kotlin/kr/archsurvey/bt/BtPairing.kt").readText()
        assertTrue(src.contains("setPin(") && src.contains("isOrderedBroadcast") && src.contains("createBond()") && src.contains("startDiscovery()"))
        assertFalse("숨은 API(리플렉션) 쓰지 않음", src.contains("getMethod(") || src.contains(".setPairingConfirmation("))
    }
}
