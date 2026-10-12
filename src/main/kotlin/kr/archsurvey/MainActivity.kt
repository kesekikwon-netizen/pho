package kr.archsurvey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kr.archsurvey.gcp.ui.GcpRoot
import kr.archsurvey.gcp.ui.GcpViewModel
import kr.archsurvey.ui.SurveyTheme

class MainActivity : ComponentActivity() {
    private val vm: GcpViewModel by viewModels()

    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permCallback?.invoke(r.values.all { it }); permCallback = null
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
        // 측점 화면은 밖에서 오래 쓰므로 화면을 켜 둔다
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 종료 두 단계(측량 앱과 같음): 연결은 컨트롤러가 끊고, 여기서 앱 작업을 완전히 닫는다
        vm.controller.onExitApp = { exitCompletely() }
        setContent { SurveyTheme { GcpRoot(vm.controller, vm.env) } }
        vm.autoConnectOnLaunch()
    }

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

    // 0.1.4: 뒤로 가면 위치를 못 받던 자동 연결은 멈추고, 앞으로 오면 다시(두 앱이 같은 수신기를 번갈아 잡던 문제)
    override fun onStart() { super.onStart(); runCatching { vm.connection.onAppForeground() } }
    override fun onStop() { runCatching { vm.connection.onAppBackground() }; super.onStop() }
    override fun onDestroy() {
        super.onDestroy()
        if (exitRequested && isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, EXIT_KILL_DELAY_MS)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.repeatCount == 0 && vm.controller.onVolumeKey()) return true
        return super.onKeyDown(keyCode, event)
    }

    companion object { const val EXIT_KILL_DELAY_MS = 700L }
}
