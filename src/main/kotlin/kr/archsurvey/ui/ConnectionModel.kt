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
import kr.archsurvey.ntrip.RtcmMountCompat
import kr.archsurvey.bt.BtError
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.feedback.FeedbackEvent

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

    /** 0.1.18 보안: 로그·예외·진단에 찍혀도 비밀번호가 나오지 않게(data class 기본 toString 은 비밀번호를 그대로 찍음) */
    override fun toString(): String = "NtripForm(host=$host, port=$port, mount=$mount, user=$user, password=${if (password.isEmpty()) "" else "***"}, useV2=$useV2, enabled=$enabled)"

    companion object {
        fun from(c: NtripConfig, enabled: Boolean) = NtripForm(c.host, c.port.toString(), c.mountPoint, c.username, c.password, c.useV2, enabled)
    }
}

/** 오류 카드: 한 문장 제목 + 할 일 + 작은 오류 번호. 번호(BT-120 등)는 문의용. */
data class ErrorCard(val code: String, val title: String, val action: String, val autoRetry: Boolean)

object ErrorCards {
    /** [host] = 지금 VRS 서버 주소(0.1.29 T17: RTS3 인증 실패 안내용). 모르면 "" */
    fun from(link: LinkStatus, ntrip: NtripStatus?, ntripEnabled: Boolean, host: String = ""): List<ErrorCard> = buildList {
        link.error?.let { e ->
            if (link.state == LinkState.NEEDS_USER || link.state == LinkState.RECONNECTING || link.state == LinkState.CONNECTING || link.state == LinkState.CONNECTED_NO_DATA || link.state == LinkState.STALE)
                add(ErrorCard(e.code, e.title, e.action, !e.needsUserAction))
        }
        if (link.repairHint && link.error == null) add(ErrorCard("BT-124", BtError.ALL_FAILED.title, BtError.ALL_FAILED.action, false))
        if (ntripEnabled && ntrip != null) ntripCard(ntrip, host)?.let { add(it) }
    }

    /** VRS(NTRIP) 오류를 원인별 문구로. 서버가 준 문구([NtripStatus.message])와 예외 종류로 가른다 */
    fun ntripCard(ntrip: NtripStatus, host: String = ""): ErrorCard? {
        val m = ntrip.message
        val gvrs = Gvrs.isHost(host)
        return when (ntrip.state) {
            NtripState.AUTH_FAILED ->
                if ("403" in m) ErrorCard("NT-403", "VRS 접속이 거부됐어요 (403)", "같은 아이디가 다른 폰·컨트롤러에서 접속 중일 수 있어요. 그쪽을 끊고 다시 시도하세요. 계약(사용 기간·권한)도 확인하세요." + (if (gvrs) " G-VRS(RTS3): ${Gvrs.AUTH_HINT}하세요." else "") + " 자동 재시도는 하지 않아요.", false)
                else if (gvrs) ErrorCard("NT-401", "VRS 아이디/비밀번호가 맞지 않아요 (401)", "G-VRS(RTS3): ${Gvrs.AUTH_HINT}하세요(대소문자 구분). 자동 재시도는 하지 않아요.", false)
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
    /** 0.1.11 VRS 옵션(GGA 전송·자동 재접속) 읽기/적용. 기본 = 둘 다 켬 */
    fun ntripOptions(): Pair<Boolean, Boolean> = true to true
    fun applyNtripOptions(sendGga: Boolean, autoRetry: Boolean) {}
    /** 0.1.11 진단 기록(NMEA 치유 시도·연결 방식) 최근 몇 건. 없으면 빈 문자열 */
    fun diagnostics(): String = ""
    /** 보정 「다시 시도」(자동 재접속 끔·인증 보류 해제) */
    fun retryNtrip() {}
    var autoConnect: Boolean
        get() = false
        set(_) {}
    /** 0.1.29 T19: 사용자가 보정 서비스·서버·마운트를 직접 바꾼 적 있는지(있으면 기기 기본값을 넣지 않음) */
    var correctionUserSet: Boolean
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
        // 0.1.29 T17: 선택 항목(기본값 아님). 출처 국토지리정보원 G-VRS 사용자 가이드(2025-10)
        CorrectionService(Gvrs.ID, "국토지리정보원 G-VRS (RTS3, 시범)", Gvrs.HOST, Gvrs.PORT, Gvrs.MOUNT, "시범 · GVRS는 내 위치(GGA) 필요 · 공공측량 불가", "RTS3 · G-VRS"),
        CorrectionService("custom", "직접 입력", "", 2101, "", "다른 캐스터 서버"),
    )
    private fun m(mp: String, fmt: String, nmea: Boolean, note: String) = MountInfo(mp, note, fmt, "GPS+GLO+GAL+BDS", nmea)
    fun forHost(host: String): List<MountInfo> = when (host.trim().lowercase()) {
        "rts1.ngii.go.kr" -> listOf(
            m("VRS-RTCM31", "RTCM 3.1", true, "미확인"),
            m("VRS-RTCM32", "RTCM 3.2", true, "미확인 · SP60 권장"),
            m("VRS-RTCM34", "RTCM 3.4", true, "R10 허용 · SP60 비호환(최대 3.2) · 미확인"),
            m("VRS-CMRx", "CMRx", true, "미확인"),
            m("VRS-RTCM23", "RTCM 2.3", true, "미확인"),
            m("RTK-RTCM32", "RTCM 3.2", false, "Single RTK · 성과심사 불가 · 미확인"),
        )
        "rts2.ngii.go.kr" -> listOf(
            m("VRS-RTCM31", "RTCM 3.1", true, "미확인"),
            m("VRS-RTCM32", "RTCM 3.2", true, "미확인 · SP60 권장"),
            m("VRS-RTCM34", "RTCM 3.4", true, "R10 허용 · SP60 비호환(최대 3.2) · 미확인"),
            m("FKP-RTCM31", "RTCM 3.1", true, "FKP 면보정 · 미확인"),
            m("SSR-SSRG", "SSR", false, "SSR 보정 · 미확인"),
        )
        // 0.1.29 T17: 가이드에 적힌 고정 이름은 GVRS 뿐. 지역명 마운트는 실제 소스테이블에서만(이름을 지어내지 않음)
        "rts3.ngii.go.kr" -> listOf(m(Gvrs.MOUNT, "미확인", true, "서버가 가까운 격자 자동 선택 · GGA 필요 · RTCM 버전 미확인"))
        else -> emptyList()
    }
}

/** 마운트 선택 창 상태 */
enum class MountLoad { IDLE, LOADING, OK, FAILED }

/**
 * 연결 진행 단계 계산(블루투스 → 수신기 데이터 → VRS 접속 → 보정 수신 → 고정).
 * 소켓이 붙었는데 GGA만 없으면(BT-130) **블루투스=성공**, **수신기 위치 데이터=실패**로 나눈다.
 * (0.1.9는 NEEDS_USER 전체를 블루투스 실패로 표시해 현장 혼동을 일으킴)
 */
object ConnectSteps {
    val labels = listOf("블루투스 연결", "수신기 위치 데이터", "VRS 서버 접속", "보정 데이터 수신", "RTK 고정(FIX)")

    fun compute(link: LinkStatus, ntrip: NtripStatus, ntripEnabled: Boolean, fixQuality: Int): List<StepState> {
        val nmeaIssue = kr.archsurvey.bt.NmeaActivation.isNmeaIssue(link.error)
        // 소켓 연결 성공 후: CONNECTED_* / STREAMING / STALE, 또는 NMEA 문제로 NEEDS_USER·치유 재연결 중
        val socketOk = when (link.state) {
            LinkState.CONNECTED_NO_DATA, LinkState.STREAMING, LinkState.STALE -> true
            LinkState.NEEDS_USER, LinkState.RECONNECTING -> nmeaIssue
            else -> false
        }
        val bt = when {
            link.state == LinkState.IDLE -> StepState.WAIT
            socketOk -> StepState.DONE
            link.state == LinkState.NEEDS_USER -> StepState.FAIL
            link.state == LinkState.CONNECTING || link.state == LinkState.RECONNECTING -> StepState.ACTIVE
            else -> StepState.DONE
        }
        val data = when (link.state) {
            LinkState.STREAMING -> StepState.DONE
            LinkState.CONNECTED_NO_DATA, LinkState.STALE -> StepState.ACTIVE
            LinkState.NEEDS_USER -> if (nmeaIssue) StepState.FAIL else StepState.WAIT
            LinkState.RECONNECTING -> if (nmeaIssue) StepState.ACTIVE else StepState.WAIT
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

    /** 진행 창 단계별 부가 문구 */
    fun detail(i: Int, link: LinkStatus, states: List<StepState>, hostMount: String?, qualityLabel: String, sats: Int): String? = when (i) {
        0 -> when (states[0]) {
            StepState.DONE -> "소켓 연결됨" + (link.strategy?.let { " · $it" } ?: "")
            StepState.FAIL -> link.message.ifBlank { link.error?.title }.orEmpty().ifBlank { null }
            StepState.ACTIVE -> link.message.ifBlank { null }
            else -> null
        }
        1 -> when (states[1]) {
            StepState.FAIL -> (link.error?.let { "${it.code} · ${it.action}" } ?: link.message).ifBlank { null }
            StepState.ACTIVE -> link.message.ifBlank { "위치(GGA) 기다리는 중" }
            StepState.DONE -> "GGA 수신 중"
            else -> null
        }
        2 -> hostMount
        4 -> "지금: $qualityLabel · 위성 ${sats}개"
        else -> null
    }
}

/**
 * 0.1.18 연결 상호작용 단계(HTML 「수신기 · VRS 연결」: none / connecting / on / fail).
 * 아래 단추가 이 값으로 바뀐다: 대기·실패 = 「연결」/「다시 시도」, 연결 중 = 「연결 취소」, 연결됨 = 「연결 해제」+「지도로 가기」.
 * 연결됨 = 수신기 위치(GGA)가 들어옴(STREAMING) 또는 잠시 끊김(STALE). 소켓만 붙고 위치가 없으면(BT-130 치유 중) 아직 「연결 중」.
 */
enum class ConnPhase { IDLE, CONNECTING, CONNECTED, FAILED }

object ConnPhases {
    fun of(requested: Boolean, link: LinkStatus): ConnPhase = when {
        !requested -> ConnPhase.IDLE
        link.state == LinkState.NEEDS_USER -> ConnPhase.FAILED
        link.state == LinkState.STREAMING || link.state == LinkState.STALE -> ConnPhase.CONNECTED
        else -> ConnPhase.CONNECTING
    }

    /** 단계 상태 한국어(HTML: 대기 / 진행 중 / 완료 / 실패) + VRS 끔일 때 건너뜀 */
    fun stateKo(s: StepState): String = when (s) {
        StepState.WAIT -> "대기"; StepState.ACTIVE -> "진행 중"; StepState.DONE -> "완료"; StepState.FAIL -> "실패"; StepState.SKIP -> "건너뜀"
    }
}

/**
 * 0.1.18 블루투스 단계 실패 머리 카드(HTML 문구) + 조사 보완(field-learning §1.2·§1.3: SP60 위성 추적 후 연결, 콜렉터 2대 제한·도난경보).
 * 소켓은 붙었는데 위치만 없는 BT-130(NMEA 문제)은 여기서 다루지 않는다 — 기존 BT-130 카드·치유 흐름 그대로.
 */
object ConnectFailCopy {
    const val TITLE = "수신기에 연결하지 못했어요"
    const val BASE = "수신기 전원이 켜져 있는지, 폰의 블루투스가 켜져 있는지 확인한 뒤 다시 시도하세요. 다른 폰에 연결되어 있으면 그 연결을 먼저 끊어야 해요."
    fun headline(profile: DeviceProfile, link: LinkStatus): ErrorCard? {
        if (link.state != LinkState.NEEDS_USER) return null
        if (kr.archsurvey.bt.NmeaActivation.isNmeaIssue(link.error)) return null
        val extra = if (profile.id == DeviceProfiles.SP60_ID) " SP60은 전원을 켜고 위성을 잡은 뒤(LED 확인) 연결하세요 — 먼저 연결하면 등록이 풀릴 수 있어요. 도난경보·시작잠금이 켜져 있으면 먼저 해제하세요." else ""
        return ErrorCard(link.error?.code ?: "-", TITLE, BASE + extra, false)
    }
}

/** [preChecked]: 이 수신기에서 “연결 전 확인”을 한 번 마쳤는지(수신기 설정은 수신기에 남으므로 다음부터 묻지 않음) */
/** 연결 화면 구역 */
enum class ConnSection { ALL, VRS }

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
    /** 장비 프로필 변경 시(안테나 모델 동기화). SurveyViewModel 이 연결 */
    /** 0.1.29: 돌려주는 문구(폴 높이를 기기 기본값으로 바꿨을 때 안내)가 있으면 알림에 함께 보인다 */
    var onProfileChanged: ((DeviceProfile) -> String?)? = null
    /** 0.1.19 연결 전용 효과음·진동(CONNECT_OK/FAIL/DISCONNECT). VM 이 FeedbackPlayer 에 연결 */
    var onFeedback: ((FeedbackEvent) -> Unit)? = null
    var showNmeaSteps by mutableStateOf(false)
    /** 접힌 영역(서버 설정·도움말·연결 전 확인)을 펼쳤는지 */
    var showServer by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showPre by mutableStateOf(false)
    val link: StateFlow<LinkStatus> get() = platform.link
    val ntrip: StateFlow<NtripStatus> get() = platform.ntrip
    var connectRequested by mutableStateOf(false); private set

    // ---------------- 0.1.18 연결 상호작용 ----------------
    /** 지금 연결 단계(아래 단추·잠금 판단) */
    fun phase(): ConnPhase = ConnPhases.of(connectRequested, link.value)
    /** 연결 중·연결됨이면 모델·수신기를 바꾸지 못하게 막는다(HTML 60·61: 「먼저 연결을 해제하세요」) */
    private fun lockedForChange(): Boolean {
        val ph = phase()
        if (ph != ConnPhase.CONNECTING && ph != ConnPhase.CONNECTED) return false
        ui.fail(LOCKED_MSG); return true
    }
    /** 「닫아도 연결은 계속돼요」 안내를 이번 실행에서 이미 보였는지(처음 1회만) */
    var keepHintShown by mutableStateOf(false); private set
    private fun keepHintOnce() { if (!keepHintShown) { keepHintShown = true; ui.toast(KEEP_HINT) } }
    /** 진행 창 「완료」 판정. 진행 창 단계 계산(ConnectSteps 마지막 단계 = GGA 품질 4)과 같은 기준 */
    fun progressDone(fixQuality: Int): Boolean = fixQuality == 4
    /**
     * 진행 창 닫기 — 「닫기(계속 연결)」 단추 · 시스템 뒤로(BackHandler) · backTarget 이 모두 이 함수 하나를 부른다(0.1.17 비차단 AND-0117-1 정리).
     * 완료 상태면 「확인」 = 진행 창 + 연결 화면 함께 닫고 지도로. 진행 중이면 창만 닫고 연결은 계속 + 첫 1회 안내.
     */
    fun dismissProgress(done: Boolean) {
        showProgress = false
        if (done) { close(); return }
        if (connectRequested) keepHintOnce()
    }
    /** 연결 화면 머리글 뒤로(HTML 59: 지도로 돌아간다 · 연결은 유지). 연결 중이면 첫 1회 안내 */
    fun leaveScreen() {
        close()
        if (phase() == ConnPhase.CONNECTING) keepHintOnce()
    }

    /** 0.1.29 T19: 사용자가 보정 설정을 직접 바꿨는지(저장됨). 바꿨으면 기기 기본값을 넣지 않는다 */
    private var correctionUserSet: Boolean = runCatching { platform.correctionUserSet }.getOrDefault(false)
    /** 기기 기본값으로 넣은 마운트 이름(사용자가 바꾸면 null). 목록에 없으면 알림 */
    var defaultMountApplied by mutableStateOf<String?>(null); private set
    /** 받은 목록에 기본 마운트 이름이 없었는지 [현장확인] */
    var defaultMountMissing by mutableStateOf(false); private set
    /** 0.1.29 T17: RTS3 고를 때 비밀번호 ngii 를 채우기 전 값(메모리만). 다른 서비스로 돌아갈 때 되돌린다 */
    private var passwordBeforeGvrs: String? = null
    /** 0.1.29 T17-2: 고정해 σH 기준(m). 지금은 설정에서 바꿀 수 없다 = 20 mm */
    var sigmaThresholdM by mutableStateOf(kr.archsurvey.core.StatusThresholds().fixedMaxSigmaHM)

    init {
        val s = platform.loadSaved()
        DeviceProfiles.byId(s.deviceId)?.let { profile = it }
        address = s.address
        form = s.ntrip
        resetChecks()
        if (s.preChecked) for (i in checks.indices) checks[i] = true
        // 0.1.29 T19: 새로 설치(기기를 고른 적 없음)면 기본 기기(R10)의 보정 기본값
        if (s.deviceId == null && applyDeviceCorrectionDefault(profile)) persistSafe()
    }

    /**
     * 0.1.29 T19 기기별 보정 기본값(R10 → RTS1 + VRS-RTCM34, SP60 → RTS2 + VRS-RTCM32). RTS3 는 기본값이 아님.
     * 사용자가 바꾼 적 있거나 연결 성공 이력이 있거나 지금 값이 손댄 값이면 아무것도 하지 않는다(사용자 값 보존).
     */
    private fun applyDeviceCorrectionDefault(p: DeviceProfile): Boolean {
        if (correctionUserSet || runCatching { platform.hadSuccess() }.getOrDefault(false)) return false
        if (!DeviceCorrectionDefaults.looksUnset(form)) return false
        val d = DeviceCorrectionDefaults.forProfile(p.id) ?: return false
        defaultMountApplied = d.mount; defaultMountMissing = false
        if (form.host.trim().equals(d.host, true) && form.mount.trim().equals(d.mount, true) && form.port.trim() == d.port.toString()) return false
        form = form.copy(host = d.host, port = d.port.toString(), mount = d.mount)
        return true
    }

    private fun markCorrectionUserSet() {
        defaultMountApplied = null; defaultMountMissing = false
        if (correctionUserSet) return
        correctionUserSet = true
        runCatching { platform.correctionUserSet = true }
    }

    /** GVRS 마운트면 GGA 전송을 켠다(끄지 못함). 켰으면 true */
    private fun ensureGgaForGvrs(): Boolean {
        if (!Gvrs.requiresGga(form) || sendGga) return false
        sendGga = true; runCatching { platform.applyNtripOptions(sendGga, autoRetry) }
        return true
    }

    /** 0.1.29 T17 연결 화면 안내: RTS3 를 골랐을 때만 */
    fun gvrsNotice(): String? = if (form.enabled && Gvrs.isHost(form.host)) Gvrs.NOTICE else null
    /** RTS3 + SP60: RTCM 버전 미확인 안내 [현장확인] */
    fun gvrsSp60Line(): String? = if (gvrsNotice() != null && profile.id == DeviceProfiles.SP60_ID) Gvrs.SP60_RTCM_LINE else null
    /** 0.1.29 T17-2: σH 기준 11 mm 미만 + SP60 → 펌웨어 5.26 안내(막지 않음) */
    fun firmwareNotice(): String? = Sp60Firmware.notice(profile, sigmaThresholdM)
    /** 연결·저장·자동 연결을 막는 입력 문제(폼 + GVRS 인데 GGA 끔) */
    fun connectProblems(): List<String> = form.problems() + if (Gvrs.requiresGga(form) && !sendGga) listOf(Gvrs.GGA_REQUIRED) else emptyList()

    private fun resetChecks() { checks.clear(); repeat(profile.preConnectChecks.size) { checks.add(false) } }

    fun refresh() { bonded = profile.sortByRelevance(platform.bondedDevices()) { it.first } }

    /** 0.1.11: 목업처럼 「장비 연결」(수신기+VRS 한 화면)과 「VRS 설정」(VRS만) 두 입구 */
    var section by mutableStateOf(ConnSection.ALL)
    fun openScreen() { refresh(); section = ConnSection.ALL; open = true }
    fun openVrs() { refresh(); section = ConnSection.VRS; open = true }
    fun close() { open = false }

    fun selectProfile(p: DeviceProfile) {
        if (p.id == profile.id) return
        if (lockedForChange()) return
        profile = p; resetChecks(); address = null; notice = null; refresh()
        val defaulted = applyDeviceCorrectionDefault(p) // 0.1.29 T19: 처음 고를 때·값이 비었을 때만
        persist()
        val poleMsg = onProfileChanged?.invoke(p) // JobConfig.antennaModelId 와 맞춤(0.1.13 P0) · 0.1.29 폴 높이 바뀜 안내
        // 알림은 하나로(앞 알림이 뒤 알림에 덮이지 않게): 폴 높이 → 기본 보정 → RTCM 호환 경고
        val msgs = listOfNotNull(
            poleMsg,
            if (defaulted) "${p.displayName.substringAfterLast(' ')} 기본 보정: ${currentService().short} · ${form.mount} (서버 목록에서 이름 확인)" else null,
            RtcmMountCompat.warnIfIncompatible(profile, form.mount),
        )
        if (msgs.isNotEmpty()) ui.toast(msgs.joinToString(" · "))
    }

    fun selectAddress(a: String) { if (a != address && lockedForChange()) return; address = a; notice = null; persist() }

    fun updateForm(f: NtripForm) {
        val corrChanged = f.host.trim() != form.host.trim() || f.port.trim() != form.port.trim() || f.mount.trim() != form.mount.trim()
        form = f
        if (corrChanged) { markCorrectionUserSet(); if (ensureGgaForGvrs()) ui.toast(Gvrs.GGA_REQUIRED) }
    }

    // ---------------- 보정 서비스·마운트 (SmartTopo 흐름: 서비스 → 목록 받기 → 검색·선택) ----------------
    fun currentService(): CorrectionService {
        // 0.1.29: 목록 순서(sv[0..3])가 아니라 id 로 찾는다(RTS3 를 넣어도 FKP·직접 입력이 밀리지 않게)
        val h = form.host.trim().lowercase()
        fun byId(id: String) = KnownMounts.services.first { it.id == id }
        return when {
            h == "rts2.ngii.go.kr" && form.mount.trim().startsWith("FKP", true) -> byId("fkp")
            h == "rts1.ngii.go.kr" -> byId("rts1")
            h == "rts2.ngii.go.kr" -> byId("rts2")
            h == "rts3.ngii.go.kr" -> byId(Gvrs.ID)
            else -> byId("custom")
        }
    }

    fun askService() = ui.choose("보정 서비스", KnownMounts.services.map { it.label + " · " + it.note }, KnownMounts.services.indexOf(currentService()), "service", KnownMounts.SOURCE) { i ->
        val sv = KnownMounts.services[i]
        markCorrectionUserSet()
        if (sv.id == "custom") { showServer = true; return@choose }
        val prof = platform.hostProfile(sv.host)
        val toGvrs = sv.id == Gvrs.ID
        // RTS3 에서 나갈 때: 미리 채운 ngii 를 그대로 두었으면 이전 비밀번호로 되돌린다(다른 서버로 새지 않게)
        var basePw = form.password
        if (!toGvrs && passwordBeforeGvrs != null) {
            if (basePw == Gvrs.GUIDE_PASSWORD) basePw = passwordBeforeGvrs.orEmpty()
            passwordBeforeGvrs = null
        }
        form = if (prof != null) form.copy(host = sv.host, port = prof.port, mount = if (sv.id == "fkp") sv.mount else prof.mount.ifBlank { sv.mount }, user = prof.user.ifBlank { form.user }, password = prof.password.ifEmpty { if (toGvrs) Gvrs.GUIDE_PASSWORD else basePw })
        else if (toGvrs) {
            // 0.1.29 T17: 아이디는 그대로, 비밀번호는 가이드 값 ngii 를 미리 채움(수정 가능), 마운트 GVRS
            if (passwordBeforeGvrs == null && !Gvrs.isHost(form.host)) passwordBeforeGvrs = form.password
            form.copy(host = sv.host, port = sv.port.toString(), mount = sv.mount, password = if (Gvrs.isHost(form.host) && form.password.isNotEmpty()) form.password else Gvrs.GUIDE_PASSWORD)
        } else form.copy(host = sv.host, port = sv.port.toString(), mount = sv.mount, password = basePw)
        val ggaOn = ensureGgaForGvrs()
        persistSafe()
        ui.toast("보정 서비스: ${sv.label} · 마운트 ${form.mount}" + (if (prof != null) " · 이 서버의 마지막 성공 설정을 불러왔어요" else "") + (if (toGvrs) " · ${Gvrs.NOTICE}" else "") + if (ggaOn) " · GGA 전송 켬" else "")
    }

    /** 서버 주소를 직접 바꾼 뒤(입력 끝) 그 서버의 마지막 성공 설정을 채운다 */
    fun recallHost() {
        val prof = platform.hostProfile(form.host) ?: return
        markCorrectionUserSet()
        form = form.copy(port = prof.port, mount = prof.mount, user = prof.user.ifBlank { form.user }, password = prof.password.ifEmpty { form.password })
        ensureGgaForGvrs()
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
                else {
                    mounts = list.sortedWith(compareBy<MountInfo> { if (it.isRtcm3) 0 else 1 }.thenBy { it.mountPoint }); mountFromKnown = false; mountLoad = MountLoad.OK
                    // 0.1.29 T19: 기기 기본 마운트 이름이 실제 목록에 없으면 알리고 직접 고르게 한다 [현장확인]
                    val dm = defaultMountApplied
                    defaultMountMissing = dm != null && form.mount.trim().equals(dm, true) && list.none { it.mountPoint.equals(dm, true) }
                    if (defaultMountMissing) ui.fail("기본 마운트 $dm 이(가) 이 서버 목록에 없어요 — 목록에서 직접 고르세요")
                    else ui.ok("마운트 목록 ${list.size}개 받음")
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                mountFail(e.message?.take(120) ?: e.javaClass.simpleName)
            }
        }
    }

    private fun mountFail(why: String) {
        mountError = why
        ui.fail("마운트 목록을 못 받았어요 · $why · 알려진 목록으로 대신 보여요")
        mounts = KnownMounts.forHost(form.host); mountFromKnown = true
        mountLoad = MountLoad.FAILED
    }

    fun cancelMountLoad() { mountJob?.cancel(); mountJob = null; if (mountLoad == MountLoad.LOADING) mountFail("받기를 취소했어요") }

    /** 0.1.18 「이 수신기에 맞는 것만」: 수신기 최대 RTCM 버전을 넘는 마운트(SP60의 3.4 등)를 목록에서 뺀다. 버전을 알 수 없는 마운트는 남긴다 */
    var mountCompatOnly by mutableStateOf(true)

    fun filteredMounts(): List<MountInfo> {
        val q = mountQuery.trim()
        val base = if (mountCompatOnly) mounts.filter { !RtcmMountCompat.isTooNew(profile, it.mountPoint, it.format, it.formatDetails) } else mounts
        return if (q.isEmpty()) base else base.filter { it.mountPoint.contains(q, true) || it.format.contains(q, true) || it.identifier.contains(q, true) || it.navSystem.contains(q, true) }
    }
    /** 필터로 숨긴 비호환 마운트 수 */
    fun hiddenIncompatibleCount(): Int = if (!mountCompatOnly) 0 else mounts.count { RtcmMountCompat.isTooNew(profile, it.mountPoint, it.format, it.formatDetails) }

    /** 0.1.19: 지금 고른 마운트가 호환 필터 때문에 목록에서 빠져 있는지(예: SP60 + VRS-RTCM34) */
    fun selectedMountHiddenByFilter(): Boolean {
        if (!mountCompatOnly) return false
        val m = form.mount.trim()
        if (m.isEmpty() || mounts.isEmpty()) return false
        val inList = mounts.any { it.mountPoint.equals(m, true) }
        if (!inList) return false
        return filteredMounts().none { it.mountPoint.equals(m, true) }
    }

    /** 0.1.18 목록 행 오른쪽 짧은 표시: 권장(이 수신기 최대 버전 이하 중 가장 알맞은 VRS) / R10 의 RTCM 3.4 허용 */
    fun mountGoodNote(m: MountInfo): String? {
        if (RtcmMountCompat.isTooNew(profile, m.mountPoint, m.format, m.formatDetails)) return null
        // 0.1.29 T17: RTS3 — GVRS(이동 측량) / 지역명 마운트(정지 측량)
        if (Gvrs.isHost(form.host)) return when {
            Gvrs.isGvrsMount(m.mountPoint) -> Gvrs.GVRS_NOTE
            REGION_MOUNT.matches(m.mountPoint.trim()) -> Gvrs.REGION_NOTE
            else -> null
        }
        val minor = RtcmMountCompat.detectMinor(m.mountPoint, m.format, m.formatDetails) ?: return null
        val rec = RecommendedMount.pick(profile, mounts, "")
        return when {
            rec != null && rec.equals(m.mountPoint, true) -> if (minor == 4 && profile.id == DeviceProfiles.R10_ID) "권장 · R10 3.4 사용 가능" else "권장"
            minor == 4 && profile.id == DeviceProfiles.R10_ID -> "R10 3.4 사용 가능"
            else -> null
        }
    }

    fun pickMount(name: String) {
        val m = name.trim()
        if (m.isEmpty()) { ui.fail("마운트 이름을 입력하세요"); return }
        val info = mounts.firstOrNull { it.mountPoint.equals(m, true) }
        form = form.copy(mount = m)
        markCorrectionUserSet()
        mountPickerOpen = false; mountJob?.cancel()
        if (ensureGgaForGvrs()) ui.toast(Gvrs.GGA_REQUIRED)
        persistSafe()
        val warn = RtcmMountCompat.warnIfIncompatible(profile, m, info?.format.orEmpty(), info?.formatDetails.orEmpty())
        if (warn != null) ui.toast(warn) else ui.ok("마운트 포인트 저장됨 · $m")
    }

    /** 목록 행에 붙일 짧은 경고(호환이면 null) */
    fun mountCompatNote(m: MountInfo): String? =
        RtcmMountCompat.warnIfIncompatible(profile, m.mountPoint, m.format, m.formatDetails)?.let { compatShort(profile) }

    /** 0.1.11 짧은 호환 문구: 수신기 이름 + 최대 RTCM 버전 + 권장 */
    private fun compatShort(p: DeviceProfile): String {
        val max = RtcmMountCompat.maxMinor(p)
        val name = p.displayName.substringAfterLast(' ')
        return "$name 비호환 · 최대 RTCM 3.$max" + if (max <= 2) " · 3.1/3.2 권장" else ""
    }

    /** 0.1.11 지금 마운트가 이 수신기에 너무 새 형식이면 같은 서버의 권장 마운트(없으면 null) */
    fun recommendedMount(): String? {
        val info = mounts.firstOrNull { it.mountPoint.equals(form.mount.trim(), true) }
        if (!RtcmMountCompat.isTooNew(profile, form.mount, info?.format.orEmpty(), info?.formatDetails.orEmpty())) return null
        return RecommendedMount.pick(profile, if (mounts.isNotEmpty()) mounts else KnownMounts.forHost(form.host), form.mount)
    }
    fun applyRecommendedMount() { recommendedMount()?.let { pickMount(it) } }

    // ---------------- 0.1.11 VRS 옵션 · 접속 계측 ----------------
    var sendGga by mutableStateOf(runCatching { platform.ntripOptions().first }.getOrDefault(true)); private set
    var autoRetry by mutableStateOf(runCatching { platform.ntripOptions().second }.getOrDefault(true)); private set
    var showUseV2 by mutableStateOf(false)
    var showDiag by mutableStateOf(false)
    val meter = NtripMeter()
    fun changeSendGga(v: Boolean) {
        // 0.1.29 T17: GVRS 마운트는 GGA 가 없으면 보정이 오지 않는다 — 끄지 못하게 경고하고 켠 채로 둔다
        if (!v && Gvrs.requiresGga(form)) { if (!sendGga) { sendGga = true; platform.applyNtripOptions(sendGga, autoRetry) }; ui.fail(Gvrs.GGA_REQUIRED); return }
        sendGga = v; platform.applyNtripOptions(sendGga, autoRetry)
        ui.toast(if (v) "GGA 전송: 켬" else "GGA 전송: 끔 — VRS 마운트는 내 위치가 없으면 보정이 오지 않을 수 있어요")
    }
    fun changeAutoRetry(v: Boolean) {
        autoRetry = v; platform.applyNtripOptions(sendGga, autoRetry)
        ui.toast(if (v) "자동 재접속: 켬" else "자동 재접속: 끔 — 끊기면 ‘다시 시도’를 눌러야 해요")
    }
    fun changeUseV2(v: Boolean) { form = form.copy(useV2 = v); persistSafe() }
    fun retryNtrip() = platform.retryNtrip()
    fun diagnostics(): String = runCatching { platform.diagnostics() }.getOrDefault("")
    /** 「저장」만(접속하지 않음) — 목업 07 하단 */
    fun saveOnly() {
        val probs = connectProblems()
        persistSafe()
        ui.result(probs.isEmpty(), if (probs.isEmpty()) "VRS 설정 저장됨 · ${form.host}:${form.port} · ${form.mount}" else "저장됨 · 확인 필요: " + probs.joinToString(" · "))
    }

    fun closeMountPicker() { mountPickerOpen = false; mountJob?.cancel(); if (mountLoad == MountLoad.LOADING) mountLoad = MountLoad.IDLE }

    // ---------------- 연결 성공 저장·자동 연결 ----------------
    /** 화면이 링크·보정 상태를 볼 때마다 부른다. 위치 데이터가 들어오면(보정 켜짐이면 보정 수신까지) 한 번 저장 */
    fun onStatus(l: LinkStatus, n: NtripStatus) {
        reportFailure(l, n)
        if (!connectRequested || savedThisRun) return
        val ok = l.state == LinkState.STREAMING && (!form.enabled || n.state == NtripState.RECEIVING)
        if (!ok) return
        savedThisRun = true
        persistSafe()
        runCatching { platform.markSuccess(form) }
        val dev = bonded.firstOrNull { it.second == address }?.first ?: address.orEmpty()
        ui.ok("연결 설정 저장됨 · $dev" + if (form.enabled) " · ${form.mount} · ${form.user}" else "")
        onFeedback?.invoke(FeedbackEvent.CONNECT_OK) // 최초 STREAMING(+VRS) 성공 — LINK_RESTORED 와 별 경로
    }

    /** 0.1.14 연결 실패 멘트: 같은 실패는 한 번만(상태가 바뀌면 다시) */
    private var lastFailKey: String? = null
    private fun reportFailure(l: LinkStatus, n: NtripStatus) {
        if (!connectRequested) { lastFailKey = null; return }
        val card = ErrorCards.from(l, n, form.enabled, form.host).firstOrNull()
        val failing = l.state == LinkState.NEEDS_USER || (form.enabled && (n.state == NtripState.AUTH_FAILED || n.state == NtripState.ERROR))
        if (!failing || card == null) { if (l.state == LinkState.STREAMING) lastFailKey = null; return }
        val key = "${l.state}/${n.state}/${card.code}"
        if (key == lastFailKey) return
        lastFailKey = key
        ui.fail("${card.title} (${card.code}) — ${card.action}")
        // 이번 연결에서 아직 성공 전 실패만 CONNECT_FAIL (성공 뒤 끊김은 LINK_LOST 한 경로)
        if (!savedThisRun) onFeedback?.invoke(FeedbackEvent.CONNECT_FAIL)
    }

    fun changeAutoConnect(v: Boolean) { autoConnect = v; platform.autoConnect = v; ui.toast(if (v) "다음 실행 때 자동 연결: 켬" else "다음 실행 때 자동 연결: 끔") }

    /** 앱 시작 시: 성공 이력 + 자동 연결 켬 + 권한·블루투스 OK 일 때만 조용히 연결. 막히면 아무것도 하지 않는다 */
    fun autoConnectOnLaunch(): Boolean {
        if (!autoConnect || !platform.hadSuccess() || connectRequested) return false
        if (!platform.hasBluetoothPermission() || !platform.bluetoothEnabled()) return false
        val addr = address ?: return false
        if (runCatching { platform.bondedDevices() }.getOrDefault(emptyList()).none { it.second == addr }) return false
        if (connectProblems().isNotEmpty()) return false
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
        val probs = connectProblems()
        if (probs.isNotEmpty()) { notice = ErrorCard("-", "VRS 입력을 확인해 주세요", probs.joinToString(" · "), false); return }
        // 0.1.11: 비호환 마운트는 첫 누름에 경고만(연결 안 함), 같은 설정으로 한 번 더 누르면 진행
        val w = if (form.enabled) RtcmMountCompat.warnIfIncompatible(profile, form.mount) else null
        if (w != null) {
            val key = profile.id + "|" + form.mount.trim()
            notice = ErrorCard("RTCM", "이 수신기에 맞지 않는 보정 형식일 수 있어요", w + " 그래도 쓰려면 ‘연결’을 한 번 더 누르세요.", false)
            if (rtcmWarnedFor != key) { rtcmWarnedFor = key; return }
        }
        persistSafe()
        startNow(addr)
        showProgress = true
    }

    private var rtcmWarnedFor: String? = null

    private fun startNow(addr: String) {
        notice = null // 0.1.19: 연결 시작 뒤 사전 점검/경고 카드 지움(남은 notice 가 「다시 시도」로 남는 것 방지)
        savedThisRun = false
        progressStartMs = platform.nowMs()
        platform.startReal(profile, addr, form.toConfig(), form.enabled)
        connectRequested = true
    }

    fun openBluetoothSettings() = platform.openBluetoothSettings()
    fun openAppSettings() = platform.openAppSettings()

    /** 수신기 연결을 끊는다(HTML 65 「연결 해제」). 지도의 위치·좌표는 「—」로 */
    fun disconnect() { stopAll(); ui.toast(DISCONNECTED_MSG); onFeedback?.invoke(FeedbackEvent.DISCONNECT) }
    /** 연결 시도를 멈춘다(HTML 64 「연결 취소」 — 연결 중일 때만) */
    fun cancelConnect() { stopAll(); ui.toast(CANCELLED_MSG) }
    private fun stopAll() { platform.stopReal(); connectRequested = false; showProgress = false }
    /** 0.1.31 T27 앱 종료: 알림·소리 없이 보정(VRS)·블루투스를 끊고 연결 서비스를 멈춘다 */
    fun stopForExit() = stopAll()
    /** 0.1.31 T27: 수신기 연결이 살아 있거나 연결 중이면 true(종료 창 문구) */
    fun linkActive(): Boolean = connectRequested || link.value.state != kr.archsurvey.device.LinkState.IDLE

    /** 연결 화면 주 단추 문구(HTML 63): 실패·사전 점검 막힘 = 「다시 시도」, 비호환 마운트 경고 뒤 = 「그래도 연결」 */
    fun connectLabel(): String = when {
        notice?.code == "RTCM" -> "그래도 연결"
        notice != null || phase() == ConnPhase.FAILED -> "다시 시도"
        else -> "연결"
    }

    companion object {
        const val KEEP_HINT = "연결은 계속돼요 · 아래 대기열에서 진행 상황을 볼 수 있어요"
        const val LOCKED_MSG = "먼저 연결을 해제하세요"
        const val DISCONNECTED_MSG = "수신기 연결을 해제했어요 · 지도의 위치·좌표가 사라져요"
        const val CANCELLED_MSG = "연결을 취소했어요"
        /** RTS3 지역명 마운트 모양(가이드 예: Seoul-Gangseo-Gaehwa, Yechon-Gaepo) */
        private val REGION_MOUNT = Regex("^[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)+$")
    }

    /** 현재 연결 상태에서 보여 줄 오류 카드 */
    fun cards(): List<ErrorCard> = ErrorCards.from(link.value, ntrip.value, form.enabled, form.host)

    private fun card(e: BtError) = ErrorCard(e.code, e.title, e.action, !e.needsUserAction)
}

/** 0.1.11 권장 마운트: 수신기 최대 RTCM 버전 이하 중 VRS(위치 필요)·가장 높은 버전. 없으면 null */
object RecommendedMount {
    fun pick(profile: DeviceProfile, list: List<MountInfo>, current: String): String? {
        val max = RtcmMountCompat.maxMinor(profile)
        val ok = list.mapNotNull { m -> RtcmMountCompat.detectMinor(m.mountPoint, m.format, m.formatDetails)?.takeIf { it <= max }?.let { m to it } }
            .filter { !it.first.mountPoint.equals(current.trim(), true) }
        return ok.sortedWith(compareByDescending<Pair<MountInfo, Int>> { it.first.needsNmea && it.first.mountPoint.startsWith("VRS", true) }.thenByDescending { it.second })
            .firstOrNull()?.first?.mountPoint
    }
}

/**
 * 0.1.11 VRS 상태 카드 계측(목업 07: 수신 kB/s · 지연 · 경과). 화면이 1초마다 [update] 를 부른다.
 * 경과 = 접속(CONNECTED/RECEIVING)을 처음 본 뒤 시간, kB/s = 직전 1초 이상 구간의 수신량, 지연 = 마지막 유효 RTCM 이후 초.
 */
class NtripMeter {
    var sessionStartMs: Long? = null; private set
    var kbps: Double? = null; private set
    private var prevBytes = 0L
    private var prevAt = 0L

    fun update(st: NtripStatus, nowMs: Long) {
        val live = st.state == NtripState.CONNECTED || st.state == NtripState.RECEIVING
        if (!live) { sessionStartMs = null; kbps = null; prevAt = 0L; return }
        if (sessionStartMs == null) sessionStartMs = nowMs
        if (prevAt == 0L || st.rtcmBytes < prevBytes) { prevBytes = st.rtcmBytes; prevAt = nowMs; return }
        val dt = nowMs - prevAt
        if (dt >= 1_000) {
            kbps = (st.rtcmBytes - prevBytes) / 1024.0 / (dt / 1000.0)
            prevBytes = st.rtcmBytes; prevAt = nowMs
        }
    }

    fun elapsedText(nowMs: Long): String? = sessionStartMs?.let { start ->
        val s = ((nowMs - start).coerceAtLeast(0) / 1000).toInt()
        String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun line(st: NtripStatus, nowMs: Long): String {
        val parts = mutableListOf<String>()
        parts += "수신 " + (kbps?.let { String.format(java.util.Locale.US, "%.1f kB/s", it) } ?: "— kB/s")
        parts += "지연 " + (st.rtcmAgeMs(nowMs)?.let { String.format(java.util.Locale.US, "%.1f s", it / 1000.0) } ?: "—")
        parts += "경과 " + (elapsedText(nowMs) ?: "—")
        return parts.joinToString(" · ")
    }
}
