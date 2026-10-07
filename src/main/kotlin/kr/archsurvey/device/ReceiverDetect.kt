package kr.archsurvey.device

/**
 * 0.1.32 수신기 자동 인식. 폰에 등록(페어링)된 블루투스 기기 중 측량 수신기를 고르고 종류(R10/SP60)를 정한다.
 *
 * 순서: ① 이 주소로 전에 위치를 받아 확인된 종류(기억) ② 이름의 모델 글자(sp60 / r10) ③ 제조사 글자(spectra / trimble·trmb).
 * NMEA 신호: `$PASH…` = Spectra·Ashtech 계열(SP60), `$PTNL…` = Trimble(R10). 연결 중 보이면 다음 연결부터 그 종류로 기억한다.
 * 근거: bluetooth-spec·field-learning §1.5(SP60 `$PASH` 명령 체계), DeviceProfile.btNameHints. 실기기 이름은 [현장확인].
 */
object ReceiverDetect {
    data class Candidate(val name: String, val address: String, val profile: DeviceProfile)

    /** 자동 연결 판단 결과 */
    sealed class Decision {
        /** 저장된 마지막 수신기가 폰에 등록돼 있음 → 그대로 연결. [fallbacks] = 응답이 없을 때 차례로 시도할 다른 수신기 */
        data class UseSaved(val target: Candidate, val fallbacks: List<Candidate>) : Decision()
        /** 저장된 것이 없거나(또는 등록이 풀림) 수신기가 하나뿐 → 그것으로 연결 */
        data class AutoPick(val target: Candidate, val savedMissing: Boolean) : Decision()
        /** 수신기가 여럿 → 사용자가 고른다 */
        data class Ambiguous(val candidates: List<Candidate>, val savedMissing: Boolean) : Decision()
        /** 등록된 수신기가 없음 */
        data class None(val savedMissing: Boolean) : Decision()
    }

    private val MODEL_SP60 = listOf("sp60", "sp 60", "sp-60", "sp_60")
    private val MODEL_R10 = listOf("r10")

    /** 이름으로 종류 판단. 모델 글자가 제조사 글자보다 먼저. 모르면 null */
    fun fromName(name: String?): DeviceProfile? {
        if (name.isNullOrBlank()) return null
        val n = name.lowercase()
        val sp = MODEL_SP60.any { it in n }
        val r10 = MODEL_R10.any { Regex("(^|[^a-z0-9])$it([^0-9]|$)").containsMatchIn(n) }
        if (sp && !r10) return DeviceProfiles.SP60
        if (r10 && !sp) return DeviceProfiles.R10
        if (sp && r10) return null
        // 제조사 글자만(모델 글자 r10 은 위 정규식으로만 — "R100" 같은 이름을 R10 으로 오인하지 않게)
        val spMaker = makerHints(DeviceProfiles.SP60).any { it in n }
        val r10Maker = makerHints(DeviceProfiles.R10).any { it in n }
        return when {
            spMaker && !r10Maker -> DeviceProfiles.SP60
            r10Maker && !spMaker -> DeviceProfiles.R10
            else -> null
        }
    }

    private fun makerHints(p: DeviceProfile) = p.btNameHints.map { it.lowercase() }.filter { it !in MODEL_SP60 && it !in MODEL_R10 }

    /** NMEA 한 덩어리(문장 여러 줄 가능)에서 제조사 고유 문장으로 종류 판단. 모르면 null */
    fun fromNmea(text: String): String? = when {
        "\$PASH" in text -> DeviceProfiles.SP60_ID
        "\$PTNL" in text -> DeviceProfiles.R10_ID
        else -> null
    }

    /** 등록된 기기 → 수신기 후보(종류를 알 수 있는 것만). [remembered]: 주소 → 확인된 종류 id */
    fun candidates(bonded: List<Pair<String, String>>, remembered: (String) -> String? = { null }): List<Candidate> =
        bonded.mapNotNull { (name, addr) ->
            val p = DeviceProfiles.byId(remembered(addr)) ?: fromName(name)
            p?.let { Candidate(name, addr, it) }
        }.distinctBy { it.address }

    /**
     * 앱 시작 때 무엇에 연결할지.
     * [savedDeviceId]/[savedAddress] = 마지막에 쓴 수신기. 저장 주소가 등록 목록에 있으면 이름을 몰라도 그것을 쓴다(사용자가 고른 기기).
     */
    fun decide(
        savedDeviceId: String?, savedAddress: String?,
        bonded: List<Pair<String, String>>, remembered: (String) -> String? = { null },
    ): Decision {
        val cands = candidates(bonded, remembered)
        val savedBonded = savedAddress?.let { a -> bonded.firstOrNull { it.second == a } }
        if (savedBonded != null) {
            val prof = DeviceProfiles.byId(remembered(savedBonded.second)) ?: DeviceProfiles.byId(savedDeviceId)
                ?: fromName(savedBonded.first) ?: DeviceProfiles.R10
            val target = Candidate(savedBonded.first, savedBonded.second, prof)
            return Decision.UseSaved(target, cands.filter { it.address != target.address })
        }
        val missing = savedAddress != null
        return when (cands.size) {
            0 -> Decision.None(missing)
            1 -> Decision.AutoPick(cands.single(), missing)
            else -> Decision.Ambiguous(cands, missing)
        }
    }
}
