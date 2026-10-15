package kr.archsurvey.bt

import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.ReceiverDetect

/*
 * 0.1.40 (사진 기준점 0.1.2 와 같은 파일) 앱 안에서 수신기 찾기 → 눌러서 등록(PIN 자동) → 바로 연결.
 * 안드로이드 없이 시험할 수 있는 순수 규칙. 안드로이드 부분은 BtPairing.kt.
 * 근거: developer.android.com/reference/android/bluetooth/BluetoothDevice (createBond · ACTION_PAIRING_REQUEST ·
 * EXTRA_PAIRING_VARIANT · setPin: targetSdk 36 이하는 BLUETOOTH_CONNECT 로 됨, 37+ 는 PRIVILEGED ·
 * setPairingConfirmation 은 PRIVILEGED 전용 → SSP 숫자 확인/동의 창은 앱이 대신 누를 수 없음).
 */

/** 안드로이드 상수와 같은 값(시험에서 android.jar 없이 쓰려고) */
object PairConst {
    const val VARIANT_PIN = 0              // BluetoothDevice.PAIRING_VARIANT_PIN (공개)
    const val VARIANT_PASSKEY = 1          // 숨은 상수
    const val VARIANT_PASSKEY_CONFIRMATION = 2 // 공개(확인은 PRIVILEGED 만)
    const val VARIANT_CONSENT = 3          // 숨은 상수(SSP Just Works)
    const val VARIANT_DISPLAY_PASSKEY = 4
    const val VARIANT_DISPLAY_PIN = 5
    const val VARIANT_OOB_CONSENT = 6
    const val VARIANT_PIN_16_DIGITS = 7
    const val BOND_NONE = 10; const val BOND_BONDING = 11; const val BOND_BONDED = 12
    /** 숨은 엑스트라 "android.bluetooth.device.extra.REASON"(ACTION_BOND_STATE_CHANGED, BOND_NONE 일 때) */
    const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"
    const val REASON_AUTH_FAILED = 1; const val REASON_AUTH_REJECTED = 2; const val REASON_AUTH_CANCELED = 3
    const val REASON_REMOTE_DEVICE_DOWN = 4; const val REASON_DISCOVERY_IN_PROGRESS = 5; const val REASON_AUTH_TIMEOUT = 6
    const val REASON_REPEATED_ATTEMPTS = 7; const val REASON_REMOTE_AUTH_CANCELED = 8; const val REASON_REMOVED = 9
    const val DEFAULT_PIN = "1234"
    /** Trimble R10 공장 기본 PIN(help.fieldsystems.trimble.com/r10/receiver-settings-configure-bt-…: "By default, the pairing code is 0000") */
    const val R10_FACTORY_PIN = "0000"
}

/** 주변에서 찾은(또는 이미 등록된) 기기 */
data class FoundDevice(val name: String, val address: String, val bonded: Boolean, val rssi: Int? = null) {
    val model: DeviceProfile? get() = ReceiverDetect.fromName(name)
    val isReceiver: Boolean get() = model != null
}

object PairList {
    /** 등록된 수신기 → 찾은 수신기(신호 센 순) → 그 밖의 이름 있는 기기. 같은 주소는 하나로(등록 정보 우선) */
    fun sort(found: Collection<FoundDevice>): List<FoundDevice> =
        found.groupBy { it.address.uppercase() }.values.map { g -> g.firstOrNull { it.bonded }?.let { b -> b.copy(rssi = g.mapNotNull { it.rssi }.maxOrNull() ?: b.rssi, name = g.firstOrNull { it.name != it.address }?.name ?: b.name) } ?: g.maxByOrNull { it.rssi ?: -999 }!! }
            .filter { it.name.isNotBlank() }
            .sortedWith(compareBy<FoundDevice>({ if (it.isReceiver) 0 else 1 }, { if (it.bonded) 0 else 1 }, { -(it.rssi ?: -999) }, { it.name }))

    /** 시도할 PIN: 설정값(기본 1234) → R10 공장값 0000 (겹치면 하나) */
    fun pins(setting: String?): List<String> = listOf(setting?.trim()?.takeIf { it.length in 4..16 && it.all(Char::isDigit) } ?: PairConst.DEFAULT_PIN, PairConst.R10_FACTORY_PIN).distinct()
}

enum class PairStep { IDLE, BONDING, WAIT_USER, CONNECTING, CONNECTING_UNBONDED, DONE, FAILED }

/** 실패 안내 단계: 1회 = 전원 껐다 켜기, 2회 이상 = R10 웹 화면에서 기록 확인 */
enum class PairFix { NONE, RETRY, CANCELLED, POWER_CYCLE, CLEAR_RECORD, NEAR, PERMISSION, BT_OFF, LOCATION_OFF, R10_WEB_NMEA }

/** 기계가 플랫폼에 시키는 일 */
sealed class PairAction {
    data class CreateBond(val address: String) : PairAction()
    /** 이미 등록됨 또는 방금 등록됨 → 기존 연결 순서(R10 insecure 먼저 등)로 연결 */
    data class Connect(val address: String, val bonded: Boolean) : PairAction()
    object None : PairAction()
}

/**
 * 한 기기 등록 상태 기계. 사용: start → (플랫폼 이벤트) onPairingRequest / onBondState / onCreateBondResult / onTimeout.
 * - PIN 방식 요청 → 지금 차례 PIN 을 돌려줌(플랫폼이 setPin + abortBroadcast)
 * - 숫자 확인·동의(SSP) → 앱이 대신 못 누름: WAIT_USER(「화면의 등록을 누르세요」)
 * - 등록 실패(BONDING → NONE): 다음 PIN 으로 한 번 더(1234 → 0000). 다 실패하면 「등록 없이 연결」(insecure SPP) 한 번 → 그래도 안 되면 안내
 * - 사용자가 취소(AUTH_CANCELED) → 재시도 안 함
 */
class PairMachine(
    val device: FoundDevice, private val pins: List<String> = PairList.pins(null), private val tryUnbonded: Boolean = true,
    /** 0.1.40 현장(12:29): R10 은 폰 등록이 계속 「등록할 수 없습니다」 → 등록 없이 insecure 연결을 먼저, 안 될 때만 PIN 등록 */
    private val directFirst: Boolean = device.model?.id == kr.archsurvey.device.DeviceProfiles.R10_ID,
) {
    /** 이번 바퀴에서 등록 없이 연결을 해 봤는지 / 등록을 해 봤는지 */
    var directTried = false; private set
    var bondTried = false; private set
    /** 0.1.41 현장(12:43): 등록 없이 연결은 됐는데 위치(GGA)가 안 옴 */
    var directNoData = false; private set
    var step = PairStep.IDLE; private set
    var fix = PairFix.NONE; private set
    /** 이번 기기에서 실패한 바퀴 수(다시 누르면 안내가 한 단계 깊어짐) */
    var failedRounds = 0; private set
    var pinIndex = 0; private set
    var pinSent: String? = null; private set
    var lastReason: Int? = null; private set
    val currentPin: String get() = pins[pinIndex.coerceAtMost(pins.lastIndex)]
    val log = mutableListOf<String>()

    fun start(alreadyBonded: Boolean): PairAction {
        fix = PairFix.NONE; pinIndex = 0; pinSent = null; lastReason = null; directTried = false; bondTried = false; directNoData = false
        if (alreadyBonded) { step = PairStep.CONNECTING; log += "이미 등록됨 → 바로 연결"; return PairAction.Connect(device.address, true) }
        if (directFirst) { step = PairStep.CONNECTING_UNBONDED; directTried = true; log += "등록 없이 바로 연결(insecure)"; return PairAction.Connect(device.address, false) }
        step = PairStep.BONDING; bondTried = true; log += "등록 시작"
        return PairAction.CreateBond(device.address)
    }

    /** createBond() 가 false(바로 실패) */
    fun onCreateBondResult(ok: Boolean): PairAction = if (ok) PairAction.None else failBond(null)

    /** ACTION_PAIRING_REQUEST. 돌려준 PIN 을 플랫폼이 setPin 한다(null = 시스템 창에 맡김) */
    fun onPairingRequest(variant: Int): String? = when (variant) {
        PairConst.VARIANT_PIN, PairConst.VARIANT_PIN_16_DIGITS -> { pinSent = currentPin; log += "PIN 요청 → $currentPin 자동 입력"; step = PairStep.BONDING; currentPin }
        else -> { step = PairStep.WAIT_USER; log += "확인 방식($variant) → 시스템 창"; null }
    }

    fun onBondState(state: Int, prev: Int, reason: Int? = null): PairAction = when {
        state == PairConst.BOND_BONDED && step != PairStep.DONE -> { step = PairStep.CONNECTING; log += "등록됨 → 연결"; PairAction.Connect(device.address, true) }
        state == PairConst.BOND_NONE && (step == PairStep.BONDING || step == PairStep.WAIT_USER) -> failBond(reason)
        else -> PairAction.None
    }

    fun onTimeout(): PairAction = if (step == PairStep.BONDING || step == PairStep.WAIT_USER) failBond(PairConst.REASON_AUTH_TIMEOUT) else PairAction.None

    private fun failBond(reason: Int?): PairAction {
        lastReason = reason; log += "등록 실패(reason=${reason ?: "?"})"
        if (reason == PairConst.REASON_AUTH_CANCELED || reason == PairConst.REASON_REMOTE_AUTH_CANCELED) {
            step = PairStep.FAILED; fix = PairFix.CANCELLED; return PairAction.None
        }
        // 다음 PIN 으로 한 번 더(PIN 을 보냈든 아니든 — 수신기가 PIN 방식으로 바뀔 수 있음)
        if (pinIndex < pins.lastIndex && reason != PairConst.REASON_REMOTE_DEVICE_DOWN) {
            pinIndex++; pinSent = null; step = PairStep.BONDING; log += "다른 PIN(${currentPin}) 으로 다시"
            return PairAction.CreateBond(device.address)
        }
        if (tryUnbonded && !directTried && reason != PairConst.REASON_REMOTE_DEVICE_DOWN) {
            directTried = true
            step = PairStep.CONNECTING_UNBONDED; log += "등록 없이 연결(insecure SPP) 시도"
            return PairAction.Connect(device.address, false)
        }
        return giveUp(reason)
    }

    /** 연결 결과(연결 단계에서만 의미) */
    fun onConnectResult(ok: Boolean, noData: Boolean = false): PairAction {
        if (step != PairStep.CONNECTING && step != PairStep.CONNECTING_UNBONDED) return PairAction.None
        if (!ok && noData && step == PairStep.CONNECTING_UNBONDED) { directNoData = true; log += "등록 없이 연결됐지만 위치(GGA) 없음" }
        if (ok) { step = PairStep.DONE; fix = PairFix.NONE; log += "연결됨"; return PairAction.None }
        if (step == PairStep.CONNECTING_UNBONDED) {
            if (!bondTried) { bondTried = true; step = PairStep.BONDING; log += "등록 없이 안 됨 → PIN 등록"; return PairAction.CreateBond(device.address) }
            return giveUp(lastReason)
        }
        step = PairStep.FAILED; fix = PairFix.RETRY; failedRounds++; return PairAction.None
    }

    private fun giveUp(reason: Int?): PairAction {
        failedRounds++
        step = PairStep.FAILED
        fix = when {
            directNoData && device.model?.id == kr.archsurvey.device.DeviceProfiles.R10_ID -> PairFix.R10_WEB_NMEA
            reason == PairConst.REASON_REMOTE_DEVICE_DOWN && failedRounds == 1 -> PairFix.NEAR
            failedRounds <= 1 -> PairFix.POWER_CYCLE
            else -> PairFix.CLEAR_RECORD
        }
        return PairAction.None
    }

    /** 「다시」: 실패 바퀴 수는 이어 간다(안내가 깊어지도록) */
    fun retry(alreadyBonded: Boolean): PairAction = start(alreadyBonded)
    fun cancel() { if (step != PairStep.DONE) { step = PairStep.IDLE } }
}

/** 단계·안내 한국어 */
object PairText {
    fun step(s: PairStep, m: PairMachine?): String = when (s) {
        PairStep.IDLE -> ""
        PairStep.WAIT_USER -> "① 폰 화면에 뜬 창에서 「등록」(또는 「확인」)을 눌러 주세요"
        PairStep.CONNECTING -> "② 연결 중 · 위치가 오면 끝"
        PairStep.BONDING -> if (m?.directNoData == true) "① 등록 없이 연결은 됐지만 위치가 안 와 등록(PIN ${m.currentPin})으로 다시" else "① 등록 중 · PIN ${m?.currentPin ?: PairConst.DEFAULT_PIN} 자동 입력"
        PairStep.CONNECTING_UNBONDED -> if (m?.bondTried == true) "② 등록이 안 돼 등록 없이 연결해 보는 중" else "① 등록 없이 바로 연결 중(PIN 필요 없음)"
        PairStep.DONE -> "✓ 연결됐어요 · 다음부터 자동 연결"
        PairStep.FAILED -> "등록·연결 실패"
    }
    fun fix(f: PairFix, deviceName: String = "수신기"): Pair<String, String>? {
        val r10 = ReceiverDetect.fromModelName(deviceName)?.id == kr.archsurvey.device.DeviceProfiles.R10_ID
        return when (f) {
            PairFix.NONE -> null
            PairFix.RETRY -> "연결이 안 됐어요" to "등록은 됐어요. 「다시」를 누르세요. 수신기가 켜져 있고 위성을 잡은 뒤면 더 잘 돼요."
            PairFix.CANCELLED -> "등록을 취소했어요" to "다시 누르고, 폰 화면에 창이 뜨면 「등록」을 눌러 주세요."
            PairFix.NEAR -> "수신기가 응답하지 않아요" to "수신기가 켜져 있는지, 폰과 2 m 안에 있는지 확인하고 「다시」를 누르세요."
            PairFix.POWER_CYCLE -> (if (r10) "R10 전원을 껐다 켜고 다시 눌러 주세요" else "수신기 전원을 껐다 켜고 다시 눌러 주세요") to
                "등록 없이 연결도, PIN 1234·0000 등록도 안 됐어요. 수신기가 예전 폰 기록을 들고 있거나, 켠 직후에만 등록을 받는 설정일 수 있어요. 켜고 1분 안에 「다시」."
            PairFix.CLEAR_RECORD -> (if (r10) "R10 웹 화면에서 블루투스 기록을 확인해 주세요" else "수신기 블루투스 설정을 확인해 주세요") to
                (if (r10) "폰 Wi-Fi 를 R10 에 연결 → 브라우저 192.168.142.1 › Bluetooth › Remotes 에서 이 폰 연결 끊기, Configuration 에서 PIN·Discoverable 확인 → 고급에서 앱 PIN 을 같은 값으로." else "수신기 PIN 을 확인해 고급 › 수신기 PIN 에 넣고 다시 눌러 주세요.")
            PairFix.R10_WEB_NMEA -> "R10 이 이 연결로 위치를 보내지 않아요" to
                "등록 없이 연결은 됐지만 위치(GGA)가 안 오고, 등록(PIN 1234·0000)도 안 됐어요. 폰 Wi-Fi 를 R10 에 연결 → 브라우저 192.168.142.1 › I/O Configuration › Port Configuration › Bluetooth(1·2·3 모두) 에서 NMEA GGA(가능하면 GST·GSA·GSV) 1 Hz 켜고 「Maintain configuration when connection dropped」 체크 → 「다시」."
            PairFix.PERMISSION -> "주변 기기 권한이 필요해요" to "수신기를 찾고 등록하려면 「주변 기기」 권한을 허용해 주세요."
            PairFix.BT_OFF -> "블루투스가 꺼져 있어요" to "폰 블루투스를 켜고 다시 눌러 주세요."
            PairFix.LOCATION_OFF -> "위치(GPS)가 꺼져 있어요" to "안드로이드 11 이하에서는 주변 기기를 찾을 때 위치가 켜져 있어야 해요."
        }
    }
}
