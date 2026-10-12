package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.gcp.model.*
import kr.archsurvey.map.*
import kr.archsurvey.ui.*
import java.util.Locale

/**
 * 0.1.1 유적 화면 = 측량 앱 지도 화면(0.1.35–0.1.37)과 같은 배치:
 * 위 한 줄(‹ 유적 이름 · 상태 알약 · ≡) / 넓은 위성 + 지적 지도(측량 앱 CanvasMapSurface·TileImages 그대로) / 「지금 재는 것」+ 폴 / Target 단추 줄 / 차수 · ID 카드 · 측량하기.
 */
@Composable
fun SiteMapScreen(c: GcpController, env: GcpEnv) {
    val t = T
    val b = c.book ?: return
    val g by c.source.gnss.collectAsState()
    LaunchedEffect(g.gga) { c.onFix() }
    Column(Modifier.fillMaxSize().background(t.bg).testTag("site_map")) {
        GTopBar(b.site.name + " · 지도", { c.back() }, c, onMenu = { c.menuOpen = true })
        Box(Modifier.weight(1f)) {
            MapArea(c, env, Modifier.fillMaxSize())
            if (c.measuringTag != null) MeasuringChip(c, Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 76.dp, end = 76.dp))
        }
        ControlArea(c)
    }
}

/** 지도에 그릴 것: 대상마다 지금 차수 표적(검은 표적 모양 + #번호) · 대상 둘레 점선 + 이름 · 내 위치 */
fun GcpController.mapState(access: VWorldAccess): MapViewState {
    val b = book
    val markers = ArrayList<MapMarker>(); val outlines = ArrayList<MapOutline>()
    if (b != null) for (o in b.objs) {
        val s = (if (o.id == objId) session else null) ?: b.current(o) ?: continue
        val shots = b.active(s)
        val pts = shots.mapNotNull { sh -> local(sh.lat, sh.lon)?.let { sh to it } }
        val mine = o.id == objId
        for ((sh, p) in pts) markers += MapMarker("", p, flagged = false, sym = MapSym.TAG, label = if (mine) "#${sh.tag}" else null)   // 다른 대상은 둘레 이름만(겹침 줄임)
        if (pts.size >= 2) {
            val cx = pts.sumOf { it.second.x } / pts.size; val cy = pts.sumOf { it.second.y } / pts.size
            val ring = pts.map { it.second }.sortedBy { Math.atan2(it.y - cy, it.x - cx) }
            outlines += MapOutline(if (mine) o.name else o.short, ring, closed = ring.size >= 3, active = false, colorIdx = 0, kind = if (mine) "area" else "trench")
        }
    }
    val gq = source.gnss.value
    val cur = latLon()?.let { (la, lo) -> local(la, lo) }?.let { p ->
        val r = StatusJudge.judge(gq, source.link.value.state, source.clockMs())
        CurrentMarker(p, r.sigmaHM ?: 0.05, r.status, null)
    }
    val tiles = MapLayers.resolve(MapBase.SATELLITE_CADASTRAL, access)
    return MapViewState(center, dpPerM, markers, cur, emptyList(), outlines = outlines, tiles = if (projection != null) tiles else emptyList(), geo = projection)
}

@Composable
fun MapArea(c: GcpController, env: GcpEnv, modifier: Modifier) {
    val t = T
    val access = remember { env.mapAccess() }
    val surface = remember { CanvasMapSurface() }
    val ms = c.mapState(access)
    Box(
        modifier.fillMaxWidth().testTag("map_area").pointerInput(Unit) {
            // 측량 앱과 같음: 한 손가락 이동 + 두 손가락 확대(손가락 아래 고정)
            detectTransformGestures { centroid, pan, zoom, _ ->
                val ppm = (c.dpPerM * density).toFloat()
                if (pan.x != 0f || pan.y != 0f) c.panBy((-pan.x / ppm).toDouble(), (pan.y / ppm).toDouble())
                if (zoom != 1f) c.scaleBy(zoom.toDouble(), ((centroid.x - size.width / 2f) / ppm).toDouble(), (-(centroid.y - size.height / 2f) / ppm).toDouble())
            }
        },
    ) {
        surface.Draw(ms, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBtn(Glyph.LOCATE, "내 위치", "tool_locate", c.follow) { if (c.follow) c.follow = false else c.recenter() }
            MapBtn(Glyph.PLUS, "확대", "tool_zoom_in") { c.zoomIn() }
            MapBtn(Glyph.MINUS, "축소", "tool_zoom_out") { c.zoomOut() }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBtn(Glyph.EXTENT, "유적 전체 보기", "tool_fit") { c.fitAll() }
        }
        if (c.latLon() == null && c.projection == null) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp).clip(RoundedCornerShape(18.dp)).background(t.bg.copy(alpha = 0.96f))
                    .border(1.dp, t.outline, RoundedCornerShape(18.dp)).padding(16.dp).testTag("no_position_card"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("수신기를 연결하면 위성지도와 지적도가 나와요", style = Ty.label.copy(fontSize = 15.sp), color = t.ink, textAlign = TextAlign.Center)
                Btn("수신기 연결하기", "no_position_connect", Modifier.fillMaxWidth(), height = 56.dp) { c.openReceiver() }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val notice = MapLayers.fallbackMessage(MapBase.SATELLITE_CADASTRAL, access.key)?.let { "V월드 키가 없어 배경지도를 못 보여줘요 · 설정 › 고급" } ?: TileImages.lastError
            notice?.let { Text(it, style = Ty.small, color = t.bad, maxLines = 3, modifier = Modifier.widthIn(max = 260.dp).background(t.raised.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map_notice")) }
            if (ms.tiles.isNotEmpty()) Text("© V월드(국토교통부) · 위성 + 지적", style = Ty.small.copy(fontSize = 11.sp), color = t.ink, modifier = Modifier.background(t.raised.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("map_attribution"))
        }
        Text(scaleText(c.dpPerM), style = Ty.coord.copy(fontSize = 12.sp), color = t.ink,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(t.bg.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("scale_label"))
    }
}

/** 측량 앱 ToolBtn 과 같은 56dp 흰 네모(누르면 켜짐 = 테라코타) */
@Composable
private fun MapBtn(g: Glyph, desc: String, tag: String, on: Boolean = false, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(if (on) G.dark else t.raised).then(if (on) Modifier else Modifier.border(1.dp, t.outline, RoundedCornerShape(16.dp)))
            .hClickable(onClickLabel = desc) { onClick() }.semantics { contentDescription = desc }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(g, if (on) G.onDark else G.dark, 24.dp) }
}

/** 화면 1 cm ≈ 실제 m (측량 앱 scaleText 와 같은 생각) */
fun scaleText(dpPerM: Double): String {
    val mPerCm = 63.0 / dpPerM // 1 cm ≈ 63 dp(밀도 무관 근사)
    val step = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0).firstOrNull { it >= mPerCm } ?: 1000.0
    return if (step < 1) "${(step * 100).toInt()} cm" else "${step.toInt()} m"
}

// =============================== 아래 조작부 ===============================
@Composable
fun ControlArea(c: GcpController) {
    val t = T
    val o = c.obj; val s = c.session
    c.poleTick
    Column(Modifier.fillMaxWidth().background(t.bg).testTag("control_area")) {
        NowRow(c)
        if (o != null && s != null) TargetRow(c, s)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (o != null && s != null) {
                SideBtn(Glyph.LIST, "${s.no}차", "tool_session") { c.sessionSheetOpen = true }
                SideBtn(Glyph.CAMERA, if (c.stageNow() == Stage.PHOTO) "ID 카드!" else "ID 카드", "tool_idcard", on = c.stageNow() == Stage.PHOTO) { c.openIdCard() }
            }
            MeasureButton(c, Modifier.weight(1f))
        }
    }
}

/** 「지금 재는 것」(누르면 대상 고르기) + 폴 칩 — 측량 앱 NowRow 모양 */
@Composable
private fun NowRow(c: GcpController) {
    val t = T
    val o = c.obj; val s = c.session
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(14.dp))
                .hClickable(onClickLabel = "대상 고르기") { c.objListOpen = true }.padding(horizontal = 14.dp).testTag("now_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val st = if (o != null && s != null) c.stageNow() else null
                Text("지금 재는 것" + (st?.let { " · ${it.ko}" } ?: ""), style = Ty.small.copy(fontSize = 11.sp), color = if (st == Stage.MEASURE || st == null) t.inkSub else G.dot, maxLines = 1, modifier = Modifier.testTag("now_stage"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o?.name ?: "대상을 고르세요", style = Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1, modifier = Modifier.testTag("now_obj"))
                    if (s != null) Text(" · ${s.no}차 ${s.label}", style = Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = G.dot, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("now_session"))
                }
            }
            GlyphIcon(Glyph.CHEVRON_DOWN, t.inkSub, 18.dp)
        }
        Column(
            Modifier.width(72.dp).height(60.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(14.dp))
                .hClickable(onClickLabel = "폴 높이") { c.poleOpen = true }.testTag("pole_chip"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(String.format(Locale.US, "%.1f", c.poleM()), style = Ty.ledger.copy(fontSize = 20.sp), color = t.ink, maxLines = 1)
            Text("폴 m", style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub)
        }
    }
}

/** Target 단추 줄(측량 앱 0.1.37 QuickTagRow 모양): 칸마다 60dp, 잰 것 ✓, 길게 = 다시 재기 / 번호 바꾸기. 끝에 ＋ */
@Composable
private fun TargetRow(c: GcpController, s: Session) {
    val t = T
    val locked = c.locked()
    val many = s.slots.size > 4
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp).testTag("qtag_row"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Target", style = Ty.label.copy(fontSize = 12.sp), color = t.ink, maxLines = 1)
            Text("${c.activeShots().size}/${s.slots.size}", style = Ty.coord.copy(fontSize = 13.sp), color = if (c.activeShots().size >= Pipeline.MIN_TARGETS) G.ok else G.dot, maxLines = 1, modifier = Modifier.testTag("qtag_count"))
            if (locked) Text("잠김", style = Ty.small.copy(fontSize = 10.sp), color = t.inkSub)
        }
        val cell: @Composable (Int, Int, Modifier) -> Unit = { i, tag, m ->
            val shot = c.shotOf(tag)
            val now = c.measuringTag == tag
            Box(
                m.height(60.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (now) G.dark else if (shot != null) G.sel else t.raised)
                    .border(if (shot != null || now) 2.dp else 1.dp, if (shot != null || now) G.dark else t.outline, RoundedCornerShape(14.dp))
                    .hCombinedClickable(onLongClick = { c.onTargetLong(i) }) { c.onTargetTap(tag) }
                    .semantics { contentDescription = "Target $tag" + if (shot != null) " · 잰 것, 다시 재려면 길게" else " · 누르면 바로 측점, 길게 = 번호 바꾸기" }
                    .testTag("qtag_$tag"),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$tag", style = Ty.ledger.copy(fontSize = if (tag >= 100) 22.sp else 28.sp), color = if (now) G.onDark else t.ink, maxLines = 1)
                    if (shot != null && !now) Text("✓", style = Ty.label.copy(fontSize = 18.sp), color = G.dark, modifier = Modifier.testTag("qtag_done_$tag"))
                }
                if (now) Text("재는 중", style = Ty.small.copy(fontSize = 10.sp), color = G.onDark, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
            }
        }
        if (many) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.slots.forEachIndexed { i, tag -> cell(i, tag, Modifier.width(64.dp)) }
        } else s.slots.forEachIndexed { i, tag -> cell(i, tag, Modifier.weight(1f)) }
        if (!many && s.slots.size < 4) repeat(4 - s.slots.size) { Spacer(Modifier.weight(1f)) }
        if (!locked) Box(
            Modifier.size(width = 48.dp, height = 60.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(14.dp))
                .hClickable(onClickLabel = "표적 더하기") { c.openAddSlot() }.testTag("add_slot"),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = Ty.title.copy(fontSize = 28.sp), color = G.dark) }
    }
}

@Composable
private fun RowScope.SideBtn(g: Glyph, label: String, tag: String, on: Boolean = false, onClick: () -> Unit) {
    val t = T
    Column(
        Modifier.width(76.dp).height(84.dp).clip(RoundedCornerShape(16.dp)).background(if (on) G.sel else t.raised)
            .border(if (on) 2.dp else 1.dp, if (on) G.dark else t.outline, RoundedCornerShape(16.dp)).hClickable(onClickLabel = label, onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(g, t.ink, 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = Ty.label.copy(fontSize = 13.sp), color = t.ink, maxLines = 1)
    }
}

/** 큰 「측량하기」(측량 앱과 같은 84dp 테라코타). 재는 중 = 진행 막대 + 누르면 중지 */
@Composable
private fun MeasureButton(c: GcpController, modifier: Modifier) {
    val t = T
    val p = rememberPill(c.source)
    val busy = c.measuringTag != null
    val noObj = c.obj == null
    val notConn = p.tone == PTone.NO_RECEIVER
    val blocked = p.tone != PTone.FIXED
    val locked = !noObj && c.locked()
    val next = if (noObj) null else c.nextTag()
    val label: String; val sub: String
    when {
        busy -> { label = "재는 중 · Target #${c.measuringTag}"; sub = "폴을 세운 채 · 누르면 중지" }
        noObj -> { label = "+ 새 대상"; sub = "종류 → 호수 고르기" }
        locked -> { label = "앞 차수 · 보기만"; sub = "새로 재려면 「차수」에서 마지막 차수" }
        notConn -> { label = "수신기 연결"; sub = "눌러서 연결" }
        blocked -> { label = "FIX 기다리는 중"; sub = "FIX 때만 저장해요" }
        else -> { label = "측량하기"; sub = "Target #$next" + if (c.session?.slots?.contains(next) == false) " (새 칸)" else "" }
    }
    val dim = !busy && !noObj && (locked || (blocked && !notConn))
    Box(
        modifier.height(84.dp).clip(RoundedCornerShape(18.dp)).background(if (dim || (notConn && !noObj && !busy)) G.lineStrong else G.dark)
            .hClickable(onClickLabel = label) { if (!locked || busy) c.onMeasureButton() }.testTag("measure_button"),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(c.progress.coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = 0.85f)).testTag("measure_progress"))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Ty.button.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium), color = G.onDark, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.testTag("measure_label"))
            Text(sub, style = Ty.small.copy(fontSize = 12.sp, lineHeight = 15.sp), color = G.onDark, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).testTag("measure_sub"))
        }
    }
}
