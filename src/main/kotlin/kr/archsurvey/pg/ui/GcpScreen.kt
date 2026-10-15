package kr.archsurvey.pg.ui

import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.data.GcpCheck
import kr.archsurvey.pg.data.GcpImport
import kr.archsurvey.ui.releaseTap
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.tag.TargetNaming

/**
 * 좌표 입력표(0.0.9 M4·M10·M11·S1): 주 버튼 「기준점 파일 불러오기」(TXT·XML·zip, 시작 위치 Documents/발굴조사),
 * 숫자 칸(동E·북N·높이), 아래 오른쪽 「저장하고 닫기」 하나, 바꾼 뒤 뒤로 → 저장/버리기. 좌표계는 파일에서 정해진 값 한 줄(탭하면 바꿈).
 */
@Composable
fun GcpScreen(store: Store, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ses0 = remember { runCatching { store.load(name) }.getOrNull() }
    if (ses0 == null) { LaunchedEffect(Unit) { onBack() }; return }
    var ses by remember { mutableStateOf(ses0) }
    val seen = remember { ses0.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.groupingBy { it }.eachCount() }
    var extra by remember { mutableStateOf(listOf<Int>()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var warn by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var srsOpen by remember { mutableStateOf(false) }
    val r10Stats = remember { mutableStateMapOf<Int, kr.archsurvey.pg.r10.Measured>() }
    var measuring by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val txt = remember { mutableStateMapOf<String, String>().apply {
        ses0.gcps.forEach { g -> put("${g.target}e", f(g.e)); put("${g.target}n", f(g.n)); put("${g.target}h", f(g.h)) } } }
    fun rowsNow() = (seen.keys + ses.gcps.map { it.target } + extra).distinct().sorted()
    fun collect(): List<Gcp> = rowsNow().mapNotNull { no ->
        val e = GcpCheck.num(txt["${no}e"] ?: ""); val n = GcpCheck.num(txt["${no}n"] ?: ""); val h = GcpCheck.num(txt["${no}h"] ?: "")
        if (e != null && n != null && h != null) {
            val old = ses.gcps.firstOrNull { it.target == no }; val m = r10Stats[no]
            when { // 0.0.14: R10 으로 잰 값의 표본 SD·보고 σ 를 같이(값을 손으로 바꾸면 버림)
                m != null && f(m.e) == f(e) && f(m.n) == f(n) && f(m.h) == f(h) -> Gcp(no, e, n, h, m.sdH, m.sdU, m.sigHMean, m.sigUMean, m.epochs)
                old != null && old.e == e && old.n == n && old.h == h -> old
                else -> Gcp(no, e, n, h)
            }
        } else null
    }
    fun saveAll() {
        val g = collect()
        ses = ses.copy(gcps = g); store.save(ses); dirty = false
        kr.archsurvey.pg.diag.PgLog.i("gcp table saved n=${g.size} epsg=${ses.epsg}") // 좌표값은 로그에 안 남김
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val fname = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: (uri.lastPathSegment ?: "")
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s -> GcpImport.read(fname, s.readBytes().take(20_000_000).toByteArray()) } }
                .getOrElse { GcpImport.Result(emptyList(), null, "파일을 열지 못했어요") }
            r.gcps.forEach { g -> txt["${g.target}e"] = f(g.e); txt["${g.target}n"] = f(g.n); txt["${g.target}h"] = f(g.h) }
            extra = (extra + r.gcps.map { it.target }).distinct()
            r.epsg?.let { ses = ses.copy(epsg = it) }
            if (r.gcps.isNotEmpty()) saveAll()
            msg = r.message + (r.epsg?.let { " · " + GcpImport.epsgLine(it) } ?: "")
            warn = GcpImport.mismatch(r.gcps, seen.keys)
            kr.archsurvey.pg.diag.PgLog.i("gcp import n=${r.gcps.size} epsg=${r.epsg} mismatch=${warn != null}")
        }
    }
    fun openPicker() {
        // 시작 위치: Documents/발굴조사 (DocumentsContract 초기 위치, 26+)
        pick.launch(arrayOf("text/*", "application/xml", "text/xml", "application/zip", "application/octet-stream"))
    }
    fun back() { if (dirty) askLeave = true else onBack() }
    BackHandler { back() }
    val rows = rowsNow()
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).releaseTap("뒤로") { back() }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
            Txt("$name · 좌표", bold = true, modifier = Modifier.weight(1f), maxLines = 1)
        }
        PrimaryBtn("기준점 파일 불러오기", Modifier.fillMaxWidth()) { openPicker() }
        Txt("사진 기준점·발굴 측량 앱에서 보낸 zip·XML·TXT", size = 14.sp, color = P.sub)
        Box(Modifier.heightIn(min = 44.dp).releaseTap("좌표계 바꾸기") { srsOpen = !srsOpen }, contentAlignment = Alignment.CenterStart) {
            Txt("좌표계: ${GcpImport.beltName(ses.epsg)} EPSG:${ses.epsg}  ›", size = 16.sp, bold = true)
        }
        if (srsOpen) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in listOf(5185, 5186, 5187, 5188)) Choice("${GcpImport.beltName(e)} $e", ses.epsg == e) { ses = ses.copy(epsg = e); store.save(ses); srsOpen = false }
        }
        msg?.let { Txt(it, bold = true, color = P.fix) }
        warn?.let { Txt(it, bold = true, color = P.error) } // M11
        // 0.0.12: 불러온·입력한 좌표로 맞춤 점검(정보만) — RMSE·최대 잔차
        val fitSum = remember(ses.gcps) { kr.archsurvey.pg.export.Poses.fit(ses)?.let { kr.archsurvey.pg.export.SiteFit.summary(it) } }
        fitSum?.let { Txt("맞춤 점검: " + kr.archsurvey.pg.export.SiteFit.line(it), size = 15.sp, bold = true, color = P.sub) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rows.isEmpty()) item { Txt("사진에서 잡힌 표적이 없어요 · 파일을 불러오거나 「＋ 표적」", color = P.sub) }
            items(rows, key = { it }) { no ->
                val e = GcpCheck.num(txt["${no}e"] ?: ""); val n = GcpCheck.num(txt["${no}n"] ?: ""); val h = GcpCheck.num(txt["${no}h"] ?: "")
                Column(Modifier.fillMaxWidth().background(P.card).padding(8.dp)) {
                    Txt("${TargetNaming.name(TargetNaming.tagId(no))} · 사진 ${seen[no] ?: 0}장", bold = true, size = 16.sp, color = if (seen[no] == null) P.error else P.ink)
                    for ((k, lab) in listOf("e" to "동 E (m)", "n" to "북 N (m)", "h" to "높이 (m)")) {
                        val key = "$no$k"; val v = txt[key] ?: ""
                        OutlinedTextField(v, { txt[key] = it; dirty = true }, Modifier.fillMaxWidth(), label = { Text(lab) }, singleLine = true,
                            textStyle = TextStyle(fontSize = 18.sp), isError = v.isNotEmpty() && GcpCheck.num(v) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
                    }
                    // 0.0.14: R10 이 연결됐을 때만 보임(없으면 지금처럼 입력·불러오기)
                    if (kr.archsurvey.pg.r10.R10Hub.connected()) {
                        val busy = measuring == no
                        OutlineBtn(if (busy) "재는 중 ${(progress * 100).toInt()}%" else "R10으로 재기", Modifier.fillMaxWidth(), enabled = measuring == null) {
                            val hub = kr.archsurvey.pg.r10.R10Hub.current ?: return@OutlineBtn
                            measuring = no; progress = 0f; warn = null
                            scope.launch {
                                val r = runCatching { hub.measure(ses.epsg) { progress = it } }.getOrElse { kr.archsurvey.pg.r10.MeasureResult.Aborted("재기 실패 · ${it.message}") }
                                measuring = null
                                when (r) {
                                    is kr.archsurvey.pg.r10.MeasureResult.Ok -> { r10Stats[no] = r.m; txt["${no}e"] = f(r.m.e); txt["${no}n"] = f(r.m.n); txt["${no}h"] = f(r.m.h); dirty = true
                                        msg = "표적 #$no R10 ${r.m.epochs}회 평균" + String.format(java.util.Locale.US, " · 흩어짐 %s · 보고 σ %s", r.m.sdH?.let { "%.1f cm".format(java.util.Locale.US, it * 100) } ?: "-", r.m.sigHMean?.let { "%.1f cm".format(java.util.Locale.US, it * 100) } ?: "정보 없음")
                                        kr.archsurvey.pg.diag.PgLog.i("gcp r10 Target#$no epochs=${r.m.epochs} sdH=${r.m.sdH} sdU=${r.m.sdU} sigH=${r.m.sigHMean} sigU=${r.m.sigUMean}") }
                                    is kr.archsurvey.pg.r10.MeasureResult.Aborted -> { warn = "표적 #$no: " + r.reasonKo; kr.archsurvey.pg.diag.PgLog.i("gcp r10 Target#$no aborted") }
                                }
                            }
                        }
                    }
                    if (e != null && n != null && h != null) GcpCheck.warn(e, n, h, ses.epsg)?.let { Txt(it, size = 14.sp, color = P.error) }
                    else if (listOf(e, n, h).any { it != null }) Txt("세 칸을 모두 채워야 쓰여요", size = 14.sp, color = P.float)
                }
            }
            item {
                OutlineBtn("＋ 표적") { extra = extra + ((rows.maxOrNull() ?: 0) + 1) }
                Spacer(Modifier.height(10.dp))
                // S1: 좌표 없을 때 축척 = 표적 한 변
                Txt("좌표 없을 때 축척 · 표적 검은 네모 한 변", size = 15.sp, color = P.sub)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cur = ses.tagSizeM ?: kr.archsurvey.pg.live.LiveEngine.DEFAULT_TAG_M
                    for (v in listOf(0.10, 0.15, 0.20, 0.30)) Choice("${(v * 100).toInt()}cm", kotlin.math.abs(cur - v) < 1e-6) { ses = ses.copy(tagSizeM = v); store.save(ses) }
                }
            }
        }
        // M10: 아래 오른쪽 하나
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            PrimaryBtn("저장하고 닫기", Modifier.width(220.dp).heightIn(min = 64.dp)) { saveAll(); onBack() }
        }
    }
    if (askLeave) androidx.compose.material3.AlertDialog(onDismissRequest = { askLeave = false },
        title = { Text("바꾼 좌표를 저장할까요?") },
        confirmButton = { androidx.compose.material3.TextButton({ askLeave = false; saveAll(); onBack() }) { Text("저장") } },
        dismissButton = { androidx.compose.material3.TextButton({ askLeave = false; onBack() }) { Text("버리기") } })
}

private fun f(v: Double) = java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).toPlainString()
