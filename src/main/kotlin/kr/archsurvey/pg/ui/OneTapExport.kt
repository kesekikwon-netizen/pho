package kr.archsurvey.pg.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.export.OneTap
import kr.archsurvey.pg.live.LiveEngine
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 한 번 눌러 내보내기 상태(작업 카드·결과 화면 공용) */
class OneTapState(private val ctx: Context, private val store: Store, private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf<String?>(null); private set
    var msg by mutableStateOf<String?>(null)
    fun run(name: String) {
        if (busy != null) return
        busy = name; msg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val ses = store.load(name); val sf = store.files
                    if (!kr.archsurvey.pg.diag.StoragePolicy.canExport(minOf(sf.free(), ctx.cacheDir.usableSpace), sf.photoBytes(ses) * 3)) throw java.io.IOException(kr.archsurvey.pg.diag.Msg.EXPORT_SPACE)
                    // 축척·로컬 표적 위치는 계산 엔진에서(앱 표시일 때만 씀)
                    val e = LiveEngine(ses); val l = java.util.concurrent.CountDownLatch(1); e.post { l.countDown() }; l.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    val local = runCatching { e.localTagsTxt() }.getOrNull(); val scale = e.scale?.let { "축척 ${it.s}  (${it.source})\r\n" }; e.close()
                    val work = File(ctx.cacheDir, "export/work"); work.mkdirs()
                    val res = OneTap.build(ses, sf.photos(name), work, local, scale)
                    val path = store.publish(res.zip) // 다운로드/사진측량/<zip>
                    kr.archsurvey.pg.diag.PgLog.i("export one-tap ok session=$name mode=${res.used} photos=${res.photos} targets=${res.targets} bytes=${res.bytes} published=$path style=${kr.archsurvey.pg.tag.TargetNaming.style}")
                    res to path
                }
            }
            r.onSuccess { (res, path) ->
                msg = "저장됨: $path · 사진 ${res.photos}장 · ${res.used.label}"
                runCatching {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", res.zip)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "iTwin으로 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { kr.archsurvey.pg.diag.PgLog.e("share sheet failed", it) }
            }.onFailure {
                kr.archsurvey.pg.diag.PgLog.e("export one-tap failed session=$name", it)
                msg = if (it.message == kr.archsurvey.pg.diag.Msg.EXPORT_SPACE) it.message else kr.archsurvey.pg.diag.Msg.EXPORT_FAIL
            }
            busy = null
        }
    }
}

@Composable
fun rememberOneTap(store: Store): OneTapState {
    val ctx = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope()
    return remember { OneTapState(ctx, store, scope) }
}

/** ⋯ 고급: 표시 방식 선택(선택만) */
@Composable
fun MarkModeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cur by remember { mutableStateOf(OneTap.mode) }
    Txt("내보내기 방식", bold = true)
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        for (m in OneTap.Mode.values()) Choice(m.label, cur == m) {
            OneTap.mode = m; cur = m
            ctx.getSharedPreferences("pg_prefs", Context.MODE_PRIVATE).edit().putString("mark_mode", m.name).apply()
            kr.archsurvey.pg.diag.PgLog.i("mark mode -> ${m.name}")
        }
    }
    Txt("자동 = 표적 잡혔으면 앱 표시, 없으면 iTwin이 표적 잡기", size = 14.sp, color = P.sub)
}
