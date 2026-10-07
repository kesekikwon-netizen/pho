package kr.archsurvey

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        // 0.1.31 T27: 종료 확인 창 「예」 → 연결은 컨트롤러가 끊고, 여기서 앱 작업을 완전히 닫는다(최근 앱 목록에서도 빠짐)
        vm.controller.onExitApp = { exitRequested = true; finishAndRemoveTask() }
        val firstLaunch = savedInstanceState == null
        setContent {
            SurveyTheme {
                // 0.1.31: 처음 켤 때만 3초 시작 모션(그동안 작업 복원·자동 연결은 뒤에서 그대로 진행). 회전 등 재생성 땐 건너뜀
                var splash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(firstLaunch) }
                if (splash) kr.archsurvey.ui.SplashScreen(kr.archsurvey.ui.versionShort(kr.archsurvey.ui.rememberAppVersionLabel())) { splash = false }
                else SurveyMapScreen(vm.controller, batteryPercent())
            }
        }
        // 0.1.12: 직전 작업이 있으면 그 작업(설정 포함)을 연 측량 화면에서 시작 — 「새 작업」을 누르기 전까지 항상 이어서.
        // 처음 설치면 0.1.11 처럼 홈. 회전 등으로 다시 만들어질 땐 보던 화면 유지. 수신기는 지난 성공 설정으로 자동 연결(설정에서 끌 수 있음)
        if (savedInstanceState == null) vm.restoreVoice() // 0.1.28: 음성 저장 켜짐 복원(권한·방식 다시 확인)
        if (savedInstanceState == null) {
            vm.controller.homeOpen = !vm.resumeJob
            if (vm.resumeJob) vm.controller.ui.toast(vm.resumeNotice())
        }
        // 0.1.32: 화면 모델이 새로 만들어질 때마다 한 번(처음 실행 + 시스템이 앱을 정리한 뒤 되살릴 때). 회전은 같은 모델이라 다시 안 함.
        // 예전엔 savedInstanceState == null 일 때만 → 최근 앱에서 되살아난 화면은 자동 연결 없이 폰 위치만 보였음
        vm.autoConnectOnLaunch()
    }

    /** 0.1.31 T27: 종료 창에서 「예」를 눌러 닫는 중 */
    private var exitRequested = false

    override fun onDestroy() {
        super.onDestroy()
        // 0.1.31 T27 완전 종료: 연결 서비스 멈춤(ACTION_STOP → 알림 제거·소켓 닫기)이 먼저 처리되도록 잠깐 뒤 프로세스를 끝낸다.
        // 측점은 저장 때 이미 디스크(Room)에 있다. 화면 작업(viewModelScope)은 Activity 가 끝나며 어차피 취소된다
        if (exitRequested && isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_KILL_DELAY_MS)
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

    companion object { const val EXIT_KILL_DELAY_MS = 700L }

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
