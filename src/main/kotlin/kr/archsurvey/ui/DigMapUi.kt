package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.dig.DigKind
import kr.archsurvey.dig.LedgerText
import java.util.Locale

/** 0.1.35 발굴 측량 지도 화면 부품: 위 한 줄(작업명·상태 알약·메뉴) · 원인 한 줄 · 「지금 재는 것」 · 되돌리기/면 닫기/측량하기 · 잰 직후 장부 */

internal fun ntripNow(): Long = android.os.SystemClock.elapsedRealtime()

/** 상태 알약: FIX 초록 「측량 가능 ±H」 · FLOAT 노랑 · 미연결 회색. 누르면 「지금 상태」 시트 */
data class DigPill(val text: String, val sub: String?, val tone: Tone)

internal fun digPill(c: SurveyController): DigPill {
    val g = c.gv
    if (g.noReceiver) return DigPill("수신기 연결 안 됨", null, Tone.NO_RECEIVER)
    // 0.1.31: FIX인데 수신기가 정밀도(σ)를 안 보냄 — 엄격 모드면 저장 잠김을 알약에 그대로
    if (g.sigmaMissingOnFix) return if (g.strictMode) DigPill("측점 불가 · σ 없음", "엄격", Tone.BAD) else DigPill("측량 가능", "σ 없음", Tone.FIXED)
    // 알약은 좁아서 수평만(±cm). 점마다 H/V는 장부 카드 · 점 목록에
    val hv = g.status.sigmaHM?.let { hm -> String.format(Locale.US, "±%.1f", hm * 100) }
    return when (g.displayStatus) {
        SurveyStatus.FIXED -> DigPill("측량 가능", hv, Tone.FIXED)
        SurveyStatus.CAUTION -> DigPill(
            if (g.strictMode) "FIX 기다리는 중"
            else if (g.quality.labelKo.contains("Float", true) || g.quality.labelKo.contains("플로트") || g.quality.labelKo.contains("유동")) "FLOAT · 주의"
            else "주의 · " + g.status.reason.textKo.take(8), hv, Tone.CAUTION)
        SurveyStatus.UNAVAILABLE -> DigPill("측량 불가", null, Tone.BAD)
    }
}

@Composable
internal fun DigStatusPill(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    rememberTick()
    val p = digPill(c)
    val (dot, border) = when (p.tone) {
        Tone.FIXED -> t.fixed to t.fixed
        Tone.CAUTION -> t.caution to t.cautionInk
        Tone.BAD -> t.bad to t.bad
        else -> t.inkSub to t.outline
    }
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (p.tone == Tone.CAUTION) t.caution.copy(alpha = 0.18f) else t.raised)
            .border(2.dp, border, RoundedCornerShape(24.dp))
            .hClickable { if (c.gv.noReceiver) c.connection?.openScreen() ?: run { c.dig.statusOpen = true } else c.dig.statusOpen = true }
            .padding(horizontal = 12.dp).semantics { contentDescription = p.text + (p.sub?.let { " $it" + if (it.startsWith("±")) " cm" else "" } ?: "") + ". 눌러서 지금 상태" }.testTag("status_pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Text(p.text, style = Ty.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = if (p.tone == Tone.FIXED) t.fixed else t.ink, maxLines = 1, modifier = Modifier.testTag("pill_fix"))
        p.sub?.let { Text(it, style = Ty.coord.copy(fontSize = 13.sp), color = t.inkSub, maxLines = 1, modifier = Modifier.testTag("pill_sigma")) }
    }
}

/** 위 한 줄: ‹ 작업명(명조) / 오늘 n점 · EPSG · 높이 | 상태 알약 | ≡ */
@Composable
internal fun DigHeader(c: SurveyController) {
    val t = T
    Column(Modifier.fillMaxWidth().background(t.bg).testTag("app_header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(Glyph.BACK, "오늘 준비(홈)", "survey_back") { c.homeOpen = true }
            // 0.1.36: 긴 작업명이 잘리던 문제 — 길이에 따라 글자를 줄이고 두 줄까지(머리줄이 조금 높아짐). 눌러서 전체 이름
            val ts = HeaderTitle.style(c.projectName)
            Column(Modifier.weight(1f).padding(start = 2.dp, top = 4.dp, bottom = 4.dp).hClickable { c.ui.toast(c.projectName) }.semantics { contentDescription = "작업 ${c.projectName}" }) {
                Text(c.projectName, style = Ty.serifHead.copy(fontSize = ts.sizeSp.sp, lineHeight = (ts.sizeSp + 3).sp), color = t.ink, maxLines = ts.maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_title"))
                Text("오늘 ${c.dig.todayCount()}점 · EPSG:${c.zone.epsg} · ${c.geoid.shortKo}", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_job"))
            }
            DigStatusPill(c, Modifier.padding(horizontal = 4.dp))
            HeaderIcon(Glyph.MENU, "메뉴", "gear") { c.dig.menuOpen = true }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.35f)))
    }
}

/** X12 원인 한 줄(고정이 아닐 때만). 엄격 모드·σ 없음 안내는 기존 문구를 이어 쓴다 */
@Composable
internal fun DigCauseLine(c: SurveyController) {
    val t = T
    rememberTick()
    val strict = c.settingsState?.requireFixed == true
    val line = c.dig.causeLine(ntripNow())?.let { base ->
        if (strict && c.gv.displayStatus == SurveyStatus.CAUTION) "$base · 엄격 모드: FIX 때만 저장" else base
    } ?: (if (c.gv.sigmaMissingOnFix) "고정이지만 정밀도(σ)가 오지 않아요 · " + (if (strict) "엄격 모드는 저장 잠김" else "저장하면 「주의」 표시") else null)
    if (line == null) return
    val noRx = c.gv.noReceiver
    Row(
        Modifier.fillMaxWidth().background(if (noRx) t.chip else t.caution.copy(alpha = 0.22f)).hClickable { if (noRx) c.connection?.openScreen() else c.dig.statusOpen = true }
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("cause_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, style = Ty.label.copy(fontSize = 14.sp, lineHeight = 18.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status_sub"))
        if (noRx) Text("연결", style = Ty.label.copy(fontWeight = FontWeight.Medium), color = t.primary, modifier = Modifier.padding(start = 8.dp).testTag("cause_connect"))
    }
}

/** 「지금 재는 것」 + 폴 칩 */
@Composable
internal fun NowRow(c: SurveyController) {
    val t = T
    val (head, tail) = c.dig.nowLine()
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(14.dp))
                .hClickable { c.dig.pickerOpen = true }.padding(horizontal = 14.dp)
                .semantics { contentDescription = "지금 재는 것 $head ${tail ?: ""}. 눌러서 바꾸기" }.testTag("now_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("지금 재는 것", style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(head, style = Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1, modifier = Modifier.testTag("now_kind"))
                    if (tail != null) Text(" · $tail", style = Ty.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = t.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("now_target"))
                }
            }
            GlyphIcon(Glyph.CHEVRON_DOWN, t.inkSub, 18.dp)
        }
        Column(
            Modifier.width(72.dp).height(60.dp).clip(RoundedCornerShape(14.dp)).background(if (c.dig.poleConfirmedToday) t.raised else t.primaryCont)
                .border(1.dp, if (c.dig.poleConfirmedToday) t.outline else t.primary, RoundedCornerShape(14.dp))
                .hClickable { c.antennaSheetOpen = true }.semantics { contentDescription = "폴 높이 ${String.format(Locale.US, "%.1f", c.antenna.inputValueM)} 미터. 눌러서 바꾸기" }.testTag("pole_chip"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(String.format(Locale.US, "%.1f", c.antenna.inputValueM), style = Ty.ledger.copy(fontSize = 20.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("antenna_input"))
            Text("폴 m", style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub)
        }
    }
}

/** 아래 조작부: 되돌리기 · 면 닫기(유구)/메모 · 측량하기(72dp 이상, 단추 안 진행) */
@Composable
internal fun DigControlArea(c: SurveyController) {
    val t = T
    val busy = c.phase == Phase.ACQUIRING
    val status = c.gv.status.status
    val notConn = c.gv.noReceiver
    val strict = c.settingsState?.requireFixed == true
    val caution = status == SurveyStatus.CAUTION
    val strictBlock = strict && caution
    val blocked = status == SurveyStatus.UNAVAILABLE
    val feature = c.dig.kind == DigKind.FEATURE
    Column(Modifier.fillMaxWidth().background(t.bg).testTag("control_area")) {
        NowRow(c)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val side: @Composable RowScope.() -> Unit = {
                SideBtn(Glyph.UNDO, if (c.dig.undoIsReopen) "닫기 취소" else "되돌리기", "tool_undo") { c.askUndo() }
                if (feature) SideBtn(Glyph.POLYGON, if (c.dig.closeArmed) "취소" else "면 닫기", "tool_close_area", on = c.dig.closeArmed) { c.dig.armClose() }
                else SideBtn(Glyph.MIC, if (c.voiceSeconds > 0) "녹음 끝" else "메모", "tool_memo") { c.toggleVoice() }
            }
            val measure: @Composable RowScope.() -> Unit = {
                val dim = blocked || strictBlock
                val armed = c.dig.closeArmed
                val label: String; val sub: String
                when {
                    armed -> { label = "면 닫기 확인"; sub = c.dig.closePreview() ?: "" }
                    busy -> { label = "재는 중 ${c.measureCount.coerceAtMost(c.epochs)}/${c.epochs}"; sub = "폴을 세운 채 · 누르면 중지" }
                    notConn -> { label = MEASURE_NO_RECEIVER; sub = "눌러서 연결" }
                    strictBlock -> { label = "FIX 기다리는 중"; sub = "엄격 · FLOAT 저장 잠김" }
                    blocked -> { label = "측점 불가"; sub = c.gv.status.reason.textKo }
                    c.phase == Phase.RESULT || c.phase == Phase.RESULT_EXPANDED -> { label = "다음 점"; sub = "누르면 바로 재요 · " + c.dig.measureSub() }
                    caution -> { label = "주의 측점"; sub = c.dig.measureSub() + " · 확인 후" }
                    else -> { label = "측량하기"; sub = c.dig.measureSub() }
                }
                Box(
                    Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (!busy && (notConn || dim) && !armed) t.blockedButton else t.primary)
                        .hCombinedClickable(onLongClick = { if (!busy && !armed) c.setupOpen = true }) { if (busy) c.cancelAcquire() else if (notConn && !armed) { c.connection?.openScreen() ?: c.onMeasure() } else c.onMeasure() }
                        .testTag("measure_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(c.progress.coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = 0.85f)).testTag("measure_progress"))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = Ty.button.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium), color = t.onPrimary, maxLines = 1, textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(if (notConn || dim) "measure_disabled_reason" else "measure_label"))
                        if (sub.isNotEmpty()) Text(sub, style = Ty.small.copy(fontSize = 12.sp, lineHeight = 15.sp), color = t.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).testTag("measure_sub"))
                    }
                }
            }
            if (c.poleOnRight) { side(); measure() } else { measure(); side() }
        }
    }
}

@Composable
private fun RowScope.SideBtn(g: Glyph, label: String, tag: String, on: Boolean = false, onClick: () -> Unit) {
    val t = T
    Column(
        Modifier.width(76.dp).height(84.dp).clip(RoundedCornerShape(16.dp)).background(if (on) t.primaryCont else t.raised)
            .border(if (on) 2.dp else 1.dp, if (on) t.primary else t.outline, RoundedCornerShape(16.dp)).hClickable(onClickLabel = label, onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(g, t.ink, 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = Ty.label.copy(fontSize = 13.sp), color = t.ink, maxLines = 1)
    }
}

/** 잰 직후 한 장(장부): ✓ P-0038 저장했어요 [고정] / 무엇 한 줄 / 해발 · 수평·수직 · 폴 / 사진 · 음성·메모. 다음 누름 = 다음 측정 */
@Composable
internal fun SavedLedgerCard(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val p = c.lastPoint ?: return
    val l = c.dig.ledger(p)
    val st = PointCaution.displayStatus(p)
    val (bg, fg) = statusColors(t, st)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(t.raised).border(1.dp, t.outline, RoundedCornerShape(16.dp))
            .padding(14.dp).testTag("result_strip"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { StatusIcon(st, fg, 16.dp) }
            Text(l.title, style = Ty.ledger.copy(fontSize = 21.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_name"))
            Text("저장했어요", style = Ty.label.copy(fontSize = 15.sp), color = t.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Text(l.chip, style = Ty.label.copy(fontSize = 12.sp), color = if (st == SurveyStatus.FIXED) t.fixed else t.cautionInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (st == SurveyStatus.FIXED) t.fixed.copy(alpha = 0.12f) else t.caution.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("result_chip"))
        }
        Text(l.line, style = Ty.label.copy(fontSize = 14.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("result_h"))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(t.ink))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            l.cells.forEachIndexed { i, cell ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(t.line.copy(alpha = 0.5f)))
                Column(Modifier.weight(if (i == 0) 1.3f else 1f).padding(horizontal = 8.dp).testTag("ledger_$i")) {
                    Text(cell.label, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(cell.value, style = Ty.ledger.copy(fontSize = if (i == 0) 22.sp else 18.sp), color = t.ink, maxLines = 1)
                        Text(" " + cell.unit, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.weight(1f).height(48.dp).testTag("result_photo"), MKind.PLAIN, 14.dp, onClick = { c.addPhotoCamera() }) {
                Text("사진 ${c.photos.size}/5", style = Ty.label, color = t.ink, maxLines = 1)
            }
            MBtn(Modifier.weight(1f).height(48.dp).testTag("result_voice"), MKind.PLAIN, 14.dp, onClick = { c.toggleVoice() }) {
                Text(if (c.voiceSeconds > 0) "녹음 끝내기 ${c.voiceSeconds}초" else "음성 · 메모", style = Ty.label, color = t.ink, maxLines = 1)
            }
        }
    }
}

/** 기준점 확인 결과(지도 위) */
@Composable
internal fun BmResultCard(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val (name, r) = c.dig.bmResult ?: return
    Column(
        modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(2.dp, if (r.pass) t.fixed else t.bad, RoundedCornerShape(14.dp))
            .hClickable { c.dig.bmResult = null }.padding(12.dp).testTag("bm_result"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(kr.archsurvey.dig.BmCheck.verdictText(name, r), style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = if (r.pass) t.fixed else t.bad)
        Text(kr.archsurvey.dig.BmCheck.deltaText(r) + " · 허용 ${String.format(Locale.US, "%.0f", r.tolM * 100)} cm", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink)
        if (!r.pass) Text(kr.archsurvey.dig.BmCheck.adviceText(r), style = Ty.small, color = t.ink, maxLines = 3)
    }
}

/** 그리드 말뚝 찾아가기 카드(지도 위): 「B-4 모서리 · 북동 1.24 m」 + 북/동 차이 + 이전/다음 */
@Composable
internal fun StakeCard(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    if (c.dig.kind != DigKind.STAKE) return
    rememberTick()
    val target = c.dig.stakeTarget
    val gd = c.dig.stakeGuide()
    Column(
        modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(2.dp, if (gd?.arrived == true) t.fixed else t.primary, RoundedCornerShape(14.dp)).padding(12.dp).testTag("stake_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (target == null) { Text("구역 · 그리드를 먼저 만드세요", style = Ty.label, color = t.ink); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(target.name, style = Ty.serifHead.copy(fontSize = 17.sp), color = t.ink, modifier = Modifier.testTag("stake_name"))
                Text(gd?.let { if (it.arrived) "도착 · 측량하기를 누르세요" else "${it.dirKo} ${String.format(Locale.US, "%.2f", it.distanceM)} m" } ?: "위치를 기다리는 중", style = Ty.ledger.copy(fontSize = 20.sp), color = if (gd?.arrived == true) t.fixed else t.ink, modifier = Modifier.testTag("stake_dist"))
                gd?.let { Text(it.offsetText, style = Ty.small, color = t.inkSub) }
            }
            MBtn(Modifier.size(48.dp).testTag("stake_prev"), MKind.PLAIN, 12.dp, onClick = { c.dig.stepStake(-1) }) { Text("‹", style = Ty.title, color = t.ink) }
            Spacer(Modifier.width(6.dp))
            MBtn(Modifier.size(48.dp).testTag("stake_next"), MKind.PLAIN, 12.dp, onClick = { c.dig.stepStake(1) }) { Text("›", style = Ty.title, color = t.ink) }
        }
    }
}

/** 정보 영역(0.1.35): 잰 직후 장부 카드. 띠 시간 뒤 사라짐(다음 누름 = 다음 측정) */
@Composable
internal fun DigInfoZone(c: SurveyController) {
    if (c.phase == Phase.RESULT && c.resultStripShown) {
        LaunchedEffect(c.resultStripSeq) { val ms = c.resultStripMs; if (ms >= Long.MAX_VALUE / 2) return@LaunchedEffect; kotlinx.coroutines.delay(ms * 2); c.hideResultStrip() }
    }
    Box(Modifier.fillMaxWidth().testTag("info_zone")) {
        when (c.phase) {
            Phase.RESULT, Phase.RESULT_EXPANDED -> if (c.resultStripShown || c.phase == Phase.RESULT_EXPANDED) SavedLedgerCard(c)
            Phase.RECORDING -> SavedLedgerCard(c)
            else -> Unit
        }
    }
}

/**
 * 0.1.36 사진 기준점(AprilTag) 카드(지도 위): 큰 「AprilTag #12」 + 장갑 낀 손으로 누르는 − / + (64dp) + 이 유구에서 잰 번호.
 * 재면 다음 번호로 저절로 넘어간다. 이미 잰 번호를 고르면 「다시 재기」(내보내기는 마지막 값)
 */
@Composable
internal fun TagCard(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val d = c.dig
    if (d.kind != DigKind.GCP) return
    val again = d.tagAlreadyMeasured
    val done = d.tagsIn(d.featureId).distinct().sorted()
    Column(
        modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).border(2.dp, if (again) t.cautionInk else t.primary, RoundedCornerShape(14.dp)).padding(10.dp).testTag("tag_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MBtn(Modifier.size(64.dp).testTag("tag_minus"), MKind.PLAIN, 14.dp, onClick = { d.stepTag(-1) }) { Text("−", style = Ty.title.copy(fontSize = 30.sp), color = t.ink) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "사진 기준점 AprilTag ${d.tagId}번" }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AprilTag", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1)
                Text("#${d.tagId}", style = Ty.ledger.copy(fontSize = 30.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("tag_id"))
                Text(d.featureName(d.featureId) ?: "유구 밖", style = Ty.label.copy(fontSize = 13.sp), color = t.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tag_feature"))
            }
            MBtn(Modifier.size(64.dp).testTag("tag_plus"), MKind.PLAIN, 14.dp, onClick = { d.stepTag(1) }) { Text("+", style = Ty.title.copy(fontSize = 30.sp), color = t.ink) }
        }
        if (again) Text("이 유구에서 이미 잰 번호 · 다시 재면 마지막 값으로 내보내요", style = Ty.small.copy(fontSize = 12.sp), color = t.cautionInk, maxLines = 2, modifier = Modifier.testTag("tag_again"))
        else if (done.isNotEmpty()) Text("잰 표적 ${done.size}개 · #" + done.joinToString(","), style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tag_done"))
        else Text("표적 가운데에 폴을 세우고 재세요 · 재면 다음 번호로", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 0.1.36 머리줄 작업명 글자 크기·줄 수(순수, 시험 가능): 짧으면 그대로 한 줄, 길면 작게 두 줄 */
object HeaderTitle {
    data class Style(val sizeSp: Int, val maxLines: Int)
    fun style(name: String): Style {
        val n = name.codePointCount(0, name.length)
        return when {
            n <= 8 -> Style(19, 1)
            n <= 14 -> Style(17, 2)
            else -> Style(15, 2)
        }
    }
}
