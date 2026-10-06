package kr.archsurvey.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.core.StatusReason
import kr.archsurvey.core.SurveyStatus
import kr.archsurvey.core.TmProjection
import kr.archsurvey.device.LinkState

/** 화면 어디서나 머리글이 상태를 읽을 수 있게 SurveyMapScreen 이 넣어 준다(시험에서 단독으로 그린 화면은 null) */
val LocalSurvey = compositionLocalOf<SurveyController?> { null }

/** 0.1.11 머리글 높이(dp): 윗줄 56 + 작업명·정밀도 두 줄 40 + 선 1 */
object HeaderDims { const val TOP_DP = 56; const val INFO_DP = 40; const val FULL_DP = TOP_DP + INFO_DP + 1; const val COMPACT_DP = TOP_DP + 1 }

internal fun toneColors(t: Tokens, tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.FIXED -> t.fixed to t.onFixed
    Tone.CAUTION -> t.caution to t.onCaution
    Tone.BAD -> t.bad to t.onBad
    Tone.OK -> t.ok to t.onFixed
    Tone.NONE -> t.chip to t.inkSub
    Tone.NO_RECEIVER -> t.chip to t.cautionInk
}

/** 1초마다 다시 그려 보정 연령·“오래됨”이 멈춰 보이지 않게 한다 */
@Composable
internal fun rememberTick(active: Boolean = true): Int {
    var tick by remember { mutableIntStateOf(0) }
    if (active) LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    return tick
}

@Composable
private fun headerPill(c: SurveyController): HeaderPill {
    val cc = c.connection
    val ntrip = cc?.ntrip?.collectAsState()?.value
    val tick = rememberTick()
    return remember(c.gv, ntrip, cc?.form?.enabled, tick) {
        HeaderModel.pill(c.gv, ntrip, cc?.form?.enabled == true, android.os.SystemClock.elapsedRealtime())
    }
}

/** 상단 상태 알약(목업): 위성 수 | 보정 상태 | 고정·유동·미연결. 누르면 GNSS 정보 창. 터치 영역은 48dp */
@Composable
fun StatusPill(c: SurveyController, modifier: Modifier = Modifier) {
    val t = T
    val p = headerPill(c)
    val (fbg, ffg) = toneColors(t, p.fixTone)
    val corrColor = when (p.correctionTone) { Tone.BAD -> t.bad; Tone.NONE -> t.inkSub; else -> t.ink }
    val shape = RoundedCornerShape(18.dp)
    Box(
        // 0.1.30 T24: 수신기 없음·끊김이면 누르면 연결 화면(그 밖엔 GNSS 정보 창)
        modifier.height(48.dp).hClickable { if (c.gv.noReceiver) (c.connection?.openScreen() ?: run { c.gnssInfoOpen = true }) else c.gnssInfoOpen = true }
            .semantics { contentDescription = "위성 ${p.sats}개, ${p.correction}, ${p.fix}. " + if (c.gv.noReceiver) "눌러서 수신기 연결" else "눌러서 GNSS 정보" }.testTag("status_pill"),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.height(36.dp).clip(shape).background(t.raised).border(1.dp, t.line.copy(alpha = 0.5f), shape), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GlyphIcon(Glyph.SAT, t.ink, 14.dp)
                Text(p.sats, style = Ty.coord.copy(fontSize = 13.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("pill_sats"))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(t.line.copy(alpha = 0.5f)))
            Text(p.correction, style = Ty.small.copy(fontSize = 12.sp), color = corrColor, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp).testTag("pill_corr"))
            Box(Modifier.fillMaxHeight().background(fbg).padding(horizontal = 12.dp).testTag("pill_fix_bg"), contentAlignment = Alignment.Center) {
                Text(p.fix, style = Ty.label.copy(fontSize = 13.sp), color = ffg, maxLines = 1, modifier = Modifier.testTag("pill_fix"))
            }
        }
    }
}

/** 머리글 아이콘 단추(48dp) */
@Composable
internal fun HeaderIcon(g: Glyph, desc: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).hClickable(onClick = onClick).semantics { contentDescription = desc }.testTag(tag), contentAlignment = Alignment.Center) {
        GlyphIcon(g, T.ink, 22.dp)
    }
}

/**
 * 0.1.11 공통 머리글(목업 02~23): ← 제목 [위성|보정|고정] ≡ / 현재 작업명 / PDOP·H·V · Ant.H.
 * [compact] 이면 윗줄만(측점 결과 패널을 펼쳤을 때 지도 비율을 지키려고).
 */
@Composable
fun AppHeader(
    c: SurveyController?, title: String, onBack: (() -> Unit)?, compact: Boolean = false,
    backTag: String = "header_back", menuTag: String = "header_menu", modifier: Modifier = Modifier,
) {
    val t = T
    Column(modifier.fillMaxWidth().background(t.bg).testTag("app_header")) {
        Row(Modifier.fillMaxWidth().height(HeaderDims.TOP_DP.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) HeaderIcon(Glyph.BACK, "뒤로", backTag, onBack) else Spacer(Modifier.width(12.dp))
            Text(title, style = Ty.title.copy(fontSize = 20.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("header_title"))
            if (c != null) {
                StatusPill(c, Modifier.padding(horizontal = 4.dp))
                HeaderIcon(Glyph.MENU, "전체 메뉴", menuTag) { c.drawerOpen = true }
            }
        }
        if (!compact && c != null) {
            Column(Modifier.fillMaxWidth().height(HeaderDims.INFO_DP.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                Text("현재 작업명 " + c.projectName, style = Ty.small.copy(fontSize = 11.sp, lineHeight = 14.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("header_job"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(HeaderModel.metrics(c.gv), style = Ty.coord.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("header_metrics"))
                    Text(HeaderModel.antH(c.antenna.inputValueM), style = Ty.coord.copy(fontSize = 12.sp, lineHeight = 18.sp), color = t.inkSub, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("header_anth"))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.35f)))
    }
}


/** StatusBand 부제 문구. 엄격+CAUTION은 측점 버튼「엄격 · FIX 필요」와 맞춘다 (UX-0126-1). */
internal fun statusBandSub(status: SurveyStatus, reason: StatusReason, notConnected: Boolean, requireFixed: Boolean, noSigmaHint: String? = null): String? {
    if (notConnected) return "블루투스로 R10 / SP60 을 연결하세요"
    // 0.1.30 T23-c: 고정·보정 양호·σ만 없음 → 띠는 ‘측량 가능’(표시). 저장 결과는 그대로 알려 준다
    if (status == SurveyStatus.CAUTION && reason == StatusReason.NO_SIGMA)
        return if (requireFixed) "엄격 모드 · " + (noSigmaHint ?: "수신기에서 GST·GSA 출력을 켜 주세요") else SigmaText.BAND_SUB_NORMAL
    // 0.1.30 T23: σ 없음(NO_SIGMA)에는 기기별 안내를 붙인다
    val why = reason.textKo + if (reason == StatusReason.NO_SIGMA && noSigmaHint != null) " · $noSigmaHint" else ""
    return when (status) {
        SurveyStatus.CAUTION -> if (requireFixed) "엄격 모드 · FIX만 측점 · $why"
            else "저장 시 표시 기록 · $why"
        SurveyStatus.UNAVAILABLE -> "측점 막힘 · ${reason.textKo}"
        SurveyStatus.FIXED -> null
    }
}

/** 측량 화면 머리글 아래 판정 줄: 측량 가능/주의/불가 + 이유 + 위성·정밀도. FULL=STATUS_BAND(두 줄 주의·불가), COMPACT=결과 펼침 */
object StatusLineDims { const val FULL_DP = SurveyDims.STATUS_BAND_DP; const val COMPACT_DP = 30 }

@Composable
internal fun StatusBand(c: SurveyController, compact: Boolean = false, modifier: Modifier = Modifier) {
    val t = T
    val st = c.gv.status
    val notConnected = st.reason == StatusReason.NOT_CONNECTED
    val shown = c.gv.displayStatus // 0.1.30 T23-c: 색·제목은 표시 상태, 부제는 저장 판정(st)
    val main = if (notConnected) "불가 · ${HeaderModel.NO_RECEIVER}"
    else if (c.gv.sigmaMissingOnFix) (if (c.gv.strictMode) SigmaText.STRICT_BAND_MAIN else SigmaText.FIX_NO_SIGMA)
    else when (shown) {
        SurveyStatus.FIXED -> "측량 가능"
        SurveyStatus.CAUTION -> "주의"
        SurveyStatus.UNAVAILABLE -> "불가"
    }
    val sub = statusBandSub(st.status, st.reason, notConnected, c.settingsState?.requireFixed == true, PrecisionGuide.noSigmaHint(c.deviceIdProvider()))
    val tick = rememberTick(!notConnected)
    val detail = if (notConnected) null else { tick; bandDetail(c) }
    val sigma = if (notConnected) null else st.sigmaHM
    val dot = when (shown) { SurveyStatus.FIXED -> t.fixed; SurveyStatus.CAUTION -> t.caution; SurveyStatus.UNAVAILABLE -> t.bad }
    val tint = when (shown) { SurveyStatus.FIXED -> t.card; SurveyStatus.CAUTION -> t.caution.copy(alpha = 0.14f); SurveyStatus.UNAVAILABLE -> t.bad.copy(alpha = 0.08f) }
    Row(
        modifier.fillMaxWidth().height((if (compact) StatusLineDims.COMPACT_DP else StatusLineDims.FULL_DP).dp).background(tint).padding(horizontal = 16.dp).testTag("status_band"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(main, style = Ty.label.copy(fontSize = 14.sp, lineHeight = 17.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("status_main"))
                if (compact && detail != null) Text(" · $detail", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status_detail"))
                else Spacer(Modifier.weight(1f))
                if (sigma != null) Text("±" + f3(sigma) + " m", style = Ty.coord.copy(fontSize = 13.sp), color = t.ink, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("status_sigma"))
                else if (!notConnected && c.gv.sigmaMissingOnFix) Text("H — · V — · " + HeaderModel.SIGMA_MISSING, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("status_sigma_note"))
            }
            if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
                if (sub != null) Text(sub, style = Ty.small.copy(fontSize = 12.sp, lineHeight = 15.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = detail == null).testTag("status_sub"))
                if (sub != null && detail != null) Text(" · ", style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub)
                if (detail != null) Text(detail, style = Ty.small.copy(fontSize = 12.sp, lineHeight = 15.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status_detail"))
            }
        }
    }
}

private fun dms(v: Double, pos: String, neg: String): String {
    val a = Math.abs(v); val d = a.toInt(); val mFull = (a - d) * 60; val m = mFull.toInt(); val s = (mFull - m) * 60
    return String.format(java.util.Locale.US, "%d° %02d′ %07.4f″ %s", d, m, s, if (v >= 0) pos else neg)
}

/** GNSS 정보 창(목업 05). SkyPlot 단추 → 위성 배치(GSV 상세가 없으면 빈 상태 안내) */
@Composable
fun GnssInfoDialog(c: SurveyController) {
    val t = T
    val g = c.gv
    val cc = c.connection
    val link = cc?.link?.collectAsState()?.value
    val p = if (g.lat != null && g.lon != null) TmProjection.forward(g.lat, g.lon, c.zone) else null
    val dev = cc?.let { x -> x.bonded.firstOrNull { it.second == x.address }?.first ?: x.address }
    val model = cc?.profile?.displayName ?: if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60"
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).hClickable { c.gnssInfoOpen = false }.testTag("gnss_info_scrim"))
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 20.dp).fillMaxWidth().clip(shape).background(t.raised).hClickable(enabled = false) {}
                .padding(20.dp).testTag("gnss_info"),
        ) {
            Text("GNSS 정보", style = Ty.title, color = t.ink, modifier = Modifier.padding(bottom = 8.dp))
            val rows = listOf(
                "위도" to (g.lat?.let { dms(it, "N", "S") } ?: "—"),
                "경도" to (g.lon?.let { dms(it, "E", "W") } ?: "—"),
                "안테나 표고(MSL)" to (g.altM?.let { f3(it) + " m" } ?: "—"),
                "타원체고" to (g.ellipsoidalM?.let { f3(it) + " m" } ?: "—"),
                "X(N)" to (p?.northing?.let { grp3(it) } ?: "—"),
                "Y(E)" to (p?.easting?.let { grp3(it) } ?: "—"),
                "해 품질" to when {
                    g.status.reason == StatusReason.NOT_CONNECTED -> HeaderModel.NO_RECEIVER
                    g.status.reason == StatusReason.LINK_DOWN -> HeaderModel.RECEIVER_LOST
                    else -> g.quality.labelKo
                },
                "위성 사용/보임" to ("${g.satellites}" + (g.satsInView?.let { " / $it" } ?: "")),
                "PDOP / HDOP / VDOP" to listOf(g.pdop, g.hdop, g.vdop).joinToString(" / ") { v -> v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—" },
                "보정 연령" to (g.correctionAgeSec?.let { String.format(java.util.Locale.US, "%.1f 초", it) } ?: "—") + (g.baseId?.let { " · 기준국 $it" } ?: ""),
                "장비" to (HeaderModel.shortModel(model) + (dev?.let { " · $it" } ?: "")),
                "좌표계" to c.zone.labelKo,
                // 0.1.14 목업 05: 시리얼·펌웨어는 NMEA 로 오지 않아 지어내지 않고 "—"(가짜 값 금지)
                "시리얼 번호" to "— (수신기가 보내지 않음)",
                "펌웨어 버전" to "— (수신기가 보내지 않음)",
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = Ty.small.copy(fontSize = 13.sp), color = t.inkSub, modifier = Modifier.weight(1f))
                    Text(v, style = Ty.coord.copy(fontSize = 13.sp), color = t.ink, maxLines = 1, modifier = Modifier.testTag("gnss_info_$i"))
                }
                if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
            }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MBtn(Modifier.weight(1f).height(48.dp).testTag("gnss_info_sky"), MKind.PLAIN, 24.dp, onClick = { c.skyPlotOpen = true }) {
                    Text("SkyPlot", style = Ty.label, color = t.ink, maxLines = 1)
                }
                MBtn(Modifier.weight(1f).height(48.dp).testTag("gnss_info_conn"), MKind.PLAIN, 24.dp, onClick = { c.gnssInfoOpen = false; c.connection?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } }) {
                    Text("장비 연결", style = Ty.label, color = t.ink, maxLines = 1)
                }
                MBtn(Modifier.weight(1f).height(48.dp).testTag("gnss_info_vrs"), MKind.PLAIN, 24.dp, onClick = { c.gnssInfoOpen = false; c.connection?.openVrs() ?: run { c.message = "VRS 설정을 열 수 없어요" } }) {
                    Text("VRS", style = Ty.label, color = t.ink, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canDisconnect = cc != null && link != null && link.state != LinkState.IDLE
                MBtn(Modifier.weight(1f).height(56.dp).testTag("gnss_info_disconnect"), MKind.PLAIN, 28.dp, alpha = if (canDisconnect) 1f else 0.45f, onClick = { if (canDisconnect) { cc!!.disconnect(); c.gnssInfoOpen = false } }) {
                    Text("연결 해제", style = Ty.label, color = t.bad)
                }
                MBtn(Modifier.weight(1f).height(56.dp).testTag("gnss_info_ok"), MKind.PRIMARY, 28.dp, onClick = { c.gnssInfoOpen = false }) {
                    Text("확인", style = Ty.label, color = t.onPrimary)
                }
            }
        }
    }
}
