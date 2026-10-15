package kr.archsurvey.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 폰 내장 위치(LocationManager) — 0.1.30 T24 부터 **지도 「폰 위치(참고)」 점 표시 전용**.
 * [GnssSource] 가 아니다: 측점 취득·평균·주의 승인·저장·내보내기로 가는 길이 없다(SurveyController 의 source 는 수신기 엔진뿐).
 * 위치 권한이 있을 때만 갱신한다(권한 요청은 호출 측).
 */
class PhoneGpsSource(private val ctx: Context) {
    /** 최신 폰 위치(없으면 null). 시각은 [clockMs] 와 같은 시계(elapsedRealtime) */
    val fix = MutableStateFlow<PhoneFix?>(null)
    fun clockMs(): Long = SystemClock.elapsedRealtime()

    @Volatile private var listening = false
    private val lm: LocationManager? get() =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = push(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 권한이 있으면 수신 시작. 없으면 아무것도 안 함(호출 측에서 권한 요청) */
    @SuppressLint("MissingPermission")
    fun ensureStarted() {
        if (!hasPermission() || listening) return
        val mgr = lm ?: return
        listening = true
        try {
            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER))
                mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { push(it) }
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { push(it) }
        } catch (e: Exception) {
            listening = false
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { lm?.removeUpdates(listener) }
        fix.value = null
    }

    private fun push(loc: Location) {
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble().coerceAtLeast(1.0) else 8.0
        fix.value = PhoneFix(loc.latitude, loc.longitude, acc, clockMs())
    }

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
