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
    if (g.sigmaMissingOnFix) return if (g.strictMode) DigPill("측점 불가 · σ 없음", "엄격", Tone.BAD) else DigPill("FIX", "σ 없음", Tone.FIXED)
    // 0.1.46 디자인 시스템: 「FIX 1.4 cm」 코드형 + 상태색 채움(색만으로 구분하지 않게 항상 글자 동반)
    val hv = g.status.sigmaHM?.let { hm -> sigmaCm(hm) }
    val isFloat = g.quality.labelKo.contains("Float", true) || g.quality.labelKo.contains("플로트") || g.quality.labelKo.contains("유동")
    return when (g.displayStatus) {
        SurveyStatus.FIXED -> DigPill("FIX", hv, Tone.FIXED)
        SurveyStatus.CAUTION -> DigPill(
            if (isFloat) "FLOAT" else if (g.strictMode) "FIX 기다리는 중" else "주의 · " + g.status.reason.textKo.take(8), hv, Tone.CAUTION)
        SurveyStatus.UNAVAILABLE -> DigPill("측량 불가", null, Tone.BAD)
    }
}

/** 0.1.46 정밀도 표기: 소수 한 자리 cm (예: 1.4 cm) */
internal fun sigmaCm(hm: Double): String = String.format(Locale.US, "%.1f cm", hm * 100)

@Composable
internal fun DigStatusPill(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    rememberTick()
    val p = digPill(c)
    val (fill, onFill) = when (p.tone) {
        Tone.FIXED -> t.fixed to t.onFixed
        Tone.CAUTION -> t.float to Color.White
        Tone.BAD -> t.bad to t.onBad
        else -> t.raised to t.ink
    }
    val dot = if (fill == t.raised) t.inkSub else onFill
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(fill)
            .border(2.dp, if (fill == t.raised) t.outline else fill, RoundedCornerShape(24.dp))
            .hClickable { if (c.gv.noReceiver) c.connection?.openScreen() ?: run { c.dig.statusOpen = true } else c.dig.statusOpen = true }
            .padding(horizontal = 14.dp).semantics { contentDescription = p.text + (p.sub?.let { " $it" } ?: "") + ". 눌러서 지금 상태" }.testTag("status_pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Text(p.text, style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = onFill, maxLines = 1, modifier = Modifier.testTag("pill_fix"))
        p.sub?.let { Text(it, style = Ty.coord.copy(fontSize = 16.sp), color = onFill, maxLines = 1, modifier = Modifier.testTag("pill_sigma")) }
    }
}

/** 0.1.43 시안 ②: ‹ 작업명(굵게) / 오늘 n점 · EPSG | 상태 알약(● FIX 1.4 cm). 메뉴는 홈 ≡ 에서 */
@Composable
internal fun DigHeader(c: SurveyController) {
    Column(Modifier.fillMaxWidth().background(D.bg).testTag("app_header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(Glyph.BACK, "오늘 준비(홈)", "survey_back") { c.homeOpen = true }
            val ts = HeaderTitle.style(c.projectName)
            Column(Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 2.dp).hClickable { c.ui.toast(c.projectName) }.semantics { contentDescription = "작업 ${c.projectName}" }, verticalArrangement = Arrangement.Center) {
                Text(c.projectName, style = DTy.h1.copy(fontSize = (ts.sizeSp + 1).sp, lineHeight = (ts.sizeSp + 5).sp), color = D.ink, maxLines = ts.maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_title"))
                Text("오늘 ${c.dig.todayCount()}점 · ${c.zone.displayKo}", style = DTy.small, color = D.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_job"))
            }
            D43StatusPill(c, Modifier.padding(start = 6.dp))
        }
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
        Text(line, style = Ty.label.copy(fontSize = 16.sp, lineHeight = 21.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status_sub"))
        if (noRx) Text("연결", style = Ty.label.copy(fontWeight = FontWeight.Medium), color = t.primary, modifier = Modifier.padding(start = 8.dp).testTag("cause_connect"))
    }
}

/** 0.1.43 시안 ②: 「재는 것」 드롭다운(누르면 무엇을 잴까요) */
@Composable
internal fun NowRow(c: SurveyController) {
    val (head, tail) = c.dig.nowLine()
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp))
            .hClickable { c.dig.pickerOpen = true }.padding(horizontal = 14.dp)
            .semantics { contentDescription = "지금 재는 것 $head ${tail ?: ""}. 눌러서 바꾸기" }.testTag("now_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("재는 것", style = DTy.small, color = D.sub, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text(head, style = DTy.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = D.ink, maxLines = 1, modifier = Modifier.testTag("now_kind"))
        if (tail != null) Text(" · $tail", style = DTy.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = D.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("now_target"))
        Spacer(Modifier.weight(1f))
        GlyphIcon(Glyph.CHEVRON_DOWN, D.sub, 18.dp)
    }
}

/** 지금 위치 값(지도 아래 3칸): 해발 = 수신기 해발 − 안테나 위상중심 높이 */
internal fun liveCells(c: SurveyController): List<Triple<String, String, String>> {
    val g = c.gv
    val apc = c.antennaResult?.totalToApcM ?: c.antenna.inputValueM
    val h = if (g.noReceiver) null else g.altM?.let { it - apc }
    fun cm(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it * 100) } ?: "—"
    return listOf(
        Triple("해발", h?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "m"),
        Triple("수평 · 수직", if (g.noReceiver) "—" else "${cm(g.status.sigmaHM)}·${cm(g.sigmaVM)}", "cm"),
        Triple("폴", String.format(Locale.US, "%.2f", c.antenna.inputValueM), "m"),
    )
}

/** 0.1.43 시안 ②: 다음 점 · 기포 / 해발·수평수직·폴 / 재는 것 / [되돌리기][면 닫기][사진] + 큰 「측정」 */
@Composable
internal fun DigControlArea(c: SurveyController) {
    val busy = c.phase == Phase.ACQUIRING
    val status = c.gv.status.status
    val notConn = c.gv.noReceiver
    val strict = c.settingsState?.requireFixed == true
    val caution = status == SurveyStatus.CAUTION
    val strictBlock = strict && caution
    val blocked = status == SurveyStatus.UNAVAILABLE
    val feature = c.dig.kind == DigKind.FEATURE
    rememberTick()
    Column(Modifier.fillMaxWidth().background(D.bg).padding(horizontal = 16.dp).testTag("control_area")) {
        DupDecisionBar(c)
        // 0.1.46 시안 s03: [다음 점 P-0006 · 유구 경계 (눌러서 바꾸기)] [폴 1.60 m] 한 줄 → 큰 측정 단추. 지도 비율을 넓힌다
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (head, tail) = c.dig.nowLine()
            Column(
                Modifier.weight(1f).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(12.dp)).hClickable { c.dig.pickerOpen = true }
                    .semantics { contentDescription = "다음 점 ${c.nextName}, 지금 재는 것 $head ${tail ?: ""}. 눌러서 바꾸기" }.testTag("now_row"),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("다음 점", style = DTy.small, color = D.sub)
                    c.tilt?.let { tc ->
                        val a = tc.angleDeg
                        if (a != null && tc.state != TiltState.OFF && tc.state != TiltState.NO_SENSOR) {
                            val over = tc.state == TiltState.OVER
                            Row(Modifier.padding(start = 10.dp).testTag("tilt_bubble"), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(if (over) D.caution else D.fix))
                                Text(" 기포 ${String.format(Locale.US, "%.1f", a)}°", style = DTy.small, color = if (over) D.caution else D.fix, modifier = Modifier.testTag("tilt_angle"))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.nextName, style = DTy.monoBig.copy(fontSize = 22.sp), color = D.ink, maxLines = 1, modifier = Modifier.testTag("next_name"))
                    Text(" · $head", style = DTy.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = D.accent, maxLines = 1, modifier = Modifier.testTag("now_kind"))
                    if (tail != null) Text(" · $tail", style = DTy.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = D.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("now_target"))
                }
            }
            val pole = String.format(Locale.US, "%.2f", c.antenna.inputValueM)
            Column(
                Modifier.widthIn(min = 104.dp).heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(12.dp)).background(D.card).border(1.5.dp, D.line, RoundedCornerShape(12.dp))
                    .hClickable { c.antennaSheetOpen = true }.padding(horizontal = 12.dp, vertical = 4.dp).semantics { contentDescription = "폴 높이 $pole 미터. 눌러서 바꾸기" }.testTag("pole_chip"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("폴", style = DTy.small, color = D.sub)
                Text("$pole m", style = DTy.mono.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = D.ink, maxLines = 1, modifier = Modifier.testTag("antenna_input"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (feature) MiniAction(Glyph.FACE, if (c.dig.closeArmed) "닫기 취소" else "면 닫기", "tool_close_area", Modifier.weight(1f), on = c.dig.closeArmed) { c.dig.armClose() }
            MiniAction(Glyph.MIC, if (c.voiceSeconds > 0) "녹음 끝" else "메모", "tool_memo", Modifier.weight(1f), on = c.voiceSeconds > 0) { c.toggleVoice() }
            MiniAction(Glyph.CAMERA, "사진", "tool_photo", Modifier.weight(1f)) { if (c.lastPoint != null) c.addPhotoCamera() else c.ui.toast("점을 잰 뒤 사진을 붙일 수 있어요") }
            if (c.dig.undoIsReopen) MiniAction(Glyph.UNDO, "면 닫기 되돌리기", "tool_undo", Modifier.weight(1f)) { c.askUndo() }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 0.1.46 시안 s03: [점 목록] + 큰 「측량」. 면 닫기·사진·메모는 위 작은 줄(한 번 탭), 되돌리기는 저장 알림 안
            val side: @Composable RowScope.() -> Unit = {
                val sh = RoundedCornerShape(16.dp)
                Column(Modifier.width(96.dp).height(FieldIvory.measureButton).clip(sh).background(D.card).border(1.5.dp, D.line, sh)
                    .hClickable { c.hub.open(ToolScreen.POINTS) }.semantics { contentDescription = "점 목록" }.testTag("tool_points"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GlyphIcon(Glyph.LIST, D.ink, 26.dp); Spacer(Modifier.height(6.dp)); Text("점 목록", style = DTy.small.copy(fontWeight = FontWeight.Bold), color = D.ink, maxLines = 1)
                }
            }
            val measure: @Composable RowScope.() -> Unit = {
                val dim = blocked || strictBlock
                val armed = c.dig.closeArmed
                val label: String; val sub: String
                when {
                    armed -> { label = "면 닫기 확인"; sub = c.dig.closePreview() ?: "" }
                    busy -> { label = "재는 중 ${c.measureCount.coerceAtMost(c.epochs)}/${c.epochs}"; sub = "누르면 중지" }
                    notConn -> { label = MEASURE_NO_RECEIVER; sub = "눌러서 연결" }
                    strictBlock -> { label = "FIX 기다리는 중"; sub = "FLOAT 저장 잠김" }
                    blocked -> { label = "측점 불가"; sub = c.gv.status.reason.textKo }
                    caution -> { label = "주의 측정"; sub = c.dig.measureSub() }
                    else -> { label = "측량"; sub = c.gv.status.sigmaHM?.let { "±" + String.format(Locale.US, "%.1f", it * 100) + " cm · 길게 눌러 설정" } ?: "" }
                }
                Box(
                    Modifier.weight(1f).height(FieldIvory.measureButton).clip(RoundedCornerShape(16.dp))
                        .background(if (!busy && (notConn || dim) && !armed) t43Blocked else D.primary)
                        .hCombinedClickable(onLongClick = { if (!busy && !armed) c.setupOpen = true }) { if (busy) c.cancelAcquire() else if (notConn && !armed) { c.connection?.openScreen() ?: c.onMeasure() } else c.onMeasure() }
                        .testTag("measure_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(c.progress.coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = 0.85f)).testTag("measure_progress"))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = DTy.button.copy(fontSize = if (label.length <= 3) 24.sp else 19.sp, lineHeight = 26.sp), color = Color.White, maxLines = 1, textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(if (notConn || dim) "measure_disabled_reason" else "measure_label"))
                        if (sub.isNotEmpty()) Text(sub, style = DTy.tiny, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).testTag("measure_sub"))
                    }
                }
            }
            if (c.poleOnRight) { side(); measure() } else { measure(); side() }
        }
    }
    // 0.1.46: 지금 위치 값(해발·수평·수직)은 아래 한 줄로(예전 3칸 지표 대신). 고정이 아니면 다음 할 일 문구 우선
    val lc = liveCells(c)
    DFootLine(listOf(if (c.gv.displayStatus == SurveyStatus.FIXED && !c.gv.noReceiver) "해발 ${lc[0].second} m · ${lc[1].second} cm" else mapHint(c), "${c.epochs}회 평균", c.currentNE()?.let { "X ${String.format(Locale.US, "%,.1f", it.e)} Y ${String.format(Locale.US, "%,.1f", it.n)}" } ?: "X — Y —"), "map_foot")
}

private val t43Blocked get() = D.blocked

/** 0.1.46 작은 보조 동작(한 번 탭, 56dp 높이) */
@Composable
private fun MiniAction(g: Glyph, label: String, tag: String, modifier: Modifier, on: Boolean = false, onClick: () -> Unit) {
    val sh = RoundedCornerShape(14.dp)
    Row(modifier.heightIn(min = FieldIvory.minTouch).clip(sh).background(if (on) FieldIvory.primarySoft else D.card).border(if (on) 2.dp else 1.dp, if (on) FieldIvory.primary else D.line, sh)
        .hClickable(onClickLabel = label, onClick = onClick).padding(horizontal = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        GlyphIcon(g, D.ink, 20.dp); Spacer(Modifier.width(6.dp)); Text(label, style = DTy.small.copy(fontWeight = FontWeight.Medium), color = D.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun mapHint(c: SurveyController): String = when {
    c.phase == Phase.ACQUIRING -> "폴을 세운 채 움직이지 마세요"
    c.gv.noReceiver -> "수신기를 연결하세요"
    c.gv.displayStatus != SurveyStatus.FIXED -> "FIX를 기다려요"
    else -> "점 위에 폴을 세우고 「측정」"
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
            Text("저장했어요", style = Ty.label.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Text(l.chip, style = Ty.label.copy(fontSize = 16.sp), color = if (st == SurveyStatus.FIXED) t.fixed else t.cautionInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (st == SurveyStatus.FIXED) t.fixed.copy(alpha = 0.12f) else t.caution.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("result_chip"))
        }
        Text(l.line, style = Ty.label.copy(fontSize = 16.sp), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("result_h"))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(t.ink))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            l.cells.forEachIndexed { i, cell ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(t.line.copy(alpha = 0.5f)))
                Column(Modifier.weight(if (i == 0) 1.3f else 1f).padding(horizontal = 8.dp).testTag("ledger_$i")) {
                    Text(cell.label, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(cell.value, style = Ty.ledger.copy(fontSize = if (i == 0) 22.sp else 18.sp), color = t.ink, maxLines = 1)
                        Text(" " + cell.unit, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1)
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
        Text(kr.archsurvey.dig.BmCheck.deltaText(r) + " · 허용 ${String.format(Locale.US, "%.0f", r.tolM * 100)} cm", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink)
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
