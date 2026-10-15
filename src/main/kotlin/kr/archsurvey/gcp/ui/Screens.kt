package kr.archsurvey.gcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.device.LinkState
import kr.archsurvey.gcp.model.*
import kr.archsurvey.ui.*
import java.time.Instant
import java.time.format.DateTimeFormatter

// =============================== 유적 고르기(0.1.5 시안 10) ===============================
@Composable
fun SitesScreen(c: GcpController) {
    Column(Modifier.fillMaxSize().background(G.bg).testTag("sites")) {
        GTopBar("사진 기준점", null, c, onMenu = { c.go(Screen.SETTINGS) }, brand = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("유적", style = Ty.label.copy(fontSize = 26.sp, fontWeight = FontWeight.Black), color = G.ink, modifier = Modifier.weight(1f))
            ModeChip(G.ink, G.white, G.lineStrong, Modifier.padding(end = 8.dp)) // 0.1.15 [미니멀 | 일반]
            SunToggle(Modifier.padding(end = 10.dp))
            Text("최근 순", style = Ty.small.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(bottom = 4.dp))
        }
        Box(Modifier.padding(horizontal = 18.dp).padding(top = 8.dp).fillMaxWidth().height(2.dp).background(G.ink))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            kr.archsurvey.ui.BatteryGuideCard(Modifier.padding(horizontal = 18.dp).padding(top = 10.dp)) // 0.1.10
            if (c.sites.isEmpty()) Text("유적이 없어요. 아래 「+ 새 유적」을 누르면 지금 위치의 동 이름으로 이름을 골라 드려요.", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(20.dp).testTag("sites_empty"))
            for (sc in c.sites.sortedByDescending { it.lastAt ?: it.site.createdAt }) {
                val pend = (sc.counts[Stage.MEASURE] ?: 0) + (sc.counts[Stage.PHOTO] ?: 0) + (sc.counts[Stage.HANDOFF] ?: 0)
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).hClickable(onClickLabel = sc.site.name) { c.openSite(sc.site.id) }.padding(horizontal = 18.dp, vertical = 12.dp).testTag("site_${sc.site.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sc.site.name, style = Ty.label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("폴더 ${sc.objCount} · ${GcpController.epsgLabel(sc.site.epsg)}원점 ${sc.site.epsg}" + (sc.lastAt?.let { " · " + c.timeLabel(it) } ?: ""), style = Ty.small.copy(fontSize = 16.sp), color = G.sub, maxLines = 1)
                    }
                    if (pend > 0) Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(G.dot)); Text(" 남음 $pend", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.ink) }
                    else if (sc.objCount > 0) Text("끝", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = G.ok)
                }
                Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(G.line))
            }
            Text("새 유적은 지금 위치의 동 이름·최근 이름·오늘 날짜 중에서 고르고, 원점(좌표계)은 수신기 위치로 미리 골라 둡니다.", style = Ty.label.copy(fontSize = 16.sp), color = G.sub, modifier = Modifier.padding(18.dp))
        }
        BottomBar { Btn("+ 새 유적", "site_new", Modifier.weight(1f), height = 68.dp, sub = "이름 · 좌표계를 골라요") { c.openNewSite() } }
    }
}

// =============================== ID 카드(사진 첫 장) ===============================
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
    val ink = Color(0xFF111111)
    Column(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 18.dp, vertical = 14.dp).testTag("idcard"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("이 화면을 사진 세트의 첫 장으로 찍으세요", style = Ty.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(b.site.name, style = Ty.label.copy(fontSize = 18.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(o.name, style = Ty.label.copy(fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.Black), color = ink, maxLines = 1, modifier = Modifier.testTag("idcard_name"))
        Text("${s.no}차 · ${s.label}", style = Ty.label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = ink)
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.size(220.dp).testTag("idcard_qr")) {
            val n = matrix.size; if (n == 0) return@Canvas
            val cell = size.width / n
            for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
        Spacer(Modifier.height(10.dp))
        Text("사진 폴더 이름", style = Ty.small.copy(fontSize = 16.sp), color = T.inkSub)
        Text(Names.photoFolder(o, s), style = Ty.ledger.copy(fontSize = 20.sp), color = ink, modifier = Modifier.testTag("idcard_folder"))
        val d = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(s.createdAt).atZone(c.zone))
        Text("표적 #${QrPayload.ranges(b.active(s).map { it.tag })} (${b.active(s).size}개) · $d", style = Ty.coord.copy(fontSize = 16.sp), color = T.inkSub)
        Text("화면 밝기를 가장 밝게 해 두었어요", style = Ty.small.copy(fontSize = 16.sp), color = T.inkSub, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("닫기", "idcard_close", Modifier.weight(0.8f), primary = false, height = 64.dp) { c.back() }
            if (s.photoDoneAt == null) Btn("사진 다 찍었어요", "idcard_done", Modifier.weight(1.2f), enabled = Pipeline.canMarkPhoto(b.active(s).size), height = 64.dp,
                sub = if (Pipeline.canMarkPhoto(b.active(s).size)) "→ 전달 필요" else "표적 ${Pipeline.MIN_TARGETS}개부터") { c.markPhotoDone() }
            else Btn("사진 끝 ✓", "idcard_done", Modifier.weight(1.2f), primary = false, height = 64.dp) { c.back() }
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

// =============================== g07 설정: 일반 · 수신기 · 고급 ===============================
@Composable
fun SettingsScreen(c: GcpController, env: GcpEnv) {
    val t = T
    c.poleTick
    var keyEdit by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val link by c.source.link.collectAsState()
    Column(Modifier.fillMaxSize().background(t.bg).testTag("settings")) {
        GTopBar("설정", { c.back() }, c, sub = "일반 · 수신기 · 고급", tag = "settings_header", onMenu = { c.menuOpen = true })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            Segmented(listOf("일반" to "set_tab_general", "수신기" to "set_tab_receiver", "고급" to "set_tab_advanced"), tab) { tab = it }
            val cc = c.connection
            val vrs = cc?.form?.let { if (!it.enabled) "VRS 끔" else it.mount.ifBlank { "마운트 없음" } } ?: "VRS"
            val linkWord = when (link.state) { LinkState.STREAMING -> "연결됨"; LinkState.IDLE -> "연결 안 됨"; LinkState.NEEDS_USER -> "확인 필요"; else -> "연결 중" }
            when (tab) {
                0 -> {
                    GroupLabel("측량")
                    CardGroup {
                        CardRow(Glyph.POLE, "폴 높이", "누르면 ±0.10 · 프리셋", "set_pole", onClick = { c.poleOpen = true }) {
                            Text(String.format(java.util.Locale.US, "%.2f m", c.poleM()), style = Ty.coord.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = G.ink, modifier = Modifier.testTag("pole_value"))
                        }
                        CardDivider()
                        Column(Modifier.padding(16.dp)) {
                            Text("평균 횟수 (FIX 때만)", style = Ty.label.copy(fontWeight = FontWeight.Bold), color = G.ink)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in GcpController.EPOCH_CHOICES) Tile("${n}회", c.prefs.epochs == n, "epochs_$n", Modifier.weight(1f), height = 56.dp) { c.setEpochs(n) } }
                        }
                    }
                    GroupLabel("장갑 낀 손")
                    CardGroup {
                        CardRow(Glyph.CHECK, "볼륨 아래 키 = 측량", "안 잰 첫 표적을 재요", "set_volume") {
                            Switch(c.prefs.volumeKey, { c.setVolumeKey(it) }, modifier = Modifier.testTag("volume_switch"), colors = SwitchDefaults.colors(checkedTrackColor = t.primary))
                        }
                    }
                }
                1 -> {
                    GroupLabel("수신기")
                    CardGroup {
                        CardRow(Glyph.BT, "기본 수신기", (cc?.profile?.displayName ?: "R10") + " · " + linkWord + " · " + vrs + " · 평균 ${c.prefs.epochs}회", "set_receiver", onClick = { c.openReceiver() }) {
                            GlyphIcon(Glyph.CHEVRON_RIGHT, G.sub, 22.dp)
                        }
                    }
                    Text("연결·VRS·마운트는 수신기 화면에서 고르기만 해요. 다른 앱(발굴 측량)이 쓰는 중이면 넘겨받기를 물어요.", style = Ty.small, color = G.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                else -> {
                    GroupLabel("고급 (현장에서는 바꾸지 마세요)")
                    CardGroup {
                        val acc = env.mapAccess()
                        CardRow(Glyph.LAYERS, "V월드 지도 키", if (acc.key.isBlank()) "없음" else "••••" + acc.key.takeLast(4) + " · 바꿀 때만 직접 입력", "set_vworld", onClick = { keyEdit = "" }, iconTint = FieldIvory.error, iconBg = Color(0xFFFBE4E1)) { GlyphIcon(Glyph.CHEVRON_RIGHT, G.sub, 22.dp) }
                        CardDivider()
                        CardRow(Glyph.INFO, "진단 기록", "연결 방식 · 오류 기록", "set_diag", onClick = { diag = c.connection?.diagnostics().orEmpty().ifBlank { "기록 없음" } }, iconTint = FieldIvory.error, iconBg = Color(0xFFFBE4E1)) { GlyphIcon(Glyph.CHEVRON_RIGHT, G.sub, 22.dp) }
                    }
                    Text("점검 기준(고정): 표적 ${Pipeline.MIN_TARGETS}개부터 사진 · 다시 잰 값 ${(TargetChecks.MOVED_M * 100).toInt()} cm 넘게 다르면 묻기 · 근처 ${TargetChecks.CLASH_RADIUS_M.toInt()} m 같은 번호 경고 · XML 정밀도 0.01",
                        style = Ty.small, color = G.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Btn("앱 종료", "exit", Modifier.fillMaxWidth(), primary = false) { c.requestExit() }
            Text("사진 기준점 ${env.version}", style = Ty.small, color = G.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("set_version"))
        }
    }
    keyEdit?.let { k ->
        BottomSheet("V월드 키 바꾸기", "vworld_sheet", onClose = { keyEdit = null }, bottom = {
            Btn("기본 키로", "vworld_default", Modifier.weight(1f), primary = false) { env.saveMapKey(""); keyEdit = null; c.ui.ok("앱 기본 키로 돌아갔어요") }
            Btn("저장", "vworld_save", Modifier.weight(1f), enabled = k.isNotBlank()) { env.saveMapKey(k); keyEdit = null; c.ui.ok("V월드 키를 저장했어요") }
        }) {
            Caption("앱에 기본 키가 들어 있어요. 다른 키를 쓸 때만 넣으세요(화면에는 끝 4자리만).")
            androidx.compose.material3.OutlinedTextField(k, { keyEdit = it.trim() }, Modifier.fillMaxWidth().testTag("vworld_key"), singleLine = true, label = { Text("인증키") })
        }
    }
    diag?.let { d -> BottomSheet("진단 기록", "diag_sheet", onClose = { diag = null }) { Text(d, style = Ty.coord.copy(fontSize = 16.sp), color = t.ink, modifier = Modifier.testTag("diag_text")) } }
}

// =============================== 뿌리 ===============================
@Composable
fun GcpRoot(c: GcpController, env: GcpEnv) {
    androidx.activity.compose.BackHandler { c.back() }
    val g by c.source.gnss.collectAsState()
    CompositionLocalProvider(LocalFixCode provides g.quality.code) {
        // 0.1.52 #3: 바탕은 시스템 막대 뒤까지, 내용(측량 단추·아래 줄)은 safeDrawing 안쪽
        Box(Modifier.fillMaxSize().background(G.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (c.screen) {
                Screen.SITES -> SitesScreen(c)
                Screen.OBJECTS -> ObjectsScreen(c)
                Screen.OBJ -> ObjScreen(c)
                Screen.TARGETS -> TargetsScreen(c, env)
                Screen.MAP -> SiteMapScreen(c, env)
                Screen.ID_CARD -> IdCardScreen(c)
                Screen.SETTINGS -> SettingsScreen(c, env)
            }
            if (c.menuOpen) MenuSheet(c)
            if (c.objListOpen) ObjListSheet(c)
            c.newObj?.let { NewObjScreen(c, it) }
            if (c.sessionSheetOpen) SessionSheet(c)
            c.newSession?.let { NewSessionSheet(c, it) }
            c.newSite?.let { NewSiteSheet(c, it) }
            if (c.handoffOpen) HandoffScreen(c)
            if (c.poleOpen) PoleSheet(c)
            c.numPick?.let { NumPickScreen(c, it) }
            c.connection?.let { cc -> ConnectionHost(cc, g.quality, g.gga?.satellites ?: 0) }
            FeedbackOverlays(c.ui)
        }
    }
}
