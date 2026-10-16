package kr.archsurvey.pg.export

import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.r10.GeoCam
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.17 project.json — 작업 요약(설계 17 §10, 우리 형식). 사람이 읽고 다른 프로그램이 읽을 수 있게 숫자만.
 * 비밀값(NTRIP 아이디·비밀번호)은 넣지 않는다.
 */
object ProjectJson {
    const val FILE = "project.json"
    const val FORMAT = "archsurvey.pg.project/1"

    fun build(s: Session, geo: GeoCam.Result, seenTargets: Set<Int>, posesUsed: Int, gcpRmse: Double?, gcpLooRmse: Double? = null, appVersion: String = ""): JSONObject = JSONObject().apply {
        put("format", FORMAT); put("app", "kr.archsurvey.pg"); if (appVersion.isNotEmpty()) put("appVersion", appVersion)
        put("name", s.name); put("created", s.created)
        put("images", JSONObject().put("count", s.stills.size).put("withPose", s.stills.count { it.pose != null })
            .put("withR10", s.stills.count { it.gnss != null }).put("r10Fixed", s.stills.count { it.gnss?.fixed == true })
            .put("reasons", JSONObject().apply { s.stills.groupingBy { it.reason ?: "manual" }.eachCount().forEach { (k, v) -> put(k, v) } }))
        put("crs", JSONObject().put("epsg", if (s.gcps.isNotEmpty()) s.epsg else geo.epsg).put("height", s.heightRef)
            .put("heightApplied", JSONObject().apply { geo.cams.values.groupingBy { it.hRef }.eachCount().forEach { (k, v) -> put(k, v) } }))
        put("targets", JSONObject().put("seen", JSONArray(seenTargets.sorted())).put("withCoords", JSONArray(s.gcps.map { it.target }.sorted()))
            .put("gcpFitRmseM", gcpRmse ?: JSONObject.NULL).put("gcpLooRmseM", gcpLooRmse ?: JSONObject.NULL).put("posesUsed", posesUsed))
        put("r10", JSONObject().apply {
            put("leverM", JSONArray().put(s.lever.up).put(s.lever.fwd).put(s.lever.right)); put("apcM", s.apcM)
            put("fits", JSONArray().apply { geo.fits.forEach { (k, f) -> put(fitJson(k, f)) } })
            put("camTiers", JSONObject().apply { geo.cams.values.groupingBy { it.tier.name }.eachCount().forEach { (k, v) -> put(k, v) } })
            put("note", "사진 위치는 GPS 보조값. 절대 좌표는 기준점(Target #N)이 정함")
        })
    }

    fun fitJson(key: String, f: GeoCam.Fit): JSONObject = JSONObject().put("arSession", key).put("tier", f.tier.name).put("samples", f.n).put("used", f.used)
        .put("rmseM", f.rmse ?: JSONObject.NULL).put("maxResidualM", f.maxRes ?: JSONObject.NULL).put("lagMs", f.lagMs)
        .put("yawDeg", f.yaw?.let { Math.toDegrees(it) } ?: JSONObject.NULL).put("pathM", f.pathM).put("reason", f.reason)
}
