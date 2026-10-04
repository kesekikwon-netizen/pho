package kr.archsurvey.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.core.*
import kr.archsurvey.data.PhotoEntity
import kr.archsurvey.data.PointEntity
import kr.archsurvey.engine.LinkState
import kr.archsurvey.export.Formats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CODES = listOf("유구", "토층", "유물", "기준점")

@Composable
fun SurveyScreen(vm: MainVm, goConnect: () -> Unit) {
    val link by vm.engine.link.collectAsStateWithLifecycle()
    val g by vm.engine.gnss.collectAsStateWithLifecycle()
    val ntrip by vm.engine.ntrip.collectAsStateWithLifecycle()
    val ui = vm.surveyUi
    var antDialog by remember { mutableStateOf(false) }
    val gga = g.gga
    val pc = remember(gga?.lat, gga?.lon, vm.tmZone) { if (gga?.hasPosition == true) TmProjection.forward(gga.lat!!, gga.lon!!, vm.tmZone) else null }
    val res = vm.antennaResult()
    val apcH = gga?.ellipsoidalHeight
    val groundH = AntennaMath.groundHeight(apcH, vm.antenna, res)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("포인트 측량", style = Ty.big, color = T.ink, modifier = Modifier.weight(1f))
            Text("🛰 ${gga?.satellites ?: 0}", style = Ty.title, color = T.ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FixBadge(g.quality)
            Column {
                Text("보정 나이 ${gga?.correctionAgeSec?.let { fmtPlain(it, 1) + " s" } ?: "—"}", style = Ty.small, color = T.inkSub)
                Text(vm.profile?.displayName ?: "측량기 미선택", style = Ty.bodyB, color = T.ink)
            }
        }
        if (link.state != LinkState.STREAMING) {
            StatusBanner(if (link.state == LinkState.IDLE) "측량기가 연결되지 않았어요. 눌러서 연결하세요." else link.message.ifEmpty { "연결 중…" },
                if (link.state == LinkState.IDLE || link.state == LinkState.NEEDS_USER) Kind.ERROR else Kind.WARN, onClick = goConnect)
        } else if (g.quality != FixQuality.RTK_FIXED) {
            StatusBanner("RTK 고정이 아니에요 (${g.quality.labelKo}). 고정될 때까지 기다리세요.", Kind.WARN)
        }
        if (vm.ntripEnabled && ntrip.state != kr.archsurvey.ntrip.NtripState.RECEIVING && link.state == LinkState.STREAMING)
            StatusBanner("VRS 보정: ${ntrip.message.ifEmpty { "대기 중" }}", Kind.WARN, onClick = goConnect)

        // 점 ID
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", "점 번호 줄이기") { vm.pointId = bump(vm.pointId, -1) }
            OutlinedTextField(vm.pointId, { vm.pointId = it.take(24) }, singleLine = true, textStyle = Ty.big,
                label = { Text("점 ID") }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            StepButton("+", "점 번호 늘리기") { vm.pointId = bump(vm.pointId, 1) }
        }
        // 라벨 (code): 의미를 해석하지 않는 단순 라벨. 수량·재질 같은 속성 입력은 없음
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CODES.forEach { c -> Chip(c, vm.code == c) { vm.code = if (vm.code == c) "" else c; vm.settings.code = vm.code } }
        }

        // 좌표
        Card {
            CoordRow("N 북", pc?.northing)
            CoordRow("E 동", pc?.easting)
            CoordRow(if (vm.antenna.receiverApplied) "H 높이" else "H 지면", groundH ?: apcH, extra = if (!vm.antenna.receiverApplied && apcH != null) "안테나(APC) ${fmt(apcH)}" else null)
            Text("${vm.tmZone.labelKo} · 타원체고", style = Ty.small, color = T.inkSub)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniCard("수평", g.gst?.sigmaHoriz?.let { "± " + fmtPlain(it, 3) + " m" } ?: "—", Modifier.weight(1f))
            MiniCard("수직", g.gst?.sigmaAlt?.let { "± " + fmtPlain(it, 3) + " m" } ?: "—", Modifier.weight(1f))
            MiniCard("HDOP", gga?.hdop?.let { fmtPlain(it, 1) } ?: "—", Modifier.weight(1f))
        }
        if (g.checksumErrors > 20) StatusBanner("수신 데이터에 오류가 많아요(${g.checksumErrors}건). 폰을 수신기 가까이 두세요. (오류 BT-131)", Kind.WARN)

        AntennaBar(vm) { antDialog = true }

        when (ui) {
            is SurveyUi.Saved -> ResultPane(vm, ui.rowId)
            else -> {
                if (ui is SurveyUi.Failed) StatusBanner(ui.message, Kind.ERROR)
                val measuring = ui is SurveyUi.Measuring
                if (measuring) {
                    LinearProgressIndicator(progress = { (ui as SurveyUi.Measuring).progress }, modifier = Modifier.fillMaxWidth().height(12.dp))
                    Text("측량 중… 폴을 움직이지 마세요 (${vm.epochs}회 평균)", style = Ty.body, color = T.inkSub)
                }
                BigButton("포인트 기록", { vm.measure() }, enabled = !measuring && link.state == LinkState.STREAMING, height = 84.dp,
                    sub = "${vm.pointId} · 한 번 누르세요")
            }
        }
        Text("실기기 시험 전 버전입니다. 측량 성과로 쓰기 전에 알려진 기준점으로 검증하세요.", style = Ty.small, color = T.inkSub)
    }
    if (antDialog) AntennaDialog(vm) { antDialog = false }
}

private fun bump(id: String, d: Int): String = if (d > 0) kr.archsurvey.data.PointIds.next(id, "P-") else {
    val m = Regex("^(.*?)(\\d+)$").find(id)
    if (m == null) id else { val n = ((m.groupValues[2].toLongOrNull() ?: 1L) - 1).coerceAtLeast(0); m.groupValues[1] + n.toString().padStart(m.groupValues[2].length, '0') }
}

@Composable
private fun CoordRow(label: String, v: Double?, extra: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ty.bodyB, color = T.inkSub, modifier = Modifier.width(88.dp))
        Column(Modifier.weight(1f)) {
            Text(fmt(v, 3), style = Ty.coord, color = T.ink)
            if (extra != null) Text(extra, style = Ty.small, color = T.inkSub)
        }
    }
}

@Composable
private fun MiniCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(T.surface).padding(12.dp)) {
        Text(label, style = Ty.small, color = T.inkSub); Text(value, style = Ty.bodyB, color = T.ink)
    }
}

/** 측량 직후: 저장됨 + 사진 + 메모 + 다음 점 (연결 → 측량 → 사진 → 저장) */
@Composable
fun ResultPane(vm: MainVm, rowId: Long) {
    val p by vm.pointFlow(rowId).collectAsStateWithLifecycle(initialValue = null)
    var memo by remember(rowId) { mutableStateOf("") }
    Card(accent = T.ok) {
        Text("✓ ${p?.pointId ?: ""} 저장됨", style = Ty.title, color = T.okText)
        p?.let {
            Text("${Formats.fixLabel(it.fixQuality)} · ${it.epochs}회 평균 · 평면 N ${fmt(it.northing)} E ${fmt(it.easting)}", style = Ty.small, color = T.inkSub)
            val gh = it.groundEllipsoidalHeight
            Text("지면 높이 ${fmt(gh)} (APC ${fmt(it.ellipsoidalHeight)} − 안테나 ${fmtPlain(it.antennaHeight, 3)})", style = Ty.small, color = T.inkSub)
            if (!it.allEpochsFixed) StatusBanner("측량 중 RTK 고정이 아닌 순간이 있었어요 (${Formats.fixLabel(it.fixQuality)}). 기록에 남아요.", Kind.WARN, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        PhotoSection(vm, rowId)
        OutlinedTextField(memo, { memo = it }, label = { Text("간단 메모") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textStyle = Ty.body)
        Spacer(Modifier.height(10.dp))
        BigButton("저장하고 다음 점", { if (memo.isNotBlank()) vm.saveMemo(rowId, memo, p?.code ?: ""); vm.nextPoint() })
    }
}

@Composable
fun PhotoSection(vm: MainVm, rowId: Long) {
    val photos by vm.photosOf(rowId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.cameraResult(ok) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> vm.galleryPicked(rowId, uris) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("사진 ${photos.size}장", style = Ty.bodyB, color = T.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineBigButton("📷 촬영", {
                scope.launch { val u = vm.cameraTarget(rowId); if (u != null) try { camera.launch(u) } catch (e: Exception) { vm.toast = "카메라를 열 수 없어요" } }
            }, Modifier.weight(1f), height = 64.dp)
            OutlineBigButton("🖼 갤러리", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f), height = 64.dp)
        }
        if (photos.isNotEmpty()) {
            val rows = photos.chunked(3)
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.forEach { ph -> Thumb(vm, ph, Modifier.weight(1f)) }
                    repeat(3 - r.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Thumb(vm: MainVm, ph: PhotoEntity, modifier: Modifier) {
    var bmp by remember(ph.relPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(ph.relPath) { bmp = withContext(Dispatchers.IO) { vm.photos.loadThumb(ph.relPath) } }
    var confirm by remember { mutableStateOf(false) }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(T.surfaceVar).clickable { confirm = true }) {
        bmp?.let { Image(it.asImageBitmap(), "점에 붙인 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    if (confirm) BigDialog("사진을 지울까요?", { confirm = false }, "지우기", { vm.deletePhoto(ph); confirm = false }, "취소") {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit) }
    }
}

// ---------------- 점 목록 ----------------

@Composable
fun ListScreen(vm: MainVm, open: (Long) -> Unit) {
    val pts by vm.points.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var withPhotos by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("측점 목록", style = Ty.big, color = T.ink)
            Text("${vm.project} · ${pts.size}점", style = Ty.body, color = T.inkSub)
        }
        item {
            Card {
                Text("내보내기", style = Ty.title, color = T.ink)
                LabeledSwitch("사진도 함께 (ZIP 안 photos 폴더)", withPhotos) { withPhotos = it }
                BigButton(if (vm.exportBusy) "만드는 중…" else "CSV · GeoJSON 내보내기", { vm.export(withPhotos) }, enabled = !vm.exportBusy && pts.isNotEmpty())
                vm.lastExport?.let { r ->
                    Text("${r.pointCount}점, 사진 ${r.photoCount}장\n폴더: ${r.dir.absolutePath}", style = Ty.small, color = T.inkSub, modifier = Modifier.padding(top = 6.dp))
                    OutlineBigButton("공유 / 보내기", { vm.shareIntent()?.let { ctx.startActivity(it) } }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
        if (pts.isEmpty()) item { CenterText("아직 측량한 점이 없어요") }
        items(pts, key = { it.point.rowId }) { pw ->
            val p = pw.point
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.surface).clickable { open(p.rowId) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.pointId + if (p.code.isNotEmpty()) "  · ${p.code}" else "", style = Ty.bodyB, color = T.ink)
                    Text("N ${fmt(p.northing)}  E ${fmt(p.easting)}", style = Ty.small, color = T.inkSub)
                    Text("${Formats.fixLabel(p.fixQuality)} · ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(p.measuredAtUtcMs))}", style = Ty.small, color = if (p.fixQuality == 4) T.okText else T.floatText)
                }
                Text("📷 ${pw.photoCount}", style = Ty.bodyB, color = T.ink)
            }
        }
    }
}

@Composable
fun PointDetailScreen(vm: MainVm, rowId: Long, onBack: () -> Unit) {
    val p by vm.pointFlow(rowId).collectAsStateWithLifecycle(initialValue = null)
    val point = p
    var memo by remember(point?.rowId) { mutableStateOf(point?.memo ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    if (point == null) { Column(Modifier.padding(16.dp)) { OutlineBigButton("← 뒤로", onBack); CenterText("점을 찾을 수 없어요") }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { OutlineBigButton("← 뒤로", onBack); Spacer(Modifier.width(12.dp)); Text(point.pointId, style = Ty.big, color = T.ink) }
        Card {
            Row(Modifier.fillMaxWidth()) { FixBadge(FixQuality.fromCode(point.fixQuality)) }
            Spacer(Modifier.height(8.dp))
            Detail("N 북", fmt(point.northing)); Detail("E 동", fmt(point.easting))
            Detail("지면 높이(타원체)", fmt(point.groundEllipsoidalHeight)); Detail("APC 높이(타원체)", fmt(point.ellipsoidalHeight))
            Detail("지면 높이(정표고)", fmt(point.groundMsl)); Detail("APC 고도(MSL)", fmt(point.altitudeMsl))
            Detail("위경도", "${fmtPlain(point.lat, 8)}, ${fmtPlain(point.lon, 8)}")
            Detail("정밀도 σH/σV", "${fmtPlain(point.sigmaH, 3)} / ${fmtPlain(point.sigmaU, 3)} m")
            Detail("위성 · HDOP · 평균", "${point.satellites} · ${fmtPlain(point.hdop, 1)} · ${point.epochs}회")
            Detail("시각", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(point.measuredAtUtcMs)))
            Detail("기기", point.device)
        }
        Card {
            Text("안테나 높이", style = Ty.title, color = T.ink)
            Detail("총 높이(지면→APC)", fmtPlain(point.antennaHeight, 3) + " m")
            Detail("기준점", point.antennaRefPoint + " (${point.antennaInputMode})")
            Detail("입력 / 오프셋", "${fmtPlain(point.antennaInputValue, 3)} m / +${fmtPlain(point.antennaPcoV, 4)} m")
            if (point.antennaExtraPartsM != 0.0 || point.antennaBaseExtM != 0.0) Detail("부품 / 연장봉", "${fmtPlain(point.antennaExtraPartsM, 3)} / ${fmtPlain(point.antennaBaseExtM, 3)} m")
            if (point.antennaReceiverApplied) Text("수신기에서 이미 반영됨 — 앱은 보정하지 않았어요", style = Ty.small, color = T.floatText)
        }
        SectionTitle("사진")
        PhotoSection(vm, rowId)
        SectionTitle("메모 / 라벨")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CODES.forEach { c -> Chip(c, point.code == c) { vm.saveMemo(rowId, memo, if (point.code == c) "" else c) } } }
        OutlinedTextField(memo, { memo = it }, label = { Text("간단 메모") }, modifier = Modifier.fillMaxWidth(), textStyle = Ty.body)
        BigButton("메모 저장", { vm.saveMemo(rowId, memo, point.code); vm.toast = "저장했어요" })
        OutlineBigButton("이 점 지우기", { confirmDelete = true }, Modifier.fillMaxWidth())
    }
    if (confirmDelete) BigDialog("${point.pointId} 을(를) 지울까요?", { confirmDelete = false }, "지우기", { vm.deletePoint(rowId); confirmDelete = false; onBack() }, "취소") {
        Text("사진도 함께 지워져요. 되돌릴 수 없어요.", style = Ty.body)
    }
}

@Composable
private fun Detail(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = Ty.small, color = T.inkSub, modifier = Modifier.weight(0.45f)); Text(v, style = Ty.body, color = T.ink, modifier = Modifier.weight(0.55f))
    }
}
