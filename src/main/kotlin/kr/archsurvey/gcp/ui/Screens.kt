package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.gcp.model.*
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.ui.hClickable
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 화면이 쓰는 Android 쪽 값(지도 키·버전). 시험은 빈 값 */
class GcpEnv(
    val mapAccess: () -> VWorldAccess = { VWorldAccess("", "") },
    val saveMapKey: (String) -> Unit = {},
    val version: String = "0.1.0",
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MD = DateTimeFormatter.ofPattern("MM/dd")
fun GcpController.timeLabel(t: Long?): String {
    if (t == null) return ""
    val z = Instant.ofEpochMilli(t).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return if (z.toLocalDate() == today) "오늘 " + HM.format(z) else MD.format(z)
}

// =============================== ① 유적 목록 ===============================
@Composable
fun SitesScreen(c: GcpController) {
    Column(Modifier.fillMaxSize().background(G.bg).testTag("sites")) {
        GHeader(null, "sites_header", title = {
            AppMark()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("사진 기준점", style = GT.serif.sz(22.sp), color = G.ink, maxLines = 1)
                Text("iTwin Capture 표적 측점", style = GT.sansL.sz(12.sp), color = G.sub, maxLines = 1)
            }
        }) {
            FixPill(c.source) { c.openReceiver() }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).hClickable(onClickLabel = "설정") { c.go(Screen.SETTINGS) }.testTag("open_settings"), contentAlignment = Alignment.Center) {
                Text("⚙", style = GT.sans.sz(22.sp), color = G.ink)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val op = c.prefs.operator.also { c.poleTick }
            Text("유적 ${c.sites.size}곳" + (if (op.isNotEmpty()) " · 이 폰 작업자 $op" else ""), style = GT.sansL.sz(13.sp), color = G.sub)
            if (c.sites.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("아직 유적이 없어요", style = GT.serif.sz(20.sp), color = G.ink)
                Text("「+ 새 유적」으로 이름 · 약호 · 좌표계를 정하세요.\n대상(유구·토층)마다 ID 가 붙고, 폴더·XML·사진 폴더 이름이 모두 그 ID 로 시작해요.", style = GT.sansL.sz(14.sp), color = G.sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            for (s in c.sites) SiteCardView(c, s)
        }
        GBtn("＋  새 유적", Modifier.fillMaxWidth().padding(16.dp), tag = "new_site", height = 60.dp) { c.openNewSite() }
    }
}

@Composable
fun AppMark(size: androidx.compose.ui.unit.Dp = 44.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(G.ink)) {
        val w = this.size.width; val s = w * 0.22f; val c = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
        drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.18f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.64f, w * 0.64f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.06f))
        drawRect(Color.White, c - androidx.compose.ui.geometry.Offset(s, s), androidx.compose.ui.geometry.Size(s, s))
        drawRect(Color.White, c, androidx.compose.ui.geometry.Size(s, s))
    }
}

@Composable
private fun SiteCardView(c: GcpController, s: SiteCard) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(G.card).border(1.dp, G.line, shape).hClickable { c.openSite(s.site.id) }.padding(16.dp).testTag("site_${s.site.code}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodeBox(s.site.code)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.site.name, style = GT.serif.sz(21.sp), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("EPSG:${s.site.epsg} · 대상 ${s.objCount} · 끝 ${s.counts[Stage.DONE] ?: 0}", style = GT.mono.sz(13.sp), color = G.sub)
            }
        }
        StageBar(s.counts)
        Row {
            Text(Stage.entries.joinToString(" · ") { "${it.short} ${s.counts[it] ?: 0}" }, style = GT.sansL.sz(12.sp), color = G.sub, modifier = Modifier.weight(1f))
            val pend = s.counts[Stage.HANDOFF] ?: 0
            Text(if (s.objCount == 0) "아직 대상 없음" else c.timeLabel(s.lastAt) + if (pend > 0) " · 전달 필요 $pend" else "", style = GT.sansL.sz(12.sp), color = G.sub)
        }
    }
}

@Composable
fun StageBar(counts: Map<Stage, Int>) {
    val total = counts.values.sum()
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (total == 0) G.todo else G.chip)) {
        if (total > 0) for (st in Stage.entries) {
            val n = counts[st] ?: 0
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(when (st) { Stage.MEASURE -> G.todo; Stage.PHOTO -> G.sub; Stage.HANDOFF -> G.ink; Stage.DONE -> G.fix }))
        }
    }
}

@Composable
fun NewSiteSheet(c: GcpController, f: NewSiteForm) {
    Sheet("새 유적", "new_site_sheet", onClose = { c.newSite = null }) {
        GField("유적 이름", f.name, "site_name", placeholder = "예) 강릉 ○○유적") { c.newSite = f.copy(name = it, error = null) }
        GField("약호 (영문 대문자 1~4자)", f.code, "site_code", mono = true, placeholder = "GN") { c.newSite = f.copy(code = ObjIds.normalizeCode(it).take(4), error = null) }
        val preview = ObjIds.format(ObjIds.normalizeCode(f.code).ifEmpty { "GN" }, c.prefs.operator, 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("대상 ID 예: ", style = GT.sansL.sz(13.sp), color = G.sub); IdBox(preview)
            Text("  · 약호는 나중에 못 바꿔요", style = GT.sansL.sz(13.sp), color = G.todo)
        }
        Text("좌표계 (수신기 설정과 같게)", style = GT.sans.sz(14.sp), color = G.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in 5185..5188) GChip("$e\n${GcpController.epsgLabel(e)}", f.epsg == e, "epsg_$e", Modifier.weight(1f)) { c.newSite = f.copy(epsg = e) }
        }
        Text("높이는 수신기 값 그대로(수신기의 좌표계·지오이드) — 앱은 폴 높이만 빼요.", style = GT.sansL.sz(12.sp), color = G.sub)
        f.error?.let { Text(it, style = GT.sans.sz(14.sp), color = G.bad, modifier = Modifier.testTag("site_error")) }
        GBtn("만들기", Modifier.fillMaxWidth(), tag = "site_create", enabled = f.name.isNotBlank() && f.code.isNotBlank()) { c.submitNewSite() }
    }
}

// =============================== ② 대상 관리판 ===============================
@Composable
fun BoardScreen(c: GcpController) {
    val b = c.book ?: return
    Column(Modifier.fillMaxSize().background(G.bg).testTag("board")) {
        GHeader({ c.back() }, "board_header", title = {
            CodeBox(b.site.code, 36.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(b.site.name, style = GT.serif.sz(21.sp), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("대상 ${b.objs.size} · ${b.site.epsg}", style = GT.mono.sz(12.sp), color = G.sub)
            }
        }) { FixPill(c.source) { c.openReceiver() } }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val counts = c.counts()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (st in Stage.entries) {
                    val on = c.filter == st; val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.weight(1f).heightIn(min = 60.dp).clip(shape).background(if (on) G.ink else G.card).border(if (on) 0.dp else 1.dp, G.line, shape)
                            .hClickable(onClickLabel = st.ko + " 만 보기") { c.toggleFilter(st) }.padding(vertical = 6.dp).testTag("count_${st.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val col = if (on) Color.White else when (st) { Stage.MEASURE -> G.todo; Stage.DONE -> G.fix; else -> G.ink }
                        Text("${counts[st] ?: 0}", style = GT.mono.sz(22.sp), color = col)
                        Text(st.ko, style = GT.sans.sz(12.sp), color = if (on) Color.White else G.ink)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, G.line, RoundedCornerShape(12.dp))) {
                    for ((k, label) in listOf(null to "전체", ObjKind.FEATURE to "유구", ObjKind.SECTION to "토층", ObjKind.OTHER to "기타")) {
                        val on = c.kindTab == k
                        Box(Modifier.weight(1f).heightIn(min = 46.dp).background(if (on) G.ink else G.card).hClickable { c.kindTab = k }.testTag("tab_${k?.id ?: "all"}"), contentAlignment = Alignment.Center) {
                            Text(label, style = GT.sans.sz(14.sp), color = if (on) Color.White else G.ink)
                        }
                    }
                }
                GBtn("⌕", Modifier.width(52.dp), if (c.searchOpen) BtnKind.INK else BtnKind.PLAIN, tag = "search_toggle", height = 46.dp) { c.searchOpen = !c.searchOpen; if (!c.searchOpen) c.query = "" }
                GBtn("지도", Modifier.width(64.dp), BtnKind.PLAIN, tag = "open_map", height = 46.dp) { c.go(Screen.SITE_MAP) }
            }
            if (c.searchOpen) GField("ID · 번호 · 이름 검색", c.query, "search_field", placeholder = "예) A012, 5호, B—B′") { c.query = it }
        }
        val rows = c.rows()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (b.objs.isEmpty()) Text("아래 「+ 유구」 · 「+ 토층」으로 첫 대상을 만드세요. 번호는 자동으로 이어져요.", style = GT.sansL.sz(14.sp), color = G.sub, modifier = Modifier.padding(vertical = 20.dp))
            else if (rows.isEmpty()) Text("조건에 맞는 대상이 없어요", style = GT.sansL.sz(14.sp), color = G.sub, modifier = Modifier.padding(vertical = 20.dp).testTag("no_rows"))
            for (r in rows) ObjRowView(c, r)
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GBtn("＋ 유구", Modifier.weight(1f), BtnKind.DASHED, tag = "add_feature", sub = "다음 ${c.nextFeatureNo()}호") { c.openNewObj(ObjKind.FEATURE) }
                GBtn("＋ 토층", Modifier.weight(1f), BtnKind.DASHED, tag = "add_section", sub = "다음 ${c.nextLetter()}—${c.nextLetter()}′") { c.openNewObj(ObjKind.SECTION) }
            }
            val pend = counts(c)[Stage.HANDOFF] ?: 0
            GBtn("PC로 넘기기 · 전달 필요 $pend", Modifier.fillMaxWidth(), tag = "open_handoff") { c.handoffScope = if (pend > 0) HandoffScope.PENDING else HandoffScope.TODAY; c.handoffOpen = true }
        }
    }
}

private fun counts(c: GcpController) = c.counts()

@Composable
private fun ObjRowView(c: GcpController, r: ObjRow) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(G.card).border(1.dp, G.line, shape).hClickable { c.openObj(r.obj.id) }.testTag("row_${r.obj.code}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(92.dp).background(if (r.stage == Stage.MEASURE) G.todo else Color.Transparent))
        Box(Modifier.padding(start = 10.dp, end = 10.dp)) { IdBox(r.obj.code) }
        Column(Modifier.weight(1f).padding(vertical = 10.dp).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.obj.name, style = GT.serif.sz(18.sp), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp)); KindBadge(r.obj.kind)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val s = r.session
                Text((s?.let { "${it.no}차 · ${it.label} · " } ?: "") + "T ${r.measured}/${r.slots}  ", style = GT.mono.sz(12.sp), color = G.sub, maxLines = 1)
                StageChips(r.stage)
            }
            val s = r.session
            if (s != null) FolderLine(Names.photoFolder(r.obj, s), if (r.xmlName == null) "XML 없음" else "XML ${c.timeLabel(s.xmlSavedAt).removePrefix("오늘 ")}")
        }
    }
}

@Composable
fun NewObjSheet(c: GcpController, f: NewObjForm) {
    Sheet(when (f.kind) { ObjKind.FEATURE -> "새 유구"; ObjKind.SECTION -> "새 토층"; ObjKind.OTHER -> "새 대상" }, "new_obj_sheet", onClose = { c.newObj = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in ObjKind.entries) GChip(k.ko, f.kind == k, "kind_${k.id}", Modifier.weight(1f)) { c.newObj = f.copy(kind = k, custom = null) }
        }
        when (f.kind) {
            ObjKind.FEATURE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("호수", style = GT.sans.sz(15.sp), color = G.ink, modifier = Modifier.weight(1f))
                    Stepper("${f.featureNo}호", "feature_no", { c.newObj = f.copy(featureNo = (f.featureNo - 1).coerceAtLeast(1), custom = null) }, { c.newObj = f.copy(featureNo = f.featureNo + 1, custom = null) })
                }
                Row(Modifier.horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in AutoNumber.FEATURE_SUBTYPES) GChip(t, f.subtype == t, "subtype_$t") { c.newObj = f.copy(subtype = t, custom = null) }
                }
            }
            ObjKind.SECTION -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("단면", style = GT.sans.sz(15.sp), color = G.ink, modifier = Modifier.weight(1f))
                Stepper("${f.letter}—${f.letter}′", "letter", { c.newObj = f.copy(letter = letterStep(f.letter, -1), custom = null) }, { c.newObj = f.copy(letter = letterStep(f.letter, 1), custom = null) })
            }
            ObjKind.OTHER -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("번호", style = GT.sans.sz(15.sp), color = G.ink, modifier = Modifier.weight(1f))
                Stepper("${f.otherNo}", "other_no", { c.newObj = f.copy(otherNo = (f.otherNo - 1).coerceAtLeast(1), custom = null) }, { c.newObj = f.copy(otherNo = f.otherNo + 1, custom = null) })
            }
        }
        GField("이름 (바꿔도 ID 는 그대로)", f.name(), "obj_name") { c.newObj = f.copy(custom = it) }
        Text("1차 이름", style = GT.sans.sz(14.sp), color = G.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in AutoNumber.SESSION_LABELS) GChip(l, f.label == l, "label_$l", Modifier.weight(1f)) { c.newObj = f.copy(label = l) }
        }
        val code = c.nextCode()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { IdBox(code, big = true, modifier = Modifier.testTag("new_obj_id")); Spacer(Modifier.width(10.dp)); Text("이 대상의 ID", style = GT.sansL.sz(13.sp), color = G.sub) }
            FolderLine("사진 폴더: ${code}_${Names.safe(f.name())}_1차")
            Text("표적 #${QrPayload.ranges(f.slots)} (실물 표적 번호 · 측점 화면에서 바꿀 수 있어요)", style = GT.mono.sz(12.sp), color = G.sub)
            f.nearNote?.let { Text(it, style = GT.sans.sz(12.sp), color = G.warn, modifier = Modifier.testTag("near_note")) }
        }
        GBtn("만들고 측점 시작", Modifier.fillMaxWidth(), tag = "obj_create") { c.submitNewObj() }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.horizontalScroll(rememberScrollState())

fun letterStep(l: String, d: Int): String {
    if (l.length != 1) return if (d > 0) l else "A"
    val ch = (l[0] + d).coerceIn('A', 'Z')
    return ch.toString()
}

@Composable
fun HandoffSheet(c: GcpController) {
    Sheet("PC로 넘기기", "handoff_sheet", onClose = { c.handoffOpen = false }) {
        Text("zip 하나에 목록.csv 와 대상별 XML·TXT 가 들어가요. 공유 창에서 드라이브 · 메일 · 카카오톡 · Quick Share 등으로 보내세요.", style = GT.sansL.sz(13.sp), color = G.sub)
        for (sc in HandoffScope.entries) {
            val n = c.handoffCount(sc); val on = c.handoffScope == sc; val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(if (on) G.ink else G.card).border(1.dp, if (on) G.ink else G.line, shape)
                    .hClickable { c.handoffScope = sc }.padding(horizontal = 14.dp).testTag("scope_${sc.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sc.ko, style = GT.sans.sz(16.sp), color = if (on) Color.White else G.ink, modifier = Modifier.weight(1f))
                Text("대상 $n", style = GT.mono.sz(15.sp), color = if (on) Color.White else G.sub)
            }
        }
        val p = c.handoffPlan()
        if (p != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.zipName, style = GT.monoM.sz(13.sp), color = G.ink, modifier = Modifier.testTag("zip_name"))
                for ((path, _) in p.entries.take(7)) Text("  $path", style = GT.mono.sz(11.sp), color = G.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.entries.size > 7) Text("  … 파일 ${p.entries.size}개", style = GT.mono.sz(11.sp), color = G.sub)
            }
            if (p.noPhoto.isNotEmpty()) Text("⚠ 사진 아직: ${p.noPhoto.joinToString(", ")} — 넣기는 하지만 「전달」로 바꾸지 않아요", style = GT.sans.sz(12.sp), color = G.warn, modifier = Modifier.testTag("handoff_nophoto"))
            GBtn("zip 보내기 · 대상 ${p.sessionIds.size}", Modifier.fillMaxWidth(), tag = "handoff_send", enabled = p.sessionIds.isNotEmpty()) { c.sendHandoff() }
        }
    }
}

// =============================== 유적 지도 ===============================
@Composable
fun SiteMapScreen(c: GcpController, env: GcpEnv) {
    val b = c.book ?: return
    var picked by remember { mutableStateOf<Long?>(null) }
    val g by c.source.gnss.collectAsState()
    val q = c.query.trim().lowercase()
    val data = b.objs.mapNotNull { o ->
        val s = b.current(o) ?: return@mapNotNull null
        val sh = b.active(s); if (sh.isEmpty()) return@mapNotNull null
        Triple(o, sh, b.stageOf(o))
    }.filter { (o, _, _) -> q.isEmpty() || o.code.lowercase().contains(q) || o.name.lowercase().contains(q) }
    val pins = data.map { (o, sh, st) -> MapPin(sh.map { it.lat }.average(), sh.map { it.lon }.average(), ObjIds.split(o.code).second, G.stage(st), st != Stage.MEASURE, o.id, big = o.id == picked) }
    val me = g.gga?.let { gg -> gg.lat?.let { la -> gg.lon?.let { lo -> la to lo } } }
    Column(Modifier.fillMaxSize().background(G.bg).testTag("site_map")) {
        GHeader({ c.back() }, "map_header", title = { Text("${b.site.name} · 지도", style = GT.serif.sz(20.sp), color = G.ink, maxLines = 1, modifier = Modifier.weight(1f)) }) { FixPill(c.source) { c.openReceiver() } }
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { GField("ID · 이름 검색", c.query, "map_search") { c.query = it } }
        GcpMap(pins, me, env.mapAccess(), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), minSpanM = 60.0,
            caption = "위성 + 지적 · 테라코타 = 측점 필요 · 초록 = 끝") { picked = it.id }
        val none = b.objs.size - data.size
        if (none > 0) Text("좌표 없는 대상 $none 개는 지도에 없어요", style = GT.sansL.sz(12.sp), color = G.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val o = b.objs.firstOrNull { it.id == picked }
        if (o != null) {
            val sh = data.firstOrNull { it.first.id == o.id }?.second.orEmpty()
            Row(Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(14.dp)).background(G.card).border(1.dp, G.line, RoundedCornerShape(14.dp)).padding(12.dp).testTag("map_pick"), verticalAlignment = Alignment.CenterVertically) {
                IdBox(o.code); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.name, style = GT.serif.sz(17.sp), color = G.ink, maxLines = 1)
                    val here = c.here()
                    if (here != null && sh.isNotEmpty()) {
                        val e = sh.map { it.e }.average(); val n = sh.map { it.n }.average()
                        Text("나에게서 ${Math.round(Math.hypot(e - here.first, n - here.second))} m · ${bearingKo(e - here.first, n - here.second)}", style = GT.mono.sz(12.sp), color = G.sub)
                    }
                }
                GBtn("이 대상 측점", Modifier.width(130.dp), tag = "map_open_obj", height = 48.dp) { c.back(); c.openObj(o.id) }
            }
        } else Spacer(Modifier.height(14.dp))
    }
}

fun bearingKo(de: Double, dn: Double): String {
    val a = (Math.toDegrees(Math.atan2(de, dn)) + 360) % 360
    return listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[((a + 22.5) / 45).toInt() % 8]
}

@Composable
fun SlotEditDialog(c: GcpController, e: SlotEdit) {
    Sheet(if (e.index < 0) "표적 추가" else "표적 번호 바꾸기", "slot_sheet", onClose = { c.slotEdit = null }) {
        Text("실물 표적에 적힌 번호를 넣으세요. iTwin 에서 「Target #번호」로 맞춰져요.", style = GT.sansL.sz(13.sp), color = G.sub)
        GField("표적 번호", e.text, "slot_number", kb = KeyboardType.Number, mono = true) { c.slotEdit = e.copy(text = it.filter { ch -> ch.isDigit() }.take(4), error = null) }
        e.error?.let { Text(it, style = GT.sans.sz(14.sp), color = G.bad, modifier = Modifier.testTag("slot_error")) }
        GBtn("확인", Modifier.fillMaxWidth(), tag = "slot_ok") { c.submitSlot() }
    }
}

@Composable
fun RenameDialog(c: GcpController, text: String) {
    Sheet("이름 바꾸기", "rename_sheet", onClose = { c.renaming = null }) {
        Text("ID ${c.obj?.code} 와 폴더 앞부분은 그대로예요. 옛 이름은 목록.csv 「옛 이름」 칸에 남아요.", style = GT.sansL.sz(13.sp), color = G.sub)
        GField("새 이름", text, "rename_field") { c.renaming = it }
        GBtn("바꾸기", Modifier.fillMaxWidth(), tag = "rename_ok") { c.submitRename() }
    }
}

@Composable
fun NewSessionSheet(c: GcpController, f: NewSessionForm) {
    Sheet("새 차수", "new_session_sheet", onClose = { c.newSession = null }) {
        val n = (c.sessionsOfObj().maxOfOrNull { it.no } ?: 0) + 1
        Text("같은 대상을 다시 드러낸 뒤(완토 · 바닥 등) 새로 찍을 때. 앞 차수 파일은 그대로 남고, 새 파일은 「_${n}차」로 따로 생겨요.", style = GT.sansL.sz(13.sp), color = G.sub)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in AutoNumber.SESSION_LABELS) GChip(l, f.label == l, "slabel_$l", Modifier.weight(1f)) { c.newSession = f.copy(label = l) }
        }
        Text("표적 #${QrPayload.ranges(f.slots)} (앞 차수와 같은 번호 · 바꿀 수 있어요)", style = GT.mono.sz(12.sp), color = G.sub)
        GBtn("${n}차 시작", Modifier.fillMaxWidth(), tag = "session_create") { c.submitNewSession() }
    }
}
