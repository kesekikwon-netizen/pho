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
            // 0.0.12: 최대 잔차·표적별 잔차(정보만)
            val sf = kr.archsurvey.pg.export.SiteFit.summary(f)
            Txt(kr.archsurvey.pg.export.SiteFit.line(sf), color = P.sub)
            sf.rows.forEach { r -> Txt(kr.archsurvey.pg.export.SiteFit.rowLine(r), size = 14.sp, color = if (r.target == sf.maxTarget && sf.maxCm >= 5) P.float else P.sub) }
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
        if (s.gcps.isEmpty()) Txt("축척: 표적 ${"%.0f".format((s.tagSizeM ?: LiveEngine.DEFAULT_TAG_M) * 100)}cm" + if (engine.scale == null) " (표적이 잡히면 적용)" else "", size = 15.sp, color = P.sub) // S1
        Spacer(Modifier.height(8.dp))
        // 보조: 좌표 입력 · 더 찍기
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineBtn("좌표 입력 (${s.gcps.size}점)") { store.save(engine.session); onGcp() }
            OutlineBtn("더 찍기") { onShootMore() }
        }
        val one = rememberOneTap(store)
        one.msg?.let { Spacer(Modifier.height(8.dp)); Txt(it, bold = true, color = if (one.path != null) P.fix else P.error) }
        // M8: PC 경로 크게
        one.path?.let { p ->
            Spacer(Modifier.height(6.dp))
            Txt(p, size = 20.sp, bold = true)
            Txt("PC 에 USB 로 연결하면 이 폴더에 있어요", size = 14.sp, color = P.sub)
            Spacer(Modifier.height(6.dp))
            OutlineBtn("다른 앱으로 공유") { one.share() }
        }
        val prefs = remember { ctx.getSharedPreferences("pg_prefs", android.content.Context.MODE_PRIVATE) }
        var legacy by remember { mutableStateOf(!prefs.getBoolean("legacy_note_seen", false)) }
        if (legacy) { Txt("새 저장 위치: Documents › 발굴조사 › <작업 이름> › 사진측량 (예전 Download/사진측량 파일은 그대로 있어요)", size = 14.sp, color = P.sub); LaunchedEffect(Unit) { prefs.edit().putBoolean("legacy_note_seen", true).apply() } }
        Spacer(Modifier.weight(1f))
        // M15: 주 버튼 하나(오른쪽 아래, 주황)
        val why = ExportFolder.blockReason(s)
        why?.let { Txt(it, color = P.error, modifier = Modifier.align(Alignment.End)) } // M5
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PrimaryBtn(if (one.busy != null) "보내는 중…" else "iTwin으로 보내기", Modifier.width(280.dp).heightIn(min = 72.dp), enabled = why == null && one.busy == null) {
                store.save(engine.session); one.run(name)
            }
        }
    }
}
