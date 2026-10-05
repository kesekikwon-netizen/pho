package kr.archsurvey.ntrip

import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles

/**
 * 수신기별 RTCM 버전 호환. 사용자 확정(0.1.10): SP60 최대 RTCM 3.2(MSM), R10은 3.4 가능.
 * 마운트 이름·소스테이블 format 문자열로 추정(캐스터마다 표기가 다름).
 */
object RtcmMountCompat {
    /** 지원하는 RTCM 3.x 소수 버전 상한. SP60=2 → 3.2, R10=4 → 3.4 */
    fun maxMinor(profile: DeviceProfile): Int = when (profile.id) {
        DeviceProfiles.SP60_ID -> 2
        DeviceProfiles.R10_ID -> 4
        else -> 4
    }

    /** 텍스트에 RTCM 3.N 이 보이면 N, 없으면 null. "RTCM34"·"RTCM 3.4"·"3.4" 등 */
    fun detectMinor(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        Regex("""(?i)RTCM\s*3[._]?(\d)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(?:^|[^0-9])3\.([0-4])(?:[^0-9]|$)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun detectMinor(mount: String, format: String = "", formatDetails: String = ""): Int? =
        detectMinor(mount) ?: detectMinor(format) ?: detectMinor(formatDetails)

    fun isTooNew(profile: DeviceProfile, mount: String, format: String = "", formatDetails: String = ""): Boolean {
        val m = detectMinor(mount, format, formatDetails) ?: return false
        return m > maxMinor(profile)
    }

    /** 경고 문구. 호환이면 null */
    fun warnIfIncompatible(profile: DeviceProfile, mount: String, format: String = "", formatDetails: String = ""): String? {
        if (!isTooNew(profile, mount, format, formatDetails)) return null
        val max = maxMinor(profile)
        return when (profile.id) {
            DeviceProfiles.SP60_ID ->
                "SP60은 RTCM 3.4를 지원하지 않아요(최대 3.2 MSM). 이 마운트($mount) 대신 VRS-RTCM32·VRS-RTCM31 같은 3.1/3.2를 고르세요."
            else ->
                "${profile.displayName}은 RTCM 3.$max 까지만 확인됐어요. 마운트 $mount 은(는) 더 높은 버전으로 보여요. 3.1/3.2를 권장해요."
        }
    }
}
