package kr.archsurvey.gcp.model

/**
 * 사진 기준점 장부(유적 → 대상 → 차수 → 표적). Android 의존 없음(JVM 시험).
 * 대상 ID(GN-A012)는 만들 때 한 번 정하고 바꾸지 않는다 — 이름이 바뀌어도 폴더·파일·사진 폴더·목록이 ID 로 이어진다.
 */
enum class ObjKind(val id: String, val ko: String) {
    FEATURE("feature", "유구"), SECTION("section", "토층"), OTHER("other", "기타");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OTHER }
}

data class Site(val id: Long, val name: String, val code: String, val epsg: Int, val createdAt: Long)

data class Obj(
    val id: Long, val siteId: Long, val seq: Int, val code: String, val kind: ObjKind, val name: String,
    val oldNames: List<String> = emptyList(), val createdAt: Long = 0L, val operator: String = "",
)

/** 차수. [slots] = 화면에 놓은 표적 번호(실제 태그 번호) 순서. 잰 번호는 [Shot] 에 */
data class Session(
    val id: Long, val objId: Long, val no: Int, val label: String, val slots: List<Int>,
    val createdAt: Long, val photoDoneAt: Long? = null, val deliveredAt: Long? = null,
    /** 표적 값이 마지막으로 바뀐 시각(측점·다시 재기·움직임 처리) */
    val changedAt: Long = createdAt, val xmlSavedAt: Long? = null,
)

/** 표적 한 번 잰 값. 같은 차수·같은 번호는 [active] 하나만(다시 재면 앞 값은 active=false 로 기록에 남음) */
data class Shot(
    val id: Long, val sessionId: Long, val tag: Int,
    val e: Double, val n: Double, val h: Double, val lat: Double, val lon: Double,
    val sigmaH: Double?, val sigmaU: Double?, val epochs: Int, val measuredAt: Long,
    val active: Boolean = true, val poleM: Double = 0.0, val apcM: Double = 0.0, val device: String = "", val note: String = "",
)

/** 상태 3단(측점 → 사진 → 전달) + 끝 */
enum class Stage(val ko: String, val short: String) {
    MEASURE("측점 필요", "측점"), PHOTO("사진 필요", "사진"), HANDOFF("전달 필요", "전달"), DONE("끝", "끝");
}

object Pipeline {
    const val MIN_TARGETS = 3

    /** 표적 3개 미만 → 측점 / 사진 체크 없음 → 사진 / 보낸 뒤 바뀌었거나 안 보냄 → 전달 / 그 밖 → 끝 */
    fun stage(activeTags: Int, s: Session, minTargets: Int = MIN_TARGETS): Stage = when {
        activeTags < minTargets -> Stage.MEASURE
        s.photoDoneAt == null -> Stage.PHOTO
        s.deliveredAt == null || s.changedAt > s.deliveredAt -> Stage.HANDOFF
        else -> Stage.DONE
    }

    fun activeTags(shots: List<Shot>, sessionId: Long): List<Shot> =
        shots.filter { it.sessionId == sessionId && it.active }.groupBy { it.tag }.map { (_, l) -> l.maxBy { it.measuredAt } }.sortedBy { it.tag }

    /** 「사진 다 찍었어요」는 표적이 [MIN_TARGETS] 개부터 */
    fun canMarkPhoto(activeTags: Int, minTargets: Int = MIN_TARGETS) = activeTags >= minTargets
}

/** 대상 ID: 유적 약호 + (작업자 글자) + 세 자리 일련번호. 예) GN-A012, 작업자 없음 GN-012 */
object ObjIds {
    private val CODE = Regex("^[A-Z][A-Z0-9]{0,3}$")
    val OPERATORS = listOf("", "A", "B", "C", "D")

    /** 유적 약호 검사(대문자로 바꾼 뒤). 문제 문구 또는 null */
    fun codeProblem(raw: String, taken: Collection<String>): String? {
        val c = normalizeCode(raw)
        return when {
            c.isEmpty() -> "약호를 넣으세요 (예: GN)"
            !CODE.matches(c) -> "약호는 영문 대문자로 시작하는 1~4글자예요 (예: GN, YY2)"
            taken.any { it.equals(c, true) } -> "이미 쓰는 약호예요 — 다른 글자를 고르세요"
            else -> null
        }
    }
    fun normalizeCode(raw: String) = raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun format(siteCode: String, operator: String, seq: Int): String = "$siteCode-$operator${seq.toString().padStart(3, '0')}"

    /** 같은 유적·같은 작업자 글자에서 다음 번호. 지운 대상 번호도 다시 쓰지 않는다(지운 것도 [seqs] 에 넣을 것) */
    fun nextSeq(seqs: Collection<Int>): Int = (seqs.maxOrNull() ?: 0) + 1

    /** ID 상자 그림용: 「GN-」 + 「A002」 */
    fun split(code: String): Pair<String, String> = code.indexOf('-').let { if (it < 0) "" to code else code.substring(0, it + 1) to code.substring(it + 1) }
}

/** 파일·폴더 이름. 대상명에서 공백·—·′ 등은 빼고(2호수혈, BB토층), ID 를 맨 앞에 */
object Names {
    fun safe(name: String): String = name.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in '가'..'힣' || it == '_' }.ifEmpty { "대상" }

    /** 유적 폴더: 이름 그대로(공백 허용), 경로에 못 쓰는 글자만 뺌 */
    fun siteFolder(site: Site): String = site.name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { site.code }

    fun objFolder(o: Obj) = "${o.code}_${safe(o.name)}"
    fun photoFolder(o: Obj, s: Session) = "${objFolder(o)}_${s.no}차"
    fun xmlName(o: Obj, s: Session, epsg: Int) = "${photoFolder(o, s)}_EPSG$epsg.xml"
    fun txtName(o: Obj, s: Session, epsg: Int) = "${photoFolder(o, s)}_EPSG$epsg.txt"
    fun listCsvName(site: Site) = "${safe(site.name)}_목록.csv"
    /** 앱 결과 폴더 상대 경로(공용 Documents 밑) */
    const val ROOT = "iTwinGCP"
    fun objRel(site: Site, o: Obj) = "$ROOT/${siteFolder(site)}/${objFolder(o)}"
    fun siteRel(site: Site) = "$ROOT/${siteFolder(site)}"
}

/** + 유구 다음 5호 / + 토층 다음 C—C′ */
object AutoNumber {
    private val FEATURE_NO = Regex("^\\s*(\\d+)\\s*호")
    private val SECTION_LETTER = Regex("^\\s*([A-Z]{1,2})\\s*[—–\\-]")
    val FEATURE_SUBTYPES = listOf("수혈", "주거지", "구", "주혈", "소성유구", "유구")
    val SESSION_LABELS = listOf("검출면", "중간", "완토", "바닥", "기타")

    fun nextFeatureNo(names: Collection<String>): Int = (names.mapNotNull { FEATURE_NO.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
    fun featureName(no: Int, subtype: String) = "${no}호 ${subtype.trim().ifEmpty { "유구" }}"

    fun nextSectionLetter(names: Collection<String>): String {
        val used = names.mapNotNull { SECTION_LETTER.find(it)?.groupValues?.get(1) }
        val max = used.maxWithOrNull(compareBy({ it.length }, { it })) ?: return "A"
        return inc(max)
    }
    private fun inc(s: String): String {
        val last = s.last()
        return if (last < 'Z') s.dropLast(1) + (last + 1) else if (s.length == 1) "AA" else inc(s.dropLast(1)) + "A"
    }
    fun sectionName(letter: String) = "$letter—$letter′ 토층"
    fun otherName(no: Int) = "기타 $no"
}

/** 표적 점검 3가지: 최소 3개(Pipeline) · 다시 잰 값이 2 cm 넘게 다름 · 20 m 안 진행 중 대상과 번호 겹침 */
object TargetChecks {
    const val MOVED_M = 0.02
    const val CLASH_RADIUS_M = 20.0
    const val MAX_TAG = 9999

    fun distance3d(a: Shot, e: Double, n: Double, h: Double): Double {
        val dx = a.e - e; val dy = a.n - n; val dz = a.h - h
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    fun horizontal(e1: Double, n1: Double, e2: Double, n2: Double) = Math.hypot(e1 - e2, n1 - n2)

    /** 같은 차수에서 다시 잰 값이 [MOVED_M] 넘게 다르면 그 거리, 아니면 null */
    fun moved(prev: Shot?, e: Double, n: Double, h: Double, limit: Double = MOVED_M): Double? =
        prev?.let { distance3d(it, e, n, h) }?.takeIf { it > limit + 1e-9 }

    data class Clash(val objCode: String, val objName: String, val tag: Int, val distanceM: Double)

    /**
     * [others] = 다른 대상(진행 중 = 끝이 아님)의 지금 차수 표적. 같은 번호가 [CLASH_RADIUS_M] 안에 있으면 겹침.
     * 한 사진에 두 대상 표적이 함께 찍히면 iTwin 에서 같은 「Target #N」 이 둘이 된다.
     */
    fun clashes(tag: Int, e: Double, n: Double, others: List<Pair<Obj, List<Shot>>>, radius: Double = CLASH_RADIUS_M): List<Clash> =
        others.flatMap { (o, shots) -> shots.filter { it.tag == tag && horizontal(it.e, it.n, e, n) <= radius }.map { Clash(o.code, o.name, tag, horizontal(it.e, it.n, e, n)) } }
            .sortedBy { it.distanceM }

    /** [e],[n] 근처(반경 안) 다른 진행 중 대상이 쓰는 번호 */
    fun nearbyTags(e: Double, n: Double, others: List<Pair<Obj, List<Shot>>>, radius: Double = CLASH_RADIUS_M): Set<Int> =
        others.flatMap { it.second }.filter { horizontal(it.e, it.n, e, n) <= radius }.map { it.tag }.toSet()

    /** [blocked] 에 없는 가장 작은 번호(1부터) */
    fun nextFree(blocked: Set<Int>, from: Int = 1): Int { var i = from.coerceAtLeast(0); while (i in blocked) i++; return i }

    /** 새 차수의 표적 칸 [count] 개: 근처에서 쓰는 번호를 피해 작은 번호부터(보통 1–4, 겹치면 5–8) */
    fun suggestSlots(blocked: Set<Int>, count: Int = 4): List<Int> {
        val out = ArrayList<Int>(); var i = 1
        while (out.size < count) { if (i !in blocked) out += i; i++ }
        return out
    }

    fun validTag(n: Int) = n in 0..MAX_TAG
}

/** ID 카드 QR 내용: GN-A002|2차|EPSG5187|T1-4 */
object QrPayload {
    fun of(code: String, sessionNo: Int, epsg: Int, tags: List<Int>): String = "$code|${sessionNo}차|EPSG$epsg|T${ranges(tags)}"

    /** [1,2,3,4] → 1-4, [1,2,5] → 1-2,5, [] → - */
    fun ranges(tags: List<Int>): String {
        val s = tags.distinct().sorted(); if (s.isEmpty()) return "-"
        val out = ArrayList<String>(); var a = s[0]; var b = s[0]
        for (x in s.drop(1)) { if (x == b + 1) b = x else { out += if (a == b) "$a" else "$a-$b"; a = x; b = x } }
        out += if (a == b) "$a" else "$a-$b"
        return out.joinToString(",")
    }
}
