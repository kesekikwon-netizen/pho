package kr.archsurvey.pg.ar

import android.app.Activity
import android.content.Context
import android.os.Build
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session

/** ARCore 는 선택: 7.0(API 24) 미만이거나 Google Play AR 서비스가 없으면 사진·표적·내보내기만 */
object ArSupport {
    enum class State { OK, NEED_INSTALL, UNSUPPORTED }

    fun state(ctx: Context): State = stateRaw(ctx).also { kr.archsurvey.pg.diag.PgLog.i("arcore availability=$it sdk=${Build.VERSION.SDK_INT}") }
    private fun stateRaw(ctx: Context): State {
        if (Build.VERSION.SDK_INT < 24) return State.UNSUPPORTED
        return try {
            val a = ArCoreApk.getInstance().checkAvailability(ctx)
            when {
                a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> State.OK
                a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> State.NEED_INSTALL
                a.isTransient -> State.NEED_INSTALL
                else -> State.UNSUPPORTED
            }
        } catch (_: Throwable) { State.UNSUPPORTED }
    }

    /** Play 스토어 설치 화면으로(사용자가 누름). true = 지금 설치됨 */
    fun requestInstall(act: Activity): Boolean = try {
        ArCoreApk.getInstance().requestInstall(act, true) == ArCoreApk.InstallStatus.INSTALLED
    } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.w("arcore install not completed: ${t.javaClass.simpleName}"); false }

    class Opened(val session: Session, val depth: Boolean, val rawDepth: Boolean, val imageW: Int, val imageH: Int)

    fun open(ctx: Context): Opened? = try {
        val s = Session(ctx)
        // 사진용 CPU 영상: 지원하는 것 중 가장 큰 해상도(폰마다 다름, 보통 1920×1080 ~ 1920×1440)
        val cfgs: List<CameraConfig> = s.getSupportedCameraConfigs(CameraConfigFilter(s))
        cfgs.maxByOrNull { it.imageSize.width * it.imageSize.height }?.let { s.cameraConfig = it }
        val c = Config(s)
        val raw = s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        val auto = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        // AUTOMATIC 이어도 raw 깊이·신뢰도를 함께 받을 수 있음(설계도 11). AUTOMATIC 안 되면 RAW 만
        c.depthMode = when { auto -> Config.DepthMode.AUTOMATIC; raw -> Config.DepthMode.RAW_DEPTH_ONLY; else -> Config.DepthMode.DISABLED }
        // 0.0.12: 손떨림 보정(EIS) 끔 — 화상 휨이 내부표정을 흔듦
        runCatching { c.imageStabilizationMode = Config.ImageStabilizationMode.OFF }
        c.focusMode = Config.FocusMode.FIXED // 초점 고정(사진측량 내부표정 일정하게)
        c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        c.lightEstimationMode = Config.LightEstimationMode.DISABLED
        s.configure(c)
        val sz = s.cameraConfig.imageSize
        kr.archsurvey.pg.diag.PgLog.i("arcore session open depthMode=${c.depthMode} raw=$raw auto=$auto image=${sz.width}x${sz.height} configs=${cfgs.size}")
        Opened(s, raw || auto, raw, sz.width, sz.height)
    } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.e("arcore session open failed", t); null }

    /** 0.0.12: ARCore 카메라 센서 유효 폭·높이(mm, 활성 영역). 모르면 null */
    fun sensorSizeMm(ctx: Context, s: Session): Pair<Double, Double>? = runCatching {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val ch = cm.getCameraCharacteristics(s.cameraConfig.cameraId)
        val phys = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
        val pix = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return@runCatching null
        val act = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@runCatching null
        val w = phys.width.toDouble() * act.width() / pix.width; val h = phys.height.toDouble() * act.height() / pix.height
        kr.archsurvey.pg.diag.PgLog.i("sensor %.2fx%.2f mm".format(w, h))
        w to h
    }.getOrNull()
}
