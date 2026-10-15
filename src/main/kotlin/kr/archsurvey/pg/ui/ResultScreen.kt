package kr.archsurvey.pg.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.pg.data.GcpTxt
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.export.BlocksExchange
import kr.archsurvey.pg.export.ExportFolder
import kr.archsurvey.pg.live.LiveEngine
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.ui.releaseTap
import java.io.File

/** 결과: 표적별 사진 수 · (좌표 있으면) 잔차 · iTwin 내보내기 */
@Composable
fun ResultScreen(store: Store, name: String, onBack: () -> Unit, onShootMore: () -> Unit, onGcp: () -> Unit = {}) {
    val ctx = LocalContext.current; val view = LocalView.current
    val loadedR = remember { runCatching { store.load(name) }.getOrNull() }
    if (loadedR == null) { LaunchedEffect(Unit) { onBack() }; return }
    val engine = remember { LiveEngine(loadedR) }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save: (kr.archsurvey.pg.data.Session) -> Unit = { store.save(it) }
    DisposableEffect(Unit) { engine.onChange = { view.post { tick++ } }; onDispose { engine.onChange = null; engine.close() } }
    BackHandler { onBack() }
    val pickGcp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { s -> GcpTxt.report(s.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8)) } }
                .getOrElse { GcpTxt.Report(emptyList(), 0, 0, "파일을 열지 못했어요") }
            msg = r.message
            if (r.gcps.isNotEmpty()) engine.setGcps(r.gcps, save)
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val s = engine.session
    val fit = engine.fit
    val counts = engine.tagCounts
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).releaseTap("뒤로") { onBack() }, contentAlignment = Alignment.Center) { Txt("‹", size = 28.sp, bold = true) }
            Txt("$name · 결과", size = FieldIvory.title, bold = true)
        }
        Txt("사진 ${s.stills.size}장 · 표적 ${counts.size}개 · ${if (s.gcps.isNotEmpty()) "좌표 ${s.gcps.size}점 (EPSG:${s.epsg})" else "로컬 좌표"}", color = P.sub)
        fit?.let { f ->
            Spacer(Modifier.height(8.dp))
            Txt("맞춤 오차 RMSE ${"%.1f".format(f.rmse * 100)} cm" + (f.looRmse?.let { " · 검사점 오차 ${"%.1f".format(it * 100)} cm" } ?: " · 검사점은 표적 4개부터"), bold = true,
                color = if ((f.looRmse ?: f.rmse) < 0.05) P.fix else if ((f.looRmse ?: f.rmse) < 0.15) P.float else P.error)
            Txt("휴대폰 미리보기 값이에요. 최종 값은 iTwin 에서 나와요.", size = 14.sp, color = P.sub)
        }
        if (fit == null && s.gcps.isNotEmpty()) kr.archsurvey.pg.diag.FitCheck.check(s.gcps, engine.tagPos).second?.let { Txt(it, color = P.float) }
        if (counts.isEmpty() && s.stills.isNotEmpty()) Txt("사진에서 표적을 못 찾았어요 · 표적이 사진 안에 다 들어오게 찍어 주세요", color = P.float)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(counts.keys.sorted()) { no ->
                val n = counts[no] ?: 0
                val r = fit?.residuals?.get(no); val loo = fit?.looResiduals?.get(no)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Target #$no", bold = true, modifier = Modifier.width(120.dp))
                    Txt("사진 ${n}장", color = if (n >= 3) P.fix else if (n == 2) P.float else P.error, modifier = Modifier.width(90.dp))
                    Txt(when {
                        loo != null -> "검사 ${"%.1f".format(loo.norm() * 100)} cm"
                        r != null -> "잔차 ${"%.1f".format(r.norm() * 100)} cm"
                        s.gcps.any { it.target == no } -> "좌표 있음"
                        else -> "좌표 없음"
                    }, color = P.sub)
                }
            }
        }
        msg?.let { Txt(it, bold = true, color = P.fix) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryBtn("좌표 입력 (${s.gcps.size}점)") { store.save(engine.session); onGcp() }
            OutlineBtn("더 찍기") { onShootMore() }
            OutlineBtn("로그 공유") { runCatching { kr.archsurvey.pg.diag.PgLog.share(ctx) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("log share failed", it) } }
        }
        Spacer(Modifier.height(8.dp))
        val one = rememberOneTap(store)
        var adv by remember { mutableStateOf(false) }
        OutlineBtn(if (adv) "⋯ 고급 닫기" else "⋯ 고급") { adv = !adv }
        if (adv) {
            NameStyleRow()
            Spacer(Modifier.height(6.dp))
            MarkModeRow()
        }
        one.msg?.let { Spacer(Modifier.height(8.dp)); Txt(it, bold = true, color = P.fix) }
        Spacer(Modifier.weight(1f))
        // 오른손 엄지: 큰 버튼 하나
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryBtn(if (one.busy != null) "보내는 중…" else "iTwin으로 보내기", Modifier.width(280.dp).heightIn(min = 72.dp), color = P.fix) {
                store.save(engine.session); one.run(name)
            }
        }
    }
}
