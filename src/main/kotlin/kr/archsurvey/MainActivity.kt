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

    private val docOpenMany = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.docs.deliverOpenMany(uris) }

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
            override fun openMany() = docOpenMany.launch(arrayOf("*/*"))
        }
        applyKeepScreen((application as App).settings.keepScreenOn)
        vm.onKeepScreen = { applyKeepScreen(it) }
        setContent {
            SurveyTheme { SurveyMapScreen(vm.controller, batteryPercent()) }
        }
        // 0.1.12: 직전 작업이 있으면 그 작업(설정 포함)을 연 측량 화면에서 시작 — 「새 작업」을 누르기 전까지 항상 이어서.
        // 처음 설치면 0.1.11 처럼 홈. 회전 등으로 다시 만들어질 땐 보던 화면 유지. 수신기는 지난 성공 설정으로 자동 연결(설정에서 끌 수 있음)
        if (savedInstanceState == null) {
            vm.controller.homeOpen = !vm.resumeJob
            if (vm.resumeJob) vm.controller.ui.toast(vm.resumeNotice())
            vm.autoConnectOnLaunch()
        }
    }

    override fun onResume() { super.onResume(); vm.controller.tilt?.resume() }
    override fun onPause() { vm.controller.tilt?.pause(); super.onPause() }

    private fun applyKeepScreen(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun batteryPercent(): Int? {
        val i: Intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
