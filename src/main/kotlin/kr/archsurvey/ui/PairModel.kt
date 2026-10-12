package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.archsurvey.bt.*
import kr.archsurvey.device.LinkState

/** 0.1.40 앱 안 등록(페어링)용 안드로이드 기능. 시험에서는 가짜. 사진 기준점 앱과 같은 파일 */
interface PairPlatform {
    fun hasPermission(): Boolean
    /** 권한 창(쉬운 한국어 설명은 화면이 먼저 보여 줌) */
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun bluetoothEnabled(): Boolean
    /** 안드로이드 11 이하: 찾기에 위치 서비스가 켜져 있어야 함. 12+ 는 늘 true */
    fun locationOkForScan(): Boolean = true
    fun bonded(): List<FoundDevice>
    /** 찾기 시작(약 12초). 찾을 때마다 onFound, 끝나면 onDone. 실패하면 false */
    fun startScan(onFound: (FoundDevice) -> Unit, onDone: () -> Unit): Boolean
    fun stopScan()
    /** createBond. 등록 요청(PIN)·결과는 [events] 로 */
    fun createBond(address: String): Boolean
    fun isBonded(address: String): Boolean
    var events: PairEvents?
}

interface PairEvents {
    /** PIN 방식이면 넣을 PIN, 아니면 null(시스템 창) */
    fun onPairingRequest(address: String, variant: Int): String?
    fun onBondState(address: String, state: Int, prev: Int, reason: Int?)
}

/**
 * 「주변 수신기 찾기」 → 눌러서 등록(PIN 자동) → 기존 연결 흐름으로 바로 연결 → 마지막 수신기로 저장(자동 연결).
 * 연결 순서(R10 insecure 먼저 등)·NMEA·NTRIP 는 그대로 [ConnectionController.connectPicked] 에 맡긴다.
 */
class PairController(
    private val platform: PairPlatform,
    val conn: ConnectionController,
    private val scope: CoroutineScope,
    /** 설정의 수신기 PIN(기본 1234, 고급에서만 바꿈) */
    private val pinSetting: () -> String = { PairConst.DEFAULT_PIN },
    private val pinSave: (String) -> Unit = {},
    private val bondTimeoutMs: Long = 45_000L,
    private val retryGapMs: Long = 1_200L,
) : PairEvents {
    var open by mutableStateOf(false)
    var scanning by mutableStateOf(false); private set
    var devices by mutableStateOf<List<FoundDevice>>(emptyList()); private set
    var machine by mutableStateOf<PairMachine?>(null); private set
    /** Compose 다시 그리기용(기계 안 상태가 바뀔 때 올림) */
    var tick by mutableStateOf(0); private set
    var fix by mutableStateOf(PairFix.NONE); private set
    /** 권한 설명을 먼저 보여 주는 중 */
    var explainPermission by mutableStateOf(false); private set
    private val found = LinkedHashMap<String, FoundDevice>()
    private var timeoutJob: Job? = null
    private var watchJob: Job? = null

    val step: PairStep get() = machine?.step ?: PairStep.IDLE
    /** 고급 · 수신기 PIN(기본 1234). 숫자 4~16자리만 */
    var pin by mutableStateOf(pinSetting()); private set
    var advancedOpen by mutableStateOf(false)
    fun changePin(p: String) { val v = p.filter(Char::isDigit).take(16); pin = v; if (v.length >= 4) pinSave(v) }

    fun openSheet() { open = true; fix = PairFix.NONE; loadBonded(); scan() }
    fun close() { open = false; platform.stopScan(); scanning = false; if (step != PairStep.DONE && step != PairStep.CONNECTING) machine = null }

    private fun loadBonded() {
        found.clear()
        runCatching { platform.bonded() }.getOrDefault(emptyList()).forEach { found[it.address.uppercase()] = it }
        devices = PairList.sort(found.values)
    }

    /** 찾기: 권한 → 블루투스 → (11 이하) 위치 → 검색 */
    fun scan() {
        fix = PairFix.NONE
        if (!platform.hasPermission()) { explainPermission = true; return }
        if (!platform.bluetoothEnabled()) { fix = PairFix.BT_OFF; return }
        loadBonded()
        if (!platform.locationOkForScan()) { fix = PairFix.LOCATION_OFF; return }
        scanning = true
        val ok = platform.startScan(
            onFound = { d -> val k = d.address.uppercase(); found[k] = (found[k]?.let { o -> if (o.bonded) o.copy(rssi = d.rssi ?: o.rssi, name = if (d.name != d.address) d.name else o.name) else d } ?: d); devices = PairList.sort(found.values) },
            onDone = { scanning = false },
        )
        if (!ok) scanning = false
    }

    /** 권한 설명 창의 「허용」 */
    fun allowPermission() {
        explainPermission = false
        platform.requestPermission { ok -> if (ok) scan() else fix = PairFix.PERMISSION }
    }
    fun dismissPermission() { explainPermission = false; fix = PairFix.PERMISSION }

    /** 목록에서 하나 누름 = 등록(필요하면) + 연결 */
    fun tap(d: FoundDevice) {
        if (step == PairStep.BONDING || step == PairStep.WAIT_USER) return
        if (!platform.hasPermission()) { explainPermission = true; return }
        if (!platform.bluetoothEnabled()) { fix = PairFix.BT_OFF; return }
        platform.stopScan(); scanning = false
        platform.events = this
        val m = machine?.takeIf { it.device.address.equals(d.address, true) } ?: PairMachine(d, PairList.pins(pin))
        machine = m; fix = PairFix.NONE
        run(m.retry(runCatching { platform.isBonded(d.address) }.getOrDefault(d.bonded)))
    }

    fun retry() { machine?.let { tap(it.device) } }

    private fun bump() { tick++; fix = machine?.fix ?: PairFix.NONE }

    private fun run(a: PairAction) {
        val m = machine ?: return
        when (a) {
            is PairAction.CreateBond -> {
                armTimeout()
                // 첫 시도는 바로, 실패 뒤 다른 PIN 시도는 잠깐 쉬고(스택이 앞 시도를 정리할 시간)
                if (m.pinIndex == 0 && !m.directTried) run(m.onCreateBondResult(platform.createBond(a.address)))
                else scope.launch { delay(retryGapMs); if (machine === m && m.step == PairStep.BONDING) run(m.onCreateBondResult(platform.createBond(a.address))) }
            }
            is PairAction.Connect -> {
                timeoutJob?.cancel()
                conn.connectPicked(a.address, m.device.name, bonded = a.bonded)
                watchLink(m)
            }
            PairAction.None -> {}
        }
        bump()
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        val m = machine
        timeoutJob = scope.launch { delay(bondTimeoutMs); if (machine === m && m != null) run(m.onTimeout()) }
    }

    /** 연결 결과: 위치가 오면 성공, 수신기 쪽 오류(NEEDS_USER)·오래 끊김이면 실패 */
    private fun watchLink(m: PairMachine) {
        watchJob?.cancel()
        watchJob = scope.launch {
            var waited = 0L
            var sawSocket = false // 소켓은 붙었음(CONNECTED_NO_DATA 를 본 적 있음)
            while (machine === m && (m.step == PairStep.CONNECTING || m.step == PairStep.CONNECTING_UNBONDED)) {
                val st = conn.link.value.state
                if (st == LinkState.CONNECTED_NO_DATA || st == LinkState.STALE) sawSocket = true
                when {
                    // 0.1.41: 등록 없이 연결은 위치(GGA)가 와야 성공 — 소켓만 붙고 GGA 없음(R10 현장 12:43)이면 등록으로 넘어감
                    st == LinkState.STREAMING || (st == LinkState.CONNECTED_NO_DATA && m.step == PairStep.CONNECTING) -> {
                        if (m.step == PairStep.CONNECTING_UNBONDED) conn.rememberDirect(m.device.name, m.device.address) // 등록 없이도 자동 연결 목록에
                        m.onConnectResult(true); bump(); devices = PairList.sort(found.values.map { if (it.address.equals(m.device.address, true)) it.copy(bonded = platform.isBonded(it.address)) else it }) }
                    st == LinkState.NEEDS_USER || (waited > 40_000L && st != LinkState.CONNECTING) || waited > 90_000L ||
                        (m.step == PairStep.CONNECTING_UNBONDED && waited >= 1_000L && st == LinkState.RECONNECTING && conn.link.value.failures >= 1) ||
                        (m.step == PairStep.CONNECTING_UNBONDED && conn.link.value.error == kr.archsurvey.bt.BtError.NO_DATA) -> {
                        val noData = conn.link.value.error == kr.archsurvey.bt.BtError.NO_DATA || sawSocket
                        if (m.step == PairStep.CONNECTING_UNBONDED) conn.cancelConnectQuiet()
                        run(m.onConnectResult(false, noData))
                    }
                }
                delay(500); waited += 500
            }
        }
    }

    // ---- PairEvents(플랫폼이 부름) ----
    override fun onPairingRequest(address: String, variant: Int): String? {
        val m = machine ?: return null
        if (!m.device.address.equals(address, true)) return null
        return m.onPairingRequest(variant).also { bump() }
    }
    override fun onBondState(address: String, state: Int, prev: Int, reason: Int?) {
        val m = machine ?: return
        if (!m.device.address.equals(address, true)) return
        run(m.onBondState(state, prev, reason))
    }
}
