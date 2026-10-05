package kr.archsurvey.device

/**
 * 측량기 모델별 프로필. 통신 로직(NMEA 드라이버, BT 전송, NTRIP)은 공통이고
 * 이름 필터·안내문·PIN·워치독·재연결 정책만 모델별로 다르다.
 *
 * 근거: android-platform.md §0, design.md §1. 실기기 검증 안 됨 — [현장확인] 표시 항목은 시험으로 확정해야 함.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    val maker: String,
    val subtitle: String,
    /** 블루투스 기기 이름 필터 (소문자 부분 일치). 일치하는 기기를 목록 상단에 표시 */
    val btNameHints: List<String>,
    val defaultPin: String,
    val pinNote: String,
    val pairingSteps: List<String>,
    val nmeaSetupTitle: String,
    val nmeaSetupSteps: List<String>,
    /** 연결 전 확인(체크박스) 문구 */
    val preConnectChecks: List<String>,
    /** 포트 관련 주의 */
    val portNote: String,
    val noDataHint: String,
    val repairHint: String,
    val requiredSentences: Set<String>,
    val recommendedSentences: List<String>,
    /** 마지막 문장 이후 이 시간이 지나면 STALE(데이터 끊김 표시) */
    val staleAfterMs: Long,
    /** 이 시간이 지나면 소켓을 닫고 재연결 */
    val reconnectAfterMs: Long,
    val reconnect: ReconnectPolicy,
    /** 수신기로 RTCM 쓰기 시 청크 크기와 청크 사이 대기 */
    val rtcmWriteChunk: Int,
    val rtcmWriteGapMs: Long,
    /** 캐스터로 GGA 올리는 주기 */
    val ggaUploadIntervalMs: Long,
    /** 보안 연결 실패 시 insecure RFCOMM 폴백 허용 */
    val allowInsecureFallback: Boolean,
    val connectAttemptsPerCycle: Int,
    /**
     * 소켓은 붙었는데 GGA가 없을 때(BT-130) 자동 NMEA 명령·재연결을 몇 번 더 시도할지.
     * 다 쓴 뒤에는 NEEDS_USER + 현장 체크리스트. 0이면 치유 없이 바로 안내.
     */
    val nmeaHealAttempts: Int = 2,
    /**
     * NMEA 자동 켜기 실패 뒤에도 소켓을 이 시간만큼 유지해 진단(TX/RX·바이트)을 설정에서 볼 수 있게 한다.
     * 0이면 활성화가 끝나는 즉시(또는 reconnectAfterMs) 끊고 치유 재연결.
     */
    val noDataHoldMs: Long = 15_000,
    val verifiedOnRealDevice: Boolean = false,
) {
    fun matchesName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return btNameHints.any { n.contains(it) }
    }

    /** 일치하는 기기를 앞으로 정렬 */
    fun <T> sortByRelevance(items: List<T>, nameOf: (T) -> String?): List<T> =
        items.sortedWith(compareByDescending<T> { matchesName(nameOf(it)) }.thenBy { nameOf(it)?.lowercase() ?: "" })
}

object DeviceProfiles {
    const val R10_ID = "trimble_r10"
    const val SP60_ID = "spectra_sp60"

    val R10 = DeviceProfile(
        id = R10_ID,
        displayName = "Trimble R10",
        maker = "Trimble",
        subtitle = "GNSS 수신기 · 블루투스",
        btNameHints = listOf("r10", "trimble", "trmb"),
        defaultPin = "0000",
        pinNote = "기본 PIN 0000 (수신기 웹 화면 Bluetooth → Info 에서 변경 가능)",
        pairingSteps = listOf(
            "R10 전원을 켜고 폰 설정 → 블루투스에서 'R10' 이름의 기기를 찾아 페어링하세요.",
            "PIN을 물으면 0000 을 입력하세요. (검색이 안 되면 수신기 웹 화면에서 Bluetooth → Discoverable 을 켜세요)",
            "페어링이 끝나면 이 앱으로 돌아와 아래 목록에서 R10을 고르세요.",
        ),
        nmeaSetupTitle = "R10에서 NMEA 출력 켜기 (처음 한 번)",
        nmeaSetupSteps = listOf(
            "R10에 Wi-Fi 또는 USB로 연결해 웹 화면(기본 192.168.142.1)을 엽니다.",
            "I/O Configuration → Port Configuration → Bluetooth 1 선택 (연결이 안 되면 Bluetooth 2, 3 순서로 시도).",
            "Input: Auto, Output: NMEA 로 설정하고 GGA, GST (권장: GSA, ZDA) 를 1 Hz 로 켭니다.",
            "'Maintain configuration when connection dropped' 를 체크하세요.",
            "※ 기본값은 모든 NMEA 가 꺼져 있습니다. 켜지 않으면 연결돼도 데이터가 오지 않습니다.",
        ),
        preConnectChecks = listOf(
            "R10 블루투스 포트의 NMEA 출력(GGA/GST)을 켰어요",
            "다른 컨트롤러(Trimble Access 등)는 같은 포트에 연결하지 않아요",
        ),
        portNote = "블루투스 포트 1부터 시도하고, 안 되면 2·3 으로 바꿔 보세요. 다른 컨트롤러가 같은 블루투스 포트에 붙으면 설정이 바뀌어 NMEA 가 끊길 수 있어요. [현장확인]",
        noDataHint = "연결은 됐지만 위치(GGA)가 없어요. ① 위성 LED ② 다른 컨트롤러 끊기 ③ 도난경보·시작잠금 확인 ④ 웹 UI NMEA(GGA/GST) 켜기 ⑤ 다시 연결",
        repairHint = "연결이 계속 실패해요. 폰 블루투스 설정에서 R10을 삭제한 뒤 다시 페어링해 보세요.",
        requiredSentences = setOf("GGA"),
        recommendedSentences = listOf("GGA", "GST", "GSA", "ZDA"),
        staleAfterMs = 5_000,
        reconnectAfterMs = 20_000,
        reconnect = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 15_000, settleMs = 500, repairHintAfterFailures = 4),
        rtcmWriteChunk = 512,
        rtcmWriteGapMs = 0,
        ggaUploadIntervalMs = 5_000,
        allowInsecureFallback = true,
        connectAttemptsPerCycle = 1,
        nmeaHealAttempts = 2,
        noDataHoldMs = 12_000,
    )

    val SP60 = DeviceProfile(
        id = SP60_ID,
        displayName = "Spectra Precision SP60",
        maker = "Spectra Precision",
        subtitle = "GNSS 수신기 · 블루투스",
        btNameHints = listOf("sp60", "spectra", "sp 60", "sps"),
        defaultPin = "0000",
        pinNote = "PIN 이 문서에 명시되어 있지 않아요. 폰이 물으면 0000, 안 되면 1234 를 시도하세요. [현장확인]",
        pairingSteps = listOf(
            "SP60 전원을 켜고 위성 추적이 시작될 때까지 기다리세요. (추적 LED 확인, 보통 1~2분)",
            "그 다음 폰 설정 → 블루투스에서 SP60 을 찾아 페어링하세요. (추적 전에 연결하면 페어링이 풀릴 수 있어요)",
            "페어링 후 이 앱의 목록에서 SP60 을 고르세요.",
        ),
        nmeaSetupTitle = "SP60 NMEA 출력 확인",
        nmeaSetupSteps = listOf(
            "SP60 의 블루투스 포트에서 NMEA 0183 출력(GGA 필수, GST 권장)이 켜져 있어야 합니다.",
            "설정 방법은 제조사 도구(SPace 앱 또는 Survey Pro 등)를 쓰세요. 앱 안에서 직접 설정하는 절차는 확인되지 않았습니다. [현장확인]",
            "SP60 은 Wi-Fi/셀룰러가 없어요. 보정(RTCM)은 이 폰이 인터넷으로 받아 블루투스로 전달합니다.",
        ),
        preConnectChecks = listOf(
            "SP60 이 위성을 잡기 시작했어요 (추적 LED 확인)",
            "SP60 블루투스 포트에서 NMEA(GGA) 출력을 켰어요",
        ),
        portNote = "장거리(베이스↔로버) 블루투스와 데이터콜렉터 연결은 별개예요. 이 앱은 데이터콜렉터용 SPP 연결을 씁니다. [현장확인]",
        noDataHint = "연결은 됐지만 위치(GGA)가 없어요. ① 위성 LED(추적) ② 다른 폰·컨트롤러·SPace 끊기 ③ 도난경보·시작잠금 확인 ④ NMEA(GGA) 켜기(앱 자동 · 안 되면 SPace/Survey Pro) ⑤ 다시 연결",
        repairHint = "연결이 계속 실패해요. SP60 이 위성을 잡은 뒤, 폰 블루투스 설정에서 SP60 을 삭제하고 다시 페어링하세요.",
        requiredSentences = setOf("GGA"),
        recommendedSentences = listOf("GGA", "GST", "GSA", "ZDA"),
        staleAfterMs = 6_000,
        // NMEA 후보 명령(포트 A/B/C) 전부 돌릴 시간 + 여유. 활성화 중 DEAD 금지 가드와 함께 씀
        reconnectAfterMs = 45_000,
        // 페어링이 풀리는 문제를 피하려고 R10 보다 느리고 완만하게 재시도 (연결 남발 금지)
        reconnect = ReconnectPolicy(
            initialDelayMs = 2_000, maxDelayMs = 20_000, settleMs = 800,
            repairHintAfterFailures = 3, firstConnectDelayMs = 3_000,
        ),
        rtcmWriteChunk = 256,
        rtcmWriteGapMs = 20,
        ggaUploadIntervalMs = 5_000,
        allowInsecureFallback = true,
        connectAttemptsPerCycle = 1,
        nmeaHealAttempts = 4,
        noDataHoldMs = 20_000,
    )

    val all: List<DeviceProfile> = listOf(R10, SP60)

    fun byId(id: String?): DeviceProfile? = all.firstOrNull { it.id == id }
}
