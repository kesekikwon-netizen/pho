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
import kr.archsurvey.pg.ui.P
import kr.archsurvey.pg.ui.ResultScreen
import kr.archsurvey.ui.Minimal
import kr.archsurvey.ui.Sunlight

class MainActivity : ComponentActivity() {
    private var camOk by mutableStateOf(false)
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Sunlight.load(this); Minimal.load(this)
        camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = P.primary, background = P.bg, surface = P.bg, onSurface = P.ink)) {
                // 화면: home / cap:<작업> / res:<작업>
                var route by rememberSaveable { mutableStateOf("home") }
                val name = route.substringAfter(':', "")
                when {
                    route == "home" -> HomeScreen(store, onNew = { s ->
                        if (!camOk) askCam.launch(Manifest.permission.CAMERA)
                        route = "cap:${s.name}"
                    }, onOpen = { s -> route = "res:${s.name}" })
                    route.startsWith("cap:") -> {
                        if (!camOk) androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(24.dp)) {
                            kr.archsurvey.pg.ui.Txt("촬영하려면 카메라 허용이 필요해요", bold = true)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                            kr.archsurvey.pg.ui.PrimaryBtn("카메라 허용") { askCam.launch(Manifest.permission.CAMERA) }
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                            kr.archsurvey.pg.ui.OutlineBtn("처음으로") { route = "home" }
                        }
                        else if (store.load(name).arcore && ArSupport.state(this) == ArSupport.State.OK)
                            CaptureScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" })
                        else FallbackScreen(store, name, onBack = { route = "home" }, onResult = { route = "res:$name" })
                    }
                    route.startsWith("res:") -> ResultScreen(store, name, onBack = { route = "home" }, onShootMore = { route = "cap:$name" })
                }
            }
        }
    }
}
