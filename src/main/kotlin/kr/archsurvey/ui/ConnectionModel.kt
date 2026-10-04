package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import kr.archsurvey.bt.BtError
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState

/** VRS(NTRIP) 입력 폼. 문자열로 들고 있다가 검증 후 [NtripConfig] 로 바꾼다. 비밀번호는 로그/화면에 노출하지 않는다. */
data class NtripForm(
    val host: String = NtripPresets.VRS.host,
    val port: String = NtripPresets.VRS.port.toString(),
    val mount: String = NtripPresets.VRS.mountPoint,
    val user: String = "",
    val password: String = "",
    val useV2: Boolean = false,
    val enabled: Boolean = true,
) {
    fun problems(): List<String> = buildList {
        if (!enabled) return@buildList
        if (host.isBlank()) add("서버 주소를 입력하세요")
        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1..65535) add("포트는 1~65535 숫자예요")
        if (mount.isBlank()) add("마운트 포인트를 입력하세요")
        if (user.isBlank()) add("VRS 아이디를 입력하세요")
        if (password.isEmpty()) add("VRS 비밀번호를 입력하세요")
    }

    fun toConfig() = NtripConfig(host.trim(), port.trim().toIntOrNull() ?: 2101, mount.trim(), user.trim(), password, useV2)

    companion object {
        fun from(c: NtripConfig, enabled: Boolean) = NtripForm(c.host, c.port.toString(), c.mountPoint, c.username, c.password, c.useV2, enabled)
    }
}

/** 오류 카드: 한 문장 제목 + 할 일 + 작은 오류 번호. 번호(BT-120 등)는 문의용. */
data class ErrorCard(val code: String, val title: String, val action: String, val autoRetry: Boolean)

object ErrorCards {
    fun from(link: LinkStatus, ntrip: NtripStatus?, ntripEnabled: Boolean): List<ErrorCard> = buildList {
        link.error?.let { e ->
            if (link.state == LinkState.NEEDS_USER || link.state == LinkState.RECONNECTING || link.state == LinkState.CONNECTING || link.state == LinkState.CONNECTED_NO_DATA || link.state == LinkState.STALE)
                add(ErrorCard(e.code, e.title, e.action, !e.needsUserAction))
        }
        if (link.repairHint && link.error == null) add(ErrorCard("BT-124", BtError.ALL_FAILED.title, BtError.ALL_FAILED.action, false))
        if (ntripEnabled && ntrip != null) when (ntrip.state) {
            NtripState.AUTH_FAILED -> add(ErrorCard("NT-401", "VRS 아이디/비밀번호가 맞지 않아요", "아이디와 비밀번호를 다시 확인하세요. 같은 계정으로 다른 곳에서 동시에 접속 중이면 끊고 다시 시도하세요. 자동 재시도는 하지 않아요.", false))
            NtripState.ERROR -> add(ErrorCard("NT-500", "VRS 서버에 연결하지 못했어요", ntrip.message.ifBlank { "서버 주소·포트·마운트 포인트와 폰의 인터넷 연결을 확인하세요." }, true))
            NtripState.RECONNECTING -> add(ErrorCard("NT-300", "VRS 다시 연결 중이에요", "폰 인터넷이 끊겼다 돌아오면 자동으로 이어져요.", true))
            else -> {}
        }
    }
}

/** 화면 바깥(Android) 기능을 연결 화면에 빌려주는 얇은 인터페이스. 시험에서는 가짜로 바꾼다. */
interface ConnectionPlatform {
    val link: StateFlow<LinkStatus>
    val ntrip: StateFlow<NtripStatus>
    fun bondedDevices(): List<Pair<String, String>>    // (이름, 주소)
    fun hasBluetoothPermission(): Boolean
    fun requestBluetoothPermission(onResult: (Boolean) -> Unit)
    fun bluetoothEnabled(): Boolean
    fun openBluetoothSettings()
    fun openAppSettings()
    fun startReal(profile: DeviceProfile, address: String, ntrip: NtripConfig, ntripEnabled: Boolean)
    fun stopReal()
    fun loadSaved(): SavedConnection
    fun save(s: SavedConnection)
}

data class SavedConnection(val fakeSignal: Boolean, val deviceId: String?, val address: String?, val ntrip: NtripForm)

/**
 * 연결 화면 상태·동작. Android 의존 없음.
 * 흐름: 모드(가짜/실제) → 수신기 모델 → 폰에 등록된 기기 선택 → 사전 확인 → VRS 입력 → 연결.
 */
class ConnectionController(
    private val platform: ConnectionPlatform,
    private val onModeChange: (fake: Boolean) -> Unit,
) {
    var open by mutableStateOf(false)
    var fakeSignal by mutableStateOf(true); private set
    var profile by mutableStateOf<DeviceProfile>(DeviceProfiles.R10); private set
    var address by mutableStateOf<String?>(null); private set
    var form by mutableStateOf(NtripForm()); private set
    val checks = mutableStateListOf<Boolean>()
    var bonded by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    var notice by mutableStateOf<ErrorCard?>(null); private set
    var showNmeaSteps by mutableStateOf(false)
    val link: StateFlow<LinkStatus> get() = platform.link
    val ntrip: StateFlow<NtripStatus> get() = platform.ntrip
    var connectRequested by mutableStateOf(false); private set

    init {
        val s = platform.loadSaved()
        fakeSignal = s.fakeSignal
        DeviceProfiles.byId(s.deviceId)?.let { profile = it }
        address = s.address
        form = s.ntrip
        resetChecks()
    }

    private fun resetChecks() { checks.clear(); repeat(profile.preConnectChecks.size) { checks.add(false) } }

    fun refresh() { bonded = profile.sortByRelevance(platform.bondedDevices()) { it.first } }

    fun openScreen() { refresh(); open = true }
    fun close() { open = false }

    fun selectProfile(p: DeviceProfile) {
        if (p.id == profile.id) return
        profile = p; resetChecks(); address = null; notice = null; refresh()
        persist()
    }

    fun selectAddress(a: String) { address = a; notice = null; persist() }

    fun updateForm(f: NtripForm) { form = f }
    fun setCheck(i: Int, v: Boolean) { if (i in checks.indices) checks[i] = v }

    fun changeFakeSignal(v: Boolean) {
        if (v == fakeSignal) return
        fakeSignal = v
        if (v) { platform.stopReal(); connectRequested = false }
        notice = null
        persist()
        onModeChange(v)
    }

    private fun persist() = platform.save(SavedConnection(fakeSignal, profile.id, address, form))

    /** 사전 점검 순서: 권한 → 블루투스 켜짐 → 기기 선택 → 사전 확인 → VRS 입력. 막히면 notice 에 오류 카드 */
    fun connect() {
        notice = null
        if (fakeSignal) { notice = ErrorCard("-", "지금은 ‘가짜 신호’예요", "위의 스위치를 끄면 실제 수신기로 연결할 수 있어요.", false); return }
        if (!platform.hasBluetoothPermission()) {
            platform.requestBluetoothPermission { granted ->
                if (granted) connect() else notice = card(BtError.PERMISSION_NEARBY_PERMANENT)
            }
            return
        }
        if (!platform.bluetoothEnabled()) { notice = card(BtError.BT_OFF); return }
        val addr = address
        if (addr == null) { notice = card(BtError.NO_DEVICE_SELECTED); return }
        if (platform.bondedDevices().none { it.second == addr }) { notice = card(BtError.NOT_PAIRED); return }
        if (checks.any { !it }) { notice = ErrorCard("-", "연결 전 확인을 모두 체크해 주세요", "수신기의 NMEA 출력을 켜지 않으면 연결돼도 위치가 오지 않아요.", false); return }
        val probs = form.problems()
        if (probs.isNotEmpty()) { notice = ErrorCard("-", "VRS 입력을 확인해 주세요", probs.joinToString(" · "), false); return }
        persist()
        platform.startReal(profile, addr, form.toConfig(), form.enabled)
        connectRequested = true
        onModeChange(false)
    }

    fun openBluetoothSettings() = platform.openBluetoothSettings()
    fun openAppSettings() = platform.openAppSettings()

    fun disconnect() { platform.stopReal(); connectRequested = false }

    /** 현재 연결 상태에서 보여 줄 오류 카드 */
    fun cards(): List<ErrorCard> = ErrorCards.from(link.value, ntrip.value, form.enabled && !fakeSignal)

    private fun card(e: BtError) = ErrorCard(e.code, e.title, e.action, !e.needsUserAction)
}
