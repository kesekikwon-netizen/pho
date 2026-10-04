package kr.archsurvey

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kr.archsurvey.ui.SurveyMapScreen
import kr.archsurvey.ui.SurveyTheme
import kr.archsurvey.ui.SurveyViewModel

class MainActivity : ComponentActivity() {
    private val vm: SurveyViewModel by viewModels()

    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permCallback?.invoke(r.values.all { it }); permCallback = null
    }

    private val cameraLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> vm.media.deliverCamera(ok) }
    private val pickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> vm.media.deliverPicker(uris) }
    private val micLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> vm.media.deliverMic(ok) }

    private val docCreate = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri -> vm.docs.deliverCreate(uri) }
    private val docOpen = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> vm.docs.deliverOpen(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.platform.permissionRequester = { perms, cb ->
            if (perms.isEmpty()) cb(true) else { permCallback = cb; permLauncher.launch(perms) }
        }
        vm.media.launcher = object : kr.archsurvey.engine.AndroidMediaPlatform.Launcher {
            override fun launchCamera(uri: android.net.Uri) = cameraLauncher.launch(uri)
            override fun launchPicker() = pickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            override fun requestMic() = micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        vm.docs.launcher = object : kr.archsurvey.engine.DocBridge.Launcher {
            override fun create(suggestedName: String) = docCreate.launch(suggestedName)
            override fun open() = docOpen.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        setContent {
            SurveyTheme { SurveyMapScreen(vm.controller, batteryPercent()) }
        }
    }

    private fun batteryPercent(): Int? {
        val i: Intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
