package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.LinkState

/**
 * 0.1.11 홈(메인 메뉴, 목업 02). 구조만 SmartTopo 흐름을 참고했고 메뉴는 발굴 측량 용어로 바꿨다.
 * 큰 검정 카드 = 일반 측량(지도), 작은 카드 = 유구 외곽 · 발굴 구역/그리드 · 점 목록 · 환경설정 · 작업 관리 · 내보내기.
 * 아래 연결 막대는 수신기·VRS 상태를 보여 주고 누르면 장비 연결 화면.
 */
@Composable
fun HomeScreen(c: SurveyController) {
    val t = T
    Column(Modifier.fillMaxSize().background(t.bg).testTag("home_screen")) {
        AppHeader(c, "발굴 측량", onBack = null, menuTag = "home_menu")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(versionShort(rememberAppVersionLabel()), style = Ty.small.copy(fontSize = 12.sp), color = t.inkSub, modifier = Modifier.testTag("home_version"))
            BigSurveyCard { c.hub.open(ToolScreen.SURVEY_HUB) }  // 0.1.14 목업 02→08: 일반 측량 메뉴
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile(Glyph.POLYGON, "유구 외곽", "외곽선을 이어 측량", "home_outline", Modifier.weight(1f)) {
                    c.homeOpen = false
                    if (!c.arch.outlineMode) c.setLinkMode(closed = true)
                }
                HomeTile(Glyph.GRID, "구역 · 그리드", "발굴 구역과 그리드", "home_areas", Modifier.weight(1f)) { c.hub.open(ToolScreen.AREAS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile(Glyph.LIST, "점 목록", "측점 검색 · 상세", "home_points", Modifier.weight(1f)) { c.hub.open(ToolScreen.POINTS) }
                HomeTile(null, "환경설정", "장비 · VRS · 좌표계", "home_settings", Modifier.weight(1f)) { c.settingsMenuOpen = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile(Glyph.FOLDER, "작업 관리", "새 작업 · 열기 · 전송", "home_jobs", Modifier.weight(1f)) { c.hub.open(ToolScreen.JOB_HUB) }
                HomeTile(Glyph.SHARE, "내보내기", "CSV · DXF · GeoJSON", "home_export", Modifier.weight(1f)) { c.exporter?.openScreen() ?: run { c.message = "내보내기는 이 빌드에 없어요" } }
            }
            c.message?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.raised).border(1.dp, t.ink, RoundedCornerShape(12.dp)).padding(12.dp).testTag("home_message")) }
        }
        HomeConnBar(c)
    }
}

/** "버전 0.1.11 (빌드 12)" → "Ver.0.1.11 · 빌드 12" */
internal fun versionShort(label: String): String {
    val m = Regex("버전 (\\S+) \\(빌드 (\\d+)\\)").find(label) ?: return label
    return "Ver.${m.groupValues[1]} · 빌드 ${m.groupValues[2]}"
}

@Composable
private fun BigSurveyCard(onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().height(152.dp).clip(shape).background(t.primary).hClickable(onClick = onClick)
            .semantics { contentDescription = "일반 측량 시작" }.padding(20.dp).testTag("home_survey"),
    ) {
        Row(Modifier.fillMaxWidth()) {
            GlyphIcon(Glyph.POLE, t.onPrimary, 30.dp)
            Spacer(Modifier.weight(1f))
            GlyphIcon(Glyph.CHEVRON_RIGHT, t.onPrimary, 20.dp)
        }
        Spacer(Modifier.weight(1f))
        Text("일반 측량", style = Ty.title.copy(fontSize = 24.sp), color = t.onPrimary)
        Text("작업관리 · 측량 · 점 찾아가기 · 계산기", style = Ty.small, color = t.onPrimary.copy(alpha = 0.8f))
    }
}

@Composable
private fun HomeTile(g: Glyph?, title: String, sub: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val t = T
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.height(116.dp).clip(shape).background(t.raised).border(1.dp, t.line.copy(alpha = 0.35f), shape).hClickable(onClick = onClick).padding(16.dp).testTag(tag),
    ) {
        Row(Modifier.fillMaxWidth()) {
            if (g != null) GlyphIcon(g, t.ink, 26.dp) else GearIcon(t.ink, 26.dp)
            Spacer(Modifier.weight(1f))
            GlyphIcon(Glyph.CHEVRON_RIGHT, t.inkSub, 16.dp)
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = Ty.title.copy(fontSize = 17.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = Ty.small.copy(fontSize = 11.sp), color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 홈 하단 연결 막대(목업 02): 블루투스 · 장비 · 연결 상태 | VRS 상태 */
@Composable
private fun HomeConnBar(c: SurveyController) {
    val t = T
    val cc = c.connection
    val link = cc?.link?.collectAsState()?.value
    val ntrip = cc?.ntrip?.collectAsState()?.value
    val tick = rememberTick(cc != null)
    // 등록 기기 이름을 보여 주려고 한 번 읽는다(권한 없으면 빈 목록 — 주소로 대신 표시)
    androidx.compose.runtime.LaunchedEffect(cc) { if (cc != null && cc.bonded.isEmpty()) runCatching { cc.refresh() } }
    val bar = remember(link, ntrip, cc?.address, cc?.profile, cc?.form?.enabled, cc?.bonded, tick) {
        val model = HeaderModel.shortModel(cc?.profile?.displayName ?: if (c.antennaModelId == "TRIMBLE_R10") "Trimble R10" else "Spectra SP60")
        val name = cc?.let { x -> x.bonded.firstOrNull { it.second == x.address }?.first ?: x.address }
        val st = link?.state ?: LinkState.IDLE
        HeaderModel.connBar(
            model, name, linkLive = st == LinkState.STREAMING, linkUp = st == LinkState.CONNECTED_NO_DATA || st == LinkState.STALE,
            connecting = st == LinkState.CONNECTING || st == LinkState.RECONNECTING, ntrip = ntrip, ntripEnabled = cc?.form?.enabled == true,
            nowMs = android.os.SystemClock.elapsedRealtime(),
        )
    }
    val dot = when (bar.leftTone) { Tone.OK -> t.ok; Tone.BAD -> t.bad; Tone.CAUTION -> t.caution; else -> t.line }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(t.raised).border(1.dp, t.line.copy(alpha = 0.45f), shape)
            .hClickable { cc?.openScreen() ?: run { c.message = "연결 화면을 열 수 없어요" } }.padding(horizontal = 16.dp).testTag("home_conn_bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlyphIcon(Glyph.BT, t.ink, 20.dp)
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(bar.left, style = Ty.label.copy(fontSize = 14.sp), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("home_conn_left"))
        Text(bar.right, style = Ty.small.copy(fontSize = 12.sp), color = if (bar.rightTone == Tone.BAD) t.bad else t.inkSub, maxLines = 1, modifier = Modifier.testTag("home_conn_right"))
    }
}

/** 0.1.11 환경설정 목록(목업 03). 각 줄은 기존 화면으로 이어진다 */
@Composable
fun SettingsMenuScreen(c: SurveyController) {
    val t = T
    val close = { c.settingsMenuOpen = false }
    Column(Modifier.fillMaxSize().background(t.bg).testTag("settings_menu")) {
        AppHeader(c, "환경설정", onBack = close, backTag = "settings_menu_back")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(24.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("환경설정", style = Ty.title.copy(fontSize = 24.sp), color = t.ink)
                    Text(versionShort(rememberAppVersionLabel()), style = Ty.small, color = t.inkSub)
                }
                GearIcon(t.ink, 44.dp)
            }
            Spacer(Modifier.height(8.dp))
            // 0.1.14 목업 03 순서: 장비 연결 · RTK 설정 · VRS 설정 · 좌표계 설정 · 현재위치 모양 · 알림 소리·진동 · 앱 정보
            val rows: List<Triple<Pair<Glyph?, String>, String, Pair<String, () -> Unit>>> = listOf(
                Triple(Glyph.BT to "장비 연결", "GNSS 수신기 블루투스 연결 (R10 · SP60)", "set_row_device" to { c.connection?.openScreen() ?: run { c.message = "연결 화면은 이 빌드에 없어요" } }),
                Triple(Glyph.SAT to "RTK 설정", "보정 형식(RTCM) · 마운트 호환 · GGA · 재접속", "set_row_rtk" to { c.hub.open(ToolScreen.RTK) }),
                Triple(Glyph.WIFI to "VRS 설정", "NTRIP 서버 · 마운트포인트 · 계정", "set_row_vrs" to { c.connection?.openVrs() ?: run { c.message = "VRS 설정은 이 빌드에 없어요" } }),
                Triple(Glyph.GLOBE to "좌표계 설정", "작업명 · 좌표계(EPSG) · 지역 · 배경맵 · Geoid → 적용하면 측량", "set_row_job" to { c.jobs?.openInfo() ?: run { c.message = "작업 관리는 이 빌드에 없어요" } }),
                Triple(Glyph.PIN to "현재위치 모양", "현재 위치 · 점 · 목표 표시 모양과 크기", "set_row_marker" to { c.markerStyleOpen = true }),
                Triple(Glyph.MIC to "알림 소리 · 진동", "측량 완료음 · 버튼 탭 진동 · 화면 켜두기", "set_row_alert" to { c.hub.open(ToolScreen.SETTINGS) }),
            )
            for ((head, sub, act) in rows) SettingsRow(head.first, head.second, sub, act.first, act.second)
            SettingsRow(Glyph.INFO, "앱 정보", rememberAppVersionLabel() + " · 발굴 측량", "set_row_info", null)
            Text("더 보기", style = Ty.label, color = t.inkSub, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            val more: List<Triple<Pair<Glyph?, String>, String, Pair<String, () -> Unit>>> = listOf(
                Triple(Glyph.POLE to "IMU · 기울기", "수신기 IMU(미지원/미수신) · 폰 기포(참고용)", "set_row_imu" to { c.hub.open(ToolScreen.IMU) }),
                Triple(Glyph.RULER to "안테나 높이", "모델 · 측정 기준점 · 높이", "set_row_antenna" to { c.antennaSheetOpen = true }),
                Triple(Glyph.GLOBE to "GNSS 정보 · SkyPlot", "좌표 · 해 품질 · DOP · 위성 배치", "set_row_gnss" to { c.gnssInfoOpen = true }),
                Triple(Glyph.GLOBE to "측량 설정", "지역(EPSG) · Geoid · 측정 횟수 · 폰 기포 · 소리", "set_row_survey" to { c.hub.open(ToolScreen.SETTINGS) }),
                Triple(Glyph.LIST to "위치 종류", "발굴 위치 종류 추가 · 순서", "set_row_types" to { c.hub.open(ToolScreen.TYPES) }),
                Triple(Glyph.LAYERS to "V월드 지적도 · 배경맵", "API 인증키 · 도메인 · 지적선 · 연결 시험", "set_row_vworld" to { c.vworld.load(); c.hub.open(ToolScreen.VWORLD) }),
                Triple(Glyph.LAYERS to "배경 레이어", "도면 · 정사영상 올리기", "set_row_bg" to { c.bg?.openManage() ?: run { c.message = "배경 레이어는 이 빌드에 없어요" } }),
                Triple(Glyph.FOLDER to "백업 · 복구", "작업 데이터 ZIP 백업", "set_row_backup" to { c.backup?.openScreen() ?: run { c.message = "백업은 이 빌드에 없어요" } }),
            )
            for ((head, sub, act) in more) SettingsRow(head.first, head.second, sub, act.first, act.second)
            c.message?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.padding(top = 12.dp).testTag("settings_menu_message")) }
        }
    }
}

@Composable
private fun SettingsRow(g: Glyph?, title: String, sub: String, tag: String, onClick: (() -> Unit)?) {
    val t = T
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.hClickable(onClick = onClick) else Modifier).padding(vertical = 8.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(t.chip), contentAlignment = Alignment.Center) { if (g != null) GlyphIcon(g, t.ink, 20.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = Ty.label.copy(fontSize = 16.sp), color = t.ink)
                Text(sub, style = Ty.small, color = t.inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) GlyphIcon(Glyph.CHEVRON_RIGHT, t.inkSub, 18.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
    }
}
