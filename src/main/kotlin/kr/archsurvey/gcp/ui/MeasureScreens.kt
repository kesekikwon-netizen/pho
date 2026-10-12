package kr.archsurvey.gcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.StatusJudge
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.gcp.measure.Gate
import kr.archsurvey.gcp.measure.MeasureGate
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.hClickable
import kr.archsurvey.ui.hCombinedClickable
import java.time.Instant
import java.time.format.DateTimeFormatter

private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

// =============================== ③ 측점 화면 ===============================
@Composable
fun MeasureScreen(c: GcpController, env: GcpEnv) {
    val b = c.book ?: return
    val o = c.obj ?: return
    val s = c.session ?: return
    c.poleTick
    val g by c.source.gnss.collectAsState()
    val l by c.source.link.collectAsState()
    val tick = kr.archsurvey.ui.rememberTick()
    val gate = remember(g, l, tick) { MeasureGate.check(StatusJudge.judge(g, l.state, c.source.clockMs())) }
    val locked = c.locked()
    val act = b.active(s)
    Column(Modifier.fillMaxSize().background(G.bg).testTag("measure")) {
        GHeader({ c.back() }, "measure_header", title = {
            IdBox(o.code); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).hClickable(onClickLabel = "이름 바꾸기") { c.renaming = o.name }.testTag("obj_title")) {
                Text(o.name, style = GT.serif.sz(18.sp), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("폴 ${String.format(java.util.Locale.US, "%.1f", c.poleM())} · ${b.site.epsg}", style = GT.mono.sz(12.sp), color = G.sub, maxLines = 1)
            }
        }) { FixPill(c.source) { c.openReceiver() } }
        // 차수 칩
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("차수", style = GT.sans.sz(13.sp), color = G.sub)
            for (x in c.sessionsOfObj()) {
                val n = b.active(x).size
                GChip("${x.no}차 · ${x.label} · $n/${x.slots.size}", x.id == s.id, "session_${x.no}") { c.pickSession(x.id) }
            }
            GChip("+ 차수", false, "new_session") { c.openNewSession() }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val me = g.gga?.let { gg -> gg.lat?.let { la -> gg.lon?.let { lo -> la to lo } } }
            val pins = s.slots.map { t -> t to act.firstOrNull { it.tag == t } }.mapNotNull { (t, sh) -> sh?.let { MapPin(it.lat, it.lon, "#$t", G.fix, true, t.toLong()) } }
            GcpMap(pins, me, env.mapAccess(), Modifier.fillMaxWidth().height(190.dp), caption = "평면 · 위가 북 · 위성 + 지적")
            if (locked) Banner("잠긴 차수 — 보기만 돼요. 새로 찍으려면 「+ 차수」.", G.sub, "locked_banner")
            else when (gate) {
                is Gate.Blocked -> Banner("못 재요 — ${gate.reasonKo}", G.todo, "gate_banner")
                Gate.AskNoSigma -> Banner("FIX · 정밀도(σ) 없음 — 재기 전에 한 번 물어요", G.warn, "gate_banner")
                Gate.Ok -> {}
            }
            val slots = s.slots
            for (row in slots.indices.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in row) TargetCard(c, i, slots[i], act.firstOrNull { it.tag == slots[i] }, gate is Gate.Ok || gate is Gate.AskNoSigma, locked, Modifier.weight(1f))
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            if (!locked) GBtn("＋ 표적 추가 · #${TargetChecks.nextFree(slots.toSet(), (slots.maxOrNull() ?: 0) + 1)}", Modifier.fillMaxWidth(), BtnKind.DASHED, tag = "add_slot") { c.openAddSlot() }
            val xml = s.xmlSavedAt?.let { Names.xmlName(o, s, b.site.epsg) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hClickable(onClickLabel = "XML 보내기") { c.shareXml() }.padding(vertical = 6.dp).testTag("xml_line"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    FolderLine(Names.objRel(b.site, o).removePrefix("${Names.ROOT}/"))
                    Text(xml?.let { "$it · ${c.timeLabel(s.xmlSavedAt)} 저장" } ?: "XML 은 첫 표적을 재면 바로 저장돼요", style = GT.mono.sz(11.sp), color = if (xml == null) G.warn else G.sub, maxLines = 2)
                }
                if (xml != null) Text("보내기 ›", style = GT.sans.sz(14.sp), color = G.ink)
            }
            Spacer(Modifier.height(4.dp))
        }
        val st = b.stage(s)
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GBtn("ID 카드 · 사진 시작", Modifier.weight(1f), BtnKind.PLAIN, tag = "open_idcard") { c.openIdCard() }
            when {
                act.size < Pipeline.MIN_TARGETS -> GBtn("표적 ${Pipeline.MIN_TARGETS}개부터", Modifier.weight(1f), enabled = false, tag = "photo_done") {}
                s.photoDoneAt == null -> GBtn("사진 다 찍었어요 ✓", Modifier.weight(1f), tag = "photo_done") { c.markPhotoDone() }
                else -> GBtn("사진 끝 ✓", Modifier.weight(1f), BtnKind.PLAIN, tag = "photo_done", sub = st.ko) { c.undoPhotoDone() }
            }
        }
    }
}

@Composable
private fun Banner(text: String, col: Color, tag: String) {
    Text(text, style = GT.sans.sz(14.sp), color = col, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.08f)).border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp).testTag(tag))
}

@Composable
private fun TargetCard(c: GcpController, index: Int, tag: Int, shot: Shot?, canMeasure: Boolean, locked: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val measuring = c.measuringTag == tag
    val done = shot != null
    Column(
        modifier.heightIn(min = 188.dp).clip(shape).background(if (done) Color(0xFFF7FAF4) else G.card)
            .border(if (done || measuring) 2.5.dp else 1.dp, if (measuring) G.todo else if (done) G.fix else G.line, shape)
            .hCombinedClickable(onLongClick = { c.onTargetLong(index) }, onClick = { c.onTargetTap(tag) })
            .padding(12.dp).testTag("target_$tag"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("Target #$tag", style = GT.serif.sz(22.sp), color = G.ink, maxLines = 1, modifier = Modifier.weight(1f))
            if (done) Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(G.fix), contentAlignment = Alignment.Center) { Text("✓", style = GT.sans.sz(16.sp), color = Color.White) }
        }
        when {
            measuring -> {
                Text("재는 중 · ${Math.round(c.progress * c.prefs.epochs)}/${c.prefs.epochs}", style = GT.sans.sz(13.sp), color = G.todo)
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(G.chip)) {
                    Box(Modifier.fillMaxWidth(c.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(G.todo))
                }
                GBtn("취소", Modifier.fillMaxWidth(), BtnKind.PLAIN, tag = "measure_cancel", height = 48.dp) { c.cancelMeasure() }
            }
            done -> {
                val t = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(shot!!.measuredAt).atZone(c.zone))
                Text("$t · 길게 눌러 다시", style = GT.sansL.sz(12.sp), color = G.sub)
                Spacer(Modifier.height(6.dp))
                Text("E ${f3(shot.e)}", style = GT.mono.sz(14.sp), color = G.ink)
                Text("N ${f3(shot.n)}", style = GT.mono.sz(14.sp), color = G.ink)
                Text("H ${f3(shot.h)}", style = GT.mono.sz(14.sp), color = G.ink)
                Text("FIX · " + (shot.sigmaH?.let { String.format(java.util.Locale.US, "±%.1f cm", it * 100) } ?: "σ 없음"), style = GT.sans.sz(12.sp), color = G.fix)
            }
            else -> {
                Text("아직 안 잼 · 길게 눌러 번호 바꾸기", style = GT.sansL.sz(12.sp), color = G.sub)
                Spacer(Modifier.weight(1f))
                val ok = canMeasure && !locked && c.measuringTag == null
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(if (ok) G.todo else G.chip), contentAlignment = Alignment.Center) {
                    Text(if (locked) "잠김" else "측점", style = GT.sans.sz(18.sp), color = if (ok) Color.White else Color(0xFF8A887F))
                }
            }
        }
    }
}

// =============================== ④ ID 카드 ===============================
@Composable
fun IdCardScreen(c: GcpController) {
    val b = c.book ?: return; val o = c.obj ?: return; val s = c.session ?: return
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val act = view.context as? android.app.Activity
        val w = act?.window; val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        onDispose { if (w != null && old != null) w.attributes = w.attributes.apply { screenBrightness = old } }
    }
    val qr = c.qr()
    val matrix = remember(qr) { QrMatrix.of(qr) }
    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 18.dp, vertical = 16.dp).testTag("idcard"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("사진 세트 첫 장으로 이 화면을 찍으세요 · 화면 밝기 최대", style = GT.sansL.sz(13.sp), color = G.sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text(o.code, style = GT.monoM.sz(if (o.code.length > 8) 52.sp else 64.sp), color = G.ink, maxLines = 1, modifier = Modifier.testTag("idcard_code"))
        Text(o.name, style = GT.serif.sz(30.sp), color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        val d = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(s.createdAt).atZone(c.zone))
        Text("${s.no}차 · ${s.label} · $d", style = GT.mono.sz(19.sp), color = G.ink)
        Spacer(Modifier.height(20.dp))
        Canvas(Modifier.size(232.dp).testTag("idcard_qr")) {
            val n = matrix.size; if (n == 0) return@Canvas
            val cell = size.width / n
            for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
        Spacer(Modifier.height(14.dp))
        Text("QR = $qr", style = GT.mono.sz(12.sp), color = G.sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Row { Text("사진 폴더: ", style = GT.sans.sz(14.sp), color = G.ink); Text(Names.photoFolder(o, s), style = GT.monoM.sz(14.sp), color = G.ink) }
        Text("표적 ${b.active(s).size}/${s.slots.size} · EPSG:${b.site.epsg}", style = GT.mono.sz(12.sp), color = G.sub)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GBtn("닫기", Modifier.weight(0.8f), BtnKind.PLAIN, tag = "idcard_close") { c.back() }
            if (s.photoDoneAt == null) GBtn("사진 다 찍었어요 ✓", Modifier.weight(1.2f), tag = "idcard_done", enabled = b.active(s).size >= Pipeline.MIN_TARGETS) { c.markPhotoDone() }
            else GBtn("사진 끝 ✓", Modifier.weight(1.2f), BtnKind.PLAIN, tag = "idcard_done") { c.back() }
        }
    }
}

/** zxing core(Apache-2.0)로 QR 칸만 만든다. 여백 0, 오류 정정 M */
object QrMatrix {
    fun of(text: String): Array<BooleanArray> {
        if (text.isEmpty()) return emptyArray()
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }
}
