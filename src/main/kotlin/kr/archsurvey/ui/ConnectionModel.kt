package kr.archsurvey.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kr.archsurvey.ntrip.MountInfo
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
        if (ntripEnabled && ntrip != null) ntripCard(ntrip)?.let { add(it) }
    }

    /** VRS(NTRIP) 오류를 원인별 문구로. 서버가 준 문구([NtripStatus.message])와 예외 종류로 가른다 */
    fun ntripCard(ntrip: NtripStatus): ErrorCard? {
        val m = ntrip.message
        return when (ntrip.state) {
            NtripState.AUTH_FAILED ->
                if ("403" in m) ErrorCard("NT-403", "VRS 접속이 거부됐어요 (403)", "같은 아이디가 다른 폰·컨트롤러에서 접속 중일 수 있어요. 그쪽을 끊고 다시 시도하세요. 계약(사용 기간·권한)도 확인하세요. 자동 재시도는 하지 않아요.", false)
                else ErrorCard("NT-401", "VRS 아이디/비밀번호가 맞지 않아요 (401)", "통합회원 ID와 비밀번호를 다시 입력하세요(대소문자 구분). 2025년 8월부터 국토지리정보원 VRS는 ‘측위보정정보 통합회원 ID’(지각변동감시시스템에서 등록)가 필요하다고 해요 — 공식 공지 원문은 확인하지 못했어요. 자동 재시도는 하지 않아요.", false)
            NtripState.ERROR ->
                if ("마운트" in m || "404" in m) ErrorCard("NT-404", "마운트 포인트를 찾을 수 없어요", "마운트 포인트 이름이 맞는지 확인하세요(서비스 안내문의 이름, 예: VRS-RTCM31). 서버는 응답했으니 주소·포트·계정은 맞아요.", false)
                else ErrorCard("NT-500", "VRS 서버에 연결하지 못했어요", m.ifBlank { "서버 주소·포트·마운트 포인트와 폰의 인터넷 연결을 확인하세요." }, true)
            NtripState.RECONNECTING -> when {
                "UnknownHost" in m -> ErrorCard("NT-301", "VRS 서버 주소를 찾지 못했어요", "서버 주소 철자를 확인하세요. 주소가 맞다면 폰의 인터넷(데이터/와이파이)이 꺼져 있어요. 자동으로 다시 시도해요.", true)
                "ConnectException" in m || "refused" in m.lowercase() -> ErrorCard("NT-302", "VRS 서버가 접속을 받지 않아요", "포트 번호를 확인하세요(보통 2101). 서버 점검 중일 수도 있어요. 자동으로 다시 시도해요.", true)
                "Timeout" in m || "timed out" in m.lowercase() -> ErrorCard("NT-303", "VRS 서버 응답이 없어요 (네트워크)", "폰의 인터넷 신호가 약해요. 신호가 좋은 곳으로 옮기세요. 자동으로 다시 시도해요.", true)
                "동안 오지 않아요" in m -> ErrorCard("NT-310", "보정 데이터가 오지 않아요", "접속은 됐지만 데이터가 없어요. ① 수신기 NMEA에 GGA가 켜져 있는지(VRS는 내 위치가 필요해요) ② 수신기가 위치를 잡았는지 ③ 마운트 포인트가 맞는지 확인하세요. 자동으로 다시 시도해요.", true)
                "서버가 연결을 닫았어요" in m || "응답 없이" in m -> ErrorCard("NT-311", "VRS 서버가 연결을 끊었어요", "계정이 동시 접속으로 끊겼거나 서버가 내 위치(GGA)를 받지 못했을 수 있어요. 자동으로 다시 시도해요.", true)
                else -> ErrorCard("NT-300", "VRS 다시 연결 중이에요", "폰 인터넷이 끊겼다 돌아오면 자동으로 이어져요.", true)
            }
            else -> null
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

    // ---- 0.1.9: 기본 구현이 있는 선택 기능(시험용 가짜는 필요한 것만 덮어쓴다) ----
    /** 캐스터 소스테이블(마운트 목록) 받기. 실패하면 예외 */
    suspend fun fetchMounts(cfg: NtripConfig): List<MountInfo> = throw UnsupportedOperationException("이 환경에서는 마운트 목록을 받을 수 없어요")
    /** 서버(주소)별 마지막 성공 아이디·비밀번호·포트·마운트 */
    fun hostProfile(host: String): NtripForm? = null
    /** 연결 성공 시 호출: 서버별 프로필·성공 시각 저장 */
    fun markSuccess(form: NtripForm) {}
    /** 한 번이라도 연결에 성공했는지 */
    fun hadSuccess(): Boolean = false
    var autoConnect: Boolean
        get() = false
        set(_) {}
    fun nowMs(): Long = System.currentTimeMillis()
}

/** 보정 서비스 선택 창의 항목. [mount] 는 처음 고를 때 넣는 기본 마운트 */
data class CorrectionService(val id: String, val label: String, val host: String, val port: Int, val mount: String, val note: String, val short: String = label)

/**
 * 소스테이블을 못 받을 때 보여 줄 “알려진” 국토지리정보원 마운트 목록.
 * 출처: research.md §4.2(국토지리정보원 공지 2022-05 정리). 문서마다 이름 표기(VRS-RTCM31 / VRS_V31 등)가 달라 현행 이름은 미확인.
 */
object KnownMounts {
    const val SOURCE = "출처: 국토지리정보원 공지(2022-05) 정리 · 현행 이름 미확인 — 문서마다 표기가 달라요(예: VRS-RTCM31 / VRS_V31)"
    val services = listOf(
        CorrectionService("rts1", "국토지리정보원 VRS (RTS1)", "RTS1.ngii.go.kr", 2101, "VRS-RTCM31", "가상기준점 방식 · 내 위치(GGA) 필요", "RTS1 · VRS"),
        CorrectionService("rts2", "국토지리정보원 VRS·FKP (RTS2)", "RTS2.ngii.go.kr", 2101, "VRS-RTCM32", "VRS·FKP·SSR", "RTS2 · VRS"),
        CorrectionService("fkp", "국토지리정보원 FKP (RTS2)", "RTS2.ngii.go.kr", 2101, "FKP-RTCM31", "면보정 방식", "RTS2 · FKP"),
        CorrectionService("custom", "직접 입력", "", 2101, "", "다른 캐스터 서버"),
    )
    private fun m(mp: String, fmt: String, nmea: Boolean, note: String) = MountInfo(mp, note, fmt, "GPS+GLO+GAL+BDS", nmea)
    fun forHost(host: String): List<MountInfo> = when (host.trim().lowercase()) {
        "rts1.ngii.go.kr" -> listOf(
            m("VRS-RTCM31", "RTCM 3.1", true, "미확인"),
            m("VRS-CMRx", "CMRx", true, "미확인"),
            m("VRS-RTCM23", "RTCM 2.3", true, "미확인"),
            m("RTK-RTCM32", "RTCM 3.2", false, "Single RTK · 성과심사 불가 · 미확인"),
        )
        "rts2.ngii.go.kr" -> listOf(
            m("VRS-RTCM31", "RTCM 3.1", true, "미확인"),
            m("VRS-RTCM32", "RTCM 3.2", true, "미확인"),
            m("FKP-RTCM31", "RTCM 3.1", true, "FKP 면보정 · 미확인"),
            m("SSR-SSRG", "SSR", false, "SSR 보정 · 미확인"),
        )
        else -> emptyList()
    }
}

/** 마운트 선택 창 상태 */
enum class MountLoad { IDLE, LOADING, OK, FAILED }

/** 연결 진행 단계 계산(블루투스 → 수신기 데이터 → VRS 접속 → 보정 수신 → 고정). 화면 없이 시험 가능 */
object ConnectSteps {
    val labels = listOf("블루투스 연결", "수신기 위치 데이터", "VRS 서버 접속", "보정 데이터 수신", "RTK 고정(FIX)")
    fun compute(link: LinkStatus, ntrip: NtripStatus, ntripEnabled: Boolean, fixQuality: Int): List<StepState> {
        val bt = when (link.state) {
            LinkState.IDLE -> StepState.WAIT
            LinkState.CONNECTING, LinkState.RECONNECTING -> StepState.ACTIVE
            LinkState.NEEDS_USER -> StepState.FAIL
            else -> StepState.DONE
        }
        val data = when (link.state) {
            LinkState.STREAMING -> StepState.DONE
            LinkState.CONNECTED_NO_DATA, LinkState.STALE -> StepState.ACTIVE
            LinkState.NEEDS_USER -> StepState.FAIL
            else -> StepState.WAIT
        }
        val (srv, rx) = if (!ntripEnabled) StepState.SKIP to StepState.SKIP else when (ntrip.state) {
            NtripState.IDLE -> StepState.WAIT to StepState.WAIT
            NtripState.CONNECTING -> StepState.ACTIVE to StepState.WAIT
            NtripState.CONNECTED -> StepState.DONE to StepState.ACTIVE
            NtripState.RECEIVING -> StepState.DONE to StepState.DONE
            NtripState.RECONNECTING -> StepState.ACTIVE to StepState.WAIT
            NtripState.AUTH_FAILED, NtripState.ERROR -> StepState.FAIL to StepState.WAIT
        }
        val fix = when {
            fixQuality == 4 -> StepState.DONE
            data == StepState.DONE -> StepState.ACTIVE
            else -> StepState.WAIT
        }
        return listOf(bt, data, srv, rx, fix)
    }
}

/** [preChecked]: 이 수신기에서 “연결 전 확인”을 한 번 마쳤는지(수신기 설정은 수신기에 남으므로 다음부터 묻지 않음) */
data class SavedConnection(val deviceId: String?, val address: String?, val ntrip: NtripForm, val preChecked: Boolean = false)

/**
 * 연결 화면 상태·동작. Android 의존 없음.
 * 흐름: 수신기 모델 → 폰에 등록된 기기 선택 → 사전 확인 → VRS 입력 → 연결.
 */
class ConnectionController(
    private val platform: ConnectionPlatform,
    /** 소스테이블 받기 등 비동기 작업용. null 이면 그때 만든다(JVM 시험은 Unconfined 를 넘긴다) */
    private val scope: CoroutineScope? = null,
) {
    /** 저장 알림·확인·선택 창 (VM 이 SurveyController.ui 를 넣어 준다) */
    var ui: FeedbackHub = FeedbackHub()
    /** 연결 진행 창 */
    var showProgress by mutableStateOf(false)
    var progressStartMs by mutableStateOf(0L); private set
    /** 이번 연결 시도에서 성공 저장을 했는지(한 번만 알림) */
    var savedThisRun by mutableStateOf(false); private set
    // 마운트 선택 창
    var mountPickerOpen by mutableStateOf(false)
    var mountLoad by mutableStateOf(MountLoad.IDLE); private set
    var mounts by mutableStateOf<List<MountInfo>>(emptyList()); private set
    var mountError by mutableStateOf(""); private set
    var mountQuery by mutableStateOf("")
    var mountManual by mutableStateOf("")
    var mountFromKnown by mutableStateOf(false); private set
    var mountStartMs by mutableStateOf(0L); private set
    private var mountJob: Job? = null
    var autoConnect by mutableStateOf(platform.autoConnect); private set
    var open by mutableStateOf(false)
    var profile by mutableStateOf<DeviceProfile>(DeviceProfiles.R10); private set
    var address by mutableStateOf<String?>(null); private set
    var form by mutableStateOf(NtripForm()); private set
    val checks = mutableStateListOf<Boolean>()
    var bonded by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set
    var notice by mutableStateOf<ErrorCard?>(null); private set
    var showNmeaSteps by mutableStateOf(false)
    /** 접힌 영역(서버 설정·도움말·연결 전 확인)을 펼쳤는지 */
    var showServer by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showPre by mutableStateOf(false)
    val link: StateFlow<LinkStatus> get() = platform.link
    val ntrip: StateFlow<NtripStatus> get() = platform.ntrip
    var connectRequested by mutableStateOf(false); private set

    init {
        val s = platform.loadSaved()
        DeviceProfiles.byId(s.deviceId)?.let { profile = it }
        address = s.address
        form = s.ntrip
        resetChecks()
        if (s.preChecked) for (i in checks.indices) checks[i] = true
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

    // ---------------- 보정 서비스·마운트 (SmartTopo 흐름: 서비스 → 목록 받기 → 검색·선택) ----------------
    fun currentService(): CorrectionService {
        val h = form.host.trim().lowercase(); val sv = KnownMounts.services
        return when {
            h == "rts2.ngii.go.kr" && form.mount.trim().startsWith("FKP", true) -> sv[2]
            h == "rts1.ngii.go.kr" -> sv[0]
            h == "rts2.ngii.go.kr" -> sv[1]
            else -> sv[3]
        }
    }

    fun askService() = ui.choose("보정 서비스", KnownMounts.services.map { it.label + " · " + it.note }, KnownMounts.services.indexOf(currentService()), "service", KnownMounts.SOURCE) { i ->
        val sv = KnownMounts.services[i]
        if (sv.id == "custom") { showServer = true; return@choose }
        val prof = platform.hostProfile(sv.host)
        form = if (prof != null) form.copy(host = sv.host, port = prof.port, mount = if (sv.id == "fkp") sv.mount else prof.mount, user = prof.user.ifBlank { form.user }, password = prof.password.ifEmpty { form.password })
        else form.copy(host = sv.host, port = sv.port.toString(), mount = sv.mount)
        persistSafe()
        ui.toast("보정 서비스: ${sv.label} · 마운트 ${form.mount}" + if (prof != null) " · 이 서버의 마지막 성공 설정을 불러왔어요" else "")
    }

    /** 서버 주소를 직접 바꾼 뒤(입력 끝) 그 서버의 마지막 성공 설정을 채운다 */
    fun recallHost() {
        val prof = platform.hostProfile(form.host) ?: return
        form = form.copy(port = prof.port, mount = prof.mount, user = prof.user.ifBlank { form.user }, password = prof.password.ifEmpty { form.password })
        ui.toast("${form.host}의 마지막 성공 설정(아이디·포트·마운트)을 불러왔어요")
    }

    fun openMountPicker() {
        mountPickerOpen = true; mountQuery = ""; mountManual = form.mount
        loadMounts()
    }

    fun loadMounts() {
        mountJob?.cancel()
        val cfg = form.toConfig()
        if (cfg.host.isBlank()) { mountFail("서버 주소가 비어 있어요"); return }
        mountLoad = MountLoad.LOADING; mountError = ""; mountStartMs = platform.nowMs()
        val sc = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        mountJob = sc.launch {
            try {
                val list = platform.fetchMounts(cfg)
                if (list.isEmpty()) mountFail("서버가 빈 목록을 보냈어요")
                else { mounts = list.sortedWith(compareBy<MountInfo> { if (it.isRtcm3) 0 else 1 }.thenBy { it.mountPoint }); mountFromKnown = false; mountLoad = MountLoad.OK }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                mountFail(e.message?.take(120) ?: e.javaClass.simpleName)
            }
        }
    }

    private fun mountFail(why: String) {
        mountError = why
        mounts = KnownMounts.forHost(form.host); mountFromKnown = true
        mountLoad = MountLoad.FAILED
    }

    fun cancelMountLoad() { mountJob?.cancel(); mountJob = null; if (mountLoad == MountLoad.LOADING) mountFail("받기를 취소했어요") }

    fun filteredMounts(): List<MountInfo> {
        val q = mountQuery.trim()
        return if (q.isEmpty()) mounts else mounts.filter { it.mountPoint.contains(q, true) || it.format.contains(q, true) || it.identifier.contains(q, true) || it.navSystem.contains(q, true) }
    }

    fun pickMount(name: String) {
        val m = name.trim()
        if (m.isEmpty()) { ui.toast("마운트 이름을 입력하세요"); return }
        form = form.copy(mount = m)
        mountPickerOpen = false; mountJob?.cancel()
        persistSafe()
        ui.toast("마운트 포인트 저장됨 · $m")
    }

    fun closeMountPicker() { mountPickerOpen = false; mountJob?.cancel(); if (mountLoad == MountLoad.LOADING) mountLoad = MountLoad.IDLE }

    // ---------------- 연결 성공 저장·자동 연결 ----------------
    /** 화면이 링크·보정 상태를 볼 때마다 부른다. 위치 데이터가 들어오면(보정 켜짐이면 보정 수신까지) 한 번 저장 */
    fun onStatus(l: LinkStatus, n: NtripStatus) {
        if (!connectRequested || savedThisRun) return
        val ok = l.state == LinkState.STREAMING && (!form.enabled || n.state == NtripState.RECEIVING)
        if (!ok) return
        savedThisRun = true
        persistSafe()
        runCatching { platform.markSuccess(form) }
        val dev = bonded.firstOrNull { it.second == address }?.first ?: address.orEmpty()
        ui.toast("연결 설정 저장됨 · $dev" + if (form.enabled) " · ${form.mount} · ${form.user}" else "")
    }

    fun changeAutoConnect(v: Boolean) { autoConnect = v; platform.autoConnect = v; ui.toast(if (v) "다음 실행 때 자동 연결: 켬" else "다음 실행 때 자동 연결: 끔") }

    /** 앱 시작 시: 성공 이력 + 자동 연결 켬 + 권한·블루투스 OK 일 때만 조용히 연결. 막히면 아무것도 하지 않는다 */
    fun autoConnectOnLaunch(): Boolean {
        if (!autoConnect || !platform.hadSuccess() || connectRequested) return false
        if (!platform.hasBluetoothPermission() || !platform.bluetoothEnabled()) return false
        val addr = address ?: return false
        if (runCatching { platform.bondedDevices() }.getOrDefault(emptyList()).none { it.second == addr }) return false
        if (form.problems().isNotEmpty()) return false
        startNow(addr)
        ui.toast("지난번 설정으로 자동 연결 중이에요")
        return true
    }

    private fun persistSafe() = runCatching { persist() }.onFailure { kr.archsurvey.diag.CrashLog.record("연결 설정 저장", it) }
    fun setCheck(i: Int, v: Boolean) { if (i in checks.indices) checks[i] = v }

    private fun persist() = platform.save(SavedConnection(profile.id, address, form, checks.isNotEmpty() && checks.all { it }))

    /** 사전 점검 순서: 권한 → 블루투스 켜짐 → 기기 선택 → 사전 확인 → VRS 입력. 막히면 notice 에 오류 카드 */
    fun connect() {
        notice = null
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
        persistSafe()
        startNow(addr)
        showProgress = true
    }

    private fun startNow(addr: String) {
        savedThisRun = false
        progressStartMs = platform.nowMs()
        platform.startReal(profile, addr, form.toConfig(), form.enabled)
        connectRequested = true
    }

    fun openBluetoothSettings() = platform.openBluetoothSettings()
    fun openAppSettings() = platform.openAppSettings()

    fun disconnect() { platform.stopReal(); connectRequested = false; showProgress = false }

    /** 현재 연결 상태에서 보여 줄 오류 카드 */
    fun cards(): List<ErrorCard> = ErrorCards.from(link.value, ntrip.value, form.enabled)

    private fun card(e: BtError) = ErrorCard(e.code, e.title, e.action, !e.needsUserAction)
}
