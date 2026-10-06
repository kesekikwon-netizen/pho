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
import kr.archsurvey.core.FixQuality
import kr.archsurvey.core.GgaMessage
import kr.archsurvey.core.GnssSnapshot
import kr.archsurvey.core.GstMessage
import java.util.Locale
import kotlin.math.sqrt

/**
 * 폰 내장 GNSS(LocationManager). 수신기 미연결 때 지도 따라가기·저정밀 측점용.
 * 품질은 항상 SINGLE + [GnssSnapshot.fromPhone]=true → StatusJudge 가 「폰GPS」주의(RTK 아님).
 * 위치 권한이 있을 때만 갱신한다(권한 요청은 호출 측).
 */
class PhoneGpsSource(private val ctx: Context) : GnssSource {
    override val link = MutableStateFlow(LinkStatus(LinkState.IDLE, message = "폰 GPS 대기"))
    override val gnss = MutableStateFlow(GnssSnapshot(fromPhone = true))
    override fun clockMs(): Long = SystemClock.elapsedRealtime()

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

    /** 권한이 있으면 수신 시작. 없으면 IDLE 유지(호출 측에서 권한 요청). */
    @SuppressLint("MissingPermission")
    fun ensureStarted() {
        if (!hasPermission()) {
            link.value = LinkStatus(LinkState.IDLE, message = "폰 위치 권한이 필요해요")
            return
        }
        if (listening) return
        val mgr = lm ?: run {
            link.value = LinkStatus(LinkState.IDLE, message = "이 기기에서 위치를 쓸 수 없어요")
            return
        }
        listening = true
        link.value = LinkStatus(LinkState.CONNECTED_NO_DATA, message = "폰 GPS 잡는 중")
        try {
            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER))
                mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { push(it) }
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { push(it) }
        } catch (e: SecurityException) {
            listening = false
            link.value = LinkStatus(LinkState.IDLE, message = "폰 위치 권한이 필요해요")
        } catch (e: Exception) {
            listening = false
            link.value = LinkStatus(LinkState.IDLE, message = "폰 GPS를 시작하지 못했어요")
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { lm?.removeUpdates(listener) }
        link.value = LinkStatus(LinkState.IDLE, message = "폰 GPS 중지")
        // 마지막 좌표는 남겨 두지 않음(수신기 절환 때 혼동 방지)
        gnss.value = GnssSnapshot(fromPhone = true)
    }

    override fun stop() = stopListening()

    private fun push(loc: Location) {
        val now = clockMs()
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble().coerceAtLeast(1.0) else 8.0
        val sig = acc / sqrt(2.0)
        val alt = if (loc.hasAltitude()) loc.altitude else null
        val gga = GgaMessage(
            raw = "PHONE",
            utcTime = String.format(Locale.US, "%06.0f", (System.currentTimeMillis() % 86_400_000L) / 1000.0),
            lat = loc.latitude,
            lon = loc.longitude,
            quality = FixQuality.SINGLE,
            satellites = 0,
            hdop = null,
            altitudeMsl = alt,
            geoidSeparation = null,
            correctionAgeSec = null,
            baseStationId = null,
        )
        val gst = GstMessage("PHONE", rmsRange = acc, sigmaLat = sig, sigmaLon = sig, sigmaAlt = if (loc.hasAltitude()) acc else null)
        gnss.value = GnssSnapshot(gga = gga, gst = gst, lastGgaAtMs = now, lastAnyAtMs = now, fromPhone = true)
        link.value = LinkStatus(LinkState.STREAMING, message = "폰 GPS")
    }

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
