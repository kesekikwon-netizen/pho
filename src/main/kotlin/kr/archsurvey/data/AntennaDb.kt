package kr.archsurvey.data

import kr.archsurvey.core.AntennaModel
import kr.archsurvey.core.RefPoint
import kr.archsurvey.device.DeviceProfiles
import org.json.JSONObject

/** assets/antenna_db.json (antenna-height.md §5) 로더 */
object AntennaDb {
    private val ID_MAP = mapOf(DeviceProfiles.R10_ID to "TRIMBLE_R10", DeviceProfiles.SP60_ID to "SPECTRA_SP60")

    fun parse(json: String): Map<String, AntennaModel> {
        val root = JSONObject(json)
        val models = root.getJSONObject("models")
        val adArr = root.optJSONArray("adapters")
        val allAdapters = (0 until (adArr?.length() ?: 0)).map {
            val a = adArr!!.getJSONObject(it)
            kr.archsurvey.core.AntennaAdapter(a.getString("id"), a.optString("model"), if (a.isNull("heightM") || !a.has("heightM")) null else a.getDouble("heightM"),
                a.optString("includedInRefPoint").takeIf { s -> s.isNotEmpty() }, a.optBoolean("doNotAddSeparately", false))
        }
        val out = LinkedHashMap<String, AntennaModel>()
        for (key in models.keys()) {
            val o = models.getJSONObject(key)
            val arr = o.getJSONArray("refPoints")
            val refs = (0 until arr.length()).map {
                val r = arr.getJSONObject(it)
                RefPoint(
                    id = r.getString("id"), labelKo = r.getString("labelKo"),
                    pcoV = r.getDouble("pcoV"), pcoH = r.optDouble("pcoH", 0.0),
                    slant = r.optString("method") == "slant",
                    tapeCorrection = if (r.has("tapeHousingCorrection")) r.getDouble("tapeHousingCorrection") else null,
                    hidden = r.optBoolean("hidden", false), confidence = r.optString("confidence"),
                )
            }
            out[key] = AntennaModel(key, o.optString("label", key), o.getString("defaultRefPoint"), refs, o.optBoolean("nmeaCarriesAntennaHeight", false),
                allAdapters.filter { it.model.equals(if (key == "TRIMBLE_R10") "r10" else "sp60", true) })
        }
        return out
    }

    /** DeviceProfile.id → 안테나 DB 키 (TRIMBLE_R10 / SPECTRA_SP60) */
    fun antennaIdForProfile(deviceProfileId: String?): String? = ID_MAP[deviceProfileId]

    /** DeviceProfile.id -> 안테나 모델 */
    fun forDevice(all: Map<String, AntennaModel>, deviceId: String?): AntennaModel? = all[ID_MAP[deviceId]]

    /** 안테나 DB 키 → DeviceProfile.id (양방향 표시 일치용) */
    fun profileIdForAntenna(antennaModelId: String?): String? =
        ID_MAP.entries.firstOrNull { it.value == antennaModelId }?.key
}
