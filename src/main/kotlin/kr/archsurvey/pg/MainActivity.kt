package kr.archsurvey.pg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it; kr.archsurvey.pg.diag.PgLog.i("camera permission result=$it") }

    override fun onResume() {
        super.onResume()
        val now = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (now != camOk) kr.archsurvey.pg.diag.PgLog.i("camera permission changed -> $now")
        camOk = now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        kr.archsurvey.pg.diag.PgLog.init(this)
        Sunlight.load(this); Minimal.load(this); Minimal.on = false // 0.0.7: 미니멀 모드 없앰
        runCatching { kr.archsurvey.pg.export.OneTap.mode = kr.archsurvey.pg.export.OneTap.Mode.valueOf(getSharedPreferences("pg_prefs", MODE_PRIVATE).getString("mark_mode", "AUTO")!!) }
        runCatching { kr.archsurvey.pg.tag.TargetNaming.style = kr.archsurvey.pg.tag.TargetNaming.Style.valueOf(getSharedPreferences("pg_prefs", MODE_PRIVATE).getString("name_style", "TARGET")!!) }
        camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = P.primary, background = P.bg, surface = P.bg, onSurface = P.ink)) {
                // 화면: home / cap:<작업> / res:<작업>
                var route by rememberSaveable { mutableStateOf("home") }
                var gcpBack by rememberSaveable { mutableStateOf("home") }
                val name = route.substringAfter(':', "")
                when {
                    route == "home" -> HomeScreen(store, onNew = { ar -> route = if (ar) "new:ar" else "new:" }, onOpen = { s -> route = "res:${s.name}" }, onGcp = { s -> gcpBack = "home"; route = "gcp:${s.name}" })
                    route.startsWith("new:") -> NewJobScreen(store, arcore = route == "new:ar", onCreated = { s ->
                        if (!camOk) askCam.launch(Manifest.permission.CAMERA)
                        route = "cap:${s.name}"
                    }, onCancel = { route = "home" })
                    route.startsWith("gcp:") -> GcpScreen(store, name, onBack = { route = gcpBack })
                    route.startsWith("cap:") -> {
                        if (!camOk) androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(24.dp)) {
                            kr.archsurvey.pg.ui.Txt("촬영하려면 카메라 허용이 필요해요", bold = true)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                            kr.archsurvey.pg.ui.PrimaryBtn("카메라 허용") { askCam.launch(Manifest.permission.CAMERA) }
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            kr.archsurvey.pg.ui.OutlineBtn("처음으로") { route = "home" }
                        }
                        else if (store.load(name).arcore && ArSupport.state(this) == ArSupport.State.OK)
                            CaptureScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" }, onGcp = { gcpBack = "cap:$name"; route = "gcp:$name" },
                                onFallback = { runCatching { store.save(store.load(name).copy(arcore = false)) }; route = "home"; route = "cap:$name" })
                        else FallbackScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" })
                    }
                    route.startsWith("res:") -> ResultScreen(store, name, onBack = { route = "home" }, onShootMore = { route = "cap:$name" }, onGcp = { gcpBack = "res:$name"; route = "gcp:$name" })
                }
            }
        }
    }
}
