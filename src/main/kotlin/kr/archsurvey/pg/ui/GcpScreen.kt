package kr.archsurvey.pg.ui

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
import kr.archsurvey.pg.data.GcpTxt
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.tag.TargetNaming

/** 좌표 입력표: 표적마다 동E · 북N · 높이. 사진에서 잡힌 표적은 미리 줄이 있음. TXT 불러오기도 됨 */
@Composable
fun GcpScreen(store: Store, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ses0 = remember { runCatching { store.load(name) }.getOrNull() }
    if (ses0 == null) { LaunchedEffect(Unit) { onBack() }; return }
    var ses by remember { mutableStateOf(ses0) }
    val seen = remember { ses0.stills.flatMap { s -> s.tags.map { TargetNaming.targetNo(it.id) } }.groupingBy { it }.eachCount() }
    var extra by remember { mutableStateOf(listOf<Int>()) }
    var msg by remember { mutableStateOf<String?>(null) }
    // 칸 글자(입력 중 그대로 보관)
    val txt = remember { mutableStateMapOf<String, String>().apply {
        ses0.gcps.forEach { g -> put("${g.target}e", f(g.e)); put("${g.target}n", f(g.n)); put("${g.target}h", f(g.h)) } } }
    fun saveAll() {
        val rows = (seen.keys + ses.gcps.map { it.target } + extra).distinct().sorted()
        val g = rows.mapNotNull { no ->
            val e = GcpCheck.num(txt["${no}e"] ?: ""); val n = GcpCheck.num(txt["${no}n"] ?: ""); val h = GcpCheck.num(txt["${no}h"] ?: "")
            if (e != null && n != null && h != null) Gcp(no, e, n, h) else null
        }
        ses = ses.copy(gcps = g); store.save(ses)
        kr.archsurvey.pg.diag.PgLog.i("gcp table saved n=${g.size} epsg=${ses.epsg}") // 좌표값 자체는 로그에 안 남김
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s -> GcpTxt.report(s.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8)) } }
                .getOrElse { GcpTxt.Report(emptyList(), 0, 0, "파일을 열지 못했어요") }
            r.gcps.forEach { g -> txt["${g.target}e"] = f(g.e); txt["${g.target}n"] = f(g.n); txt["${g.target}h"] = f(g.h) }
            extra = (extra + r.gcps.map { it.target }).distinct()
            saveAll(); msg = r.message ?: "${r.gcps.size}점 불러왔어요"
            kr.archsurvey.pg.diag.PgLog.i("gcp import n=${r.gcps.size} skipped=${r.skipped}")
        }
    }
    BackHandler { saveAll(); onBack() }
    val rows = (seen.keys + ses.gcps.map { it.target } + extra).distinct().sorted()
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineBtn("‹ 저장") { saveAll(); onBack() }
            Spacer(Modifier.width(8.dp))
            Txt("$name · 좌표", bold = true, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("좌표계", size = 15.sp, color = P.sub)
            for (e in listOf(5185, 5186, 5187, 5188)) Choice(when (e) { 5185 -> "서부"; 5186 -> "중부"; 5187 -> "동부"; else -> "동해" } + " $e", ses.epsg == e) { ses = ses.copy(epsg = e); store.save(ses) }
        }
        Txt("동E · 북N · 높이(m). 칸을 비우면 그 표적은 좌표 없음", size = 14.sp, color = P.sub)
        msg?.let { Txt(it, bold = true, color = P.fix) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rows.isEmpty()) item { Txt("사진에서 잡힌 표적이 없어요 · 「＋ 표적」 으로 줄을 만드세요", color = P.sub) }
            items(rows, key = { it }) { no ->
                val e = GcpCheck.num(txt["${no}e"] ?: ""); val n = GcpCheck.num(txt["${no}n"] ?: ""); val h = GcpCheck.num(txt["${no}h"] ?: "")
                Column(Modifier.fillMaxWidth().background(P.card).padding(8.dp)) {
                    Txt("${TargetNaming.name(TargetNaming.tagId(no))} · 사진 ${seen[no] ?: 0}장", bold = true, size = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((k, lab) in listOf("e" to "동 E", "n" to "북 N", "h" to "높이")) {
                            val key = "$no$k"; val v = txt[key] ?: ""
                            OutlinedTextField(v, { txt[key] = it }, Modifier.weight(if (k == "h") 0.8f else 1.2f), label = { Text(lab) }, singleLine = true,
                                textStyle = TextStyle(fontSize = 17.sp), isError = v.isNotEmpty() && GcpCheck.num(v) == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
                        }
                    }
                    if (e != null && n != null && h != null) GcpCheck.warn(e, n, h, ses.epsg)?.let { Txt(it, size = 14.sp, color = P.error) }
                    else if (listOf(e, n, h).any { it != null }) Txt("세 칸을 모두 채워야 쓰여요", size = 14.sp, color = P.float)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineBtn("＋ 표적") { extra = extra + ((rows.maxOrNull() ?: 0) + 1) }
            OutlineBtn("TXT 불러오기") { pick.launch(arrayOf("text/*", "application/octet-stream")) }
            Spacer(Modifier.weight(1f))
            PrimaryBtn("저장", Modifier.width(140.dp).heightIn(min = 60.dp), color = P.fix) { saveAll(); msg = "저장했어요 · 좌표 ${ses.gcps.size}점" }
        }
    }
}

private fun f(v: Double) = java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).toPlainString()
