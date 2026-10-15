package kr.archsurvey.dig

import kr.archsurvey.core.NE
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 0.1.35 발굴 전용 로직(Android 의존 없음). 화면은 이 결과만 보여 준다.
 * 점 이름(P-0001)은 바뀌지 않는 번호이고, 유구·칸·종류는 점의 속성이다.
 */

/** 「지금 재는 것」 8종. [positionType] 은 예전부터 저장·내보내기에 쓰던 위치 종류 글자(호환) */
enum class DigKind(val id: String, val labelKo: String, val positionType: String, val subKo: String) {
    FEATURE("feature", "유구 경계", "유구 경계점", "선 → 면 · 면적"),
    ARTIFACT("artifact", "유물", "유물 출토 위치", "번호 자동 · 해발"),
    LEVEL("level", "레벨", "레벨점", "검출면 · 바닥 · 상면"),
    SECTION("section", "토층 단면", "토층 단면", "A — A′ 양 끝"),
    TRENCH("trench", "트렌치", "트렌치", "Tr. 모서리"),
    STAKE("stake", "그리드 말뚝", "그리드 모서리", "찾아가서 박기"),
    BM("bm", "기준점", "기준점", "BM · 확인"),
    AREA("area", "조사구역", "조사구역 경계", "경계");

    /** 점을 이어 선·면을 만드는 종류(유구·트렌치·조사구역) */
    val outlineKind: Boolean get() = this == FEATURE || this == TRENCH || this == AREA

    companion object {
        fun fromId(id: String?): DigKind? = entries.firstOrNull { it.id == id }
        /** 0.1.34 이전 위치 종류 글자 → 종류. 사용자가 만든 종류·「기타」는 null(이름 그대로 보여 줌) */
        fun fromPositionType(t: String?): DigKind? = when (t?.trim()) {
            null, "" -> null
            else -> entries.firstOrNull { it.positionType == t.trim() } ?: when (t.trim()) { "레벨", "레벨 점" -> LEVEL; "유구 경계" -> FEATURE; "유물" -> ARTIFACT; else -> null }
        }
        /** 저장된 id 가 있으면 그것, 없으면 위치 종류 글자로 */
        fun resolve(id: String?, positionType: String?): DigKind? = fromId(id) ?: fromPositionType(positionType)
    }
}

enum class LevelKind(val id: String, val labelKo: String) {
    DETECT("detect", "검출면"), TOP("top", "상면"), FLOOR("floor", "바닥");
    companion object { fun fromId(id: String?): LevelKind? = entries.firstOrNull { it.id == id } }
}

/** 유구 이름: 「2호 수혈」(번호+호+종류), 가번호 「가-3」. 옛 외곽 이름(E-001)은 그대로 */
object FeatureNaming {
    /** 제안 기본값(현장 확인 전) — 설정에서 바꿀 수 있다 */
    val DEFAULT_TYPES = listOf("수혈", "주거지", "주혈", "구", "토광묘", "석곽묘", "소성유구", "건물지", "기타")
    data class Parsed(val no: Int?, val type: String, val provisional: Boolean)

    private val numbered = Regex("^(\\d+)호\\s*(.+)$")
    private val prov = Regex("^가-(\\d+)$")

    fun name(no: Int, type: String) = "${no}호 ${type.trim()}"
    fun provisional(n: Int) = "가-$n"
    fun parse(label: String): Parsed {
        val s = label.trim()
        numbered.find(s)?.let { return Parsed(it.groupValues[1].toInt(), it.groupValues[2].trim(), false) }
        prov.find(s)?.let { return Parsed(it.groupValues[1].toInt(), "", true) }
        return Parsed(null, s, false)
    }
    fun nextNo(existing: List<String>, type: String): Int =
        (existing.map { parse(it) }.filter { !it.provisional && it.no != null && it.type == type.trim() }.maxOfOrNull { it.no!! } ?: 0) + 1
    fun nextProvisional(existing: List<String>): Int =
        (existing.map { parse(it) }.filter { it.provisional }.maxOfOrNull { it.no!! } ?: 0) + 1
    fun newName(existing: List<String>, type: String, provisional: Boolean): String =
        if (provisional) provisional(nextProvisional(existing)) else name(nextNo(existing, type), type)

    /** 이름 이력(옛 이름 → 새 이름 순서, 줄바꿈 구분). 같은 이름은 한 번만 */
    fun withHistory(history: String, name: String): String {
        val l = history(history); val n = name.trim()
        if (n.isEmpty() || l.lastOrNull() == n) return history
        return (l + n).joinToString("\n")
    }
    fun history(h: String): List<String> = h.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun renameProblem(raw: String, others: Collection<String>): String? {
        val n = raw.trim()
        return when {
            n.isEmpty() -> "이름을 입력하세요"
            n.length > 20 -> "이름은 20자까지예요"
            n.contains('\n') || n.contains(',') -> "쉼표와 줄바꿈은 쓸 수 없어요"
            others.any { it == n } -> "같은 이름의 유구가 이미 있어요"
            else -> null
        }
    }
    fun encodeTypes(l: List<String>) = l.joinToString("\n")
    fun decodeTypes(raw: String?): List<String> = raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TYPES
}

/** 유물 번호 매기기. 기본 = 작업 전체 일련번호(제안값, 현장 확인 전) */
enum class ArtifactMode(val id: String, val labelKo: String) {
    JOB_SERIAL("job", "작업 전체 일련번호"), PER_FEATURE("feature", "유구마다 1부터");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: JOB_SERIAL }
}
data class ArtifactNo(val featureId: Long?, val no: Int)
object ArtifactNumbering {
    fun next(existing: List<ArtifactNo>, featureId: Long?, mode: ArtifactMode): Int = when (mode) {
        ArtifactMode.JOB_SERIAL -> (existing.maxOfOrNull { it.no } ?: 0) + 1
        ArtifactMode.PER_FEATURE -> (existing.filter { it.featureId == featureId }.maxOfOrNull { it.no } ?: 0) + 1
    }
}

/** 지도·판정용 유구 모양 */
data class FeatureShape(val id: Long, val name: String, val closed: Boolean, val vertices: List<NE>)
/** 유구 판정 결과. auto = 닫힌 면 안이라 앱이 정함, outsideSelected = 고른 닫힌 유구의 면 밖 */
data class Assignment(val featureId: Long?, val auto: Boolean, val outsideSelected: Boolean)

object DigGeometry {
    /** 다각형 안(광선 교차). 꼭짓점 3개 미만이면 false */
    fun inside(p: NE, poly: List<NE>): Boolean {
        if (poly.size < 3) return false
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.n > p.n) != (b.n > p.n) && p.e < (b.e - a.e) * (p.n - a.n) / (b.n - a.n) + a.e) c = !c
            j = i
        }
        return c
    }
    fun area(poly: List<NE>): Double {
        if (poly.size < 3) return 0.0
        var s = 0.0
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; s += a.e * b.n - b.e * a.n }
        return abs(s) / 2.0
    }
    /** 닫힌 유구 면 안이면 그 유구(여럿이면 가장 작은 것), 아니면 고른 유구, 없으면 유구 밖 */
    fun assign(p: NE, features: List<FeatureShape>, selected: Long?): Assignment {
        val hit = features.filter { it.closed && inside(p, it.vertices) }.minByOrNull { area(it.vertices) }
        if (hit != null) return Assignment(hit.id, auto = true, outsideSelected = false)
        val sel = features.firstOrNull { it.id == selected } ?: return Assignment(null, auto = false, outsideSelected = false)
        return Assignment(sel.id, auto = false, outsideSelected = sel.closed)
    }
}

/** 깊이: 검출면 기준 높이(검출면 레벨 평균, 없으면 유구 경계점 높이 평균)와의 차 */
object DigDepth {
    fun mean(l: List<Double>): Double? = if (l.isEmpty()) null else l.average()
    fun detectionRef(detectLevels: List<Double>, boundaryHeights: List<Double>): Double? = mean(detectLevels) ?: mean(boundaryHeights)
    /** 검출면에서 아래로 몇 m(양수 = 깊음) */
    fun depthBelow(ref: Double?, h: Double?): Double? = if (ref == null || h == null) null else ref - h
    /** 검출면 대비 높이(음수 = 아래) */
    fun relative(ref: Double?, h: Double?): Double? = if (ref == null || h == null) null else h - ref
}

data class NEH(val n: Double, val e: Double, val h: Double?)
data class BmResult(val dN: Double, val dE: Double, val dH: Double?, val horizM: Double, val pass: Boolean, val tolM: Double)

/** 기준점 확인: 아침에 BM 한 번 → 알려진 값과 차이. 기준 3 cm 는 제안값(현장·기관 기준 확인 전) */
object BmCheck {
    const val DEFAULT_TOL_M = 0.03
    fun evaluate(known: NEH, measured: NEH, tolM: Double = DEFAULT_TOL_M): BmResult {
        val dN = measured.n - known.n; val dE = measured.e - known.e
        val dH = if (known.h != null && measured.h != null) measured.h - known.h else null
        val hz = hypot(dN, dE)
        val pass = hz <= tolM + 1e-9 && (dH == null || abs(dH) <= tolM + 1e-9)
        return BmResult(dN, dE, dH, hz, pass, tolM)
    }
    private fun cm(m: Double): String { val v = m * 100.0; val s = String.format(Locale.US, "%.1f", abs(v)); return (if (v < -0.049) "−" else "+") + s }
    fun deltaText(r: BmResult): String = "ΔN ${cm(r.dN)} · ΔE ${cm(r.dE)}" + (r.dH?.let { " · Δ해발 ${cm(it)}" } ?: "") + " cm"
    fun verdictText(name: String, r: BmResult): String = if (r.pass) "기준점 확인 · $name 통과" else "기준점 확인 · $name 차이 큼"
    fun adviceText(r: BmResult): String {
        val tol = String.format(Locale.US, "%.0f", r.tolM * 100)
        return if (r.pass) "알려진 값과 $tol cm 안이에요. 폴 높이·좌표계·지오이드가 맞다는 뜻이에요."
        else "알려진 값과 $tol cm 넘게 달라요. 폴 높이·좌표계(지역)·높이(지오이드)를 확인한 뒤 다시 재세요."
    }
}

/** 그리드 말뚝 찾아가기 안내 */
object StakeGuide {
    const val ARRIVE_M = 0.03
    data class G(val distanceM: Double, val bearingDeg: Double, val dirKo: String, val dN: Double, val dE: Double, val arrived: Boolean) {
        val offsetText: String get() = (if (dN >= 0) "북" else "남") + " " + String.format(Locale.US, "%.2f", abs(dN)) + " m · " +
            (if (dE >= 0) "동" else "서") + " " + String.format(Locale.US, "%.2f", abs(dE)) + " m"
    }
    private val DIRS = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    fun of(cur: NE, target: NE, arriveM: Double = ARRIVE_M): G {
        val dN = target.n - cur.n; val dE = target.e - cur.e
        val d = hypot(dN, dE)
        var b = Math.toDegrees(atan2(dE, dN)); if (b < 0) b += 360.0
        val dir = DIRS[(((b + 22.5) % 360.0) / 45.0).toInt()]
        return G(d, b, dir, dN, dE, d <= arriveM)
    }
}

/** 토층 단면선 A — A′: 같은 글자 두 끝 */
object SectionLabels {
    const val PRIME = "′"
    fun lineOf(label: String) = label.removeSuffix(PRIME)
    fun lineName(line: String) = "$line — $line$PRIME"
    fun next(existing: List<String>): String {
        val last = existing.lastOrNull()
        if (last != null && !last.endsWith(PRIME) && existing.none { it == last + PRIME }) return last + PRIME
        val used = existing.map { lineOf(it) }.toSet()
        var c = 'A'
        while (c.toString() in used && c < 'Z') c++
        return c.toString()
    }
    fun completeLines(existing: List<String>): List<String> = existing.filter { !it.endsWith(PRIME) && existing.contains(it + PRIME) }
}

enum class StepState { DONE, NEXT, TODO }
data class PrepStep(val id: String, val title: String, val sub: String, val state: StepState, val action: String?,
    /** 0.1.56 M2: 주황 경고 줄 */
    val warn: Boolean = false)
data class PrepInput(
    val receiverLive: Boolean, val receiverLabel: String, val ntripEnabled: Boolean, val ntripReceiving: Boolean, val ntripLabel: String,
    val poleM: Double, val modelShort: String, val poleConfirmedToday: Boolean, val epsg: Int, val heightLabel: String, val crsReady: Boolean,
    val bmName: String, val bmCheckedToday: Boolean, val bmPassedToday: Boolean?, val bmKnown: Boolean,
    /** 0.1.56 M2: 지금 FIX(●) 중 */
    val fixNow: Boolean = false,
)

/** 홈 「오늘 준비」 5단계: 수신기 · VRS · 폴 높이 확인 · 좌표계·높이 · 기준점 확인. 끝나지 않은 첫 단계가 「다음」 */
object TodayPrep {
    const val VRS_OFF_WARN = "VRS 꺼짐 — 켜야 cm 정밀도"
    const val VRS_OFF_FIX = "수신기 보정으로 FIX 중"
    fun steps(i: PrepInput): List<PrepStep> {
        val pole = String.format(Locale.US, "%.1f", i.poleM)
        val raw = listOf(
            Triple("receiver", i.receiverLive, PrepStep("receiver", "수신기",
                if (i.receiverLive) "${i.receiverLabel} · 연결됨" else "${i.receiverLabel} · 연결 안 됨 · 수신기를 켜고 위성을 잡은 뒤", StepState.TODO, if (i.receiverLive) null else "연결")),
            // 0.1.56 M2: VRS 꺼짐은 ✓ 아님 — FIX 아니면 주황 「VRS 꺼짐 — 켜야 cm 정밀도 [켜기]」, FIX(●) 중이면 회색 「수신기 보정으로 FIX 중」 ✓
            Triple("vrs", i.ntripReceiving || (!i.ntripEnabled && i.fixNow), PrepStep("vrs", "VRS 보정",
                when {
                    !i.ntripEnabled && i.fixNow -> VRS_OFF_FIX
                    !i.ntripEnabled -> VRS_OFF_WARN
                    i.ntripReceiving -> "${i.ntripLabel} 수신 중"
                    else -> "${i.ntripLabel} · 아직 안 들어와요"
                }, StepState.TODO,
                when { i.ntripReceiving -> null; !i.ntripEnabled && i.fixNow -> null; !i.ntripEnabled -> "켜기"; else -> "보기" },
                warn = !i.ntripReceiving && !(!i.ntripEnabled && i.fixNow))),
            Triple("pole", i.poleConfirmedToday, PrepStep("pole", "폴 높이 확인",
                if (i.poleConfirmedToday) "${i.modelShort} · $pole m · 오늘 확인함" else "${i.modelShort} · $pole m · 폴 눈금과 같나요?", StepState.TODO, if (i.poleConfirmedToday) null else "맞아요")),
            Triple("crs", i.crsReady, PrepStep("crs", "좌표계 · 높이",
                "EPSG:${i.epsg} · 해발 ${i.heightLabel}", StepState.TODO, if (i.crsReady) null else "확인")),
            Triple("bm", i.bmCheckedToday && i.bmPassedToday == true, PrepStep("bm", "기준점 확인",
                when {
                    !i.bmKnown -> "${i.bmName} 이 아직 없어요 · 기준점 위에서 한 번 재면 기준값이 돼요"
                    i.bmCheckedToday && i.bmPassedToday == true -> "${i.bmName} · 오늘 확인 통과"
                    i.bmCheckedToday -> "${i.bmName} · 차이가 커요 · 폴·좌표계 확인 후 다시 재세요"
                    else -> "${i.bmName}에 폴을 세우고 한 번 재세요 (아침 1회)"
                }, StepState.TODO, if (i.bmCheckedToday && i.bmPassedToday == true) null else "재기")),
        )
        var nextGiven = false
        return raw.map { (_, done, s) ->
            when {
                done -> s.copy(state = StepState.DONE)
                !nextGiven -> { nextGiven = true; s.copy(state = StepState.NEXT) }
                else -> s
            }
        }
    }
    fun doneCount(s: List<PrepStep>) = s.count { it.state == StepState.DONE }
    fun allDone(s: List<PrepStep>) = s.all { it.state == StepState.DONE }
}

/** 도면용 DXF 레이어(종류별) */
object DigLayers {
    val ALL = listOf("조사구역", "그리드", "트렌치", "유구", "유물", "레벨", "토층", "기준점", "기타")
    fun of(kind: DigKind?, level: LevelKind?): String = when (kind) {
        DigKind.FEATURE -> "유구"; DigKind.ARTIFACT -> "유물"; DigKind.LEVEL -> "레벨"; DigKind.SECTION -> "토층"
        DigKind.TRENCH -> "트렌치"; DigKind.STAKE -> "그리드"; DigKind.BM -> "기준점"; DigKind.AREA -> "조사구역"; null -> "기타"
    }
}

/** 도면 축척 → 글자 높이(종이 2.5 mm) */
object DrawingScale {
    val CHOICES = listOf(20, 100, 200)
    const val DEFAULT = 100
    fun textHeightM(denominator: Int): Double = denominator * 0.0025
}

/** X11: 점이 있는 작업은 좌표계·지역·지오이드를 잠근다(길게 눌러 풀기) */
object CrsLock { fun locked(pointCount: Int, unlocked: Boolean) = pointCount > 0 && !unlocked }

/** X2: 볼륨 아래 키 = 측점. 화면이 켜져 앱이 앞에 있고 측량 지도가 보일 때만, 길게 눌러 반복은 무시 */
object VolumeKeyGate {
    fun shouldMeasure(enabled: Boolean, foreground: Boolean, route: String, repeatCount: Int) =
        enabled && foreground && route == "survey" && repeatCount == 0
}

data class CauseInput(
    val noReceiver: Boolean = false, val fixed: Boolean = false, val floatOrWorse: Boolean = false,
    val ntripEnabled: Boolean = false, val ntripReceiving: Boolean = false, val ntripRetrying: Boolean = false,
    val rtcmAgeSec: Double? = null, val correctionAgeSec: Double? = null, val satellites: Int = 0, val rtcmStalled: Boolean = false,
)

/** X12: 고정이 안 되는 이유를 사람 말 한 줄로 */
object CauseLine {
    const val NO_RECEIVER = "수신기 연결 안 됨 · 누르면 연결" // 0.1.56 S1: 원인 · 누르면 행동, 한 줄
    const val INTERNET = "인터넷이 약해 보정이 안 와요 · 다시 잇는 중이에요"
    const val STALLED = "보정이 수신기로 전달되지 않아요 · 수신기 블루투스 입력을 확인하세요"
    const val SKY = "위성이 가려져요 · 나무·벽에서 조금 떨어져 보세요"
    const val WAIT_FIX = "고정을 기다리는 중이에요 · 폴을 세우고 잠시 기다리세요"
    fun of(i: CauseInput): String? {
        if (i.noReceiver) return NO_RECEIVER
        if (i.fixed || !i.floatOrWorse) return null
        if (i.ntripEnabled) {
            if (i.ntripRetrying || !i.ntripReceiving) return INTERNET
            if (i.rtcmStalled) return STALLED
            val age = i.rtcmAgeSec
            if (age != null && age > 5.0) return "보정이 ${age.toInt()}초 늦게 와요 · 인터넷 신호를 확인하세요"
        }
        val cage = i.correctionAgeSec
        if (!i.ntripEnabled && cage != null && cage > 5.0) return "보정이 ${cage.toInt()}초 늦게 와요 · 인터넷 신호를 확인하세요"
        if (i.satellites in 1..11) return SKY
        return WAIT_FIX
    }
}

/** 하루(현지 날짜) 열쇠 yyyyMMdd — 오늘 폴 확인·기준점 확인 기록 */
object DayKey {
    fun of(ms: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd", Locale.US); f.timeZone = tz; return f.format(java.util.Date(ms))
    }
}

/** 그리드 말뚝(칸 모서리) 목록: 「B-4 모서리」 = 칸 B-4 의 원점 쪽 모서리. 바깥 끝 줄도 포함 */
object StakeNodes {
    data class Node(val name: String, val ne: NE)
    fun list(g: kr.archsurvey.arch.GridDef, max: Int = 2000): List<Node> {
        val out = ArrayList<Node>()
        for (j in 0..g.rows) for (i in 0..g.cols) {
            if (out.size >= max) return out
            out.add(Node("${kr.archsurvey.arch.CellNames.cell(i, j)} 모서리", g.toNE(i * g.spacingM, j * g.spacingM)))
        }
        return out
    }
    /** 아직 박지 않은 말뚝 중 지금 위치에서 가장 가까운 것(위치가 없으면 첫 번째) */
    fun nearestOpen(nodes: List<Node>, done: Set<String>, cur: NE?): Node? {
        val open = nodes.filter { it.name !in done }
        if (cur == null) return open.firstOrNull()
        return open.minByOrNull { hypot(it.ne.n - cur.n, it.ne.e - cur.e) }
    }
}

/** 장부 칸: 이름 · 값 · 단위 */
data class LedgerCell(val label: String, val value: String, val unit: String)

/** 잰 직후 한 장(사용자 HTML 「저장했어요」 카드) — 화면은 이 글자만 그린다 */
data class SavedLedger(val title: String, val chip: String, val line: String, val cells: List<LedgerCell>)

object LedgerText {
    fun m3(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: "—"
    fun cm1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it * 100.0) } ?: "—"
    fun signed2(v: Double?) = v?.let { (if (it < -0.0005) "−" else "+") + String.format(Locale.US, "%.2f", abs(it)) } ?: "—"
}
