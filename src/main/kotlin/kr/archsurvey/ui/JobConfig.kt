package kr.archsurvey.ui

import kr.archsurvey.core.AntennaMath
import kr.archsurvey.core.GeoidModel
import kr.archsurvey.core.KoreanTmZone
import kr.archsurvey.map.MapBase

/**
 * 0.1.12 작업별 설정: 작업을 열면 이 값으로 화면을 맞추고, 바꾸면 그 작업 이름으로 저장한다.
 * 앱을 다시 켜면 직전 작업 + 이 설정이 그대로 복원된다(「새 작업」을 누르기 전까지).
 * 수신기 연결(블루투스 주소·VRS 계정)은 장비 단위라 작업과 따로 전역 저장(0.1.9 자동 연결 그대로).
 */
data class JobConfig(
    val epsg: Int = KoreanTmZone.CENTRAL.epsg,
    val geoid: GeoidModel = GeoidModel.RECEIVER,
    val mapBase: MapBase = MapBase.SATELLITE_CADASTRAL,
    val antennaModelId: String = "TRIMBLE_R10",
    val poleM: Double = 2.0,
    val refPointId: String = "",
    val epochs: Int = 3,
    /** 폴 높이를 사용자가 정했는지(출처 USER) */
    val poleUserSet: Boolean = false,
) {
    val zone: KoreanTmZone get() = KoreanTmZone.fromEpsg(epsg)

    /** 한 줄 저장 형식 "k=v;k=v". 알 수 없는 키는 무시(이후 버전 호환) */
    fun encode(): String = listOf(
        "v" to "1", "epsg" to epsg.toString(), "geoid" to geoid.id, "map" to mapBase.id, "ant" to antennaModelId,
        "pole" to String.format(java.util.Locale.US, "%.1f", AntennaMath.snapPole(poleM)), "ref" to refPointId,
        "epochs" to epochs.toString(), "poleUser" to poleUserSet.toString(),
    ).joinToString(";") { (k, v) -> k + "=" + v.replace(";", "").replace("=", "") }

    companion object {
        fun decode(s: String?): JobConfig? {
            if (s.isNullOrBlank()) return null
            val m = s.split(';').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            if (m.isEmpty()) return null
            val d = JobConfig()
            return JobConfig(
                epsg = m["epsg"]?.toIntOrNull()?.takeIf { e -> KoreanTmZone.entries.any { it.epsg == e } } ?: d.epsg,
                geoid = GeoidModel.fromId(m["geoid"]),
                mapBase = MapBase.fromId(m["map"]),
                antennaModelId = m["ant"]?.takeIf { it.isNotBlank() } ?: d.antennaModelId,
                poleM = m["pole"]?.toDoubleOrNull()?.let { AntennaMath.snapPole(it) } ?: d.poleM,
                refPointId = m["ref"] ?: "",
                epochs = m["epochs"]?.toIntOrNull()?.coerceIn(1, 30) ?: d.epochs,
                poleUserSet = m["poleUser"]?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** 작업별 설정 저장소(Android = SharedPreferences "job_cfg_<작업>"). 시험에서는 메모리 */
interface JobConfigPlatform {
    fun load(job: String): JobConfig?
    fun save(job: String, cfg: JobConfig)
    fun delete(job: String) {}
}

class MemoryJobConfigPlatform : JobConfigPlatform {
    val map = HashMap<String, String>()
    override fun load(job: String) = JobConfig.decode(map[job])
    override fun save(job: String, cfg: JobConfig) { map[job] = cfg.encode() }
    override fun delete(job: String) { map.remove(job) }
}

/** 경도로 지역(원점) 고르기 — SmartTopo 「자동」과 같은 뜻. 경계 126/128/130°E */
object ZoneAuto {
    fun fromLon(lon: Double?): KoreanTmZone? = when {
        lon == null || lon.isNaN() -> null
        lon < 124.0 || lon > 132.5 -> null
        lon < 126.0 -> KoreanTmZone.WEST
        lon < 128.0 -> KoreanTmZone.CENTRAL
        lon < 130.0 -> KoreanTmZone.EAST
        else -> KoreanTmZone.EAST_SEA
    }
}
