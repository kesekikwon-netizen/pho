package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** 지도 화면 위에 얹는 연결 UI: 오류 알림 줄(항상) + 전체 화면 연결 설정(열었을 때). */
@Composable
fun ConnectionHost(c: SurveyController, cc: ConnectionController) {
    val link by cc.link.collectAsState()
    val ntrip by cc.ntrip.collectAsState()
    val cards = if (cc.fakeSignal) emptyList() else ErrorCards.from(link, ntrip, cc.form.enabled)
    if (!cc.open && cards.isNotEmpty()) AlertStrip(cards.first(), onClick = { cc.openScreen() })
    if (cc.open) ConnectionScreen(cc)
}

@Composable
private fun AlertStrip(card: ErrorCard, onClick: () -> Unit) {
    val t = T
    Box(Modifier.fillMaxWidth().padding(top = (SurveyDims.STATUS_BAND_DP + 6).dp, start = 72.dp, end = (SurveyDims.POLE_STRIP_DP + 8).dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.bg.copy(alpha = 0.96f))
                .border(1.dp, t.bad.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp).testTag("alert_strip"),
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
    val cards = if (cc.fakeSignal) emptyList() else ErrorCards.from(link, ntrip, cc.form.enabled)
    val p = cc.profile
    Column(Modifier.fillMaxSize().background(t.bg).testTag("connection_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).clickable { cc.close() }.testTag("conn_back"), contentAlignment = Alignment.Center) { Text("‹", fontSize = 28.sp, color = t.ink) }
            Text("수신기 연결 · VRS", style = Ty.title, color = t.ink, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1. 모드
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("가짜 신호 모드", style = Ty.label, color = t.ink)
                        Text(if (cc.fakeSignal) "화면 시험용 가짜 위치예요. 저장되는 점도 가짜예요." else "실제 수신기에서 위치를 받아요.", style = Ty.small, color = t.inkSub)
                    }
                    Switch(cc.fakeSignal, { cc.changeFakeSignal(it) }, modifier = Modifier.testTag("conn_fake_switch"))
                }
            }
            if (!cc.fakeSignal) {
                // 2. 수신기 모델
                Card {
                    Text("수신기 모델", style = Ty.label, color = t.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in DeviceProfiles.all) Choice(d.displayName, d.id == p.id, Modifier.weight(1f).testTag("conn_model_${d.id}")) { cc.selectProfile(d) }
                    }
                    Text(p.pinNote, style = Ty.small, color = t.inkSub)
                }
                // 3. 폰에 등록된 기기
                Card {
                    Text("폰에 등록된 블루투스 기기", style = Ty.label, color = t.ink)
                    if (cc.bonded.isEmpty()) {
                        Text("등록된 기기가 없어요. 먼저 폰의 블루투스 설정에서 수신기를 등록하세요.", style = Ty.small, color = t.inkSub)
                        for ((i, s) in p.pairingSteps.withIndex()) Text("${i + 1}. $s", style = Ty.small, color = t.inkSub)
                    }
                    for ((name, addr) in cc.bonded) Choice("$name  ($addr)", addr == cc.address, Modifier.fillMaxWidth().testTag("conn_bonded_$addr")) { cc.selectAddress(addr) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("목록 새로고침", Modifier.weight(1f).testTag("conn_refresh")) { cc.refresh() }
                        Btn("블루투스 설정 열기", Modifier.weight(1f).testTag("conn_bt_settings")) { cc.openBluetoothSettings() }
                    }
                }
                // 4. 사전 확인
                Card {
                    Text("연결 전 확인", style = Ty.label, color = t.ink)
                    for ((i, txt) in p.preConnectChecks.withIndex()) {
                        Row(Modifier.fillMaxWidth().height(48.dp).clickable { cc.setCheck(i, !cc.checks.getOrElse(i) { false }) }.testTag("conn_check_$i"), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(cc.checks.getOrElse(i) { false }, { cc.setCheck(i, it) })
                            Text(txt, style = Ty.body, color = t.ink)
                        }
                    }
                    Text(if (cc.showNmeaSteps) "▼ ${p.nmeaSetupTitle}" else "▶ ${p.nmeaSetupTitle}", style = Ty.small, color = t.accentText, modifier = Modifier.clickable { cc.showNmeaSteps = !cc.showNmeaSteps }.padding(vertical = 8.dp))
                    if (cc.showNmeaSteps) for ((i, s) in p.nmeaSetupSteps.withIndex()) Text("${i + 1}. $s", style = Ty.small, color = t.inkSub)
                    Text(p.portNote, style = Ty.small, color = t.inkSub)
                }
                // 5. VRS
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("VRS(NTRIP) 보정", style = Ty.label, color = t.ink, modifier = Modifier.weight(1f))
                        Switch(cc.form.enabled, { cc.updateForm(cc.form.copy(enabled = it)) }, modifier = Modifier.testTag("conn_ntrip_switch"))
                    }
                    if (cc.form.enabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("VRS 기본값", Modifier.weight(1f)) { val v = NtripPresets.VRS; cc.updateForm(cc.form.copy(host = v.host, port = v.port.toString(), mount = v.mountPoint)) }
                            Btn("FKP 기본값", Modifier.weight(1f)) { val v = NtripPresets.FKP; cc.updateForm(cc.form.copy(host = v.host, port = v.port.toString(), mount = v.mountPoint)) }
                        }
                        Field("서버 주소", cc.form.host, "conn_host") { cc.updateForm(cc.form.copy(host = it)) }
                        Field("포트", cc.form.port, "conn_port", KeyboardType.Number) { cc.updateForm(cc.form.copy(port = it.filter { ch -> ch.isDigit() }.take(5))) }
                        Field("마운트 포인트", cc.form.mount, "conn_mount") { cc.updateForm(cc.form.copy(mount = it)) }
                        Field("아이디", cc.form.user, "conn_user") { cc.updateForm(cc.form.copy(user = it)) }
                        Field("비밀번호", cc.form.password, "conn_pw", KeyboardType.Password, hide = true) { cc.updateForm(cc.form.copy(password = it)) }
                        Text("서버·마운트는 국토지리정보원 공개 안내(2022-05) 기준 기본값이에요. 현행 정책은 확인이 필요해요. 아이디·비밀번호는 직접 입력하세요.", style = Ty.small, color = t.inkSub)
                    }
                }
            }
            // 6. 상태·오류
            cc.notice?.let { ErrorCardView(it, "conn_notice") }
            if (!cc.fakeSignal) {
                Card {
                    Text("연결 상태", style = Ty.label, color = t.ink)
                    Text(linkText(link.state) + (if (link.attempt > 0) " · 시도 ${link.attempt}회" else ""), style = Ty.body, color = t.ink, modifier = Modifier.testTag("conn_link_state"))
                    if (link.message.isNotBlank()) Text(link.message, style = Ty.small, color = t.inkSub)
                    if (cc.form.enabled) Text("보정: " + ntripText(ntrip.state), style = Ty.body, color = t.ink, modifier = Modifier.testTag("conn_ntrip_state"))
                }
                for (card in cards) ErrorCardView(card, "conn_card_${card.code}")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!cc.fakeSignal) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = cc.connectRequested && link.state != LinkState.IDLE
                if (running) Btn("연결 종료", Modifier.weight(1f).height(56.dp).testTag("conn_disconnect")) { cc.disconnect() }
                Box(
                    Modifier.weight(2f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(t.primary).clickable { cc.connect() }.testTag("conn_connect"),
                    contentAlignment = Alignment.Center,
                ) { Text(if (running) "다시 연결" else "연결", style = Ty.button.copy(fontSize = 18.sp), color = t.onPrimary) }
            }
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

@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = T
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) t.primary.copy(alpha = 0.22f) else t.bg)
            .border(1.dp, if (selected) t.accent else t.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text((if (selected) "✓ " else "") + label, style = Ty.body, color = t.ink) }
}

@Composable
internal fun Btn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = T
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(t.bg).border(1.dp, t.outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Ty.label, color = t.ink)
    }
}

@Composable
internal fun Field(label: String, value: String, tag: String, kb: KeyboardType = KeyboardType.Text, hide: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag),
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (hide) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
fun ErrorCardView(card: ErrorCard, tag: String) {
    val t = T
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bad.copy(alpha = 0.10f)).border(1.dp, t.bad.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(card.title, style = Ty.label, color = t.ink)
        Text(card.action, style = Ty.body, color = t.ink)
        Text(if (card.code == "-") "" else "오류 ${card.code}" + if (card.autoRetry) " · 자동 재시도 중" else "", style = Ty.small, color = t.inkSub)
    }
}
