package kr.archsurvey.gcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.*
import java.util.Locale

/** 0.1.1 모든 고르기 창: 글자 입력 없이 큰 타일·번호판(엄지로 닿는 아래쪽) */

// ---------------- 대상 고르기(진행 칸 한 줄 = 거르기) ----------------
@Composable
fun ObjListSheet(c: GcpController) {
    val t = T
    val counts = c.counts()
    BottomSheet("대상 고르기", "obj_sheet", onClose = { c.objListOpen = false }, bottom = {
        Btn("+ 새 대상", "obj_new", Modifier.weight(1f)) { c.openNewObj() }
    }) {
        // 진행 칸 한 줄(누르면 그것만)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("stage_row")) {
            for (st in Stage.entries) {
                val on = c.filter == st
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(if (on) t.primary else t.raised)
                        .border(1.dp, if (on) t.primary else t.outline, RoundedCornerShape(12.dp)).hClickable(onClickLabel = st.ko) { c.toggleFilter(st) }.testTag("stage_${st.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("${counts[st] ?: 0}", style = Ty.ledger.copy(fontSize = 20.sp), color = if (on) t.onPrimary else if (st == Stage.DONE) t.fixed else t.ink)
                    Text(st.ko, style = Ty.small.copy(fontSize = 16.sp), color = if (on) t.onPrimary else t.inkSub, maxLines = 1)
                }
            }
        }
        val rows = c.rows()
        if (rows.isEmpty()) Caption(if (c.filter != null) "「${c.filter?.ko}」 대상이 없어요" else "아직 대상이 없어요 · 아래 「+ 새 대상」", tag = "obj_empty")
        for (r in rows) {
            val sel = r.obj.id == c.objId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) t.primaryCont else t.raised)
                    .border(if (sel) 2.dp else 1.dp, if (sel) t.primary else t.outline, RoundedCornerShape(14.dp)).hClickable(onClickLabel = r.obj.name) { c.openObj(r.obj.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag("obj_${r.obj.code}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.obj.name, style = Ty.serifHead.copy(fontSize = 18.sp), color = t.ink, maxLines = 1)
                    Text((r.session?.let { "${it.no}차 ${it.label} · " } ?: "") + "표적 ${r.measured}/${r.slots}", style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1)
                }
                Text(r.stage.ko, style = Ty.label.copy(fontSize = 16.sp), color = when (r.stage) { Stage.DONE -> t.fixed; Stage.MEASURE -> t.accentText; else -> t.ink },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(t.chip).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

// ---------------- 새 대상: ① 종류 타일 → ② 호수(다음 번호 미리) + 차수 ----------------
@Composable
fun NewObjSheet(c: GcpController, f: NewObjForm) {
    when (f.step) {
        NewObjStep.KIND -> BottomSheet("새 대상 · 종류", "newobj_kind", onClose = { c.newObj = null }) {
            Caption("무엇을 사진측량하나요? 누르면 다음 번호가 골라져요.")
            TileGrid(ObjKind.entries, 3) { k ->
                val used = c.usedNos(k)
                Tile(k.ko, false, "kind_${k.id}", Modifier.weight(1f), sub = if (k.lettered) "다음 ${ObjNames.letter(ObjNames.next(used))}—" else "다음 ${ObjNames.next(used)}호", height = 76.dp) { c.pickKind(k) }
            }
        }
        NewObjStep.NUMBER -> BottomSheet("새 대상 · ${f.kind.ko}", "newobj_no", onClose = { c.newObj = null }, bottom = {
            Btn("← 종류", "newobj_back", Modifier.weight(0.7f), primary = false) { c.newObj = f.copy(step = NewObjStep.KIND) }
            Btn("만들기", "newobj_ok", Modifier.weight(1.3f), enabled = f.no !in f.used, sub = "${ObjNames.label(f.kind, f.no)} · 1차 ${f.label}") { c.submitNewObj() }
        }) {
            if (f.kind.lettered) {
                Caption("토층 단면 글자 · 쓴 글자는 흐리게")
                val max = maxOf(26, ((f.used.maxOrNull() ?: 0) / 26 + 1) * 26 + if ((f.used.maxOrNull() ?: 0) >= 26) 0 else 0)
                TileGrid((1..max).toList(), 6, 6.dp) { n ->
                    Tile(ObjNames.letter(n), n == f.no, "letter_$n", Modifier.weight(1f), enabled = n !in f.used, height = 52.dp, big = true) { c.pickObjNo(n) }
                }
            } else {
                // 큰 숫자 + 번호판 열기 + 다음 번호 몇 개
                Row(
                    Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(16.dp)).background(T.raised).border(2.dp, if (f.no in f.used) T.bad else T.primary, RoundedCornerShape(16.dp))
                        .hClickable(onClickLabel = "번호판") { c.openObjNoPad() }.padding(horizontal = 16.dp).testTag("objno_display"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${f.no}", style = Ty.ledger.copy(fontSize = 44.sp), color = T.ink, modifier = Modifier.testTag("objno_value"))
                    Text(" 호 ${f.kind.ko}", style = Ty.serifHead.copy(fontSize = 22.sp), color = T.ink, modifier = Modifier.weight(1f))
                    Text("번호판 ›", style = Ty.label.copy(fontSize = 16.sp), color = T.primary)
                }
                if (f.no in f.used) Caption("${f.no}호 ${f.kind.ko} 은(는) 이미 있어요", T.bad, "objno_taken")
                val nx = ObjNames.next(f.used)
                val quick = (listOf(nx) + (nx + 1..nx + 3).filter { it !in f.used && it <= ObjNames.MAX_NO }).distinct()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, n) in quick.withIndex()) Tile("${n}호", f.no == n, "objno_q_$n", Modifier.weight(1f), sub = if (i == 0) "다음 번호" else null) { c.pickObjNo(n) }
                }
            }
            Text("1차 이름", style = Ty.label.copy(fontSize = 16.sp), color = T.ink)
            TileGrid(SessionLabels.ALL, 4, 6.dp) { l -> Tile(l, l == f.label, "label_$l", Modifier.weight(1f), height = 56.dp) { c.pickLabel(l) } }
            Caption("표적 칸: #" + QrPayload.ranges(f.slots) + " · 칸 번호는 측점 화면에서 길게 눌러 바꿔요")
            f.nearNote?.let { Caption(it, T.accentText, "near_note") }
            Caption("폴더: ${ObjNames.folder(f.kind, f.no)} / ${ObjNames.folder(f.kind, f.no)}_1차_EPSG${c.book?.site?.epsg}.xml", tag = "newobj_folder")
        }
    }
}

// ---------------- 차수 고르기 / 새 차수 ----------------
@Composable
fun SessionSheet(c: GcpController) {
    val o = c.obj ?: return
    BottomSheet("${o.name} · 차수", "session_sheet", onClose = { c.sessionSheetOpen = false }, bottom = {
        Btn("+ 새 차수", "session_new", Modifier.weight(1f), sub = "다음 ${c.sessionsOfObj().size + 1}차 · 앞 차수 파일은 그대로") { c.openNewSession() }
    }) {
        for (s in c.sessionsOfObj().reversed()) {
            val b = c.book ?: break
            val n = b.active(s).size
            Tile("${s.no}차 · ${s.label}", s.id == c.sessionId, "session_${s.no}", Modifier.fillMaxWidth(), sub = "표적 $n/${s.slots.size} · ${b.stage(s).ko}" + if (s.id != c.sessionsOfObj().last().id) " · 잠김(보기만)" else "") { c.pickSession(s.id) }
        }
    }
}

@Composable
fun NewSessionSheet(c: GcpController, f: NewSessionForm) {
    val o = c.obj ?: return
    val no = c.sessionsOfObj().size + 1
    BottomSheet("${o.name} · 새 ${no}차", "newsession_sheet", onClose = { c.newSession = null }, bottom = {
        Btn("${no}차 시작", "newsession_ok", Modifier.weight(1f), sub = f.label) { c.submitNewSession() }
    }) {
        Caption("단계를 고르세요. 표적 칸은 앞 차수와 같은 번호(#${QrPayload.ranges(f.slots)})로 시작해요.")
        TileGrid(SessionLabels.ALL, 2) { l -> Tile(l, l == f.label, "newsession_$l", Modifier.weight(1f), height = 72.dp) { c.newSession = f.copy(label = l) } }
    }
}

// ---------------- 번호판(1–999): 표적 번호 · 호수 · 지구 ----------------
@Composable
fun NumPickSheet(c: GcpController, p: NumPick) {
    val t = T
    BottomSheet(p.title, "numpad_sheet", onClose = { c.numPick = null }, bottom = {
        val okLabel = when { !p.ok -> if (p.taken) "이미 있어요" else "번호를 고르세요"; p.forWhat is PickFor.ObjNo -> "${p.value}호 고르기"; p.forWhat is PickFor.District -> "${p.value}지구 고르기"; else -> "#${p.value} 고르기" }
        Btn(okLabel, "numpad_ok", Modifier.weight(1f), enabled = p.ok) { c.submitNum() }
    }) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(16.dp)).background(t.raised).border(2.dp, if (p.ok) t.primary else t.bad, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (p.value == 0) "—" else "${p.value}", style = Ty.ledger.copy(fontSize = 44.sp), color = t.ink, modifier = Modifier.weight(1f).testTag("numpad_value"))
            Text(when { p.taken -> "이미 있음"; p.value in p.near -> "근처 대상과 겹침"; else -> "" }, style = Ty.label.copy(fontSize = 16.sp), color = if (p.taken) t.bad else t.accentText, modifier = Modifier.testTag("numpad_warn"))
        }
        // 빠른 고르기: 다음 빈 번호 몇 개
        val free = generateSequence(1) { it + 1 }.filter { it !in p.used && it !in p.near }.take(4).toList()
        val start = (p.used.maxOrNull() ?: 0) + 1
        val quick = (listOf(TargetChecks.nextFree(p.used + p.near, start)) + free).distinct().filter { it <= p.max }.take(4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, n) in quick.withIndex()) Tile("$n", p.value == n, "numpad_q_$n", Modifier.weight(1f), sub = if (i == 0) "다음" else "빈 번호", height = 56.dp, big = true) { c.numSet(n) }
        }
        // 번호판 3×4: 1–9 / 지움 0 비움 (장갑 손가락 64dp)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "C")
        TileGrid(keys, 3) { k ->
            Tile(k, false, "key_$k", Modifier.weight(1f), height = 64.dp, big = true) {
                when (k) { "⌫" -> c.numBack(); "C" -> c.numSet(0); else -> c.numDigit(k.toInt()) }
            }
        }
        Caption("세 자리까지 · 처음 누른 숫자가 새 번호가 돼요" + if (p.forWhat is PickFor.AddSlot || p.forWhat is PickFor.ChangeSlot) " · 실물 표적에 적힌 번호" else "")
    }
}

// ---------------- 새 유적: 이름 고르기(위치·최근·날짜) + 지구 + 좌표계 ----------------
@Composable
fun NewSiteSheet(c: GcpController, f: NewSiteForm) {
    val t = T
    BottomSheet("새 유적", "newsite_sheet", onClose = { c.newSite = null }, bottom = {
        Btn("만들기", "newsite_ok", Modifier.weight(1f), enabled = f.name() != null, sub = (f.name() ?: "이름을 고르세요") + " · EPSG:${f.epsg}") { c.submitNewSite() }
    }) {
        Text("이름", style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
        f.addressNote?.let { Caption(it, if (f.locating) t.inkSub else t.accentText, "newsite_addr") }
        for ((i, s) in f.suggestions.withIndex()) Tile(s, f.typed == null && f.base == s, "site_sug_$i", Modifier.fillMaxWidth(), height = 56.dp) { c.newSite = f.copy(base = s, typed = null) }
        if (f.typed != null) {
            // 직접 입력(맨 마지막 방법) — 이 칸에서만 자판이 뜬다
            androidx.compose.material3.OutlinedTextField(f.typed, { c.newSite = f.copy(typed = it.take(40)) }, Modifier.fillMaxWidth().testTag("site_typed"), singleLine = true,
                label = { Text("유적 이름 직접 입력") })
        } else Tile("직접 입력…", false, "site_type", Modifier.fillMaxWidth(), sub = "목록에 없을 때만 · 자판", height = 52.dp) { c.newSite = f.copy(typed = f.base ?: "") }
        Text("지구", style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile("없음", f.district == null, "district_none", Modifier.weight(1f), height = 52.dp) { c.newSite = f.copy(district = null) }
            for (n in 1..4) Tile("$n", f.district == n, "district_$n", Modifier.weight(1f), height = 52.dp, big = true) { c.newSite = f.copy(district = n) }
            Tile(if ((f.district ?: 0) > 4) "${f.district}" else "…", (f.district ?: 0) > 4, "district_more", Modifier.weight(1f), height = 52.dp, big = true) {
                c.numPick = NumPick(PickFor.District, "몇 지구?", f.district ?: 5, emptySet(), max = 99)
            }
        }
        Text("좌표계 (수신기와 같게)", style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
        TileGrid(GcpController.EPSGS, 2) { e -> Tile("${GcpController.epsgLabel(e)}원점", e == f.epsg, "epsg_$e", Modifier.weight(1f), sub = "EPSG:$e", height = 64.dp) { c.newSite = f.copy(epsg = e) } }
        Caption("위치가 있으면 경도로 원점을 미리 골라요. 높이는 수신기 값 그대로(지오이드 설정 없음).")
    }
}

// ---------------- 메뉴 · PC로 넘기기 · 폴 ----------------
@Composable
fun MenuSheet(c: GcpController) {
    BottomSheet("메뉴", "menu_sheet", onClose = { c.menuOpen = false }) {
        // 0.1.15 맨 위 [미니멀 | 일반] · 미니멀에서 숨긴 것은 모두 여기서(선택만)
        ModeChip(G.ink, G.white, G.lineStrong, Modifier.testTag("menu_mode"))
        if (Minimal.on) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (c.screen == Screen.OBJECTS) for ((st, label) in listOf(null to "전체", Stage.MEASURE to "측량 필요", Stage.PHOTO to "사진 필요", Stage.HANDOFF to "보내기"))
                Tile("보기: $label", c.filter == st, "menu_stage_${st?.name?.lowercase() ?: "all"}", Modifier.fillMaxWidth()) { c.menuOpen = false; c.filter = st }
            if (c.screen == Screen.TARGETS) {
                Tile("대상(폴더)으로", false, "menu_obj", Modifier.fillMaxWidth()) { c.menuOpen = false; c.back() }
                if (!c.locked()) Tile("표적 더하기", false, "menu_add_slot", Modifier.fillMaxWidth()) { c.menuOpen = false; c.openAddSlot() }
            }
            if (c.screen == Screen.TARGETS || c.screen == Screen.MAP) Tile("전체 보기(맞춤)", false, "menu_fit", Modifier.fillMaxWidth()) { c.menuOpen = false; c.fitAll() }
            Tile(if (Minimal.zoomButtons) "확대 단추 숨기기" else "확대 단추 보이기", Minimal.zoomButtons, "menu_zoom_buttons", Modifier.fillMaxWidth()) { Minimal.setZoomButtons(ctx, !Minimal.zoomButtons) }
        }
        Tile("유적 지도", false, "menu_map", Modifier.fillMaxWidth(), sub = "위성 + 지적 · 모든 폴더의 표적 자리") { c.menuOpen = false; c.go(Screen.MAP); c.fitAll() }
        Tile("PC로 보내기", false, "menu_handoff", Modifier.fillMaxWidth(), sub = "보낼 폴더 ${c.handoffCount(HandoffScope.PENDING)} · zip 하나로 공유") { c.menuOpen = false; c.handoffOpen = true }
        Tile("유적 바꾸기", false, "menu_sites", Modifier.fillMaxWidth()) { c.menuOpen = false; c.openSitePicker() }
        Tile("설정", false, "menu_settings", Modifier.fillMaxWidth(), sub = "수신기 · VRS · 폴 · 평균") { c.menuOpen = false; c.go(Screen.SETTINGS) }
    }
}

@Composable
fun HandoffSheet(c: GcpController) {
    val b = c.book ?: return
    BottomSheet("PC로 넘기기", "handoff_sheet", onClose = { c.handoffOpen = false }) {
        Caption("고르면 바로 zip(목록.csv + 대상별 XML·TXT)을 만들어 공유 창을 열어요.")
        for (sc in HandoffScope.entries) {
            val n = c.handoffCount(sc)
            Tile(sc.ko, false, "handoff_${sc.name.lowercase()}", Modifier.fillMaxWidth(), sub = "대상 ${n}개", enabled = n > 0, height = 72.dp) { c.sendHandoff(sc) }
        }
        Caption("파일 이름: " + Handoff.zipName(b.site, c.handoffScope, System.currentTimeMillis(), c.zone), tag = "handoff_zip")
    }
}

/** g09 폴 높이 시트: 큰 1.60 m · ±0.10 · 프리셋 4개 · 적용(값은 누를 때마다 바로 저장 — 적용 = 닫기) */
@Composable
fun PoleSheet(c: GcpController) {
    c.poleTick
    BottomSheet("폴 높이", "pole_sheet", onClose = { c.poleOpen = false }, bottom = {
        BigPrimary("적용", null, "pole_apply", Modifier.weight(1f)) { c.poleOpen = false }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.US, "%.2f", c.poleM()), style = Ty.ledger.copy(fontSize = 64.sp, fontWeight = FontWeight.Bold), color = G.ink, modifier = Modifier.testTag("pole_value"))
            Text(" m", style = Ty.label.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = G.sub, modifier = Modifier.padding(bottom = 12.dp))
        }
        Text("수신기 위상중심까지 · 바꾼 기록이 점마다 남아요", style = Ty.small, color = G.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("− 0.10", false, "pole_minus", Modifier.weight(1f), height = 76.dp, big = true) { c.stepPole(-10) }
            Tile("+ 0.10", false, "pole_plus", Modifier.weight(1f), height = 76.dp, big = true) { c.stepPole(10) }
        }
        TileGrid(listOf(1.5, 1.6, 1.8, 2.0), 4, 10.dp) { v -> Tile(String.format(Locale.US, "%.2f", v), Math.abs(c.poleM() - v) < 1e-6, "pole_$v", Modifier.weight(1f), height = 64.dp, big = true) { c.setPole(v) } }
    }
}
