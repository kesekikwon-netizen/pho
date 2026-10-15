package kr.archsurvey.gcp.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 유적 하나의 장부 묶음(화면·목록·넘기기 공용) */
data class SiteBook(val site: Site, val objs: List<Obj>, val sessions: List<Session>, val shots: List<Shot>) {
    fun sessionsOf(o: Obj) = sessions.filter { it.objId == o.id }.sortedBy { it.no }
    fun current(o: Obj): Session? = sessionsOf(o).lastOrNull()
    fun active(s: Session) = Pipeline.activeTags(shots, s.id)
    fun stage(s: Session, min: Int = Pipeline.MIN_TARGETS) = Pipeline.stage(active(s).size, s, min)
    fun stageOf(o: Obj, min: Int = Pipeline.MIN_TARGETS): Stage = current(o)?.let { stage(it, min) } ?: Stage.MEASURE
    fun counts(min: Int = Pipeline.MIN_TARGETS): Map<Stage, Int> = Stage.entries.associateWith { st -> objs.count { stageOf(it, min) == st } }
    /** 진행 중(끝 아님) 다른 대상의 지금 차수 표적 — 번호 겹침 점검용 */
    fun othersInProgress(except: Long?, min: Int = Pipeline.MIN_TARGETS): List<Pair<Obj, List<Shot>>> =
        objs.filter { it.id != except && stageOf(it, min) != Stage.DONE }.mapNotNull { o -> current(o)?.let { o to active(it) } }.filter { it.second.isNotEmpty() }
    fun lastActivity(): Long? = (sessions.map { maxOf(it.changedAt, it.photoDoneAt ?: 0, it.deliveredAt ?: 0) } + objs.map { it.createdAt }).maxOrNull()
}

/** 목록.csv — PC 쪽에서 사진 폴더와 맞춰 보는 장부. UTF-8 BOM(엑셀) · CRLF · 차수마다 한 줄 */
object ListCsv {
    val HEADER = listOf("폴더", "대상", "종류", "차수", "차수 이름", "날짜", "표적 번호", "XML 파일", "사진 폴더", "상태", "마지막 저장")

    fun write(book: SiteBook, zone: ZoneId, min: Int = Pipeline.MIN_TARGETS): String {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder("\uFEFF").append(HEADER.joinToString(",") { q(it) }).append("\r\n")
        for (o in book.objs.sortedWith(ObjOrder)) for (s in book.sessionsOf(o)) {
            val tags = book.active(s).map { it.tag }
            val st = book.stage(s, min)
            val row = listOf(
                o.code, o.name, o.kind.ko, "${s.no}차", s.label, day.format(Instant.ofEpochMilli(s.createdAt)),
                tags.joinToString(" "), if (tags.isEmpty()) "" else "${Names.objFolder(o)}/${Names.xmlName(o, s, book.site.epsg)}",
                Names.photoFolder(o, s), st.ko, s.xmlSavedAt?.let { stamp.format(Instant.ofEpochMilli(it)) } ?: "",
            )
            sb.append(row.joinToString(",") { q(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

enum class HandoffScope(val ko: String, val fileWord: String) { PENDING("전달 필요", "전달"), TODAY("오늘", "오늘"), ALL("전체", "전체") }

/** PC로 넘기기 zip 한 벌의 계획(내용·전달 완료로 바꿀 차수). Android 의존 없음 */
data class HandoffPlan(
    val zipName: String,
    val entries: List<Pair<String, String>>,
    val sessionIds: List<Long>,
    /** 보내면 「전달」 로 바꿀 차수(사진까지 끝난 것만) */
    val markDelivered: List<Long>,
    /** 사진 아직 — zip 에는 넣지만 ⚠ */
    val noPhoto: List<String>,
    /** 0.1.17: 공용 Documents 밑 유적 폴더(zip 사본을 둘 곳) */
    val siteRel: String = "",
)

object Handoff {
    fun select(book: SiteBook, scope: HandoffScope, now: Long, zone: ZoneId, min: Int = Pipeline.MIN_TARGETS): List<Pair<Obj, Session>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun isToday(t: Long?) = t != null && Instant.ofEpochMilli(t).atZone(zone).toLocalDate() == today
        return book.objs.sortedWith(ObjOrder).flatMap { o -> book.sessionsOf(o).map { o to it } }.filter { (_, s) ->
            val has = book.active(s).isNotEmpty()
            when (scope) {
                HandoffScope.PENDING -> book.stage(s, min) == Stage.HANDOFF
                HandoffScope.TODAY -> has && (isToday(s.changedAt) || isToday(s.photoDoneAt) || isToday(s.createdAt))
                HandoffScope.ALL -> has
            }
        }
    }

    fun count(book: SiteBook, scope: HandoffScope, now: Long, zone: ZoneId, min: Int = Pipeline.MIN_TARGETS) = select(book, scope, now, zone, min).size

    fun zipName(site: Site, scope: HandoffScope, now: Long, zone: ZoneId): String {
        val d = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(Instant.ofEpochMilli(now))
        return "${Names.safe(site.name)}_${scope.fileWord}_$d.zip"
    }

    fun plan(book: SiteBook, scope: HandoffScope, now: Long, zone: ZoneId, min: Int = Pipeline.MIN_TARGETS): HandoffPlan {
        val sel = select(book, scope, now, zone, min)
        val entries = ArrayList<Pair<String, String>>()
        entries += Names.listCsvName(book.site) to ListCsv.write(book, zone, min)
        val epsg = book.site.epsg
        entries += ItwinGuide.FILE to ItwinGuide.text(epsg)
        for ((o, s) in sel) {
            val shots = book.active(s)
            entries += "${Names.objFolder(o)}/${Names.xmlName(o, s, epsg)}" to ItwinXml.write(shots, epsg)
            entries += "${Names.objFolder(o)}/${Names.txtName(o, s, epsg)}" to ItwinXml.txt(shots)
        }
        val mark = sel.filter { (_, s) -> book.stage(s, min).let { it == Stage.HANDOFF || it == Stage.DONE } }.map { it.second.id }
        val noPhoto = sel.filter { (_, s) -> s.photoDoneAt == null }.map { (o, s) -> Names.photoFolder(o, s) }
        return HandoffPlan(zipName(book.site, scope, now, zone), entries, sel.map { it.second.id }, mark, noPhoto, Names.siteRel(book.site))
    }
}

/** 목록 순서: 호수 대상(종류 순, 번호 순) 다음 토층(글자 순) — 폴더 이름 정렬과 같다 */
val ObjOrder: Comparator<Obj> = compareBy<Obj>({ it.kind.lettered }, { if (it.kind.lettered) 0 else it.no }, { it.kind.ordinal }, { it.no })

/**
 * 0.1.17: zip 안 「iTwin_불러오기.txt」. Bentley 문서(ContextCapture/iTwin Capture Modeler 「Importing control points」, KB0044805) 근거.
 * 가장 확실한 길 = Surveys XML(좌표계가 파일 안에 있어 마법사 없음). TXT 는 쉼표 구분이라 「Custom Text Format (Wizard)」 로만 읽힌다
 * (기본 TXT 읽기는 공백 구분 → 이름 「Target #1」 의 공백·쉼표 때문에 못 읽음).
 */
object ItwinGuide {
    const val FILE = "iTwin_불러오기.txt"
    fun text(epsg: Int): String = listOf(
        "iTwin Capture Modeler 에 표적 좌표 넣기",
        "",
        "1) 블록에 사진을 넣은 뒤 Surveys(측량) 탭 > Import surveys from file(파일에서 측량 가져오기).",
        "2) 파일 형식은 \"ContextCapture Surveys (XML)\" 을 고르고 각 폴더의 *_EPSG$epsg.xml 을 엽니다.",
        "   - 좌표계(EPSG:$epsg)가 파일 안에 있어 따로 고를 것이 없습니다.",
        "3) TXT 를 쓰려면 반드시 \"Custom Text Format (Wizard)\" 를 고르고: 구분자 = 쉼표, 머리줄 없음,",
        "   열 = 이름 · X(동) · Y(북) · Z, 좌표계 = EPSG:$epsg. (기본 TXT 읽기는 공백 구분이라 이 파일을 못 읽습니다)",
        "4) 공중삼각측량(Aerotriangulation): Positioning = Control points, \"Use targets (QR Codes, AprilTags or ChiliTags)\" 켬,",
        "   Targets extraction = AprilTags. 사진에서 찾은 표적과 같은 이름(Target #N)의 좌표가 저절로 합쳐집니다.",
        "5) iTwin 이 표적 이름을 \"AprilTag #N\" 으로 붙이는 버전이면, TXT 의 이름을 그에 맞게 바꾸거나 표적 이름을 바꿔 맞춥니다.",
        "",
        "x = 동(E), y = 북(N), z = 수신기 높이, 정밀도 0.01 m.",
    ).joinToString("\r\n", postfix = "\r\n")
}
