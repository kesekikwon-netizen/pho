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

    /** 0.1.42 「프로그램 종료」 뒤 처음 실행: 저장된 화면 상태를 버리고 시작 화면부터 */
    private val cleanExit by lazy { kr.archsurvey.ui.CleanExit(object : kr.archsurvey.ui.CleanExitStore {
        override var exited: Boolean
            get() = (application as App).settings.cleanExit
            set(v) { (application as App).settings.cleanExit = v }
    }) }
    private var freshAfterExit = false

    override fun onCreate(savedInstanceStateRaw: Bundle?) {
        freshAfterExit = cleanExit.consumeOnLaunch()
        val savedInstanceState = if (freshAfterExit) null else savedInstanceStateRaw
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
        vm.controller.onExitApp = { exitCompletely() }
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
            // 0.1.35: 오늘 폴 높이를 아직 확인하지 않았으면 「오늘 준비」(홈)부터, 했으면 마지막 작업 지도로 바로
            // 0.1.42: 「프로그램 종료」 뒤 실행이면 지난 지도 화면이 아니라 홈부터(작업은 그대로 열려 있어 「이어서 측량」 한 번)
            vm.controller.homeOpen = !vm.resumeJob || !vm.controller.dig.poleConfirmedToday || freshAfterExit
            if (vm.resumeJob) vm.controller.ui.toast(vm.resumeNotice())
            // 0.1.39: 처음 설치(작업이 없음)면 새 작업 화면부터 — 고르기만으로 만들고 바로 측량
            else vm.controller.jobs?.openNew()
        }
        // 0.1.32: 화면 모델이 새로 만들어질 때마다 한 번(처음 실행 + 시스템이 앱을 정리한 뒤 되살릴 때). 회전은 같은 모델이라 다시 안 함.
        // 예전엔 savedInstanceState == null 일 때만 → 최근 앱에서 되살아난 화면은 자동 연결 없이 폰 위치만 보였음
        vm.autoConnectOnLaunch()
    }

    /** 0.1.31 T27: 종료 창에서 「예」를 눌러 닫는 중 */
    private var exitRequested = false

    /**
     * 0.1.42 완전 종료([kr.archsurvey.ui.ExitSteps.ORDER]): 연결(서비스·알림·자동 재연결·앱 사이 잠금)은 컨트롤러가 먼저 끊었다 →
     * 다음 실행은 시작 화면부터 표시 → 작업 닫기(최근 앱에서 빠짐) → 남은 알림 지우기 → 잠깐 뒤(서비스 정지 처리) 프로세스 종료.
     * onDestroy 를 기다리지 않는다(삼성에서 늦게 오거나 안 와서 프로세스가 남고, 아이콘을 누르면 예전 화면이 보였음)
     */
    private fun exitCompletely() {
        exitRequested = true
        // 연결 서비스가 혹시 남아 있으면 바로 멈춤(새로 띄우지 않는 stopService — 포그라운드 알림도 같이 사라짐)
        runCatching { stopService(android.content.Intent(this, kr.archsurvey.engine.SurveyConnectionService::class.java)) }
        runCatching { cleanExit.mark() }
        finishAndRemoveTask()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(kr.archsurvey.engine.SurveyConnectionService.NOTI_ID) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_KILL_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 0.1.31 T27 완전 종료: 연결 서비스 멈춤(ACTION_STOP → 알림 제거·소켓 닫기)이 먼저 처리되도록 잠깐 뒤 프로세스를 끝낸다.
        // 측점은 저장 때 이미 디스크(Room)에 있다. 화면 작업(viewModelScope)은 Activity 가 끝나며 어차피 취소된다
        if (exitRequested && isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_KILL_DELAY_MS)
        }
    }

    // 0.1.42: 뒤로 가면 위치를 못 받던 자동 연결은 멈추고, 앞으로 오면 다시(두 앱이 같은 수신기를 번갈아 잡던 문제)
    override fun onStart() { super.onStart(); runCatching { vm.controller.connection?.onAppForeground() } }
    override fun onStop() { runCatching { vm.controller.connection?.onAppBackground() }; super.onStop() }
    override fun onResume() { super.onResume(); resumed = true; vm.controller.tilt?.resume(); vm.controller.voice?.setForeground(true) }
    override fun onPause() { resumed = false; vm.controller.voice?.setForeground(false); vm.controller.tilt?.pause(); vm.onAppBackground(); super.onPause() }

    /** 0.1.28 T14: 이어폰 재생/일시정지 1번 = 저장 요청(음성 저장이 켜져 있고 측량 화면이 앞일 때만). 확인 대기 중이면 취소 */
    private var resumed = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // 0.1.35 X2: 볼륨 아래 키 = 측점(설정에서 켰을 때만 · 화면 켜짐 + 앱이 앞 + 측량 지도일 때만 · 길게 눌러 반복 무시). 실기기 미확인
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && vm.controller.dig.volumeKey) {
            if (kr.archsurvey.dig.VolumeKeyGate.shouldMeasure(true, resumed && hasWindowFocus(), vm.controller.visibleRoute(), event.repeatCount)) { vm.controller.onMeasure(); return true }
            if (resumed && vm.controller.visibleRoute() == "survey") return true   // 반복 누름은 소리 크기를 바꾸지 않고 먹는다
        }
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
