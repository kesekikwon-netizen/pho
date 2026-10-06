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
    private val driveSignIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        vm.driveAuth.deliver(r.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.platform.permissionRequester = { perms, cb ->
            if (perms.isEmpty()) cb(true) else { permCallback = cb; permLauncher.launch(perms) }
        }
        // 0.1.30 T24: 지도 「폰 위치(참고)」 점용 위치 권한(측점에는 안 씀). ViewModel init 시점에는 requester 가 아직 null
        vm.ensurePhoneLocationPermission()
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
        vm.driveAuth.launcher = object : kr.archsurvey.engine.DriveAuthBridge.Launcher {
            override fun launch(intent: android.content.Intent) { driveSignIn.launch(intent) }
        }
        applyKeepScreen((application as App).settings.keepScreenOn)
        vm.onKeepScreen = { applyKeepScreen(it) }
        setContent {
            SurveyTheme { SurveyMapScreen(vm.controller, batteryPercent()) }
        }
        // 0.1.12: 직전 작업이 있으면 그 작업(설정 포함)을 연 측량 화면에서 시작 — 「새 작업」을 누르기 전까지 항상 이어서.
        // 처음 설치면 0.1.11 처럼 홈. 회전 등으로 다시 만들어질 땐 보던 화면 유지. 수신기는 지난 성공 설정으로 자동 연결(설정에서 끌 수 있음)
        if (savedInstanceState == null) vm.restoreVoice() // 0.1.28: 음성 저장 켜짐 복원(권한·방식 다시 확인)
        if (savedInstanceState == null) {
            vm.controller.homeOpen = !vm.resumeJob
            if (vm.resumeJob) vm.controller.ui.toast(vm.resumeNotice())
            vm.autoConnectOnLaunch()
        }
    }

    override fun onResume() { super.onResume(); vm.controller.tilt?.resume(); vm.controller.voice?.setForeground(true) }
    override fun onPause() { vm.controller.voice?.setForeground(false); vm.controller.tilt?.pause(); vm.onAppBackground(); super.onPause() }

    /** 0.1.28 T14: 이어폰 재생/일시정지 1번 = 저장 요청(음성 저장이 켜져 있고 측량 화면이 앞일 때만). 확인 대기 중이면 취소 */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val media = keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        if (media) {
            if (event.repeatCount > 0) return vm.controller.voice?.enabled == true // 길게 누름 반복은 무시
            if (vm.controller.voice?.onMediaKey() == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

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
