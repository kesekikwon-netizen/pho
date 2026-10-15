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

/** 한 번 눌러 내보내기 상태(작업 카드·결과 화면 공용). 0.0.9: 저장 + 경로 크게 표시, 공유는 선택(M8·버린 안 「공유 창 먼저」) */
class OneTapState(private val ctx: Context, private val store: Store, private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf<String?>(null); private set
    var msg by mutableStateOf<String?>(null)
    /** 마지막으로 저장한 PC 경로(결과 화면에 크게) */
    var path by mutableStateOf<String?>(null); private set
    var lastZip: File? = null; private set
    fun run(name: String) {
        if (busy != null) return
        busy = name; msg = null; path = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val ses = store.load(name); val sf = store.files
                    kr.archsurvey.pg.export.ExportFolder.blockReason(ses)?.let { throw IllegalStateException(it) } // M5
                    val need = sf.photoBytes(ses) * 3; val free = minOf(sf.free(), ctx.cacheDir.usableSpace)
                    if (!kr.archsurvey.pg.diag.StoragePolicy.canExport(free, need)) throw java.io.IOException(kr.archsurvey.pg.diag.Msg.spaceNeed(need - free)) // S8
                    val e = LiveEngine(ses); val l = java.util.concurrent.CountDownLatch(1); e.post { l.countDown() }; l.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    val scale = if (ses.gcps.isEmpty()) e.scale?.let { "축척: 표적 ${"%.0f".format((ses.tagSizeM ?: LiveEngine.DEFAULT_TAG_M) * 100)}cm (${it.source})\r\n" } else null; e.close()
                    val work = File(ctx.cacheDir, "export/work"); work.mkdirs()
                    val res = OneTap.build(ses, sf.photos(name), work, scale)
                    val p = store.publish(res.zip, name)
                    kr.archsurvey.pg.diag.PgLog.i("export one-tap ok session=$name photos=${res.photos} targets=${res.targets} coords=${res.withCoords} bytes=${res.bytes}")
                    res to p
                }
            }
            r.onSuccess { (res, p) ->
                path = p; lastZip = res.zip
                msg = "저장됨 · 사진 ${res.photos}장" + if (res.withCoords) " · 좌표 포함" else " · 좌표 없음"
            }.onFailure {
                kr.archsurvey.pg.diag.PgLog.e("export one-tap failed session=$name", it)
                msg = when (it) { is IllegalStateException -> it.message; is java.io.IOException -> it.message?.takeIf { m -> m.contains("필요") } ?: kr.archsurvey.pg.diag.Msg.EXPORT_FAIL; else -> kr.archsurvey.pg.diag.Msg.EXPORT_FAIL }
            }
            busy = null
        }
    }
    /** 선택: 공유 창(PC 로 보낼 앱 고르기) */
    fun share() {
        val z = lastZip ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", z)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { kr.archsurvey.pg.diag.PgLog.e("share sheet failed", it) }
    }
}

@Composable
fun rememberOneTap(store: Store): OneTapState {
    val ctx = androidx.compose.ui.platform.LocalContext.current; val scope = rememberCoroutineScope()
    return remember { OneTapState(ctx, store, scope) }
}

