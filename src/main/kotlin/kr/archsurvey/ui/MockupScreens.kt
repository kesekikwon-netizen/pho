package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.map.*
import kr.archsurvey.ntrip.RtcmMountCompat

/*
 * 0.1.14 목업 23화면 정렬: 이 파일은 목업에만 있던 화면들.
 * 08 일반 측량 메뉴 · 09 작업관리 · 03 RTK 설정 행 · 14 백업/복구 창 · 18 사진 확인 · 20 점 길게 누르기 메뉴 · 22 위치 모양 · 23 IMU/기울기.
 * SmartTopo 의 구조·순서만 참고(로고·아이콘·색 복제 없음). 발굴 용어로 바꿈: 포인트→점, 토목용 메뉴→찾아가기·외곽·구역.
 */

@Composable
internal fun HeroCard(title: String, sub: String, g: Glyph, tag: String) {
    val t = T
    Row(
        Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(24.dp)).background(t.raised).border(1.dp, t.line.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(20.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Ty.title.copy(fontSize = 24.sp), color = t.ink)
            Text(sub, style = Ty.small, color = t.inkSub)
        }
        GlyphIcon(g, t.ink, 44.dp)
    }
}

@Composable
internal fun MenuRow(g: Glyph?, title: String, sub: String?, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = T
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).hClickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.chip), contentAlignment = Alignment.Center) { if (g != null) GlyphIcon(g, t.ink, 20.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = Ty.label.copy(fontSize = 16.sp), color = if (enabled) t.ink else t.inkSub)
                sub?.let { Text(it, style = Ty.small, color = t.inkSub, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (enabled) GlyphIcon(Glyph.CHEVRON_RIGHT, t.inkSub, 18.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line.copy(alpha = 0.25f)))
    }
}

// ---------------- 08 일반 측량 메뉴 ----------------

@Composable
fun SurveyHubScreen(c: SurveyController) {
    val t = T
    ToolScaffold("일반 측량", "survey_hub", onBack = { c.hub.back() }) {
        HeroCard("일반 측량", "General Survey", Glyph.POLE, "survey_hub_hero")
        Column {
            MenuRow(Glyph.FOLDER, "작업관리", "새 작업 · 열기 · 전송 · 정보 · 점 목록 · 백업", "hub_jobs") { c.hub.open(ToolScreen.JOB_HUB) }
            MenuRow(Glyph.POLE, "측량", "지도에서 점 측량 (${c.projectName})", "hub_survey") { c.goToSurvey() }
            MenuRow(Glyph.PIN, "점 찾아가기", "저장한 점·좌표로 안내", "hub_goto") { c.hub.open(ToolScreen.GOTO) }
            MenuRow(Glyph.POLYGON, "유구 외곽 측량", "외곽선을 이어 측량", "hub_outline") { c.goToSurvey(); if (!c.arch.outlineMode) c.setLinkMode(closed = true) }
            MenuRow(Glyph.CALC, "계산기", "거리·방위 · 면적 · 좌표", "hub_calc") { c.hub.open(ToolScreen.CALC) }
            MenuRow(Glyph.GRID, "구역 · 그리드", "발굴 구역과 그리드", "hub_areas") { c.hub.open(ToolScreen.AREAS) }
        }
        Text("공사용 메뉴는 발굴 기록 범위 밖이라 넣지 않았어요. 점 찾아가기 · 유구 외곽 · 구역 · 그리드로 바꿨어요.", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("survey_hub_note"))
    }
}

// ---------------- 09 작업관리 ----------------

@Composable
fun JobHubScreen(c: SurveyController) {
    ToolScaffold("작업관리", "job_hub", onBack = { c.hub.back() }) {
        HeroCard("작업관리", "Work Management · 지금 ${c.projectName}", Glyph.FOLDER, "job_hub_hero")
        Column {
            MenuRow(Glyph.PLUS, "새 작업 생성", "작업명 · 좌표계 · 지역 · 배경맵 · Geoid", "jobhub_new") { c.jobs?.openNew() ?: c.ui.fail("작업 관리를 쓸 수 없어요") }
            MenuRow(Glyph.FOLDER, "작업 열기 / 삭제", "다른 작업으로 바꾸거나 지우기", "jobhub_open") { c.jobs?.openList() ?: c.ui.fail("작업 관리를 쓸 수 없어요") }
            MenuRow(Glyph.SHARE, "작업 전송", "CSV · DXF · GeoJSON · KML · Shape → 공유/저장", "jobhub_export") { c.exporter?.openScreen() ?: c.ui.fail("내보내기는 이 빌드에 없어요") }
            MenuRow(Glyph.INFO, "작업 정보 확인", "적용하면 측량 화면으로", "jobhub_info") { c.jobs?.openInfo() ?: c.ui.fail("작업 관리를 쓸 수 없어요") }
            MenuRow(Glyph.LIST, "점 목록", "검색 · 정렬 · 길게 눌러 메뉴", "jobhub_points") { c.hub.open(ToolScreen.POINTS) }
            MenuRow(Glyph.LAYERS, "데이터 백업 / 복구", "ZIP 백업 · 파일에서 복구 · 이전 버전", "jobhub_backup") { c.backupQuickOpen = true }
        }
    }
}

// ---------------- 14 데이터 백업 / 복구 창 ----------------

@Composable
fun BackupQuickDialog(c: SurveyController) {
    val t = T
    val b = c.backup
    val close = { c.backupQuickOpen = false }
    Box(Modifier.fillMaxSize().background(t.scrim).hClickable { close() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.raised).hClickable(enabled = false) {}.padding(20.dp).testTag("backup_quick"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("데이터 백업 / 복구", style = Ty.title, color = t.ink)
            if (b == null) Text("백업은 이 빌드에 없어요", style = Ty.body, color = t.inkSub)
            else {
                MBtn(Modifier.fillMaxWidth().height(52.dp).testTag("backup_quick_create"), MKind.PRIMARY, 26.dp, onClick = { close(); b.create() }) {
                    Text(if (b.running) "처리 중…" else "데이터 백업", style = Ty.label, color = t.onPrimary)
                }
                MBtn(Modifier.fillMaxWidth().height(52.dp).testTag("backup_quick_restore"), MKind.PLAIN, 26.dp, onClick = { close(); b.openScreen(); b.pickFile() }) {
                    Text("데이터 복구 (파일에서)", style = Ty.label, color = t.ink)
                }
                MBtn(Modifier.fillMaxWidth().height(52.dp).testTag("backup_quick_prev"), MKind.PLAIN, 26.dp, onClick = { close(); b.openScreen() }) {
                    Text("이전 버전 데이터 복구", style = Ty.label, color = t.ink)
                }
            }
            Text("백업 파일은 앱 폴더에 쌓여요. Google Drive 자동 백업·측점 업로드는 백업 화면에서 계정을 연결하세요. 복구 전에는 지금 상태를 ‘복구 직전’ 백업으로 자동 보관해요.", style = Ty.small, color = t.inkSub)
            MBtn(Modifier.fillMaxWidth().height(56.dp).testTag("backup_quick_close"), MKind.PLAIN, 28.dp, onClick = close) { Text("닫기", style = Ty.label, color = t.ink) }
        }
    }
}

// ---------------- 03 RTK 설정 ----------------

@Composable
fun RtkScreen(c: SurveyController) {
    val t = T
    val cc = c.connection
    ToolScaffold("RTK 설정", "rtk_screen", onBack = { c.hub.back() }) {
        if (cc == null) { Text("연결 기능이 이 빌드에 없어요", style = Ty.body, color = t.inkSub); return@ToolScaffold }
        val prof = cc.profile
        val max = RtcmMountCompat.maxMinor(prof)
        Card {
            Text("수신기 · 보정 방식", style = Ty.label, color = t.ink)
            KV("수신기", prof.displayName, "rtk_receiver")
            KV("보정 방식", "VRS (NTRIP · 인터넷)", "rtk_mode")
            KV("받을 수 있는 형식", if (prof.id == DeviceProfiles.SP60_ID) "RTCM 3.1 / 3.2 (3.4 불가) · CMR/CMR+ · ATOM" else "RTCM 3.x (3.2 MSM · 3.4 가능) · CMR/CMRx", "rtk_formats")
            Text("기지국 무선(UHF) 보정은 이 앱에서 설정하지 않아요. 수신기 설정 앱/컨트롤러에서 하세요.", style = Ty.small, color = t.inkSub)
        }
        Card {
            Text("지금 마운트", style = Ty.label, color = t.ink)
            KV("서버", "${cc.form.host}:${cc.form.port}", "rtk_host")
            KV("마운트", cc.form.mount.ifBlank { "—" }, "rtk_mount")
            val warn = RtcmMountCompat.warnIfIncompatible(prof, cc.form.mount)
            Text(warn ?: "이 수신기와 맞는 형식이에요 (최대 RTCM 3.$max)", style = Ty.small, color = if (warn != null) t.bad else t.inkSub, modifier = Modifier.testTag("rtk_compat"))
            cc.recommendedMount()?.let { rec -> Btn("권장 마운트로 바꾸기 · $rec", Modifier.fillMaxWidth().testTag("rtk_use_recommended")) { cc.applyRecommendedMount() } }
            KV("내 위치 보내기", if (cc.sendGga) "켬 (VRS 필요)" else "끔", "rtk_gga")
            KV("자동 재접속", if (cc.autoRetry) "켬" else "끔", "rtk_retry")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Btn("VRS 설정", Modifier.fillMaxWidth().testTag("rtk_open_vrs")) { cc.openVrs() } }
            Box(Modifier.weight(1f)) { Btn("장비 연결", Modifier.fillMaxWidth().testTag("rtk_open_device")) { cc.openScreen() } }
        }
    }
}

@Composable
private fun KV(k: String, v: String, tag: String) {
    val t = T
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Ty.small, color = t.inkSub, modifier = Modifier.width(112.dp))
        Text(v, style = Ty.body, color = t.ink, modifier = Modifier.weight(1f).testTag(tag))
    }
}

// ---------------- 23 IMU · 기울기 ----------------

/** 부호 있는 도분초 문자열 (예: -0°08′35″) */
internal fun signedDms(deg: Double): String {
    val s = if (deg < 0) "-" else ""
    val a = Math.abs(deg); val d = a.toInt(); val mF = (a - d) * 60; val m = mF.toInt(); val sec = ((mF - m) * 60).toInt()
    return String.format(java.util.Locale.US, "%s%d°%02d′%02d″", s, d, m, sec)
}

object ImuCopy {
    /** 수신기 IMU 상태 문구(사실대로). R10: SurePoint 는 수신기·컨트롤러 기능이고 이 앱은 그 값을 받지 않음. SP60: 경사보정 없음 */
    fun receiverLine(profileId: String): Pair<String, String> = if (profileId == DeviceProfiles.SP60_ID)
        "미지원" to "SP60 에는 IMU 경사보정이 없어요. 수신기의 원형 기포(수준기)를 맞춰 폴을 세우세요."
    else
        "앱에서 받지 않음" to "R10 의 SurePoint(전자 기포·기울기)는 수신기·Trimble 컨트롤러 기능이에요. 이 앱은 그 값을 받지 않아 좌표를 기울기로 보정하지 않아요."
    const val PHONE = "아래 기포는 폰 가속도 센서예요(참고용). 폰을 폴에 세로로 단단히 붙였을 때만 의미가 있고, 좌표를 보정하지 않아요 — 수신기 IMU 와 다릅니다."
}

@Composable
fun ImuScreen(c: SurveyController) {
    val t = T
    val tc = c.tilt
    val prof = c.connection?.profile ?: DeviceProfiles.R10
    ToolScaffold("기울기", "imu_screen", onBack = { c.hub.back() }, bottom = {
        DualBar("취소", "imu_cancel", { c.hub.back() }, "▷ 측량 시작", "imu_start") { c.goToSurvey() }
    }) {
        val (rs, rl) = ImuCopy.receiverLine(prof.id)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("수신기 IMU 경사보정 · ${prof.displayName}", style = Ty.label, color = t.ink)
                    Text(rs, style = Ty.label, color = t.caution, modifier = Modifier.testTag("imu_receiver_state"))
                }
                MBtn(Modifier.height(48.dp).width(88.dp).testTag("imu_receiver_cal"), MKind.PLAIN, 24.dp, alpha = 0.45f, onClick = { c.ui.fail(rl) }) { Text("보정", style = Ty.label, color = t.inkSub) }
            }
            Text(rl, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("imu_receiver_note"))
        }
        if (tc == null || !tc.available) {
            Card { Text("폰 기울기 센서가 없어 폰 기포도 쓸 수 없어요.", style = Ty.body, color = t.inkSub, modifier = Modifier.testTag("imu_no_phone")) }
            return@ToolScaffold
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("폰 기포 영점 (참고용)", style = Ty.label, color = t.ink)
                    Text(if (tc.calibrated) "맞춤" else "기본값 (폰 세로)", style = Ty.label, color = if (tc.calibrated) t.fixed else t.inkSub, modifier = Modifier.testTag("imu_phone_state"))
                }
                MBtn(Modifier.height(48.dp).width(88.dp).testTag("imu_phone_cal"), MKind.PLAIN, 24.dp, onClick = {
                    val err = tc.calibrate(); if (err == null) c.ui.ok("폰 기포 영점을 맞췄어요") else c.ui.fail(err)
                }) { Text("보정", style = Ty.label, color = t.ink) }
            }
            if (!tc.enabled) Btn("폰 기포 켜기", Modifier.fillMaxWidth().testTag("imu_phone_enable")) { tc.changeEnabled(true); c.ui.ok("폰 기포를 켰어요") }
            tc.calibrationNote?.let { Text(it, style = Ty.small, color = t.inkSub) }
        }
        Card {
            val pole = c.antenna.inputValueM.coerceAtLeast(0.1)
            val allowCm = TiltCopy.offsetCm(tc.limitDeg, pole)
            val b = tc.bubble
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val col = if (tc.isOver) t.caution else t.fixed
                androidx.compose.foundation.Canvas(Modifier.size(132.dp).testTag("imu_bubble")) {
                    val r = size.minDimension / 2f; val c0 = center
                    drawCircle(t.line, r, c0, style = Stroke(1.dp.toPx()))
                    drawLine(t.line, Offset(c0.x - r, c0.y), Offset(c0.x + r, c0.y), 1.dp.toPx()); drawLine(t.line, Offset(c0.x, c0.y - r), Offset(c0.x, c0.y + r), 1.dp.toPx())
                    drawCircle(t.bad, r / 2f, c0, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    if (b != null) {
                        val full = Math.toRadians(tc.limitDeg * 2.0)
                        val sx = (kotlin.math.asin(b.x.coerceIn(-1.0, 1.0)) / full).coerceIn(-1.0, 1.0)
                        val sy = (kotlin.math.asin(b.y.coerceIn(-1.0, 1.0)) / full).coerceIn(-1.0, 1.0)
                        drawCircle(col, 12.dp.toPx(), Offset(c0.x + (sx * (r - 12.dp.toPx())).toFloat(), c0.y + (sy * (r - 12.dp.toPx())).toFloat()))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("기울기 허용 거리", style = Ty.small, color = t.inkSub)
                    Text(String.format(java.util.Locale.US, "%.1f cm", allowCm), style = Ty.title.copy(fontSize = 24.sp), color = t.ink, modifier = Modifier.testTag("imu_allow_cm"))
                    Text("허용 ${tc.f1(tc.limitDeg)}° · 폴 ${f3(pole)} m", style = Ty.small, color = t.inkSub)
                    val xd = b?.let { Math.toDegrees(kotlin.math.asin(it.x.coerceIn(-1.0, 1.0))) }
                    val yd = b?.let { Math.toDegrees(kotlin.math.asin(it.y.coerceIn(-1.0, 1.0))) }
                    Text("X ${xd?.let { signedDms(it) } ?: "—"}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("imu_x"))
                    Text("Y ${yd?.let { signedDms(it) } ?: "—"}", style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("imu_y"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Btn("허용 −", Modifier.fillMaxWidth().testTag("imu_limit_down")) { tc.stepLimit(false) } }
                Box(Modifier.weight(1f)) { Btn("허용 +", Modifier.fillMaxWidth().testTag("imu_limit_up")) { tc.stepLimit(true) } }
            }
            Text(ImuCopy.PHONE, style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("imu_phone_note"))
        }
    }
}

// ---------------- 22 위치 모양 설정 ----------------

@Composable
fun MarkerStyleDialog(c: SurveyController) {
    val t = T
    var s by remember { mutableStateOf(c.markerStyle) }
    val sizes = MarkerStyle.SIZES
    Box(Modifier.fillMaxSize().background(t.scrim).hClickable { c.markerStyleOpen = false }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.raised).hClickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(20.dp).testTag("marker_style"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("위치 모양 설정", style = Ty.title, color = t.ink)
            @Composable fun ShapeRow(label: String, value: String, size: Int, tag: String, pickShape: () -> Unit, pickSize: (Int) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = Ty.small, color = t.inkSub, modifier = Modifier.width(72.dp))
                    MBtn(Modifier.weight(1f).height(48.dp).testTag("${tag}_shape"), MKind.PLAIN, 14.dp, onClick = pickShape) { Text("$value ▾", style = Ty.label, color = t.ink, maxLines = 1) }
                    MBtn(Modifier.width(64.dp).height(48.dp).testTag("${tag}_size"), MKind.PLAIN, 14.dp, onClick = {
                        c.ui.choose(ChoiceRequest("$label 크기", sizes.map { "$it" + if (it == 3) " (기본)" else "" }, sizes.indexOf(size), "${tag}size") { i -> pickSize(sizes[i]) })
                    }) { Text("$size ▾", style = Ty.label, color = t.ink) }
                }
            }
            ShapeRow("현재위치", s.current.labelKo, s.currentSize, "ms_current", {
                val o = CurrentShape.entries; c.ui.choose(ChoiceRequest("현재위치 모양", o.map { it.labelKo }, o.indexOf(s.current), "mscur") { s = s.copy(current = o[it]) })
            }) { s = s.copy(currentSize = it) }
            ShapeRow("측량점", s.point.labelKo, s.pointSize, "ms_point", {
                val o = PointShape.entries; c.ui.choose(ChoiceRequest("측량점 모양", o.map { it.labelKo }, o.indexOf(s.point), "mspt") { s = s.copy(point = o[it]) })
            }) { s = s.copy(pointSize = it) }
            ShapeRow("찾아갈 점", s.target.labelKo, s.targetSize, "ms_target", {
                val o = TargetShape.entries; c.ui.choose(ChoiceRequest("찾아갈 점 모양", o.map { it.labelKo }, o.indexOf(s.target), "mstg") { s = s.copy(target = o[it]) })
            }) { s = s.copy(targetSize = it) }
            Text("위치 부가 정보 표시", style = Ty.small, color = t.inkSub)
            Seg(MarkerExtra.entries.map { it.labelKo }, MarkerExtra.entries.indexOf(s.extra), "ms_extra") { s = s.copy(extra = MarkerExtra.entries[it]) }
            Text("점 이름 배경색", style = Ty.small, color = t.inkSub)
            Seg(LabelBg.entries.map { it.labelKo }, LabelBg.entries.indexOf(s.labelBg), "ms_bg") { s = s.copy(labelBg = LabelBg.entries[it]) }
            MBtn(Modifier.fillMaxWidth().height(48.dp).testTag("ms_reset"), MKind.PLAIN, 24.dp, onClick = { s = MarkerStyle() }) { Text("초기 설정으로 복원", style = Ty.label, color = t.ink) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MBtn(Modifier.weight(1f).height(56.dp).testTag("ms_cancel"), MKind.PLAIN, 28.dp, onClick = { c.markerStyleOpen = false }) { Text("취소", style = Ty.label, color = t.ink) }
                MBtn(Modifier.weight(1f).height(56.dp).testTag("ms_apply"), MKind.PRIMARY, 28.dp, onClick = { c.applyMarkerStyle(s) }) { Text("적용", style = Ty.label, color = t.onPrimary) }
            }
        }
    }
}

/** 둥근 2칸(이상) 선택 막대 */
@Composable
internal fun Seg(options: List<String>, selected: Int, tag: String, onPick: (Int) -> Unit) {
    val t = T
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(t.chip).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(if (on) t.raised else t.chip).hClickable { onPick(i) }.testTag("${tag}_$i"),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Ty.label, color = if (on) t.ink else t.inkSub, maxLines = 1) }
        }
    }
}

// ---------------- 20 점 길게 누르기 메뉴 ----------------

/** 0.1.46 시안 s04 아래 점 시트: 종류 바꾸기 칸 · 다시 측량 · 지도에서 보기 · 찾아가기 · 자세히 · 삭제(길게 누름, 마지막 점만) · 닫기 */
@Composable
fun PointMenuSheet(c: SurveyController, p: SavedPoint) {
    val close = { c.pointMenuFor = null }
    val isLast = c.points.lastOrNull()?.name == p.name
    val cur = c.points.firstOrNull { it.name == p.name } ?: p
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(T.scrim.copy(alpha = 0.18f)).hClickable { close() }.testTag("point_menu_scrim"))
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp).clip(shape).background(D.bg).hClickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp).testTag("point_menu"),
            verticalArrangement = Arrangement.spacedBy(FieldIvory.gap),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(CircleShape).background(D.line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cur.name, style = DTy.h1, color = D.ink, modifier = Modifier.testTag("point_menu_title"))
                    Text(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(cur.measuredAtMs)) + " · " + PointCaution.hvLine(cur), style = DTy.small, color = D.sub, maxLines = 2, modifier = Modifier.testTag("point_menu_hv"))
                }
                val okSt = PointCaution.displayStatus(cur) == kr.archsurvey.core.SurveyStatus.FIXED
                if (!okSt) Text(PointCaution.label(cur), style = DTy.small.copy(fontWeight = FontWeight.Bold), color = FieldIvory.float, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(FieldIvory.float.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Text("종류 바꾸기", style = DTy.small, color = D.sub)
            val opts = (listOf(cur.type) + c.types).filter { it.isNotBlank() }.distinct().take(6)
            ChoiceGrid(opts.mapIndexed { i, ty -> GridItem(ty, "pm_type_$i", cur.type == ty) { c.updatePointMeta(cur.name, ty, cur.memo) } })
            ChoiceGrid(listOf(
                GridItem("다시 측량", "pm_resurvey") { close(); c.startResurvey(cur) },
                GridItem("지도에서 보기", "pm_map") { close(); c.hub.closeAll(); c.goToSurvey(); c.focusOn(cur) },
                GridItem("찾아가기", "pm_goto") { close(); c.hub.startGoto(cur) },
                GridItem("자세히 · 메모", "pm_edit") { close(); c.hub.openDetail(cur) },
            ), columns = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val sh = RoundedCornerShape(14.dp)
                Box(Modifier.weight(1f).heightIn(min = FieldIvory.minTouch).clip(sh).border(1.5.dp, if (isLast) FieldIvory.error.copy(alpha = 0.5f) else D.line, sh)
                    .hCombinedClickable(onLongClick = { if (isLast) { close(); c.askUndo() } }) { c.ui.toast(if (isLast) "삭제는 길게 누르세요" else "기록 보존을 위해 마지막 점만 지울 수 있어요") }
                    .semantics { contentDescription = if (isLast) "삭제, 길게 누르기" else "삭제는 마지막 점만" }.testTag("pm_delete"), contentAlignment = Alignment.Center) {
                    Text(if (isLast) "삭제 (길게 누름)" else "삭제는 마지막 점만", style = DTy.bodyB, color = if (isLast) FieldIvory.error else D.weak)
                }
                DPrimary("닫기", "pm_close", Modifier.weight(1f), height = FieldIvory.minTouch, color = FieldIvory.primary) { close() }
            }
        }
    }
}

// ---------------- 18 사진 확인 ----------------

@Composable
fun PhotoReviewScreen(c: SurveyController) {
    val t = T
    val p = c.lastPoint
    val ph = c.photos.lastOrNull()
    Column(Modifier.fillMaxSize().background(t.ink).testTag("photo_review")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Text("${p?.name ?: "—"} · 사진 ${c.photos.size} / ${MediaLimits.PHOTOS}", style = Ty.label, color = t.ink,
                modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(16.dp)).background(t.raised).padding(horizontal = 12.dp, vertical = 6.dp).testTag("photo_review_count"))
            var bm by remember(ph?.rel) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(ph?.rel) { bm = ph?.let { x -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.media?.thumb(x.rel) }.getOrNull() } } }
            val b = bm
            if (b != null) androidx.compose.foundation.Image(b, "방금 찍은 사진", Modifier.fillMaxSize().padding(top = 64.dp, bottom = 16.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CAMERA, t.bg, 64.dp) }
        }
        Row(Modifier.fillMaxWidth().background(t.bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MBtn(Modifier.weight(1f).height(56.dp).testTag("photo_review_cancel"), MKind.PLAIN, 28.dp, onClick = { c.discardPhoto() }) { Text("취소", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(56.dp).testTag("photo_review_retake"), MKind.PLAIN, 28.dp, onClick = { c.retakePhoto() }) { Text("다시 촬영", style = Ty.label, color = t.ink) }
            MBtn(Modifier.weight(1f).height(56.dp).testTag("photo_review_ok"), MKind.PRIMARY, 28.dp, onClick = { c.confirmPhoto() }) { Text("확정", style = Ty.label, color = t.onPrimary) }
        }
    }
}
