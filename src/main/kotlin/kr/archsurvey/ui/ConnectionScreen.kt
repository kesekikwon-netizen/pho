package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.device.LinkState
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState
import kr.archsurvey.ntrip.RtcmMountCompat

/** 지도 화면 위에 얹는 연결 UI: 오류 알림 줄(항상) + 전체 화면 연결 설정(열었을 때). */
@Composable
fun ConnectionHost(c: SurveyController, cc: ConnectionController) {
    val link by cc.link.collectAsState()
    val ntrip by cc.ntrip.collectAsState()
    val cards = ErrorCards.from(link, ntrip, cc.form.enabled, cc.form.host)
    if (!cc.open && cards.isNotEmpty()) AlertStrip(cards.first(), onClick = { cc.openScreen() })
    if (cc.open) ConnectionScreen(cc)
    if (cc.open && cc.mountPickerOpen) MountPicker(cc)
    // 연결 성공 → 설정 자동 저장(한 번)
    LaunchedEffect(link, ntrip, cc.connectRequested) { cc.onStatus(link, ntrip) }
    cc.pair?.let { if (it.open) PairSheet(it) } // 0.1.40
    if (cc.showProgress && cc.connectRequested) ConnectionProgress(c, cc, link, ntrip)
}

/** 연결 진행 창: 블루투스 → 수신기 데이터 → VRS 접속 → 보정 수신 → 고정. 닫아도 연결은 계속된다 */
@Composable
private fun ConnectionProgress(c: SurveyController, cc: ConnectionController, link: kr.archsurvey.device.LinkStatus, ntrip: kr.archsurvey.engine.NtripStatus) {
    val states = ConnectSteps.compute(link, ntrip, cc.form.enabled, c.gv.quality.code)
    val hostMount = if (cc.form.enabled) "${cc.form.host}:${cc.form.port} · ${cc.form.mount}" else null
    val steps = ConnectSteps.labels.mapIndexed { i, l ->
        StepView(l, states[i], ConnectSteps.detail(i, link, states, hostMount, c.gv.quality.labelKo, c.gv.satellites))
    }
    val sec = rememberElapsedSec(cc.progressStartMs)
    val card = ErrorCards.from(link, ntrip, cc.form.enabled, cc.form.host).firstOrNull()
    val done = cc.progressDone(c.gv.quality.code)
    // 0.1.17: 시스템 뒤로 = 「닫기(계속 연결)」/「확인」과 같음 (뒤 화면이 대신 뒤로 가지 않게)
    // 0.1.18: 단추·BackHandler·backTarget 모두 cc.dismissProgress 하나로(완료 판정 기준도 하나)
    androidx.activity.compose.BackHandler { cc.dismissProgress(done) }
    ProgressWindow(
        when {
            done -> "연결 완료 · 고정됨"
            states[0] == StepState.DONE && states[1] == StepState.FAIL -> "연결됨 · 위치 없음"
            else -> "연결 중"
        }, steps, sec, "conn_progress",
        note = card?.let { "${it.title} (${it.code}) — ${it.action}" } ?: if (done) "측량할 준비가 됐어요." else "닫아도 연결은 계속돼요. 아래 대기열에서 진행을 볼 수 있어요.",
        // 0.1.14 진행 게이지: 끝난 단계 / 전체(건너뛴 단계는 끝난 것으로)
        progress = states.count { it == StepState.DONE || it == StepState.SKIP }.toFloat() / states.size.coerceAtLeast(1),
    ) {
        if (!done) MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_progress_cancel"), MKind.PLAIN, 16.dp, onClick = { cc.cancelConnect() }) { Text("연결 취소", style = Ty.label, color = T.ink) }
        MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_progress_close"), MKind.PRIMARY, 16.dp, onClick = { cc.dismissProgress(done) }) {
            Text(if (done) "확인" else "닫기(계속 연결)", style = Ty.label, color = T.onPrimary)
        }
    }
}

/** 마운트 선택 창: 서버의 소스테이블을 받아 검색·선택. 실패하면 알려진 목록 + 직접 입력 */
@Composable
private fun MountPicker(cc: ConnectionController) {
    val t = T
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(t.scrim).hClickable { cc.closeMountPicker() })
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.9f).clip(shape).background(t.bg).border(1.dp, t.line, shape)
                .padding(horizontal = 16.dp, vertical = 16.dp).testTag("mount_picker"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("마운트 포인트 선택", style = Ty.title, color = t.ink, modifier = Modifier.weight(1f))
                MBtn(Modifier.size(48.dp).testTag("mount_close"), MKind.PLAIN, 16.dp, onClick = { cc.closeMountPicker() }) { Text("✕", style = Ty.label, color = t.ink) }
            }
            Text("${cc.form.host}:${cc.form.port}", style = Ty.small, color = t.inkSub)
            when (cc.mountLoad) {
                MountLoad.LOADING -> {
                    val sec = rememberElapsedSec(cc.mountStartMs)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).border(1.dp, t.line, RoundedCornerShape(16.dp)).padding(16.dp).testTag("mount_loading"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("서버에서 마운트 목록을 받는 중 · ${sec}초", style = Ty.label, color = t.ink)
                            Text("보통 몇 초 걸려요(최대 15초)", style = Ty.small, color = t.inkSub)
                        }
                        Btn("취소", Modifier.testTag("mount_cancel")) { cc.cancelMountLoad() }
                    }
                    // 0.1.14 게이지(최대 15초 기준)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.card).testTag("mount_gauge")) {
                        Box(Modifier.fillMaxWidth((sec / 15f).coerceIn(0.04f, 0.95f)).fillMaxHeight().background(t.accent))
                    }
                }
                MountLoad.FAILED -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bad.copy(alpha = 0.08f)).border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(12.dp).testTag("mount_failed"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("목록을 받지 못했어요", style = Ty.label, color = t.ink)
                    Text(cc.mountError, style = Ty.small, color = t.ink, maxLines = 3)
                    if (cc.mounts.isNotEmpty()) Text("아래는 알려진 목록이에요. " + KnownMounts.SOURCE, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("mount_known_note"))
                    else Text("이 서버의 알려진 목록이 없어요. 아래에 이름을 직접 입력하세요.", style = Ty.small, color = t.inkSub)
                    Btn("다시 받기", Modifier.testTag("mount_retry")) { cc.loadMounts() }
                }
                MountLoad.OK -> Text("서버 목록 ${cc.mounts.size}개 · 받은 그대로 보여 줘요", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("mount_ok"))
                MountLoad.IDLE -> {}
            }
            if (cc.mounts.isNotEmpty()) Field("검색", cc.mountQuery, "mount_search") { cc.mountQuery = it }
            // 0.1.18 호환 필터: 이 수신기 최대 RTCM 버전을 넘는 마운트 숨기기(SP60 = 3.2, R10 = 3.4)
            if (cc.mounts.isNotEmpty()) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(cc.mountCompatOnly, role = androidx.compose.ui.semantics.Role.Switch) { cc.mountCompatOnly = it }.testTag("mount_compat_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${cc.profile.displayName.substringAfterLast(' ')}에 맞는 것만 (최대 RTCM 3.${RtcmMountCompat.maxMinor(cc.profile)})", style = Ty.label, color = t.ink)
                    val hid = cc.hiddenIncompatibleCount()
                    Text(if (hid > 0) "비호환 ${hid}개 숨김 · 끄면 모두 보여요" else "숨긴 마운트 없음", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("mount_compat_note"))
                }
                Switch(cc.mountCompatOnly, null, modifier = Modifier.testTag("mount_compat"), colors = blackSwitch())
            }
            // 0.1.19: 고른 마운트가 호환 필터로 목록에서 빠졌으면 안내
            if (cc.selectedMountHiddenByFilter()) Text(
                "지금 선택(${cc.form.mount})은 이 수신기에 맞지 않아 목록에서 숨겼어요 · 필터를 끄거나 권장 마운트로 바꾸세요",
                style = Ty.small, color = t.bad, modifier = Modifier.testTag("mount_selected_hidden"),
            )
            val list = cc.filteredMounts()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in list) {
                    val on = m.mountPoint == cc.form.mount
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(if (on) t.primaryCont else t.bg)
                            .border(if (on) 1.5.dp else 1.dp, if (on) t.accent else t.line, RoundedCornerShape(16.dp)).hClickable { cc.pickMount(m.mountPoint) }
                            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("mount_item_${m.mountPoint}"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.mountPoint, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.weight(1f))
                            cc.mountGoodNote(m)?.let { Text(it, style = Ty.label.copy(fontSize = 12.sp), color = t.ink, modifier = Modifier.padding(end = 6.dp).testTag("mount_good_${m.mountPoint}")) }
                            if (on) Text("선택됨", style = Ty.label.copy(fontSize = 12.sp), color = t.accentText)
                        }
                        Text(mountLine(m, cc.mountFromKnown), style = Ty.small, color = t.inkSub, maxLines = 2)
                        cc.mountCompatNote(m)?.let { Text(it, style = Ty.small, color = t.bad, maxLines = 1, modifier = Modifier.testTag("mount_warn_${m.mountPoint}")) }
                    }
                }
                if (cc.mounts.isNotEmpty() && list.isEmpty()) Text("‘${cc.mountQuery}’와 맞는 마운트가 없어요", style = Ty.small, color = t.inkSub)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("직접 입력", cc.mountManual, "mount_manual") { cc.mountManual = it } }
                MBtn(Modifier.height(48.dp).testTag("mount_apply"), MKind.PRIMARY, 16.dp, onClick = { cc.pickMount(cc.mountManual) }) {
                    Text("적용", style = Ty.label, color = t.onPrimary, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

private fun mountLine(m: kr.archsurvey.ntrip.MountInfo, known: Boolean): String = buildList {
    add(m.format.ifBlank { "형식 미상" } + (m.formatDetails.takeIf { it.isNotBlank() && !known }?.let { " ($it)" } ?: ""))
    if (m.navSystem.isNotBlank() && !known) add(m.navSystem)
    add(if (m.needsNmea) "VRS·내 위치 필요" else "고정 기준국")
    if (m.lat != null && m.lon != null && !(m.lat == 0.0 && m.lon == 0.0)) add(String.format(java.util.Locale.US, "%.3f, %.3f", m.lat, m.lon))
    if (known) add(m.identifier) else if (m.identifier.isNotBlank()) add(m.identifier)
    when (m.authentication) { "B" -> add("인증 Basic"); "D" -> add("인증 Digest"); "N" -> add("인증 없음") }
}.joinToString(" · ")

@Composable
private fun AlertStrip(card: ErrorCard, onClick: () -> Unit) {
    val t = T
    Box(Modifier.fillMaxWidth().padding(top = (HeaderDims.FULL_DP + StatusLineDims.FULL_DP + 8).dp, start = 72.dp, end = (SurveyDims.MIN_TOUCH_DP + 8).dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.bg.copy(alpha = 0.96f))
                .border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).hClickable(onClick = onClick).padding(10.dp).testTag("alert_strip"),
        ) {
            Text(card.title, style = Ty.label, color = t.ink, maxLines = 2)
            Text(if (card.autoRetry) "자동으로 다시 시도해요 · 눌러서 자세히 (${card.code})" else "눌러서 해결 방법 보기 (${card.code})", style = Ty.small, color = t.inkSub)
        }
    }
}

@Composable
fun ConnectionScreen(cc: ConnectionController) {
    val t = T
    val link by cc.link.collectAsState()
    val ntrip by cc.ntrip.collectAsState()
    val cards = ErrorCards.from(link, ntrip, cc.form.enabled, cc.form.host)
    val p = cc.profile
    val vrsOnly = cc.section == ConnSection.VRS
    Column(Modifier.fillMaxSize().background(t.bg).testTag("connection_screen")) {
        DTopBar(if (vrsOnly) "VRS 설정" else "장비 연결", { cc.leaveScreen() }, backTag = "conn_back")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 0.1.43 시안 ⑥: 큰 상태 글자 + 작은 보정·시도 줄, 그 아래 가로 5단계
            val live = link.state == LinkState.STREAMING
            Row(verticalAlignment = Alignment.Bottom) {
                Text(linkText(link.state), style = DTy.h1, color = if (live) D.fix else if (link.state == LinkState.NEEDS_USER) D.bad else D.ink, modifier = Modifier.testTag("conn_link_state"))
                Text("  " + (if (cc.form.enabled) "보정 " + ntripText(ntrip.state) else "VRS 끔") + " · 시도 ${link.attempt}회", style = DTy.small, color = D.sub, modifier = Modifier.padding(bottom = 3.dp).testTag("conn_ntrip_state"))
            }
            if (link.message.isNotBlank()) Text(link.message, style = DTy.small, color = D.sub)
            if (!vrsOnly) ConnStepsInline(cc, link, ntrip)
            // 0.1.18 HTML 실패 카드: 원인과 해결 방법(블루투스 단계 실패일 때). BT-130(위치 없음)은 아래 기존 카드
            ConnectFailCopy.headline(p, link)?.takeIf { cc.connectRequested }?.let { ErrorCardView(it, "conn_fail_card") }
            cc.notice?.let { ErrorCardView(it, "conn_notice") }
            for (card in cards) ErrorCardView(card, "conn_card_${card.code}")

            if (!vrsOnly) DeviceSection(cc, link)
            VrsSection(cc, ntrip, vrsOnly)

            if (!vrsOnly) ConnHelpSection(cc, link)
            DLeaderRow("앱 시작 때 자동 연결", null, "conn_auto_row", toggle = cc.autoConnect) { cc.changeAutoConnect(!cc.autoConnect) }
            Spacer(Modifier.height(8.dp))
        }
        DBottomBar {
            val running = cc.connectRequested && link.state != LinkState.IDLE
            if (vrsOnly) {
                if (running) MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_disconnect"), MKind.PLAIN, 28.dp, onClick = { cc.disconnect() }) {
                    Text("접속 끊기", style = Ty.label.copy(fontSize = 16.sp), color = t.bad)
                } else MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_cancel"), MKind.PLAIN, 28.dp, onClick = { cc.close() }) {
                    Text("취소", style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
                }
                MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_save"), MKind.PRIMARY, 28.dp, onClick = { cc.saveOnly(); if (running) cc.connect() }) {
                    Text(if (running) "저장 후 재접속" else "저장", style = Ty.label.copy(fontSize = 16.sp), color = t.onPrimary)
                }
            } else when (ConnPhases.of(cc.connectRequested, link)) {
                // 0.1.18 HTML 63~66: 연결 중 = 「연결 취소」만 / 연결됨 = 「연결 해제」(심홍 글자) + 「지도로 가기」 / 그 밖 = 「연결」·「다시 시도」
                ConnPhase.CONNECTING -> MBtn(Modifier.weight(1f).height(56.dp).testTag("conn_cancel_connect"), MKind.PLAIN, 28.dp, onClick = { cc.cancelConnect() }) {
                    Text("연결 취소", style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
                }
                ConnPhase.CONNECTED -> {
                    MBtn(Modifier.weight(0.39f).height(56.dp).testTag("conn_disconnect"), MKind.PLAIN, 28.dp, onClick = { cc.disconnect() }) {
                        Text("연결 해제", style = Ty.label.copy(fontSize = 16.sp), color = t.bad)
                    }
                    MBtn(Modifier.weight(0.61f).height(56.dp).testTag("conn_go_map"), MKind.PRIMARY, 28.dp, onClick = { cc.close() }) {
                        Text("지도로 가기", style = Ty.label.copy(fontSize = 16.sp), color = t.onPrimary)
                    }
                }
                ConnPhase.IDLE, ConnPhase.FAILED -> Row(Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(10.dp)).background(D.primary).hClickable { cc.connect() }.testTag("conn_connect"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    GlyphIcon(Glyph.BT, Color.White, 18.dp); Spacer(Modifier.width(8.dp))
                    val lbl = cc.connectLabel()
                    if (lbl == "연결") Text(HeaderModel.shortModel(cc.profile.displayName) + "에 ", style = DTy.button, color = Color.White)
                    Text(lbl, style = DTy.button, color = Color.White, modifier = Modifier.testTag("conn_connect_label"))
                }
            }
        }
    }
}

/** 0.1.18 HTML 연결 단계 목록(번호 원 + 이름 + 대기/진행 중/완료/실패). 진행 창과 같은 계산(ConnectSteps) */
@Composable
private fun ConnStepsInline(cc: ConnectionController, link: kr.archsurvey.device.LinkStatus, ntrip: kr.archsurvey.engine.NtripStatus) {
    val t = T
    val q = LocalSurvey.current?.gv?.quality?.code ?: 0
    val states = ConnectSteps.compute(link, ntrip, cc.form.enabled, q)
    Column(Modifier.fillMaxWidth().testTag("conn_steps")) {
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 36.dp).padding(top = 13.dp).height(1.dp).background(D.line))
            Row(Modifier.fillMaxWidth()) {
                for ((i, _) in ConnectSteps.labels.withIndex()) {
                    val label = listOf("블루투스", "위치 수신", "VRS 접속", "보정 수신", "FIX").getOrElse(i) { "" }
                    val st = states[i]
                    val ring = when (st) { StepState.FAIL -> D.bad; StepState.DONE -> D.fix; StepState.ACTIVE -> D.accentLight; else -> D.line }
                    Column(Modifier.weight(1f).testTag("conn_step_$i"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (st == StepState.DONE) D.fix else D.card).border(1.5.dp, ring, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            when (st) {
                                StepState.DONE -> Text("✓", style = DTy.tiny, color = Color.White)
                                StepState.ACTIVE -> Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(D.accentLight))
                                StepState.FAIL -> Text("!", style = DTy.tiny, color = D.bad)
                                else -> Text("${i + 1}", style = DTy.tiny, color = D.sub)
                            }
                        }
                        Text(label, style = DTy.tiny, color = if (st == StepState.ACTIVE) D.ink else D.sub, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        Text(ConnPhases.stateKo(st), style = DTy.tiny.copy(fontSize = 1.sp), color = Color.Transparent, modifier = Modifier.testTag("conn_step_state_$i"))
                    }
                }
            }
        }
        DRule()
    }
}

@Composable
internal fun blackSwitch() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = T.onPrimary, checkedTrackColor = T.primary, checkedBorderColor = T.primary,
    uncheckedThumbColor = T.outline, uncheckedTrackColor = T.raised, uncheckedBorderColor = T.outline,
)

/** 목업 04: 수신기 탭(세그먼트) + 폰에 등록된 장비 카드 + 연결 전 확인 */
@Composable
private fun DeviceSection(cc: ConnectionController, link: kr.archsurvey.device.LinkStatus) {
    val t = T
    val p = cc.profile
    Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(10.dp)).background(D.card).border(1.dp, D.line, RoundedCornerShape(10.dp))) {
        for ((i, d) in DeviceProfiles.all.withIndex()) {
            val on = d.id == p.id
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(D.line))
            Box(Modifier.weight(1.4f).fillMaxHeight().then(if (on) Modifier.clip(RoundedCornerShape(10.dp)).background(D.sel).border(1.8.dp, D.ink, RoundedCornerShape(10.dp)) else Modifier)
                .hClickable { cc.selectProfile(d) }.testTag("conn_model_${d.id}"), contentAlignment = Alignment.Center) {
                Text(d.displayName.replace("Spectra Precision", "Spectra"), style = if (on) DTy.bodyB else DTy.body, color = D.ink, maxLines = 1)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(D.line))
        // 「기타」: 다른 수신기는 통신 검증 전이라 지원하지 않음(누르면 안내)
        Box(Modifier.weight(0.8f).fillMaxHeight().hClickable { cc.ui.fail("기타 수신기는 아직 지원하지 않아요 · R10 · SP60 만 연결돼요") }.testTag("conn_model_other"), contentAlignment = Alignment.Center) {
            Text("기타", style = DTy.body, color = D.ink, maxLines = 1)
        }
    }
    DSectionLabel("등록된 장비 · 폰 블루투스", Modifier.padding(top = 4.dp))
    if (cc.bonded.isEmpty()) {
        Text(if (cc.pair != null) "등록된 기기가 없어요. 아래 「주변 수신기 찾기」를 누르세요." else "등록된 기기가 없어요. 먼저 폰의 블루투스 설정에서 수신기를 등록하세요.", style = Ty.small, color = t.inkSub)
        for ((i, st) in p.pairingSteps.withIndex()) Text("${i + 1}. $st", style = Ty.small, color = t.inkSub)
    }
    for ((name, addr) in cc.bonded) {
        val on = addr == cc.address
        val isLive = on && (link.state == LinkState.STREAMING || link.state == LinkState.CONNECTED_NO_DATA)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (on) D.sel else Color.Transparent)
                .hClickable { cc.selectAddress(addr) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("conn_bonded_$addr"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(Glyph.POLE, D.ink, 20.dp)
            Column(Modifier.weight(1f)) {
                Text(name.replace(", ", " · "), style = DTy.bodyB.copy(fontSize = 17.sp), color = D.ink, maxLines = 1)
                Text(addr, style = DTy.monoS.copy(fontSize = 11.sp), color = D.sub, maxLines = 1)
            }
            if (isLive) Text("연결됨", style = DTy.small, color = D.fix) else if (on) Text("고름", style = DTy.small, color = D.ink)
        }
        DRule(false)
    }
    // 0.1.42 다른 앱(발굴 측량/사진 기준점)이 같은 수신기를 쓰는 중이면 한 번에 넘겨받기
    ReceiverLockCard(cc)
    // 0.1.40 앱 안에서 찾기 → 눌러서 등록(PIN 자동)+연결. 폰 블루투스 설정으로 갈 필요 없음
    PairFindButton(cc)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("등록 목록 새로 읽기", style = DTy.small, color = D.sub, modifier = Modifier.heightIn(min = 48.dp).hClickable {
            cc.refresh()
            if (cc.bonded.isEmpty()) cc.ui.fail("등록된 장비가 없어요 · 위 「주변 수신기 찾기」를 누르세요") else cc.ui.ok("등록된 장비 ${cc.bonded.size}개를 찾았어요")
        }.padding(vertical = 12.dp).testTag("conn_refresh"))
        Text("폰 블루투스 설정", style = DTy.small, color = D.sub, modifier = Modifier.heightIn(min = 48.dp).hClickable { cc.openBluetoothSettings() }.padding(vertical = 12.dp).testTag("conn_bt_settings_top"))
    }
}

/** 0.1.43 시안 ⑥ 아래쪽: 처음 연결 도움말 · 진단 기록(0.1.41~42 진단 카드를 여기로) · 연결 전 확인 · 자동 연결 */
@Composable
private fun ConnHelpSection(cc: ConnectionController, link: kr.archsurvey.device.LinkStatus) {
    val t = T
    val p = cc.profile
    DLeaderRow("처음 연결 도움말", null, "conn_help_toggle") { cc.showHelp = !cc.showHelp }
    if (cc.showHelp) {
        Btn("블루투스 설정 열기", Modifier.fillMaxWidth().testTag("conn_bt_settings")) { cc.openBluetoothSettings() }
        Text(p.pinNote, style = DTy.small, color = D.sub)
        Text(p.portNote, style = DTy.small, color = D.sub)
        for ((i, st) in p.pairingSteps.withIndex()) Text("${i + 1}. $st", style = DTy.small, color = D.sub)
    }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).hClickable { cc.showDiag = !cc.showDiag }.testTag("conn_diag_toggle"), verticalAlignment = Alignment.CenterVertically) {
            Text("진단 기록", style = DTy.body, color = D.ink); Text("  문의할 때 공유", style = DTy.tiny, color = D.sub, modifier = Modifier.weight(1f))
            GlyphIcon(Glyph.CHEVRON_RIGHT, D.sub, 12.dp)
        }
        DRule(false)
    }
    if (cc.showDiag) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("연결 방식 " + (link.strategy ?: "—") + " · 상태 " + linkText(link.state) + (link.error?.let { " · 오류 ${it.code}" } ?: "") + " · 시도 ${link.attempt}회", style = DTy.small, color = D.ink, modifier = Modifier.testTag("conn_diag_link"))
        val d = remember(link, cc.showDiag) { cc.diagnostics() }
        Text(d.ifBlank { "아직 기록된 진단이 없어요. 연결을 시도하면 NMEA 자동 켜기 명령·응답(ACK)과 위치 문장(GGA) 수신 여부가 여기에 남아요." }, style = DTy.monoS.copy(fontSize = 11.sp, lineHeight = 15.sp), color = D.sub, modifier = Modifier.testTag("conn_diag_text"))
        Text("전체 기록 공유: 설정 › 버전 · 오류 기록", style = DTy.small, color = D.sub)
    }
    // 연결 전 확인: 모두 체크했으면 한 줄로 접는다
    val allChecked = cc.checks.isNotEmpty() && cc.checks.all { it }
    if (allChecked && !cc.showPre) {
        FoldRow("연결 전 확인 ✓ 완료", false, "conn_pre_toggle") { cc.showPre = true }
    } else Card {
        Text("연결 전 확인", style = Ty.label, color = t.ink)
        for ((i, txt) in p.preConnectChecks.withIndex()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).hClickable { cc.setCheck(i, !cc.checks.getOrElse(i) { false }) }.testTag("conn_check_$i"), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(cc.checks.getOrElse(i) { false }, { cc.setCheck(i, it) }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = t.primary, checkmarkColor = t.onPrimary))
                Text(txt, style = Ty.body, color = t.ink)
            }
        }
        FoldRow(p.nmeaSetupTitle, cc.showNmeaSteps, "conn_nmea_toggle") { cc.showNmeaSteps = !cc.showNmeaSteps }
        if (cc.showNmeaSteps) for ((i, st) in p.nmeaSetupSteps.withIndex()) Text("${i + 1}. $st", style = Ty.small, color = t.inkSub)
    }
}

/** 목업 07 스위치 줄: 줄 전체가 눌림(48dp 이상), 오른쪽 검정 스위치 */
@Composable
private fun ToggleLine(label: String, sub: String, on: Boolean, tag: String, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    val t = T
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(on, role = androidx.compose.ui.semantics.Role.Switch) { onChange(it) }.padding(vertical = 6.dp).testTag(tag + "_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Ty.body.copy(fontSize = 15.sp), color = t.ink)
            Text(sub, style = Ty.small, color = if (warn) t.bad else t.inkSub, modifier = Modifier.testTag(tag + "_note"))
        }
        Switch(on, null, modifier = Modifier.testTag(tag), colors = blackSwitch())
    }
}

/** 목업 07의 줄: 왼쪽 이름(고정 폭) + 오른쪽 입력 상자(48dp) + 아래 얇은 선 */
@Composable
private fun FormRow(label: String, content: @Composable BoxScope.() -> Unit) {
    val t = T
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Ty.body.copy(fontSize = 15.sp), color = t.ink, modifier = Modifier.width(100.dp))
            Box(Modifier.weight(1f), content = content)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
    }
}

@Composable
private fun FormInput(value: String, tag: String, placeholder: String = "", kb: KeyboardType = KeyboardType.Text, hide: Boolean = false, onChange: (String) -> Unit) {
    val t = T
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(t.raised).border(1.dp, t.outline.copy(alpha = 0.6f), shape).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
            textStyle = Ty.coord.copy(fontSize = 15.sp, color = t.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(t.ink),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            visualTransformation = if (hide) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Ty.body, color = t.inkSub); inner() } },
        )
    }
}

@Composable
private fun FormSelect(value: String, tag: String, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(t.raised).border(1.dp, t.outline.copy(alpha = 0.6f), shape).hClickable(onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = Ty.coord.copy(fontSize = 15.sp), color = t.ink, maxLines = 1, modifier = Modifier.weight(1f))
        GlyphIcon(Glyph.CHEVRON_DOWN, t.ink, 18.dp)
    }
}

/** 목업 07: 서버 주소 · 포트 · 마운트포인트 · 아이디 · 비밀번호 · GGA 전송 + 접속 상태 카드 */
@Composable
private fun VrsSection(cc: ConnectionController, ntrip: kr.archsurvey.engine.NtripStatus, vrsOnly: Boolean) {
    val t = T
    // 0.1.43: 다 채워진 VRS 는 요약 + 「고치기」. 처음(아이디 등 빠짐)에는 이 화면에서 바로 채우게 펼쳐 둔다(열 때 한 번 정함)
    val full = remember { cc.form.enabled && cc.form.problems().isNotEmpty() }
    if (!vrsOnly && !full) { VrsSummary(cc); return }
    if (!vrsOnly) DSectionLabel("VRS 보정 · NTRIP")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(cc.form.enabled, role = androidx.compose.ui.semantics.Role.Switch) { cc.updateForm(cc.form.copy(enabled = it)) }.testTag("conn_ntrip_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("VRS 사용", style = Ty.body.copy(fontSize = 15.sp), color = t.ink, modifier = Modifier.weight(1f))
                Switch(cc.form.enabled, null, modifier = Modifier.testTag("conn_ntrip_switch"), colors = blackSwitch())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
        }
        if (cc.form.enabled) {
            // SmartTopo 흐름: 보정 서비스 → 서버·포트 → 마운트(목록 받기·검색·선택) → 계정
            FormRow("보정 서비스") { FormSelect(cc.currentService().short, "conn_service") { cc.askService() } }
            FormRow("서버 주소") { FormInput(cc.form.host, "conn_host") { cc.updateForm(cc.form.copy(host = it)) } }
            FormRow("포트") { FormInput(cc.form.port, "conn_port", kb = KeyboardType.Number) { cc.updateForm(cc.form.copy(port = it.filter { ch -> ch.isDigit() }.take(5))) } }
            FormRow("마운트포인트") { FormSelect(cc.form.mount.ifBlank { "눌러서 목록 받기" }, "conn_mount_pick") { cc.openMountPicker() } }
            FormRow("아이디") { FormInput(cc.form.user, "conn_user", placeholder = "통합회원 ID") { cc.updateForm(cc.form.copy(user = it)) } }
            FormRow("비밀번호") { FormInput(cc.form.password, "conn_pw", kb = KeyboardType.Password, hide = true) { cc.updateForm(cc.form.copy(password = it)) } }
            ToggleLine("GGA 전송", if (cc.sendGga) "내 위치(GGA)를 서버에 보내요 · VRS에 필요" else "꺼짐 — VRS 마운트는 보정이 오지 않을 수 있어요", cc.sendGga, "conn_gga", warn = !cc.sendGga) { cc.changeSendGga(it) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
            ToggleLine("자동 재접속", if (cc.autoRetry) "끊기면 알아서 다시 접속해요(인증 오류 제외)" else "꺼짐 — 끊기면 ‘다시 시도’를 눌러요", cc.autoRetry, "conn_autoretry") { cc.changeAutoRetry(it) }
        }
    }
    // 0.1.29 T17: RTS3(G-VRS) 를 골랐을 때만 시범 서비스 안내(+ SP60 이면 RTCM 버전 미확인 안내)
    cc.gvrsNotice()?.let { n ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.caution.copy(alpha = 0.16f)).border(1.5.dp, t.caution, RoundedCornerShape(16.dp)).padding(14.dp).testTag("conn_gvrs_notice"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(n, style = Ty.label, color = t.ink)
            cc.gvrsSp60Line()?.let { Text(it, style = Ty.small, color = t.ink, modifier = Modifier.testTag("conn_gvrs_sp60")) }
        }
    }
    // 0.1.29 T17-2: σH 기준 11 mm 미만 + SP60 → 펌웨어 안내(막지 않음)
    cc.firmwareNotice()?.let { Text(it, style = Ty.small, color = t.caution, modifier = Modifier.padding(horizontal = 4.dp).testTag("conn_sp60_fw")) }
    if (cc.form.enabled) {
        // 0.1.11 P0(통신): 수신기에 맞지 않는 RTCM 버전 마운트면 권장 마운트로 바꾸는 단추
        val compat = RtcmMountCompat.warnIfIncompatible(cc.profile, cc.form.mount)
        if (compat != null) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.caution.copy(alpha = 0.16f)).border(1.5.dp, t.caution, RoundedCornerShape(16.dp)).padding(14.dp).testTag("conn_rtcm_warn"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("보정 형식 확인", style = Ty.label, color = t.ink)
            Text(compat, style = Ty.small, color = t.ink)
            val rec = cc.recommendedMount()
            if (rec != null) MBtn(Modifier.fillMaxWidth().height(48.dp).testTag("conn_rtcm_fix"), MKind.PRIMARY, 24.dp, onClick = { cc.applyRecommendedMount() }) {
                Text("권장 마운트로 바꾸기 · $rec", style = Ty.label, color = t.onPrimary, maxLines = 1)
            }
        }
        // 접속 상태 카드(목업 07 아래)
        val dot = when (ntrip.state) { NtripState.RECEIVING -> t.ok; NtripState.AUTH_FAILED, NtripState.ERROR -> t.bad; NtripState.IDLE -> t.line; else -> t.caution }
        val tick = rememberTick(true)
        val now = remember(ntrip, tick) { android.os.SystemClock.elapsedRealtime() }
        val meterLine = remember(ntrip, tick) { cc.meter.update(ntrip, now); cc.meter.line(ntrip, now) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp).testTag("conn_vrs_status"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
                Text(ntripText(ntrip.state), style = Ty.label, color = t.ink)
            }
            Text(meterLine, style = Ty.coord.copy(fontSize = 12.sp), color = t.ink, modifier = Modifier.testTag("conn_vrs_meter"))
            Text(
                "누적 " + String.format(java.util.Locale.US, "%.1f", ntrip.rtcmBytes / 1024.0) + " kB · 프레임 ${ntrip.framesOk}" + (if (ntrip.crcErrors > 0) " · CRC 오류 ${ntrip.crcErrors}" else "") + if (ntrip.paused) " · 수신기 위치 대기" else "",
                style = Ty.coord.copy(fontSize = 12.sp), color = t.inkSub,
            )
            if (ntrip.message.isNotBlank() && ntrip.state != NtripState.RECEIVING) Text(ntrip.message, style = Ty.small, color = t.inkSub, maxLines = 3)
            if (ntrip.state == NtripState.ERROR || ntrip.state == NtripState.AUTH_FAILED || ntrip.state == NtripState.RECONNECTING)
                Btn("보정 다시 시도", Modifier.fillMaxWidth().testTag("conn_ntrip_retry")) { cc.retryNtrip() }
        }
        Text("마운트 줄을 누르면 서버에서 전체 목록을 받아 고를 수 있어요(아이디·비밀번호를 먼저 넣으세요). 국토지리정보원 공식 FAQ: VRS는 ‘측위보정정보 통합회원 ID’(지각변동감시시스템 geodesy.ngii.go.kr 에서 등록)를 쓰고, RTS1·RTS2 비밀번호는 ngii 예요. ID 하나로 여러 개를 만들 수 있어요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("conn_id_help"))
        FoldRow("고급 · 마운트 이름 직접 입력 · 마지막 성공 설정", cc.showServer, "conn_server_toggle") { cc.showServer = !cc.showServer }
        if (cc.showServer) {
            Field("마운트 포인트", cc.form.mount, "conn_mount") { cc.updateForm(cc.form.copy(mount = it)) }
            ToggleLine("NTRIP v2", "국토지리정보원은 보통 v1 · 다른 서버에서 v2가 필요할 때만", cc.form.useV2, "conn_v2") { cc.changeUseV2(it) }
            Btn("이 서버의 마지막 성공 설정 불러오기", Modifier.fillMaxWidth().testTag("conn_recall")) { cc.recallHost() }
            Text("서버·마운트 기본값은 국토지리정보원 공개 안내(2022-05) 기준이에요. 현행 정책은 첫 접속 때 확인이 필요해요.", style = Ty.small, color = t.inkSub)
        }
    }
}

private fun linkText(s: LinkState) = when (s) {
    LinkState.IDLE -> "연결 안 됨"
    LinkState.CONNECTING -> "수신기에 연결하는 중"
    LinkState.CONNECTED_NO_DATA -> "연결됨 · 위치 데이터를 기다리는 중"
    LinkState.STREAMING -> "연결됨 · 위치 수신 중"
    LinkState.STALE -> "연결됨 · 데이터가 잠시 끊김"
    LinkState.RECONNECTING -> "다시 연결하는 중"
    LinkState.NEEDS_USER -> "확인이 필요해요"
}

private fun ntripText(s: NtripState) = when (s) {
    NtripState.IDLE -> "대기"
    NtripState.CONNECTING -> "서버에 접속하는 중"
    NtripState.CONNECTED -> "접속됨 · 데이터를 기다리는 중"
    NtripState.RECEIVING -> "보정 데이터 수신 중"
    NtripState.RECONNECTING -> "다시 접속하는 중"
    NtripState.AUTH_FAILED -> "인증 실패"
    NtripState.ERROR -> "오류"
}

/** 목업 s31/s8 카드: 밝은 면 + 얇은 선 + 둥근 모서리 18dp */
@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = T
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.raised).border(1.dp, t.line, shape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** 목업의 알약 선택 버튼: 고르면 연한 테라코타 면 + 굵은 테두리 + 체크 */
@Composable
internal fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    MBtn(modifier.heightIn(min = 48.dp), if (selected) MKind.SELECTED else MKind.PLAIN, 16.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selected) GlyphIcon(Glyph.CHECK, t.accentText, 14.dp)
            Text(label, style = Ty.label, color = t.ink)
        }
    }
}

@Composable
internal fun Btn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = T
    MBtn(modifier.heightIn(min = 48.dp), MKind.PLAIN, 16.dp, onClick = onClick) {
        Text(label, style = Ty.label, color = t.ink, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** 목업의 입력 줄: 48dp, 왼쪽 작은 이름 + 오른쪽 값 */
@Composable
internal fun Field(label: String, value: String, tag: String, kb: KeyboardType = KeyboardType.Text, hide: Boolean = false, placeholder: String = "", onChange: (String) -> Unit) {
    val t = T
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(t.raised).border(1.dp, t.outline, shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.widthIn(min = 72.dp, max = 110.dp))
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag),
            textStyle = Ty.body.copy(color = t.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            visualTransformation = if (hide) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Ty.body, color = t.inkSub)
                    inner()
                }
            },
        )
    }
}

@Composable
fun ErrorCardView(card: ErrorCard, tag: String) {
    val t = T
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bad.copy(alpha = 0.10f)).border(1.dp, t.bad.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(card.title, style = Ty.label, color = t.ink)
        Text(card.action, style = Ty.body, color = t.ink)
        Text(if (card.code == "-") "" else "오류 ${card.code}" + if (card.autoRetry) " · 자동 재시도 중" else "", style = Ty.small, color = t.inkSub)
    }
}

/** 0.1.43 시안 ⑥ VRS 카드: 서버 · 마운트 · 아이디 · 보내기 요약 + 「고치기」(VRS 설정 화면 · 고르는 방식) */
@Composable
private fun VrsSummary(cc: ConnectionController) {
    val f = cc.form
    Column(Modifier.testTag("conn_vrs_summary")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("VRS 보정 · NTRIP", style = DTy.small, color = D.sub)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).background(D.hair))
            Text("고치기", style = DTy.body.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = D.accent,
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).hClickable { cc.openVrs() }.padding(vertical = 12.dp, horizontal = 6.dp).testTag("conn_vrs_edit"))
        }
        for ((k, v) in listOf("서버" to (if (f.host.isBlank()) "—" else "${f.host}:${f.port}"), "마운트" to f.mount.ifBlank { "—" }, "아이디" to f.user.ifBlank { "—" },
                "보내기" to (if (!f.enabled) "VRS 끔" else (if (cc.sendGga) "내 위치(GGA) 켬" else "내 위치(GGA) 끔") + (if (cc.autoRetry) " · 끊기면 다시 접속" else "")))) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(k, style = DTy.small, color = D.sub, modifier = Modifier.width(80.dp))
                Text(v, style = if (k == "보내기") DTy.bodyB.copy(fontSize = 15.sp) else DTy.mono, color = D.ink, modifier = Modifier.testTag("conn_vrs_$k"))
            }
        }
        DRule(false)
    }
}
