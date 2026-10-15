package kr.archsurvey.gcp.model

/**
 * 사진 기준점 장부(유적 → 대상 → 차수 → 표적). Android 의존 없음(JVM 시험).
 * 대상 = 종류 + 호수(만들 때 고르고 바꾸지 않음). 폴더·파일·사진 폴더·목록이 모두 「012호_주거지」로 이어진다.
 */
/** 0.1.1: 대상 종류(큰 타일로 고름). 토층만 번호 대신 글자(A—A′) */
enum class ObjKind(val id: String, val ko: String) {
    DWELLING("dwelling", "주거지"), PIT("pit", "수혈"), DITCH("ditch", "구"), POSTHOLE("posthole", "주혈"),
    BUILDING("building", "건물지"), TOMB("tomb", "무덤"), KILN("kiln", "가마"), SECTION("section", "토층"), OTHER("other", "기타");
    val lettered: Boolean get() = this == SECTION
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OTHER }
}

/** 유적: 이름 + 좌표계만(0.1.1 약호 없앰) */
data class Site(val id: Long, val name: String, val epsg: Int, val createdAt: Long)

/**
 * 대상 = 종류 + 호수(1–999, 토층은 1=A, 2=B …). 한 유적에서 (종류, 호수)는 하나뿐 — 바꾸지 않는다.
 * 폴더·파일 이름은 이 둘로만 만든다(012호_주거지, A_토층). 안쪽 고유 번호는 [id](DB).
 */
data class Obj(val id: Long, val siteId: Long, val kind: ObjKind, val no: Int, val createdAt: Long = 0L,
    /** 0.1.17: 사용자가 직접 쓴 폴더 이름(있으면 이것만 씀). 옛 대상은 빈칸 → 종류+호수 이름 */
    val title: String = "") {
    /** 화면 이름: 사용자 이름 / 12호 주거지 / A—A′ 토층 */
    val name: String get() = title.ifBlank { ObjNames.label(kind, no) }
    /** 짧은 이름(지도·목록) */
    val short: String get() = title.ifBlank { ObjNames.short(kind, no) }
    /** 폴더·파일 머리: 사용자 이름(폴더에 못 쓰는 글자는 _) / 012호_주거지 / A_토층 */
    val code: String get() = if (title.isNotBlank()) ObjNames.safe(title) else ObjNames.folder(kind, no)
}

object ObjNames {
    const val MAX_NO = 999
    fun letter(no: Int): String { var n = no.coerceAtLeast(1); val sb = StringBuilder(); while (n > 0) { n--; sb.insert(0, 'A' + n % 26); n /= 26 }; return sb.toString() }
    fun label(kind: ObjKind, no: Int) = if (kind.lettered) "${letter(no)}—${letter(no)}′ 토층" else "${no}호 ${kind.ko}"
    fun short(kind: ObjKind, no: Int) = if (kind.lettered) "${letter(no)}—${letter(no)}′" else "${no}호"
    fun folder(kind: ObjKind, no: Int) = if (kind.lettered) "${letter(no)}_토층" else "${no.toString().padStart(3, '0')}호_${kind.ko}"
    /** 다음 번호: 쓴 번호 중 가장 큰 것 + 1(999 를 넘으면 빈 번호 중 가장 작은 것) */
    fun next(used: Collection<Int>): Int {
        val m = (used.maxOrNull() ?: 0) + 1
        if (m <= MAX_NO) return m
        return (1..MAX_NO).firstOrNull { it !in used } ?: MAX_NO
    }
    fun valid(no: Int) = no in 1..MAX_NO
    /** 0.1.17: 직접 쓴 이름 → 폴더·파일 이름(\ / : * ? " < > | 와 제어문자 → _, 앞뒤 공백·점 제거, 40자) */
    fun safe(raw: String): String = raw.trim().map { if (it.code < 0x20 || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim('.', ' ').take(40).ifEmpty { "폴더" }
    /** 0.1.17: 새 폴더 이름 문제(없으면 null) */
    fun problem(raw: String, existing: Collection<String>): String? {
        val n = raw.trim()
        return when {
            n.isEmpty() -> "폴더 이름을 입력하세요"
            n.length > 40 -> "이름은 40자까지예요"
            existing.any { it.equals(n, ignoreCase = true) || safe(it).equals(safe(n), ignoreCase = true) } -> "같은 이름의 폴더가 이미 있어요"
            else -> null
        }
    }
}

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

/** 파일·폴더 이름. 대상명에서 공백·—·′ 등은 빼고(2호수혈, BB토층), ID 를 맨 앞에 */
object Names {
    fun safe(name: String): String = name.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in '가'..'힣' || it == '_' }.ifEmpty { "대상" }

    /** 유적 폴더: 이름 그대로(공백 허용), 경로에 못 쓰는 글자만 뺌 */
    fun siteFolder(site: Site): String = site.name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { "유적" }

    /** 012호_주거지 / A_토층 */
    fun objFolder(o: Obj) = o.code
    fun photoFolder(o: Obj, s: Session) = "${objFolder(o)}_${s.no}차"
    fun xmlName(o: Obj, s: Session, epsg: Int) = "${photoFolder(o, s)}_EPSG$epsg.xml"
    fun txtName(o: Obj, s: Session, epsg: Int) = "${photoFolder(o, s)}_EPSG$epsg.txt"
    fun listCsvName(site: Site) = "${safe(site.name)}_목록.csv"
    /** 앱 결과 폴더 상대 경로(공용 Documents 밑) */
    const val ROOT = "iTwinGCP"
    fun objRel(site: Site, o: Obj) = "$ROOT/${siteFolder(site)}/${objFolder(o)}"
    fun siteRel(site: Site) = "$ROOT/${siteFolder(site)}"
}

/** 차수 이름(타일) */
object SessionLabels { val ALL = listOf("검출면", "중간", "완토", "바닥") ; fun next(prev: String?) = ALL.getOrElse(ALL.indexOf(prev) + 1) { ALL.last() } }

/** 표적 점검 3가지: 최소 3개(Pipeline) · 다시 잰 값이 2 cm 넘게 다름 · 20 m 안 진행 중 대상과 번호 겹침 */
object TargetChecks {
    const val MOVED_M = 0.02
    const val CLASH_RADIUS_M = 20.0
    const val MAX_TAG = 999

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

    fun validTag(n: Int) = n in 1..MAX_TAG
}

/** ID 카드 QR 내용: 012호_주거지|2차|EPSG5187|T1-4 (사진 폴더 이름과 같은 머리) */
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

/** V월드 주소(getAddress) 결과 중 이름 짓기에 쓰는 칸 */
data class AddressParts(val sido: String, val sigungu: String, val beopjeong: String, val haengjeong: String)

/**
 * 0.1.1 유적 이름을 글자 입력 없이: 지금 위치 주소로 만든 이름 + 최근 유적 이름 + 「유적 날짜」 중 고르고, 필요하면 「N지구」를 붙인다.
 */
object SiteNames {
    private val DISTRICT = Regex("\\s*\\d+\\s*지구$")

    /** 강릉시 → 강릉, 양평군 → 양평, 수원시 팔달구 → 수원, 중구 → 중구 */
    fun city(sigungu: String): String {
        val first = sigungu.trim().split(' ').firstOrNull().orEmpty()
        return if ((first.endsWith("시") || first.endsWith("군")) && first.length >= 3) first.dropLast(1) else first
    }
    fun base(name: String) = name.replace(DISTRICT, "").trim()

    fun suggest(addr: AddressParts?, recent: List<String>, today: java.time.LocalDate): List<String> {
        val out = LinkedHashSet<String>()
        if (addr != null) {
            val c = city(addr.sigungu)
            val dong = addr.beopjeong.trim().ifEmpty { addr.haengjeong.trim() }
            if (dong.isNotEmpty()) { if (c.isNotEmpty()) out += "$c $dong 유적"; out += "$dong 유적" }
            val h = addr.haengjeong.trim()
            if (h.isNotEmpty() && h != dong && c.isNotEmpty()) out += "$c $h 유적"
            if (dong.isEmpty() && c.isNotEmpty()) out += "$c 유적"
        }
        recent.map { base(it) }.filter { it.isNotEmpty() }.forEach { out += it }
        out += "유적 $today"
        return out.toList().take(8)
    }

    fun withDistrict(base: String, district: Int?): String = if (district == null || district <= 0) base.trim() else "${base.trim()} ${district}지구"

    /** 이미 있는 이름이면 「(2)」, 「(3)」… */
    fun unique(name: String, taken: Collection<String>): String {
        if (taken.none { it == name }) return name
        var i = 2; while (taken.any { it == "$name ($i)" }) i++
        return "$name ($i)"
    }
}
