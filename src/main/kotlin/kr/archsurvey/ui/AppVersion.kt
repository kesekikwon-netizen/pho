package kr.archsurvey.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 설치된 앱의 versionName / versionCode 를 읽어 "버전 0.1.3 (빌드 4)" 형태로 돌려준다. 읽지 못하면 "버전 확인 불가". */
fun appVersionLabel(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    val name = pi.versionName
    if (name.isNullOrBlank()) "버전 확인 불가" else "버전 $name (빌드 $code)"
}.getOrDefault("버전 확인 불가")

@Composable
fun rememberAppVersionLabel(): String { val ctx = LocalContext.current; return remember(ctx) { appVersionLabel(ctx) } }
