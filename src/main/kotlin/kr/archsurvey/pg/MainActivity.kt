package kr.archsurvey.pg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import kr.archsurvey.pg.ar.ArSupport
import kr.archsurvey.pg.data.Store
import kr.archsurvey.pg.ui.CaptureScreen
import kr.archsurvey.pg.ui.FallbackScreen
import kr.archsurvey.pg.ui.HomeScreen
import kr.archsurvey.pg.ui.NewJobScreen
import kr.archsurvey.pg.ui.GcpScreen
import kr.archsurvey.pg.ui.P
import kr.archsurvey.pg.ui.ResultScreen
import kr.archsurvey.ui.Minimal
import kr.archsurvey.ui.Sunlight

class MainActivity : ComponentActivity() {
    private var camOk by mutableStateOf(false)
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { kr.archsurvey.pg.diag.PgLog.i("storage permission result=$it") }
    // 0.0.14 R10(선택): 블루투스 권한은 사용자가 「R10 연결」을 누를 때만 묻는다
    private var permCb: ((Boolean) -> Unit)? = null
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permCb?.invoke(r.values.all { it }); permCb = null }
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it; kr.archsurvey.pg.diag.PgLog.i("camera permission result=$it") }

    // 0.0.14: R10 을 켠 경우만(만들지 않음)
    override fun onStart() { super.onStart(); runCatching { kr.archsurvey.pg.r10.R10Hub.current?.cc?.onAppForeground() } }
    override fun onStop() { runCatching { kr.archsurvey.pg.r10.R10Hub.current?.cc?.onAppBackground() }; super.onStop() }

    override fun onResume() {
        super.onResume()
        val now = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (now != camOk) kr.archsurvey.pg.diag.PgLog.i("camera permission changed -> $now")
        camOk = now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 0.0.14: 내보낸 사진 EXIF GPS = 레버암 고친 카메라 위치(높이는 해발, R10 FIX 사진만)
        kr.archsurvey.pg.export.ExportFolder.exifGps = { f, c, t -> kr.archsurvey.pg.r10.GeoExif.write(f, c.lat, c.lon, c.h, t, true, c) }
        enableEdgeToEdge()
        kr.archsurvey.pg.diag.PgLog.init(this)
        Sunlight.load(this); Minimal.load(this); Minimal.on = false // 0.0.7: 미니멀 모드 없앰
        camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        // Android 9 이하: Documents/발굴조사 에 쓰려면 저장 권한(10+ 는 필요 없음)
        if (android.os.Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            runCatching { askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = P.primary, background = P.bg, surface = P.bg, onSurface = P.ink)) {
                // 화면: home / cap:<작업> / res:<작업>
                var route by rememberSaveable { mutableStateOf("home") }
                var gcpBack by rememberSaveable { mutableStateOf("home") }
                val name = route.substringAfter(':', "")
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                when {
                    route == "home" -> HomeScreen(store, onNew = { ar -> route = if (ar) "new:ar" else "new:" }, onOpen = { s -> route = "res:${s.name}" }, onGcp = { s -> gcpBack = "home"; route = "gcp:${s.name}" }, onResume = { s -> if (!camOk) askCam.launch(Manifest.permission.CAMERA); route = "cap:${s.name}" })
                    route.startsWith("new:") -> NewJobScreen(store, arcore = route == "new:ar", onCreated = { s ->
                        if (!camOk) askCam.launch(Manifest.permission.CAMERA)
                        route = "cap:${s.name}"
                    }, onCancel = { route = "home" }, onResume = { n -> if (!camOk) askCam.launch(Manifest.permission.CAMERA); route = "cap:$n" })
                    route.startsWith("gcp:") -> GcpScreen(store, name, onBack = { route = gcpBack })
                    route.startsWith("cap:") -> {
                        if (!camOk) androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(24.dp)) {
                            kr.archsurvey.pg.ui.Txt("촬영하려면 카메라 허용이 필요해요", bold = true)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                            kr.archsurvey.pg.ui.PrimaryBtn("카메라 허용") { askCam.launch(Manifest.permission.CAMERA) }
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            // S7: 「다시 묻지 않음」이면 허용 창이 안 뜸 → 앱 설정 열기
                            kr.archsurvey.pg.ui.OutlineBtn("앱 설정 열기 (권한 › 카메라)") { runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) } }
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            kr.archsurvey.pg.ui.OutlineBtn("처음으로") { route = "home" }
                        }
                        else if (store.load(name).arcore && ArSupport.state(this@MainActivity) == ArSupport.State.OK)
                            CaptureScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" }, onGcp = { gcpBack = "cap:$name"; route = "gcp:$name" },
                                onFallback = { runCatching { store.save(store.load(name).copy(arcore = false)) }; route = "home"; route = "cap:$name" })
                        else FallbackScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" })
                    }
                    route.startsWith("res:") -> ResultScreen(store, name, onBack = { route = "home" }, onShootMore = { route = "cap:$name" }, onGcp = { gcpBack = "res:$name"; route = "gcp:$name" })
                }
                // 0.0.14 R10 창·연결 화면(사용자가 열었을 때만)
                if (kr.archsurvey.pg.r10.R10Hub.sheetOpen) kr.archsurvey.pg.r10.R10Sheet { kr.archsurvey.pg.r10.R10Hub.sheetOpen = false }
                kr.archsurvey.pg.r10.R10Hub.current?.let { h ->
                    if (h.platform.permissionRequester == null) h.platform.permissionRequester = { perms, cb -> if (perms.isEmpty()) cb(true) else { permCb = cb; askPerms.launch(perms) } }
                    val g by h.app.engine.gnss.collectAsState()
                    kr.archsurvey.ui.ConnectionHost(h.cc, g.gga?.quality ?: kr.archsurvey.core.FixQuality.NONE, g.gga?.satellites ?: 0)
                    kr.archsurvey.ui.FeedbackOverlays(h.cc.ui)
                }
                }
            }
        }
    }
}
