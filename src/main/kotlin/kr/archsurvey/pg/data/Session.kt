package kr.archsurvey.pg.data

import kr.archsurvey.pg.tag.TagDetection
import org.json.JSONArray
import org.json.JSONObject

/** 사진 한 장(원본 JPG 그대로) + ARCore 자세(세계←카메라, OpenGL 축) + 내부표정(이 JPG 화소 기준) + 검출 표적 */
data class Still(
    val id: Int, val file: String, val w: Int, val h: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    /** tx,ty,tz,qx,qy,qz,qw — 없으면(ARCore 없음) null */
    val pose: DoubleArray?,
    val time: Long,
    val tags: List<TagDetection>,
)

/** GCP: 표적 번호 N(Target #N) → E, N, H */
data class Gcp(val target: Int, val e: Double, val n: Double, val h: Double)

data class Session(
    val name: String,
    val created: Long,
    val stills: List<Still> = emptyList(),
    val gcps: List<Gcp> = emptyList(),
    val epsg: Int = 5187,
    /** 좌표 없음 축척: 표적 검은 네모 한 변(m) / 막대 길이(m) — 둘 다 선택 목록에서만 */
    val tagSizeM: Double? = null,
    val barM: Double? = null,
    val arcore: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1); put("name", name); put("created", created); put("epsg", epsg); put("arcore", arcore)
        tagSizeM?.let { put("tagSizeM", it) }; barM?.let { put("barM", it) }
        put("gcps", JSONArray().apply { gcps.forEach { g -> put(JSONObject().put("t", g.target).put("e", g.e).put("n", g.n).put("h", g.h)) } })
        put("stills", JSONArray().apply {
            stills.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("file", s.file); put("w", s.w); put("h", s.h)
                    put("fx", s.fx); put("fy", s.fy); put("cx", s.cx); put("cy", s.cy); put("time", s.time)
                    s.pose?.let { p -> put("pose", JSONArray().apply { p.forEach { put(it) } }) }
                    put("tags", JSONArray().apply {
                        s.tags.forEach { t ->
                            put(JSONObject().put("id", t.id).put("ham", t.hamming).put("cx", t.cx).put("cy", t.cy)
                                .put("c", JSONArray().apply { t.corners.forEach { put(it) } }))
                        }
                    })
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val st = o.optJSONArray("stills") ?: JSONArray()
            val gs = o.optJSONArray("gcps") ?: JSONArray()
            return Session(
                name = o.getString("name"), created = o.getLong("created"), epsg = o.optInt("epsg", 5187), arcore = o.optBoolean("arcore", true),
                tagSizeM = if (o.has("tagSizeM")) o.getDouble("tagSizeM") else null,
                barM = if (o.has("barM")) o.getDouble("barM") else null,
                gcps = (0 until gs.length()).map { gs.getJSONObject(it).let { g -> Gcp(g.getInt("t"), g.getDouble("e"), g.getDouble("n"), g.getDouble("h")) } },
                stills = (0 until st.length()).map { i ->
                    val s = st.getJSONObject(i)
                    val ta = s.optJSONArray("tags") ?: JSONArray()
                    Still(
                        s.getInt("id"), s.getString("file"), s.getInt("w"), s.getInt("h"),
                        s.getDouble("fx"), s.getDouble("fy"), s.getDouble("cx"), s.getDouble("cy"),
                        s.optJSONArray("pose")?.let { p -> DoubleArray(p.length()) { p.getDouble(it) } },
                        s.optLong("time"),
                        (0 until ta.length()).map { k ->
                            val t = ta.getJSONObject(k); val c = t.getJSONArray("c")
                            TagDetection(t.getInt("id"), t.optInt("ham"), t.getDouble("cx"), t.getDouble("cy"), DoubleArray(c.length()) { c.getDouble(it) })
                        },
                    )
                },
            )
        }
    }
}

/**
 * 좌표 TXT 읽기. 사진 기준점 앱이 내보내는 「Target #N,E,N,Z」(쉼표, CRLF, 머리줄 없음)가 기본.
 * 빈칸·탭·세미콜론 구분, 「AprilTag #N」 「TAG_36h11_07」 「N」(숫자만) 이름도 받는다. 읽지 못한 줄은 건너뛴다.
 */
object GcpTxt {
    data class Report(val gcps: List<Gcp>, val skipped: Int, val duplicates: Int, val message: String)

    /** 읽은 결과 + 한 줄 안내 */
    fun report(text: String): Report {
        if (text.isBlank()) return Report(emptyList(), 0, 0, "빈 파일이에요")
        if (text.count { it == '\u0000' } > 0) return Report(emptyList(), 0, 0, "글자 파일(TXT)이 아니에요")
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        val nos = ArrayList<Int>()
        val g = parse(text, nos)
        val dup = nos.size - nos.toSet().size
        val skipped = lines.size - nos.size
        val msg = when {
            g.isEmpty() -> "좌표를 읽지 못했어요 · 「Target #N,E,N,H」 형식인지 보세요"
            g.any { kotlin.math.abs(it.e) < 1000 && kotlin.math.abs(it.n) < 1000 } -> "경위도처럼 보여요 · 평면좌표(EPSG:5187) TXT 를 골라 주세요"
            else -> "좌표 ${g.size}점 불러왔어요" + (if (skipped > 0) " · ${skipped}줄 건너뜀" else "") + (if (dup > 0) " · 같은 번호 ${dup}개는 마지막 값" else "")
        }
        val ok = if (msg.startsWith("경위도")) emptyList() else g
        return Report(ok, skipped, dup, msg)
    }

    fun parse(text: String): List<Gcp> = parse(text, ArrayList())

    private fun num(s: String): Double? = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e8 }

    private fun parse(text: String, seen: MutableList<Int>): List<Gcp> {
        val out = LinkedHashMap<Int, Gcp>()
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            // 세미콜론·탭 구분이면 쉼표는 소수점(유럽식 엑셀)
            val parts: List<String> = if (line.contains(';') || line.contains('\t')) line.split(';', '\t').map { it.trim().replace(',', '.') }
            else if (line.contains(',')) line.split(',').map { it.trim() }
            else {
                // 「Target #3 123.4 567.8 9.0」: 이름 안 빈칸 보존
                val m = Regex("""^(.*?)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s*$""").find(line) ?: continue
                m.groupValues.drop(1)
            }
            if (parts.size < 4) continue
            val no = kr.archsurvey.pg.tag.TargetNaming.parseTargetNo(parts[0]) ?: parts[0].toIntOrNull() ?: continue
            val e = num(parts[1]) ?: continue
            val n = num(parts[2]) ?: continue
            val h = num(parts[3]) ?: continue
            if (no <= 0) continue
            if (e == 0.0 && n == 0.0) continue // 빈 틀(gcp_template 의 0 자리) 은 좌표 아님
            seen += no
            out[no] = Gcp(no, e, n, h)
        }
        return out.values.sortedBy { it.target }
    }
}
