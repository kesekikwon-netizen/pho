package kr.archsurvey.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kr.archsurvey.core.*
import kr.archsurvey.map.MapSurface

internal fun statusColors(t: Tokens, s: SurveyStatus): Pair<Color, Color> = when (s) {
    SurveyStatus.FIXED -> t.fixed to t.onFixed
    SurveyStatus.CAUTION -> t.caution to t.onCaution
    SurveyStatus.UNAVAILABLE -> t.bad to t.onBad
}

internal fun f3(x: Double) = String.format(java.util.Locale.US, "%.3f", x)

/** 측량 + 지도 상시 화면. 상태 띠 / 지도 / 정보 / 컨트롤 네 영역이 겹치지 않는다. */
@Composable
fun SurveyMapScreen(
    c: SurveyController, batteryPct: Int?, map: MapSurface = rememberMapSurface(c),
) {
    val t = T
    androidx.compose.runtime.CompositionLocalProvider(LocalSurvey provides c, LocalTapHaptic provides (c.settingsState?.vibration ?: true)) {
    Box(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.systemBars).testTag("screen")) {
        // 0.1.35: 위 한 줄(작업명·상태 알약·메뉴) · 원인 한 줄 · 넓은 지도 · 잰 직후 장부 · 지금 재는 것 + 조작부.
        // 폴드 안쪽 화면(가로 600dp 이상)은 왼쪽 지도 · 오른쪽 결과 + 오늘 점 + 조작부 2단
        if (c.homeOpen) HomeToday(c) else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                DigHeader(c)
                DigCauseLine(c)
                if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                    MapArea(c, map, Modifier.weight(1.4f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().background(t.bg).testTag("side_pane")) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            if (c.lastPoint != null) SavedLedgerCard(c)
                            TodayPointsList(c)
                        }
                        DigControlArea(c)
                    }
                } else {
                    MapArea(c, map, Modifier.weight(1f))
                    DigControlArea(c)
                }
            }
        }
        if (c.settingsMenuOpen) SettingsMenuScreen(c)
        // 시스템 뒤로: 열린 창부터 닫고, 지도면 홈으로
        androidx.activity.compose.BackHandler(enabled = backTarget(c) != null) { backTarget(c)?.invoke() }
        if (c.quickSheetOpen) QuickSheet(c)
        // 0.1.46: 같은 자리 점은 지도를 가리지 않는 결정 바(DigControlArea 안 DupDecisionBar)
        if (c.tiltPrompt != null) TiltSheet(c)
        c.bg?.let { b ->
            androidx.activity.compose.BackHandler(enabled = b.screen != kr.archsurvey.ui.bg.BgScreen.NONE) { b.close() }
            kr.archsurvey.ui.bg.BackgroundSheets(b)
        }
        if (c.setupOpen) SetupSheet(c)
        if (c.antennaSheetOpen) AntennaSheet(c)
        if (c.hub.screen != null) ToolHost(c)
        SavedToast(c) // 0.1.45 측정 뒤 지도 위 작은 알림(탭을 막지 않음)
        if (pointConfirmVisible(c)) PointConfirmScreen(c) // 0.1.45: 알림·점을 눌렀을 때만
        DigOverlays(c) // 0.1.35 무엇을 잴까요 · 지도에 보이기 · 지금 상태 · 메뉴 · 유구 이름 바꾸기
        c.exporter?.let { if (it.open) ExportScreen(it) }
        c.backup?.let { if (it.open) BackupScreen(it, c.drive) }
        c.connection?.let { ConnectionHost(c, it) }
        DrawerOverlay(c)
        if (c.gnssInfoOpen) GnssInfoDialog(c)
        if (c.skyPlotOpen) SkyPlotScreen(c)
        // 0.1.14 목업 14·18·20·22
        if (c.backupQuickOpen) BackupQuickDialog(c)
        c.pointMenuFor?.let { PointMenuSheet(c, it) }
        if (c.markerStyleOpen) MarkerStyleDialog(c)
        if (c.photoReviewOpen) PhotoReviewScreen(c)
        // 0.1.35 X4: 재는 중 진행은 측량하기 단추 안(지도를 가리지 않음)
        BusyQueueBar(c) // 0.1.14 대기열·진행 게이지
        if (!c.homeOpen) VoiceConfirmBanner(c) // 0.1.28 음성 확인 대기 띠
        FeedbackOverlays(c.ui)
    }
    }
}

/** 0.1.29 T22-4: 중복 점 창에서 뒤로를 눌렀을 때 안내 */
internal const val DUP_BACK_HINT = "방금 측정값을 어떻게 할지 골라 주세요"

/** 시스템 뒤로 단추가 할 일. 0.1.31: 맨 끝(홈)은 종료 확인 창 — null 을 돌려주지 않는다 */
internal fun backTarget(c: SurveyController): (() -> Unit)? {
    val cc = c.connection
    return when {
        // 0.1.29 T22-3: 기울기 확인 창 = 「측점 안 함」과 같다(SurveyController 로직은 그대로, 화면 쪽에서만)
        c.tiltPrompt != null -> { { c.answerTilt(false) } }
        // 0.1.29 T22-4: 중복 점 선택 창은 반드시 골라야 함 — 뒤로를 먹고 창은 그대로(방금 측정값을 잃지 않게)
        c.dupPrompt != null -> { { c.ui.toast(DUP_BACK_HINT) } }
        // 0.1.29 T22-5: 측정(취득) 중 뒤로 = 기존 「중지」 경로(cancelAcquire — 저장 안 함). 예전엔 null 이라 앱이 나갔다
        c.phase == Phase.ACQUIRING -> { { c.cancelAcquire() } }
        c.photoReviewOpen -> { { c.confirmPhoto() } }
        c.markerStyleOpen -> { { c.markerStyleOpen = false } }
        c.dig.renameFor != null -> { { c.dig.renameFor = null } }
        c.dig.pickerOpen -> { { c.dig.pickerOpen = false } }
        c.dig.layersOpen -> { { c.dig.saveLayers(); c.dig.layersOpen = false } }
        c.dig.statusOpen -> { { c.dig.statusOpen = false } }
        c.dig.closeArmed -> { { c.dig.closeArmed = false } }
        c.pointMenuFor != null -> { { c.pointMenuFor = null } }
        c.backupQuickOpen -> { { c.backupQuickOpen = false } }
        c.skyPlotOpen -> { { c.skyPlotOpen = false } }
        c.gnssInfoOpen -> { { c.gnssInfoOpen = false } }
        c.drawerOpen -> { { c.drawerOpen = false } }
        // 0.1.17: 「연결 중」 진행 창이 떠 있으면 그 창부터 닫음(=「닫기(계속 연결)」, 연결은 계속)
        // 0.1.18: 진행 창 단추·BackHandler 와 같은 함수(완료면 「확인」 = 연결 화면까지 닫기, 진행 중이면 첫 1회 「연결은 계속돼요」)
        cc != null && cc.showProgress && cc.connectRequested -> { { cc.dismissProgress(cc.progressDone(c.gv.quality.code)) } }
        cc != null && cc.open && cc.mountPickerOpen -> { { cc.closeMountPicker() } }
        cc != null && cc.open -> { { cc.leaveScreen() } }
        c.exporter?.open == true -> { { c.exporter?.close() } }
        c.backup?.open == true -> { { c.backup?.close() } }
        c.hub.screen != null -> { { c.hub.back() } }
        c.antennaSheetOpen -> { { c.antennaSheetOpen = false } }
        c.setupOpen -> { { c.setupOpen = false } }
        c.quickSheetOpen -> { { c.quickSheetOpen = false } }
        c.settingsMenuOpen -> { { c.settingsMenuOpen = false } }
        !c.homeOpen && c.phase != Phase.ACQUIRING -> { { c.homeOpen = true } }
        // 0.1.31: 홈에서 뒤로 = 앱 종료 확인 창(예 → 수신기·VRS 연결 끊고 완전 종료). 예전 null = 연결 서비스가 남은 채 화면만 닫혔다
        else -> { { c.requestExit() } }
    }
}

/** 측정 진행 창: 1/3 → 2/3 → 3/3, 경과 시간, 중지 */
@Composable
private fun MeasureProgress(c: SurveyController) {
    val n = c.epochs; val k = c.measureCount.coerceAtMost(n)
    val steps = (1..n).map { i -> StepView("$i/$n 측정", when { i <= k -> StepState.DONE; i == k + 1 -> StepState.ACTIVE; else -> StepState.WAIT }) }
    val sec = rememberElapsedSec(c.measureStartedAtMs) { c.nowMs() }
    ProgressWindow(
        "측정 중 $k/$n", if (n <= 5) steps else emptyList(), sec, "measure_progress",
        note = "폴을 세운 채 움직이지 마세요 · ${c.gv.status.reason.textKo} · 끝나면 ${n}회 평균을 저장해요", progress = k.toFloat() / n,
    ) {
        MBtn(Modifier.weight(1f).height(56.dp).testTag("measure_stop"), MKind.PLAIN, 16.dp, onClick = { c.cancelAcquire() }) { Text("중지", style = Ty.label, color = T.ink) }
    }
}

// ---------------- 상태 띠 ----------------

private fun cm1(m: Double) = String.format(java.util.Locale.US, "%.1f", m * 100.0)

/** GGA 가 이 초보다 오래되면 위성 수 옆에 “오래됨” */
internal const val SATS_STALE_SEC = 3L

/** 상태 띠 셋째 줄: 위성 사용/보임 · 수평/수직 정확도(cm). 값이 없으면 "—". GGA 가 3초 넘게 안 오면 “N초 전(오래됨)” */
internal fun bandDetail(c: SurveyController): String {
    val g = c.gv
    val sats = "위성 ${g.satellites}" + (g.satsInView?.let { "/$it" } ?: "")
    val age = c.ggaAgeSec()
    val stale = if (age != null && age > SATS_STALE_SEC) " · ${age}초 전(오래됨)" else ""
    return g.quality.labelKo.removePrefix("RTK ") + " · " + sats + " · H " + (g.status.sigmaHM?.let { "±${cm1(it)}cm" } ?: "—") + " · V " + (g.sigmaVM?.let { "±${cm1(it)}cm" } ?: "—") + stale
}

/** 수신기가 연결되지 않았을 때 지도 가운데에 뜨는 안내 카드 */
@Composable
fun NotConnectedCard(c: SurveyController, modifier: Modifier) {
    if (c.gv.status.reason != StatusReason.NOT_CONNECTED) return
    val t = T
    Column(
        modifier.padding(horizontal = 40.dp).clip(RoundedCornerShape(18.dp)).background(t.bg.copy(alpha = 0.96f))
            .border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(16.dp).testTag("not_connected_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("수신기 연결 안 됨", style = Ty.title, color = t.ink)
        Text("블루투스로 R10 / SP60을 연결하면 위치가 나타나요. 그 전에는 위치를 표시하지 않고 측점도 할 수 없어요.", style = Ty.small, color = t.inkSub, textAlign = TextAlign.Center)
        Box(
            Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).glossy(t.primary)
                .hClickable { c.connection?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } }.testTag("not_connected_open"),
            contentAlignment = Alignment.Center,
        ) { Text("수신기 연결하기", style = Ty.label, color = t.onPrimary) }
    }
}

// ---------------- 지도 영역 ----------------

@Composable
fun MapArea(c: SurveyController, map: MapSurface, modifier: Modifier) {
    val t = T
    val ms = c.mapState()
    Box(
        modifier.fillMaxWidth().testTag("map_area").pointerInput(Unit) {
            // 0.1.21: 한 손가락 팬 + 두 손가락 핀치 줌(중심점 고정)
            detectTransformGestures { centroid, pan, zoom, _ ->
                val ppm = (c.dpPerM * density).toFloat()
                if (pan.x != 0f || pan.y != 0f) {
                    c.panBy((-pan.x / ppm).toDouble(), (pan.y / ppm).toDouble(), c.mapState().center)
                }
                if (zoom != 1f) {
                    val fx = ((centroid.x - size.width / 2f) / ppm).toDouble()
                    val fy = (-(centroid.y - size.height / 2f) / ppm).toDouble()
                    c.scaleBy(zoom.toDouble(), fx, fy)
                }
            }
        },
    ) {
        map.Draw(ms, Modifier.fillMaxSize())
        // 0.1.43 시안 ②: 위 「재는 것」 띠 + 레이어 / 오른쪽 가운데 내 위치(어두움) · + − 맞춤 / 왼쪽 아래 축척
        Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapNowBanner(c)
            StakeCard(c)
            BmResultCard(c)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapSquareBtn(Glyph.LAYERS, "지도에 보이기", "tool_layers") { c.dig.layersOpen = true }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapSquareBtn(Glyph.LOCATE, "내 위치 고정/해제", "tool_locate", dark = c.follow || true, selected = c.follow) { if (c.follow) c.follow = false else c.recenter() }
            Column(Modifier.width(48.dp).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp))) {
                MapGroupBtn("확대", "tool_zoom_in") { GlyphIcon(Glyph.PLUS, D.ink, 18.dp) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(D.hair))
                MapGroupBtn("축소", "tool_zoom_out") { GlyphIcon(Glyph.MINUS, D.ink, 18.dp) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(D.hair))
                MapGroupBtn("전체 보기", "tool_fit") { Text("맞춤", style = DTy.tiny.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = D.ink) }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 0.1.12 V월드: 키 없음 안내 · 오류 · 출처 표시(약관)
            // 키없음 → 타일오류(지적 WMS 구분) → 위성만 모드 안내 순
            val notice = c.mapNotice()
                ?: (if (ms.tiles.isNotEmpty()) TileImages.lastError else null)
                ?: kr.archsurvey.map.MapLayers.modeHint(c.mapBase)
            val noticeColor = if (TileImages.lastError != null && notice == TileImages.lastError) t.bad else if (c.mapNotice() != null) t.bad else t.inkSub
            notice?.let {
                // 0.1.46: 오류는 표준 오류 칩(예외 이름 없이 + [다시]), 안내는 작은 흰 글상자
                if (noticeColor == t.bad) ErrorChip(it, Modifier.widthIn(max = 300.dp), tag = "map_notice", onRetry = if (notice == TileImages.lastError) ({ TileImages.retry() }) else null)
                else Text(it, style = Ty.small, color = noticeColor, maxLines = 3, modifier = Modifier.widthIn(max = 260.dp).background(t.raised.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map_notice"))
            }
            if (ms.tiles.isNotEmpty()) Text("© V월드(국토교통부)", style = Ty.small.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.background(t.raised.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("map_attribution"))
            c.bg?.mapCaption()?.let {
                Text(it, style = Ty.small, color = t.ink, modifier = Modifier.background(t.raised.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map_caption"))
            }
        }
        val h = c.antennaResult?.totalToApcM
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 0.1.14: 위치 종류(코드) 칩 — 하단 툴바가 목업처럼 선·면·삭제·새 연결이 되면서 지도 왼쪽 아래로
            ArchOverlays(c)
            VoicePill(c) // 0.1.28 T14 듣는 중 표시
            c.message?.let { msg ->
                Text(msg, style = Ty.body, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(t.raised.copy(alpha = 0.95f), RoundedCornerShape(10.dp)).border(1.dp, t.accent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp).testTag("message"))
            }
            // 0.1.35 축척 막대 글자(화면 1 cm ≈ 실제 m)
            Text(scaleText(c.dpPerM), style = Ty.coord.copy(fontSize = 16.sp), color = t.ink,
                modifier = Modifier.background(t.bg.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag("scale_label"))
        }
    }
}

/** 0.1.43 지도 위 「재는 것」 띠(시안: ● 유구 경계 · 가-2 | 4번째 점 · 처음 점을 누르면 닫혀요) */
@Composable
internal fun MapNowBanner(c: SurveyController) {
    val (head, tail) = c.dig.nowLine()
    val hint = c.dig.measureSub().let { m -> if (tail != null && m.startsWith("$tail · ")) m.removePrefix("$tail · ") else m }.let { m -> if (c.dig.kind == kr.archsurvey.dig.DigKind.FEATURE && (c.dig.selectedFeature?.pointRowIds?.size ?: 0) >= 3) "$m · 처음 점을 누르면 닫혀요" else m }
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp))
            .hClickable { c.dig.pickerOpen = true }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("map_now_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(D.accentLight))
        Text("  " + head + (tail?.let { " · $it" } ?: ""), style = DTy.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = D.ink, maxLines = 1)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(16.dp).background(D.line))
        Text(hint, style = DTy.small, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MapSquareBtn(g: Glyph, desc: String, tag: String, dark: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(if (dark) D.dark else D.card).border(if (selected) 2.dp else 1.dp, if (selected) D.accentLight else if (dark) D.dark else D.line, RoundedCornerShape(10.dp))
            .hClickable(onClick = onClick).semantics { contentDescription = desc }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(g, if (dark) Color.White else D.ink, 22.dp) }
}

@Composable
private fun MapGroupBtn(desc: String, tag: String, content: @Composable () -> Unit) {
    val c = LocalSurvey.current
    Box(Modifier.fillMaxWidth().height(48.dp).hClickable {
        when (tag) { "tool_zoom_in" -> c?.zoomIn(); "tool_zoom_out" -> c?.zoomOut(); else -> c?.fitAll() }
    }.semantics { contentDescription = desc }.testTag(tag), contentAlignment = Alignment.Center) { content() }
}

// ---------------- 정보 영역 (좌표 / 취득 / 결과 띠 / 녹음) ----------------

@Composable
fun InfoZone(c: SurveyController) {
    val t = T
    if (c.phase == Phase.RESULT && c.resultStripShown) {
        androidx.compose.runtime.LaunchedEffect(c.resultStripSeq) { kotlinx.coroutines.delay(c.resultStripMs); c.hideResultStrip() }
    }
    val h = if (c.phase == Phase.RESULT && !c.resultStripShown) 0 else SurveyLayout.infoHeightDp(c.phase)
    Box(Modifier.fillMaxWidth().height(h.dp).background(t.card).testTag("info_zone")) {
        when (c.phase) {
            Phase.IDLE -> Unit
            Phase.ACQUIRING -> AcquireStrip(c)
            Phase.RESULT -> if (c.resultStripShown) ResultStrip(c)
            Phase.RESULT_EXPANDED -> Unit
            Phase.RECORDING -> RecordStrip(c)
        }
    }
}

@Composable
private fun AcquireStrip(c: SurveyController) {
    val t = T
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("측정 ${c.measureCount.coerceAtMost(c.epochs)}/${c.epochs} · ${c.gv.status.reason.textKo}", style = Ty.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("acquire_text"))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.line)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(c.progress.coerceIn(0f, 1f)).background(t.primary))
            }
        }
        SmallButton("취소", "acquire_cancel") { c.cancelAcquire() }
    }
}

/** 측점 직후 잠깐 보이는 한 줄 결과 띠. 측점 버튼을 막지 않는다. 누르면 사진·음성이 있는 패널로 펼쳐진다 */
@Composable
private fun ResultStrip(c: SurveyController) {
    val t = T
    val p = c.lastPoint ?: return
    val (bg, fg) = statusColors(t, PointCaution.displayStatus(p))
    Row(
        Modifier.fillMaxSize().hClickable { c.toggleExpand() }.padding(horizontal = 12.dp).testTag("result_strip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(6.dp).height(32.dp).clip(RoundedCornerShape(3.dp)).background(bg))   // 상태색 막대(칩과 같은 색)
        Text(p.name, style = Ty.coord.copy(fontSize = 20.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_name"))
        val tiltOver = p.tiltMaxDeg != null && c.tilt != null && p.tiltMaxDeg > c.tilt!!.limitDeg
        Text("${p.type} · 저장됨 · ${PointCaution.hv(p)}" + if (tiltOver) " · 기울기 ${String.format(java.util.Locale.US, "%.1f", p.tiltMaxDeg)}°" else "", style = Ty.small, color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("result_h"))
        Row(
            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).glossy(bg).padding(start = 6.dp, end = 9.dp).testTag("result_chip"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusIcon(PointCaution.displayStatus(p), fg, 14.dp)
            Text(PointCaution.chip(p), style = Ty.label.copy(fontSize = 16.sp), color = fg, maxLines = 1)
        }
        GlyphIcon(Glyph.CHEVRON_UP, t.accent, 20.dp)
    }
}

/** 펼친 결과 패널 (목업 s6b): 점 이름·종류·상태, N/E/H·±, 사진·음성·펼치기, 다음 점 · 되돌리기 */
@Composable
private fun ResultPanel(c: SurveyController) {
    val t = T
    val p = c.lastPoint
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    val expanded = c.phase == Phase.RESULT_EXPANDED
    Column(
        Modifier.fillMaxWidth().height((SurveyDims.RESULT_PANEL_DP + if (expanded) SurveyDims.EXPANDED_EXTRA_DP else 0).dp)
            .shadow(1.dp, shape).clip(shape).glossy(t.card, strength = 0.30f).border(1.dp, t.line, shape)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp).testTag("info_zone"),
    ) {
        if (expanded) Box(Modifier.height(SurveyDims.EXPANDED_EXTRA_DP.dp)) { ExpandedExtra(c) }
        // 0.1.14 목업 17: 「측량된 점 정보」 · 이름+고정 · X/Y/Z · 수평/수직 정밀도 · 「다음 점(Pxx)을 계속 측량할까요?」 아니요 / 예, 계속
        Text("측량된 점 정보", style = Ty.label.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1, modifier = Modifier.height(18.dp).testTag("result_title"))
        if (p != null) {
            val (bg, fg) = statusColors(t, PointCaution.displayStatus(p))
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = Ty.coord.copy(fontSize = 20.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_name"))
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).glossy(bg).padding(start = 6.dp, end = 9.dp).testTag("result_chip"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusIcon(PointCaution.displayStatus(p), fg, 14.dp)
                    Text(PointCaution.chip(p), style = Ty.label.copy(fontSize = 16.sp), color = fg, maxLines = 1)
                }
                Text(p.type, style = Ty.small, color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MBtn(Modifier.height(40.dp).testTag("photo_count_btn"), MKind.PLAIN, 14.dp, onClick = { if (c.photos.size < MediaLimits.PHOTOS) c.addPhotoCamera() else c.toggleExpand() }) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GlyphIcon(Glyph.CAMERA, t.accent, 18.dp)
                        Text("${c.photos.size}/${MediaLimits.PHOTOS}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("photo_count"))
                    }
                }
                MBtn(Modifier.size(40.dp).testTag("voice_btn"), MKind.PRIMARY, 20.dp, onClick = { c.toggleVoice() }) { GlyphIcon(Glyph.MIC, t.onPrimary, 18.dp) }
                MBtn(Modifier.size(40.dp).testTag("expand_btn"), MKind.PLAIN, 14.dp, onClick = { c.toggleExpand() }) {
                    GlyphIcon(if (expanded) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_UP, t.accent, 20.dp)
                }
            }
            Column(Modifier.fillMaxWidth().height(40.dp), verticalArrangement = Arrangement.Center) {
                val m = Ty.coord.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
                Text("X(N) ${grp3(p.northing)}  Y(E) ${grp3(p.easting)}", style = m, color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_coord"))
                Text("Z(H) ${p.groundHeightM?.let { grp3(it) } ?: "—"}  수평 ±${p.sigmaHM?.let { f3(it) } ?: "—"}  수직 ±${p.sigmaU?.let { f3(it) } ?: "—"} m", style = m, color = t.ink, maxLines = 1, modifier = Modifier.testTag("result_h"))
            }
        }
        Text("다음 점(${c.nextName})을 계속 측량할까요?", style = Ty.small.copy(fontSize = 16.sp), color = t.ink, maxLines = 1, modifier = Modifier.height(22.dp).padding(top = 2.dp).testTag("result_question"))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MBtn(Modifier.weight(1f).height(56.dp).testTag("result_no"), MKind.PLAIN, 28.dp, onClick = { c.dismissResult() }) {
                Text("아니요", style = Ty.label.copy(fontSize = 16.sp), color = t.ink, maxLines = 1)
            }
            MBtn(Modifier.weight(1f).height(56.dp).testTag("next_point"), MKind.PRIMARY, 28.dp, onClick = { c.nextPoint() }) {
                Text("예, 계속", style = Ty.button.copy(fontSize = 18.sp), color = t.onPrimary, maxLines = 1)
            }
            MBtn(Modifier.width(48.dp).height(48.dp).semantics { contentDescription = "마지막 점 되돌리기" }.testTag("undo_button"), MKind.PLAIN, 14.dp, onClick = { c.askUndo() }) { GlyphIcon(Glyph.UNDO, t.accent, 20.dp) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ExpandedExtra(c: SurveyController) {
    val t = T
    val p = c.lastPoint ?: return
    Column(Modifier.fillMaxWidth().height(SurveyDims.EXPANDED_EXTRA_DP.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "H ${p.groundHeightM?.let { f3(it) } ?: "—"} m (지면, 타원체고) · 안테나 위상중심 ${f3(p.antenna.apcHeightM)} m · 기준점 ${p.antenna.refPointLabel}",
            style = Ty.small, color = t.inkSub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("expanded_info"),
        )
        var confirmPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var confirmVoice by remember { mutableStateOf<VoiceItem?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(MediaLimits.PHOTOS) { i ->
                val ph = c.photos.getOrNull(i)
                Box(
                    Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(12.dp)).background(t.bg)
                        .hClickable { if (ph != null) confirmPhoto = ph else c.addPhotoCamera() }.testTag("photo_slot_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ph != null) PhotoThumb(c, ph) else GlyphIcon(Glyph.CAMERA, t.inkSub, 20.dp)
                }
            }
            Text("사진 ${c.photos.size}/${MediaLimits.PHOTOS}", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("photo_count"))
            SmallButton("앨범", "photo_pick") { c.addPhotoPicker() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).glossy(t.primary).hClickable { c.toggleVoice() }.padding(horizontal = 16.dp).testTag("voice_big"),
                contentAlignment = Alignment.Center,
            ) { Row(verticalAlignment = Alignment.CenterVertically) { GlyphIcon(Glyph.MIC, t.onPrimary, 20.dp); Spacer(Modifier.width(8.dp)); Text("음성 남기기", style = Ty.label, color = t.onPrimary) } }
            for (v in c.voices) {
                val playing = c.playingVoiceId == v.id
                Box(
                    Modifier.height(SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(if (playing) t.primary.copy(alpha = 0.25f) else t.bg)
                        .hCombinedClickable(onLongClick = { confirmVoice = v }, onClick = { c.playVoice(v) }).padding(horizontal = 10.dp).testTag("voice_item_${v.id}"),
                    contentAlignment = Alignment.Center,
                ) { Text((if (playing) "■ " else "▶ ") + "0:%02d".format((v.durationMs / 1000).toInt()), style = Ty.label, color = t.ink) }
            }
        }
        confirmPhoto?.let { ph ->
            ConfirmDialog("이 사진을 지울까요?", "앱 폴더의 사진만 지워요. 앨범의 원본은 그대로예요.", "지우기", { c.deletePhoto(ph); confirmPhoto = null }, { confirmPhoto = null })
        }
        confirmVoice?.let { v ->
            ConfirmDialog("이 음성 메모를 지울까요?", "지우면 되돌릴 수 없어요.", "지우기", { c.deleteVoice(v); confirmVoice = null }, { confirmVoice = null })
        }
    }
}

@Composable
internal fun PhotoThumb(c: SurveyController, ph: PhotoItem) {
    var b by remember(ph.rel) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(ph.rel) {
        b = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.media?.thumb(ph.rel) }.getOrNull() }
    }
    val bm = b
    if (bm != null) androidx.compose.foundation.Image(bm, "사진", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else GlyphIcon(Glyph.CAMERA, T.accent, 20.dp)
}

@Composable
internal fun ConfirmDialog(title: String, body: String, okLabel: String, onOk: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOk, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_ok")) { Text(okLabel) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_cancel")) { Text("취소") } },
    )
}

@Composable
private fun RecordStrip(c: SurveyController) {
    val t = T
    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(t.bad))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("녹음 중 0:%02d / 0:%02d".format(c.voiceSeconds, MediaLimits.VOICE_SECONDS), style = Ty.label, color = t.ink, modifier = Modifier.testTag("record_text"))
            Text(if (c.media != null) "마이크로 녹음하고 있어요 · 끝내기를 누르면 저장" else "화면 시안 — 실제 녹음은 아직 연결 전", style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub)
        }
        SmallButton("끝내기", "record_stop") { c.toggleVoice() }
    }
}

@Composable
private fun SmallButton(label: String, tag: String, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.padding(end = 8.dp).height(SurveyDims.MIN_TOUCH_DP.dp).widthIn(min = SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp))
            .background(t.bg).hClickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ty.label, color = t.ink) }
}

@Composable
private fun IconBtn(g: Glyph, tag: String, onClick: () -> Unit) {
    val t = T
    Box(Modifier.size(SurveyDims.MIN_TOUCH_DP.dp).hClickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) { GlyphIcon(g, t.accent) }
}

// ---------------- 컨트롤 영역 ----------------

// ---------------- 왼쪽 서랍 ----------------

@Composable
fun DrawerOverlay(c: SurveyController) {
    val t = T
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AnimatedVisibility(c.drawerOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(t.scrim).hClickable { c.drawerOpen = false }.testTag("drawer_scrim"))
        }
        AnimatedVisibility(
            c.drawerOpen,
            enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it },
        ) {
            val dshape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
            Column(
                Modifier.width(maxWidth * 0.8f).fillMaxHeight().clip(dshape).glossy(t.card).border(1.5.dp, t.outline, dshape)
                    .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 16.dp).testTag("drawer"),
            ) {
                val st = c.gv.status
                val shownSt = c.gv.displayStatus // 0.1.31: 띠·알약과 같은 표시 상태(정밀도 정보 없음 고정 = 초록)
                val (bg, fg) = statusColors(t, shownSt)
                Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(kr.archsurvey.R.drawable.ic_logo_mark_terracotta),
                        contentDescription = "발굴 측량 로고", modifier = Modifier.size(30.dp).testTag("drawer_logo"),
                    )
                    Text("발굴 측량", style = Ty.small, color = t.accentText)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    // 0.1.38: 수신기 이름·상태 칩은 머리줄 알약과 같은 정보라 뺐다. 대신 작업 폴더
                    Column(Modifier.weight(1f)) {
                        Text(c.projectName, style = Ty.title.copy(fontSize = 18.sp), color = t.ink, maxLines = 1)
                        c.jobs?.currentFolder?.let { Text(it, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 2, modifier = Modifier.testTag("drawer_folder")) }
                    }
                    // 0.1.38: 톱니(설정)는 아래 「설정 · 구글 드라이브」와 같은 곳이라 뺐다
                }
                for (sec in DrawerMenu.sections) {
                    Text(sec.title, style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, modifier = Modifier.height(26.dp).padding(horizontal = 6.dp).wrapContentHeight(Alignment.Bottom))
                    for (it in sec.items) DrawerRow(c, it)
                }
                Spacer(Modifier.height(16.dp))
                // 0.1.31: 앱 종료(수신기 연결을 끊고 완전히 닫기 — 확인 창)
                MBtn(Modifier.fillMaxWidth().height(48.dp).testTag("drawer_exit"), MKind.PLAIN, 16.dp, onClick = { c.drawerOpen = false; c.requestExit() }) {
                    Text(AppExit.MENU_LABEL, style = Ty.label, color = t.ink)
                }
                Spacer(Modifier.height(8.dp))
                Text(rememberAppVersionLabel(), style = Ty.small, color = t.inkSub, modifier = Modifier.padding(start = 6.dp).testTag("drawer_version"))
            }
        }
    }
}

private fun runMenu(c: SurveyController, item: MenuItem) {
    when (item.action) {
            MenuAction.OPEN_AREAS -> { c.drawerOpen = false; c.hub.open(ToolScreen.AREAS) }
            MenuAction.OPEN_FEATURES -> { c.drawerOpen = false; c.homeOpen = false; c.hub.open(ToolScreen.FEATURES) }
            MenuAction.OPEN_LAYERS -> { c.drawerOpen = false; c.homeOpen = false; c.dig.layersOpen = true }
            MenuAction.OPEN_TYPES -> { c.drawerOpen = false; c.hub.open(ToolScreen.TYPES) }
            MenuAction.GO_SURVEY -> c.goToSurvey() // 0.1.14: 홈·환경설정 위에서 눌러도 측량으로
            MenuAction.OPEN_BG -> { c.drawerOpen = false; c.bg?.openManage() ?: run { c.message = "배경 레이어는 이 빌드에 없어요" } }
            MenuAction.OPEN_ANTENNA -> { c.drawerOpen = false; c.antennaSheetOpen = true }
            MenuAction.OPEN_TOOL_POINTS -> { c.drawerOpen = false; c.hub.open(ToolScreen.POINTS) }
            MenuAction.OPEN_TOOL_GOTO -> { c.drawerOpen = false; c.hub.open(ToolScreen.GOTO) }
            MenuAction.OPEN_TOOL_CALC -> { c.drawerOpen = false; c.hub.open(ToolScreen.CALC) }
            MenuAction.OPEN_TOOL_SETTINGS -> { c.drawerOpen = false; c.hub.open(ToolScreen.SETTINGS) }
            MenuAction.OPEN_JOB_NEW -> { c.drawerOpen = false; c.jobs?.openNew() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_JOB_LIST -> { c.drawerOpen = false; c.jobs?.openList() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_JOB_INFO -> { c.drawerOpen = false; c.jobs?.openInfo() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }
            MenuAction.OPEN_BACKUP -> { c.drawerOpen = false; c.backup?.openScreen() ?: run { c.message = "백업은 이 빌드에 없어요" } }
            MenuAction.OPEN_EXPORT -> { c.drawerOpen = false; c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }
            MenuAction.OPEN_CONNECTION -> { c.drawerOpen = false; c.connection?.openScreen() ?: run { c.message = "연결 화면은 이 빌드에 없어요" } }
        }
}

private fun menuGlyph(id: String): Glyph? = when (id) {
    "fav_points", "job_points" -> Glyph.LIST
    "fav_goto", "goto" -> Glyph.LOCATE
    "fav_calc", "calc" -> Glyph.CALC
    "job_new" -> Glyph.PLUS
    "job_open" -> Glyph.FOLDER
    "job_export" -> Glyph.SHARE
    "job_info" -> Glyph.INFO
    "job_backup" -> Glyph.LAYERS
    "features" -> Glyph.LIST
    "layers" -> Glyph.LAYERS
    "bg" -> Glyph.LAYERS
    "settings" -> Glyph.INFO
    "areas" -> Glyph.LAYERS
    "types" -> Glyph.LIST
    "survey" -> Glyph.PIN
    "connection" -> Glyph.BT
    "antenna" -> Glyph.RULER
    "pole_side" -> Glyph.EXTENT
    else -> null
}

@Composable
private fun DrawerRow(c: SurveyController, item: MenuItem) {
    val t = T
    val selected = item.action == MenuAction.GO_SURVEY && c.hub.screen == null
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(SurveyDims.MIN_TOUCH_DP.dp).clip(shape)
            .background(if (selected) t.primaryCont else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, t.accent, shape) else Modifier)
            .alpha(if (item.enabled) 1f else 0.5f)
            .hClickable(enabled = item.enabled) { runMenu(c, item) }.padding(horizontal = 12.dp).testTag("menu_${item.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val g = menuGlyph(item.id)
        if (g != null) GlyphIcon(g, t.accent, 20.dp) else Spacer(Modifier.width(20.dp))
        Text(item.label, style = Ty.body.copy(fontSize = 16.sp, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Light), color = t.ink, modifier = Modifier.weight(1f))
        item.note?.let { Text(it, style = Ty.small, color = t.inkSub) }
    }
}

/** 즐겨찾기 카드: 아이콘 위, 글자 아래 (목업 s17) */
@Composable
private fun FavCard(c: SurveyController, item: MenuItem, modifier: Modifier) {
    val t = T
    MBtn(modifier.height(48.dp).testTag("menu_${item.id}"), MKind.PLAIN, 14.dp, onClick = { runMenu(c, item) }) {
        menuGlyph(item.id)?.let { GlyphIcon(it, t.accent, 18.dp) }
        Text(item.label, style = Ty.small.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = t.ink, maxLines = 1)
    }
}

// ---------------- 안테나 높이 시트 (s4 최소판) ----------------

/** 0.1.46 시안 s06 폴 높이 시트: 큰 숫자 · ±0.10 · 자주 쓰는 높이 4칸 · 적용. 수신기 모델·기준점은 「자세히」 */
@Composable
fun AntennaSheet(c: SurveyController) {
    val t = T
    var more by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim.copy(alpha = 0.18f)).hClickable { c.antennaSheetOpen = false })
        val sh = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.75f)
                .clip(sh).background(D.bg).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("antenna_sheet"),
            verticalArrangement = Arrangement.spacedBy(FieldIvory.gap),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 44.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(D.line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("폴 높이", style = DTy.h2.copy(fontSize = 20.sp), color = D.ink, modifier = Modifier.weight(1f))
                Box(Modifier.size(FieldIvory.minTouch).clip(CircleShape).hClickable { c.antennaSheetOpen = false }.semantics { contentDescription = "닫기" }.testTag("sheet_x"), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CLOSE, D.ink, 22.dp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(String.format(java.util.Locale.US, "%.2f", c.antenna.inputValueM), style = DTy.h1.copy(fontSize = FieldIvory.display, lineHeight = 70.sp), color = D.ink, modifier = Modifier.testTag("sheet_input"))
                Text(" m", style = DTy.h2.copy(fontSize = 22.sp), color = D.ink, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text("수신기 위상중심까지 · 바뀐 기록이 점마다 남아요", style = DTy.small, color = D.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            ChoiceGrid(listOf(
                GridItem("− 0.10", "step_minus") { c.stepAntennaCm(-AntennaMath.POLE_STEP_CM) },
                GridItem("+ 0.10", "step_plus") { c.stepAntennaCm(AntennaMath.POLE_STEP_CM) },
            ), columns = 2)
            ChoiceGrid(AntennaMath.CHIPS_M.map { m ->
                GridItem(String.format(java.util.Locale.US, "%.2f", m), "pole_chip_" + String.format(java.util.Locale.US, "%.1f", m), kotlin.math.abs(c.antenna.inputValueM - m) < 1e-6) { c.setPoleHeight(m) }
            }, columns = 4)
            Row(Modifier.fillMaxWidth().heightIn(min = FieldIvory.minTouch).hClickable { more = !more }.testTag("sheet_more"), verticalAlignment = Alignment.CenterVertically) {
                Text("수신기 ${if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60"} · ${c.antennaModel.visibleRefs.firstOrNull { it.id == c.antenna.refPointId }?.labelKo ?: ""}", style = DTy.small, color = D.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (more) "접기 ⌃" else "자세히 ›", style = DTy.small, color = D.accent)
            }
            if (more) {
                ChoiceGrid(listOf("TRIMBLE_R10" to "Trimble R10", "SPECTRA_SP60" to "Spectra SP60").map { (id, label) -> GridItem(label, "model_$id", c.antennaModelId == id) { c.setAntennaModel(id) } }, columns = 2)
                Text("어디부터 쟀나요", style = DTy.small, color = D.sub)
                for (r in c.antennaModel.visibleRefs) {
                    val sel = c.antenna.refPointId == r.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = FieldIvory.minTouch).clip(RoundedCornerShape(14.dp)).background(if (sel) FieldIvory.primarySoft else D.card)
                            .border(if (sel) 2.dp else 1.dp, if (sel) FieldIvory.primary else D.line, RoundedCornerShape(14.dp)).hClickable { c.setRefPoint(r.id) }.padding(horizontal = 12.dp).testTag("ref_${r.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.labelKo, style = DTy.body, color = D.ink, modifier = Modifier.weight(1f))
                        Text("+${f3(r.pcoV)} m", style = DTy.mono, color = D.sub)
                    }
                }
                Text(if (c.antennaModelId == "TRIMBLE_R10") (if (c.antenna.refPointId == "bottom_of_antenna_mount") AntennaMath.ADAPTER_NOTE_R10_MOUNT else AntennaMath.ADAPTER_NOTE_R10) else AntennaMath.ADAPTER_NOTE_SP60, style = DTy.small, color = D.sub)
            }
            val r = c.antennaResult
            Text(r?.let { AntennaMath.summary(it) } ?: "계산할 수 없어요", style = DTy.small, color = D.ink, modifier = Modifier.testTag("sheet_summary"))
            AntennaMath.mismatchWarning(c.antenna, c.antennaModel)?.let { Text(it, style = DTy.small, color = FieldIvory.error) }
            DPrimary("적용", "sheet_close", Modifier.fillMaxWidth(), height = FieldIvory.minTouch + 8.dp, color = FieldIvory.primary) { c.antennaSheetOpen = false }
        }
    }
}

@Composable
private fun StepBtn(label: String, tag: String, onClick: () -> Unit) {
    val t = T
    Box(
        Modifier.size(width = 84.dp, height = SurveyDims.MIN_TOUCH_DP.dp).clip(RoundedCornerShape(14.dp)).background(t.card).hClickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ty.label, color = t.ink) }
}

/** 「배경지도」 버튼: V월드 배경맵(작업별 저장) 고르기 + 올린 도면 켜기/끄기 */
internal fun chooseMapBase(c: SurveyController) {
    val bases = kr.archsurvey.map.MapBase.entries
    val opts = bases.map { it.labelKo } + listOf("V월드 키 · 도메인 설정…", "올린 도면 보이기 / 숨기기")
    c.ui.choose(ChoiceRequest("배경지도", opts, bases.indexOf(c.mapBase), "mapbase",
        if (c.vworld.hasKey) "고른 배경맵은 이 작업에 저장돼요. 지적도는 참고용이에요." else "V월드 키가 없어서 지금은 점만 보여요. ‘V월드 키 · 도메인 설정’에서 넣으세요.") { i ->
        when {
            i < bases.size -> {
                c.changeMapBase(bases[i])
                if (bases[i] == kr.archsurvey.map.MapBase.SATELLITE) {
                    c.message = "지적선이 꺼져 있어요. ‘위성 + 지적선’을 고르면 지적 경계가 겹쳐요."
                }
            }
            i == bases.size -> { c.vworld.load(); c.hub.open(ToolScreen.VWORLD) }
            else -> c.bg?.toggle() ?: run { c.message = "배경 레이어는 이 빌드에 없어요" }
        }
    })
}

/** 기본은 자체 Canvas 지도. `-PwithMapLibre` 로 빌드해 클래스가 있을 때만 MapLibre 지도를 쓴다(실기기 확인 전이라 기본값 아님). */
@Composable
fun rememberMapSurface(c: SurveyController): MapSurface = remember(c) {
    if (kr.archsurvey.BuildConfig.USE_MAPLIBRE) {
        runCatching { Class.forName("kr.archsurvey.ui.MapLibreMapSurface").getConstructor(kr.archsurvey.map.LocalProjection::class.java).newInstance(c.projection) as MapSurface }
            .getOrElse { CanvasMapSurface() }
    } else CanvasMapSurface()
}


/** 기울기 확인 창(설정 ‘측점 때 확인’): 그래도 측점 / 취소 */
@Composable
fun TiltSheet(c: SurveyController) {
    val t = T
    val a = c.tiltPrompt ?: return
    val tc = c.tilt ?: return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).hClickable { c.answerTilt(false) })
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(shape).background(t.bg).border(1.dp, t.line, shape).padding(20.dp).testTag("tilt_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("폴이 기울었어요 · ${tc.f1(a)}°", style = Ty.title, color = t.ink)
            Text("허용 ${tc.f1(tc.limitDeg)}°를 넘었어요. 폴을 세운 뒤 측점하는 것이 좋아요. 기울기 값은 점과 함께 기록돼요.", style = Ty.body, color = t.inkSub)
            Text(TiltCopy.offsetLine(a) + " · " + TiltCopy.SHORT, style = Ty.small, color = t.inkSub)
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("tilt_cancel"), MKind.PRIMARY, 18.dp, onClick = { c.answerTilt(false) }) { Text("취소 (폴 세우기)", style = Ty.label, color = t.onPrimary) }
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("tilt_go"), MKind.PLAIN, 18.dp, onClick = { c.answerTilt(true) }) { Text("그래도 측점", style = Ty.label, color = t.ink) }
        }
    }
}


/** 0.1.35 축척 글자: 「2 m · 화면 1:200」(화면 1 dp ≈ 1/160 inch 로 근사) */
internal fun scaleText(dpPerM: Double): String {
    val nice = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
    val m = nice.firstOrNull { it * dpPerM >= 60 } ?: nice.last()
    val denom = (160.0 / 0.0254 / dpPerM).let { d -> listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000).minByOrNull { kotlin.math.abs(it - d) } ?: 1000 }
    val ms = if (m < 1) String.format(java.util.Locale.US, "%.1f", m) else String.format(java.util.Locale.US, "%.0f", m)
    return "$ms m · 화면 1:$denom"
}

/** 폴드 안쪽 오른쪽 칸: 오늘 잰 점 */
@Composable
internal fun TodayPointsList(c: SurveyController) {
    val t = T
    val day = c.dig.today()
    val list = c.points.filter { kr.archsurvey.dig.DayKey.of(it.measuredAtMs) == day }.takeLast(30).reversed()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("today_points"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("오늘 점 ${list.size}", style = Ty.serifHead.copy(fontSize = 16.sp), color = t.ink)
        for (p in list) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).hClickable { c.focusOn(p) }, verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.width(76.dp))
            Text(c.dig.whatLine(p), style = Ty.small.copy(fontSize = 16.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** 0.1.45: 측정 저장 뒤 ~2초 지도 위 작은 알림 「P-0038 저장 · 해발 · ±cm」. 자기 크기만 차지해 지도·측정 단추 탭을 막지 않는다. 누르면 점 자세히 */
@Composable
fun SavedToast(c: SurveyController) {
    if (!(c.phase == Phase.RESULT && c.resultStripShown && !c.pointDetailOpen && !c.homeOpen && c.hub.screen == null &&
            c.connection?.open != true && c.exporter?.open != true && c.dupPrompt == null && c.tiltPrompt == null &&
            !c.setupOpen && !c.antennaSheetOpen && !c.dig.pickerOpen && !c.drawerOpen)) return // 다른 창 위에는 안 띄움
    val p = c.lastPoint ?: return
    androidx.compose.runtime.LaunchedEffect(c.resultStripSeq) { kotlinx.coroutines.delay(c.resultStripMs); c.hideResultStrip() }
    Box(Modifier.fillMaxSize().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
        val h = p.groundHeightM?.let { "해발 " + String.format(java.util.Locale.US, "%.2f", it) + " m" } ?: "해발 —"
        val s = p.sigmaHM?.let { "±" + String.format(java.util.Locale.US, "%.1f", it * 100) + " cm" } ?: "±—"
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(D.ink.copy(alpha = 0.9f)).hClickable { c.openPointDetail() }
                .heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics { contentDescription = "${p.name} 저장됨, 눌러서 자세히" }.testTag("saved_toast"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${p.name} 저장 · $h · $s", style = Ty.label.copy(fontSize = 16.sp), color = D.bg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("saved_toast_text"))
            // 0.1.46: 3초 안 한 번에 되돌리기(확인 창 없이 — 알림 자체가 확인)
            Box(Modifier.padding(start = 8.dp).heightIn(min = 48.dp).hClickable { c.hideResultStrip(); c.undo() }.padding(horizontal = 8.dp).semantics { contentDescription = "${p.name} 되돌리기" }.testTag("saved_toast_undo"), contentAlignment = Alignment.Center) {
                Text("되돌리기", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = FieldIvory.decor)
            }
        }
    }
}
