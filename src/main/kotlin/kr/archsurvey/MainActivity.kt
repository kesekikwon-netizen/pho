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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.platform.permissionRequester = { perms, cb ->
            if (perms.isEmpty()) cb(true) else { permCallback = cb; permLauncher.launch(perms) }
        }
        // 측점 화면은 밖에서 오래 쓰므로 화면을 켜 둔다
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 종료 두 단계(측량 앱과 같음): 연결은 컨트롤러가 끊고, 여기서 앱 작업을 완전히 닫는다
        vm.controller.onExitApp = { exitRequested = true; finishAndRemoveTask() }
        setContent { SurveyTheme { GcpRoot(vm.controller, vm.env) } }
        vm.autoConnectOnLaunch()
    }

    private var exitRequested = false

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
