package kr.archsurvey.engine

import android.content.Intent

/** Google Sign-In Activity 결과를 ViewModel 수명으로 받기 위한 다리. */
class DriveAuthBridge {
    interface Launcher { fun launch(intent: Intent) }
    var launcher: Launcher? = null
    private var cb: ((Intent?) -> Unit)? = null

    fun launch(intent: Intent, onResult: (Intent?) -> Unit) {
        val l = launcher
        if (l == null) { onResult(null); return }
        cb = onResult
        l.launch(intent)
    }

    fun deliver(data: Intent?) {
        val c = cb; cb = null; c?.invoke(data)
    }
}
