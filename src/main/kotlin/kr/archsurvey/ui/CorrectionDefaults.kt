package kr.archsurvey.ui

import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles

/**
 * 0.1.29 T19 기기별 보정 기본값(사용자 결정 10-06): R10 → 국토지리정보원 RTS1 + RTCM 3.4 마운트, SP60 → RTS2 + RTCM 3.2 마운트.
 * 마운트 이름 VRS-RTCM34 / VRS-RTCM32 는 저장소 문서에 있는 이름(KnownMounts·deep-research O1: 한국측량학회지 2026-08 논문 권장 조합)이며
 * 현행 소스테이블 이름은 [현장확인] — 목록을 받았을 때 이름이 없으면 목록에서 고르라고 알린다(이름을 지어내지 않음).
 * 적용 조건: 사용자가 보정 설정을 바꾼 적 없음 + 연결 성공 이력 없음 + 지금 값이 「손대지 않은」 값. RTS3(G-VRS)는 기본값이 아니다.
 */
data class CorrectionDefault(val serviceId: String, val host: String, val port: Int, val mount: String)

object DeviceCorrectionDefaults {
    val R10 = CorrectionDefault("rts1", "RTS1.ngii.go.kr", 2101, "VRS-RTCM34")
    val SP60 = CorrectionDefault("rts2", "RTS2.ngii.go.kr", 2101, "VRS-RTCM32")

    fun forProfile(id: String?): CorrectionDefault? = when (id) {
        DeviceProfiles.R10_ID -> R10
        DeviceProfiles.SP60_ID -> SP60
        else -> null
    }

    /** 앱 출고값(0.1.28 까지: RTS1 + VRS-RTCM31) 또는 기기 기본값 그대로 = 사용자가 손대지 않은 값 */
    fun looksUnset(form: NtripForm): Boolean {
        val h = form.host.trim().lowercase(); val m = form.mount.trim().lowercase()
        if (h.isEmpty()) return true
        val untouched = listOf(
            kr.archsurvey.ntrip.NtripPresets.VRS.host to kr.archsurvey.ntrip.NtripPresets.VRS.mountPoint,
            R10.host to R10.mount, SP60.host to SP60.mount,
        )
        return untouched.any { (uh, um) -> uh.lowercase() == h && (m.isEmpty() || um.lowercase() == m) } && form.port.trim() == "2101"
    }
}

/**
 * 0.1.29 T17 국토지리정보원 G-VRS (RTS3, 시범). 출처: 국토지리정보원 「G-VRS 서비스 사용자 가이드」(2025-10).
 * RTS3.ngii.go.kr:2101 · 기존 ID(RTS1·RTS2·통합회원) / 비밀번호 ngii · 마운트 = 지역명(소스테이블) 또는 GVRS(서버가 GGA 위치로 가까운 격자 선택).
 * 선택 항목일 뿐 기본값 아님. 품질 판정(Fixed·age·GST)은 RTS1 과 같다(완화 없음). RTCM 버전은 가이드에 없음 [현장확인].
 */
object Gvrs {
    const val ID = "gvrs"
    const val HOST = "RTS3.ngii.go.kr"
    const val PORT = 2101
    /** 가이드에 적힌 유일한 고정 마운트 이름. 지역명 마운트는 실제 소스테이블에서만 고른다 */
    const val MOUNT = "GVRS"
    /** 가이드 안내 비밀번호 — 이 서비스를 고를 때만 미리 채움(수정 가능). 다른 서버 기본값에 넣지 않는다 */
    const val GUIDE_PASSWORD = "ngii"
    const val NOTICE = "시범 서비스 · 공공측량 성과에는 사용 불가"
    const val AUTH_HINT = "RTS1·RTS2 가입 ID인지, 비밀번호 ngii인지 확인"
    const val GGA_REQUIRED = "GVRS 마운트는 내 위치(GGA)를 보내야 보정이 와요 — GGA 전송은 켜 둬요"
    const val SP60_RTCM_LINE = "G-VRS의 RTCM 버전은 공식 안내에 없어요 [현장확인] · SP60은 3.2까지 — 고정이 안 되면 RTS2 VRS-RTCM32를 쓰세요"
    const val GVRS_NOTE = "추천 · 서버가 가까운 격자 자동 선택(GGA 필요) · 이동 측량"
    const val REGION_NOTE = "가까운 격자점 직접 선택 · 정지 측량"

    fun isHost(host: String) = host.trim().equals(HOST, ignoreCase = true)
    fun isGvrsMount(mount: String) = mount.trim().equals(MOUNT, ignoreCase = true)
    /** GVRS 마운트는 GGA 전송 필수(지역명 마운트는 강제하지 않음 [현장확인]) */
    fun requiresGga(form: NtripForm) = form.enabled && isHost(form.host) && isGvrsMount(form.mount)
}

/**
 * 0.1.29 T17-2 SP60 펌웨어 5.26: 5.26 미만은 RTK HRMS 가 11 mm 밑으로 내려가지 않는다(V5.26 릴리스 노트).
 * σH 기준이 11 mm 미만이고 기기가 SP60 이면 안내만 한다(펌웨어 버전은 앱이 읽지 못함 [미확인] → 막지 않음). 기본 20 mm 는 해당 없음.
 */
object Sp60Firmware {
    const val HRMS_FLOOR_M = 0.011
    const val NOTICE = "SP60 펌웨어 5.26 이상 필요"
    fun notice(profile: DeviceProfile, sigmaThresholdM: Double): String? =
        if (profile.id == DeviceProfiles.SP60_ID && sigmaThresholdM < HRMS_FLOOR_M - 1e-9)
            "$NOTICE — 5.26 미만은 수평 정밀도가 11 mm 밑으로 표시되지 않아 계속 ‘주의’가 될 수 있어요" else null
}
