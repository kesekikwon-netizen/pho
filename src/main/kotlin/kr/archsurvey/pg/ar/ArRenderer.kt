package kr.archsurvey.pg.ar

import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kr.archsurvey.pg.geo.V3
import kr.archsurvey.pg.live.LiveEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.acos
import kotlin.math.abs

/** 화면 위에 그릴 것(표적 칩, 다음 촬영 화살표) — 화면 px */
data class Overlay(
    val tracking: Boolean, val slow: Boolean,
    val tags: List<Triple<Int, Float, Float>>, // 번호, x, y
    val target: FloatArray?, /** 목표가 화면 밖/뒤 */ val targetOff: Boolean,
    val depthMode: String,
    /** 추적 실패 이유(TrackingFailureReason 이름) */
    val failure: String? = null,
)

/** 사진 한 장(원 JPG + 밝기 + 자세·내부표정) */
class Shot(val jpeg: ByteArray, val gray: ByteArray, val w: Int, val h: Int, val pose: DoubleArray, val fx: Double, val fy: Double, val cx: Double, val cy: Double)

/**
 * 0.0.5 실시간 3D (설계도 11):
 * - 프레임마다 ARCore 데이터는 한 번만 받고(같은 timestamp 반복 무시) 받은 이미지는 항상 close.
 * - 즉시 보이는 점 = 특징점(ID 중복 제거, 카메라 영상에 투영해 색). 깊이는 사진 찍을 때 + 0.5초마다(발열 시 늦춤) 2 cm 복셀에 누적.
 * - 깊이 NotYetAvailable 는 정상(건너뜀), 그 밖의 예외만 오류로 셈.
 * - 그리기: 배경(timestamp 0 이면 안 그림) → 깊이 점 → 특징점. 고정 크기 VBO 두 개, 바뀐 범위만 glBufferSubData.
 */
class ArRenderer(
    private val session: Session,
    private val engine: LiveEngine,
    private val depthOn: Boolean,
    private val onOverlay: (Overlay) -> Unit,
    private val onShot: (Shot) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var auto = true
    /** 발열 정책: 라이브 깊이 간격(0 = 사진 때만) */
    @Volatile var depthIntervalMs = 500L
    @Volatile var shotsAllowed = true
    @Volatile var paused = false
    @Volatile private var shotRequested = false
    @Volatile var viewW = 1; @Volatile var viewH = 1
    var rotation = 0
    var glVersion = 2
    private var geomChanged = true
    private var lastFailure: String? = null

    private var camTex = 0
    private var bgProg = 0; private var cloudProg = 0
    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadBuf = fbuf(quad); private val uvBuf = fbuf(FloatArray(8))
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16)

    private var lastDepthAt = 0L; private var lastFeatAt = 0L; private var lastShotAt = 0L; private var lastOverlayAt = 0L
    private var lastShotPos: FloatArray? = null; private var lastShotQ: FloatArray? = null
    private val speed = kr.archsurvey.pg.live.SpeedGate()
    private var lastFrameTs = 0L
    private var wasTracking = false

    // S1~S3 증거 카운터(5초마다 로그)
    private var depthOk = 0; private var depthNotYet = 0; private var depthErr = 0; private var lastDepthErr = ""
    private var imgErr = 0; private var rejectTooFast = 0; private var lastRejectLog = 0L
    private var frames = 0; private var fpsAt = 0L; private var lastTracking: TrackingState? = null
    private var glErrLogged = 0

    fun requestShot() { shotRequested = true; kr.archsurvey.pg.diag.PgLog.d("shutter pressed") }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.28f, 0.25f, 0.22f, 1f)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session.setCameraTextureName(camTex)
        bgProg = program(
            "attribute vec4 p; attribute vec2 uv; varying vec2 v; void main(){ gl_Position=p; v=uv; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES t; void main(){ gl_FragColor=texture2D(t,v); }")
        cloudProg = program(
            "uniform mat4 m; uniform float k; uniform float lo; uniform float hi; attribute vec3 p; attribute vec4 c; varying vec4 vc; void main(){ vec4 q=m*vec4(p,1.0); gl_Position=q; gl_PointSize=clamp(k/max(q.w,0.1),lo,hi); vc=c; }",
            "precision mediump float; varying vec4 vc; void main(){ vec2 d=gl_PointCoord-vec2(0.5); if(dot(d,d)>0.25) discard; gl_FragColor=vc; }")
        vbos.fill(0); vboCap.fill(0)
        engine.voxels.gpu.markAll(); engine.features.gpu.markAll()
        kr.archsurvey.pg.diag.PgLog.i("gl surface created es=$glVersion ${GLES20.glGetString(GLES20.GL_VERSION)} / ${GLES20.glGetString(GLES20.GL_RENDERER)} bg=$bgProg cloud=$cloudProg")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height); viewW = width; viewH = height
        session.setDisplayGeometry(rotation, width, height); geomChanged = true
        kr.archsurvey.pg.diag.PgLog.i("gl surface ${width}x$height rotation=$rotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        resetState()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame: Frame = try { session.update() } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.w("session.update failed: ${t.javaClass.simpleName} ${t.message}"); return }
        if (frame.hasDisplayGeometryChanged() || geomChanged) {
            geomChanged = false
            quadBuf.position(0); uvBuf.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadBuf, Coordinates2d.TEXTURE_NORMALIZED, uvBuf)
            quadBuf.position(0); uvBuf.position(0)
        }
        if (frame.timestamp == 0L) return // 카메라 첫 영상 전: 배경 그리지 않음
        drawBackground()
        val newFrame = frame.timestamp != lastFrameTs; lastFrameTs = frame.timestamp
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        lastFailure = if (tracking) null else camera.trackingFailureReason.name
        if (!tracking && shotRequested) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=not_tracking $lastFailure") }
        val now = SystemClock.uptimeMillis()
        frames++
        if (camera.trackingState != lastTracking) { kr.archsurvey.pg.diag.PgLog.i("tracking $lastTracking -> ${camera.trackingState} reason=${camera.trackingFailureReason}"); lastTracking = camera.trackingState }
        if (tracking && !wasTracking) speed.reset() // 추적 다시 잡은 직후 첫 프레임 무시
        wasTracking = tracking
        if (tracking) {
            camera.getViewMatrix(view, 0); camera.getProjectionMatrix(proj, 0, 0.05f, 30f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val pose = camera.pose
            val pos = pose.translation; val q = pose.rotationQuaternion
            if (newFrame && speed.feed(frame.timestamp, pos, q)) kr.archsurvey.pg.diag.PgLog.d("too fast move=%.2f m/s rot=%.0f deg/s".format(speed.move, speed.rot))
            val slow = speed.slow(frame.timestamp)

            val moved = lastShotPos == null || kr.archsurvey.pg.live.ArRendererMath.dist(lastShotPos!!, pos) > 0.25 || kr.archsurvey.pg.live.ArRendererMath.angDeg(lastShotQ!!, q) > 10
            val autoDue = !paused && auto && shotsAllowed && now - lastShotAt > 2000 && moved
            if (paused && shotRequested) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=paused") }
            if (shotRequested && !shotsAllowed) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=policy(storage/thermal)") }
            var wantShot = false
            if ((shotRequested || autoDue) && slow) {
                rejectTooFast++
                if (shotRequested || now - lastRejectLog > 1000) { lastRejectLog = now; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=too_fast move=%.2fm/s rot=%.0fdeg/s manual=$shotRequested (count=$rejectTooFast)".format(speed.move, speed.rot)) }
                shotRequested = false
            } else if (shotRequested || autoDue) wantShot = true

            if (newFrame && !paused) {
                val featDue = now - lastFeatAt >= 100
                val depthDue = depthOn && (wantShot || (depthIntervalMs > 0 && now - lastDepthAt >= depthIntervalMs))
                if (featDue || depthDue || wantShot) grab(frame, camera, featDue, depthDue, wantShot, now, pos, q)
            }
            drawPoints()
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR && glErrLogged < 20) { glErrLogged++; kr.archsurvey.pg.diag.PgLog.e("GL error 0x${Integer.toHexString(e)}") }
        }
        if (now - fpsAt >= 5000) { if (fpsAt > 0) logAccept(now, tracking, camera); fpsAt = now; frames = 0 }
        if (now - lastOverlayAt > 100) { lastOverlayAt = now; onOverlay(overlay(tracking, tracking && speed.slow(frame.timestamp))) }
    }

    private var lastFps = 0.0
    private fun logAccept(now: Long, tracking: Boolean, camera: com.google.ar.core.Camera) {
        lastFps = frames * 1000.0 / (now - fpsAt)
        val tries = depthOk + depthErr
        kr.archsurvey.pg.diag.PgLog.d("fps=%.1f tracking=${camera.trackingState} failure=${camera.trackingFailureReason} paused=$paused auto=$auto".format(lastFps))
        kr.archsurvey.pg.diag.PgLog.d("S1 depth ok=$depthOk notYet=$depthNotYet err=$depthErr(${lastDepthErr}) errPct=%.1f imgErr=$imgErr | motion move=%.3fm/s rot=%.1fdeg/s jumps=${speed.jumps} gaps=${speed.gaps} tooFastRejects=$rejectTooFast".format(if (tries > 0) depthErr * 100.0 / tries else 0.0, speed.move, speed.rot))
        kr.archsurvey.pg.diag.PgLog.d("S2 voxels=${engine.voxelCount} evicted=${engine.voxels.evicted} features=${engine.featureCount} depthBatches=${engine.depthBatches} onScreen=${onScreen()} fps=%.1f".format(lastFps))
        if (tracking) probeNext = true
    }

    /** 이번 프레임 데이터 한 번만: 카메라 영상(필요할 때), 특징점, 깊이, 사진. 모든 이미지는 finally 에서 close */
    private fun grab(frame: Frame, camera: com.google.ar.core.Camera, featDue: Boolean, depthDue: Boolean, wantShot: Boolean, now: Long, pos: FloatArray, q: FloatArray) {
        val img: Image? = try { frame.acquireCameraImage() } catch (e: com.google.ar.core.exceptions.NotYetAvailableException) { null } catch (t: Throwable) { imgErr++; null }
        try {
            val intr = camera.imageIntrinsics
            val poseM = FloatArray(16); camera.pose.toMatrix(poseM, 0)
            if (featDue) { lastFeatAt = now; featurePoints(frame, intr, poseM, img) }
            if (depthDue) { lastDepthAt = now; depth(frame, intr, poseM, img, wantShot) }
            if (wantShot) {
                val manual = shotRequested; shotRequested = false
                if (img != null && takeShot(img, camera)) { lastShotAt = now; lastShotPos = pos; lastShotQ = q; kr.archsurvey.pg.diag.PgLog.i("shot ACCEPT manual=$manual") }
                else kr.archsurvey.pg.diag.PgLog.w("shot REJECT reason=camera_image_unavailable manual=$manual")
            }
        } finally { img?.close() }
    }

    private fun featurePoints(frame: Frame, intr: com.google.ar.core.CameraIntrinsics, poseM: FloatArray, img: Image?) {
        try {
            frame.acquirePointCloud().use { pc ->
                val b = pc.points; val ids = pc.ids; val n = b.remaining() / 4
                if (n == 0) return
                val out = FloatArray(n * 3); val id = IntArray(n); var k = 0
                for (i in 0 until n) {
                    val x = b.get(); val y = b.get(); val z = b.get(); val c = b.get(); val pid = ids.get()
                    if (c < 0.1f) continue
                    out[3 * k] = x; out[3 * k + 1] = y; out[3 * k + 2] = z; id[k] = pid; k++
                }
                if (k == 0) return
                val rgb = img?.let { colorsForWorld(out, k, intr, poseM, it) }
                engine.addFeatures(id, out, rgb, k)
            }
        } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.w("pointcloud failed ${t.javaClass.simpleName}") }
    }

    private fun depth(frame: Frame, intr: com.google.ar.core.CameraIntrinsics, poseM: FloatArray, img: Image?, fromPhoto: Boolean) {
        val d: Image = try { frame.acquireRawDepthImage16Bits() }
            catch (e: com.google.ar.core.exceptions.NotYetAvailableException) { depthNotYet++; return }
            catch (t: Throwable) { depthErr++; lastDepthErr = t.javaClass.simpleName; return }
        try {
            val conf: Image? = try { frame.acquireRawDepthConfidenceImage() } catch (_: Throwable) { null }
            try {
                val w = d.width; val h = d.height
                val pl = d.planes[0]; val buf = pl.buffer.order(ByteOrder.LITTLE_ENDIAN); val rs = pl.rowStride; val ps = pl.pixelStride
                val cpl = conf?.planes?.get(0); val cbuf = cpl?.buffer; val crs = cpl?.rowStride ?: 0; val cps = cpl?.pixelStride ?: 1
                val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
                val sx = w.toFloat() / dims[0]; val sy = h.toFloat() / dims[1]
                val o = kr.archsurvey.pg.live.DepthSampler.sample(w, h, { x, y -> buf.getShort(y * rs + x * ps).toInt() and 0xffff },
                    if (cbuf != null) { x, y -> cbuf.get(y * crs + x * cps).toInt() and 0xff } else null,
                    f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy, poseM, grid = if (fromPhoto) 2 else 3)
                if (o.n == 0) { depthOk++; return }
                val rgb = FloatArray(o.n * 3)
                if (img != null) { val ys = YuvSampler(img); val ix = img.width.toFloat() / w; val iy = img.height.toFloat() / h
                    for (i in 0 until o.n) ys.rgb((o.u[i] * ix).toInt(), (o.v[i] * iy).toInt(), rgb, 3 * i) }
                else for (i in 0 until o.n) { rgb[3 * i] = 0.85f; rgb[3 * i + 1] = 0.92f; rgb[3 * i + 2] = 1f }
                engine.addDepth(o.xyz, rgb, o.conf, o.n, fromPhoto)
                depthOk++
            } finally { conf?.close() }
        } catch (t: Throwable) { depthErr++; lastDepthErr = t.javaClass.simpleName; kr.archsurvey.pg.diag.PgLog.w("depth sample failed ${t.javaClass.simpleName} ${t.message}") }
        finally { d.close() }
    }

    /** 세계 점 → 카메라 영상 화소 색 */
    private fun colorsForWorld(xyz: FloatArray, n: Int, intr: com.google.ar.core.CameraIntrinsics, poseM: FloatArray, img: Image): FloatArray {
        val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
        val sx = img.width.toFloat() / dims[0]; val sy = img.height.toFloat() / dims[1]
        val inv = FloatArray(16); Matrix.invertM(inv, 0, poseM, 0)
        val sampler = YuvSampler(img); val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = xyz[3 * i]; val y = xyz[3 * i + 1]; val z = xyz[3 * i + 2]
            val cx = inv[0] * x + inv[4] * y + inv[8] * z + inv[12]; val cy = inv[1] * x + inv[5] * y + inv[9] * z + inv[13]; val cz = inv[2] * x + inv[6] * y + inv[10] * z + inv[14]
            if (cz >= -0.05f) { out[3 * i] = 0.85f; out[3 * i + 1] = 0.92f; out[3 * i + 2] = 1f; continue }
            val u = (f[0] * (cx / -cz) + pp[0]) * sx; val v = (f[1] * (-cy / -cz) + pp[1]) * sy
            sampler.rgb(u.toInt(), v.toInt(), out, 3 * i)
        }
        return out
    }

    private fun resetState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glColorMask(true, true, true, true); GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE); GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        val p = GLES20.glGetAttribLocation(bgProg, "p"); val uv = GLES20.glGetAttribLocation(bgProg, "uv")
        quadBuf.position(0); uvBuf.position(0)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(uv)
        GLES20.glDepthMask(true)
    }

    // 0 = 깊이 복셀, 1 = 특징점. 고정 크기(상한) 버퍼를 한 번 만들고 바뀐 범위만 올림
    private val vbos = IntArray(2); private val vboCap = IntArray(2); private val vboCount = IntArray(2)
    private var probeNext = false; private var probePx = -1; private var probePy = -1; private var probeWant: FloatArray? = null; private var probeCam = IntArray(3)

    private fun upload(slot: Int, g: kr.archsurvey.pg.live.GpuSlots) {
        if (vbos[slot] == 0) { val t = IntArray(1); GLES20.glGenBuffers(1, t, 0); vbos[slot] = t[0] }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbos[slot])
        if (vboCap[slot] != g.cap) { GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, g.cap * 28, null, GLES20.GL_DYNAMIC_DRAW); vboCap[slot] = g.cap; g.markAll() }
        val d = g.takeDirty()
        if (d != null) { GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, d.first * 28, d.count * 28, d.buf); vboCount[slot] = d.total }
    }

    private fun drawPoints() {
        if (cloudProg == 0) return
        // 깊이 점 위치를 정해 두고(5초마다), 점 그리기 전 카메라 화소를 읽음 → S3 색 비교
        if (probeNext) { probeNext = false; pickProbe(); if (probePx >= 0) probeCam = readPx(probePx, probePy) }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true)
        GLES20.glUseProgram(cloudProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cloudProg, "m"), 1, false, vp, 0)
        val p = GLES20.glGetAttribLocation(cloudProg, "p"); val c = GLES20.glGetAttribLocation(cloudProg, "c")
        for ((slot, g) in listOf(0 to engine.voxels.gpu, 1 to engine.features.gpu)) {
            upload(slot, g)
            if (vboCount[slot] == 0) continue
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProg, "k"), viewH * (if (slot == 0) 0.006f else 0.010f))
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProg, "lo"), if (slot == 0) 2f else 4f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProg, "hi"), if (slot == 0) 10f else 14f)
            GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 28, 0)
            GLES20.glVertexAttribPointer(c, 4, GLES20.GL_FLOAT, false, 28, 12)
            GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(c)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vboCount[slot])
            GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(c)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (probePx >= 0 && probeWant != null) {
            val got = readPx(probePx, probePy); val w = probeWant!!.map { (it * 255).toInt() }
            val dCam = maxOf(abs(w[0] - probeCam[0]), abs(w[1] - probeCam[1]), abs(w[2] - probeCam[2]))
            val dDraw = maxOf(abs(w[0] - got[0]), abs(w[1] - got[1]), abs(w[2] - got[2]))
            kr.archsurvey.pg.diag.PgLog.d("S3 probe at=($probePx,$probePy) want=${w.joinToString(",")} cam=${probeCam.joinToString(",")} got=${got.joinToString(",")} dRGBcam=$dCam dRGBdrawn=$dDraw gl=0x${Integer.toHexString(GLES20.glGetError())}")
            probePx = -1; probeWant = null
        }
    }

    private fun readPx(x: Int, y: Int): IntArray {
        val bb = ByteBuffer.allocateDirect(4); GLES20.glReadPixels(x.coerceIn(0, viewW - 1), y.coerceIn(0, viewH - 1), 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        return intArrayOf(bb.get(0).toInt() and 255, bb.get(1).toInt() and 255, bb.get(2).toInt() and 255)
    }

    /** 깊이 복셀 중 화면 가운데에 가장 가까운 점 하나(관측 많은 것) */
    private fun pickProbe() {
        val g = engine.voxels.gpu; val n = g.size; if (n == 0) return
        var best = Float.MAX_VALUE; val step = maxOf(1, n / 4000); var i = 0; val o = FloatArray(4); val v = FloatArray(4)
        while (i < n) {
            val b = i * 7
            v[0] = g.data[b]; v[1] = g.data[b + 1]; v[2] = g.data[b + 2]; v[3] = 1f
            Matrix.multiplyMV(o, 0, vp, 0, v, 0)
            if (o[3] > 0.1f) { val nx = o[0] / o[3]; val ny = o[1] / o[3]; val d = nx * nx + ny * ny
                if (d < best && abs(nx) < 0.9f && abs(ny) < 0.9f) { best = d; probePx = ((nx + 1) / 2 * viewW).toInt(); probePy = ((ny + 1) / 2 * viewH).toInt(); probeWant = floatArrayOf(g.data[b + 3], g.data[b + 4], g.data[b + 5]) } }
            i += step
        }
    }

    /** 화면 안 점 수(표본 추정) */
    private fun onScreen(): Int {
        var tot = 0; val o = FloatArray(4); val v = FloatArray(4)
        for (g in listOf(engine.voxels.gpu, engine.features.gpu)) {
            val n = g.size; if (n == 0) continue
            val step = maxOf(1, n / 5000); var i = 0; var c = 0
            while (i < n) { val b = i * 7; v[0] = g.data[b]; v[1] = g.data[b + 1]; v[2] = g.data[b + 2]; v[3] = 1f
                Matrix.multiplyMV(o, 0, vp, 0, v, 0)
                if (o[3] > 0.05f && abs(o[0] / o[3]) <= 1f && abs(o[1] / o[3]) <= 1f) c++
                i += step }
            tot += c * step
        }
        return tot
    }

    /** YUV_420_888 한 화소 → RGB(0..1) */
    class YuvSampler(img: Image) {
        private val w = img.width; private val h = img.height
        private val yb = img.planes[0].buffer; private val yrs = img.planes[0].rowStride; private val yps = img.planes[0].pixelStride
        private val ub = img.planes[1].buffer; private val vb = img.planes[2].buffer; private val crs = img.planes[1].rowStride; private val cps = img.planes[1].pixelStride
        fun rgb(u0: Int, v0: Int, out: FloatArray, o: Int) {
            val u = u0.coerceIn(0, w - 1); val v = v0.coerceIn(0, h - 1)
            val Y = (yb.get(v * yrs + u * yps).toInt() and 255).toFloat()
            val ci = (v / 2) * crs + (u / 2) * cps
            val U = (ub.get(ci).toInt() and 255) - 128f; val V = (vb.get(ci).toInt() and 255) - 128f
            out[o] = ((Y + 1.402f * V) / 255f).coerceIn(0f, 1f)
            out[o + 1] = ((Y - 0.344f * U - 0.714f * V) / 255f).coerceIn(0f, 1f)
            out[o + 2] = ((Y + 1.772f * U) / 255f).coerceIn(0f, 1f)
        }
    }

    private fun takeShot(img: Image, camera: com.google.ar.core.Camera): Boolean {
        if (img.format != ImageFormat.YUV_420_888) return false
        val w = img.width; val h = img.height
        val nv21 = yuvToNv21(img)
        val gray = ByteArray(w * h); System.arraycopy(nv21, 0, gray, 0, w * h)
        val bo = ByteArrayOutputStream(); YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), 95, bo)
        val intr = camera.imageIntrinsics
        val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
        val sx = w.toDouble() / dims[0]; val sy = h.toDouble() / dims[1]
        val t = camera.pose.translation; val q = camera.pose.rotationQuaternion
        onShot(Shot(bo.toByteArray(), gray, w, h, doubleArrayOf(t[0].toDouble(), t[1].toDouble(), t[2].toDouble(), q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()),
            f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy))
        return true
    }

    private fun overlay(tracking: Boolean, slow: Boolean): Overlay {
        val tp = mutableListOf<Triple<Int, Float, Float>>()
        if (tracking) {
            // 표적 위치는 작업 스레드 계산값(포지션 맵)을 쓴다 — 가벼움(표적 수 개)
            val pos = engine.tagPos
            for ((no, p) in pos) screen(p)?.let { if (it[2] > 0) tp += Triple(no, it[0], it[1]) }
        }
        var target: FloatArray? = null; var off = false
        engine.progress.target?.let { tg -> if (tracking) screen(tg)?.let { s ->
            var x = s[0]; var y = s[1]
            if (s[2] <= 0) { x = viewW - x; y = viewH - y; off = true }
            if (x < 0 || y < 0 || x > viewW || y > viewH) off = true
            target = floatArrayOf(x.coerceIn(0f, viewW.toFloat()), y.coerceIn(0f, viewH.toFloat()))
        } }
        return Overlay(tracking, slow, tp, target, off, (if (depthOn) "깊이" else "특징점") + " · 점 " + (engine.voxelCount + engine.featureCount), lastFailure)
    }

    /** 세계 → 화면 px (z = w 부호) */
    private fun screen(p: V3): FloatArray? {
        val v = floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f); val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, vp, 0, v, 0)
        if (abs(o[3]) < 1e-6f) return null
        val nx = o[0] / o[3]; val ny = o[1] / o[3]
        return floatArrayOf((nx + 1) / 2 * viewW, (1 - ny) / 2 * viewH, o[3])
    }

    companion object {
        fun fbuf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        fun dist(a: FloatArray, b: FloatArray): Double { val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]; return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()) }
        fun angDeg(a: FloatArray, b: FloatArray): Double { val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f); return Math.toDegrees(2 * acos(d.toDouble())) }

        fun yuvToNv21(img: Image): ByteArray {
            val w = img.width; val h = img.height
            val out = ByteArray(w * h * 3 / 2)
            val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
            val yb = y.buffer; var o = 0
            for (r in 0 until h) { yb.position(r * y.rowStride); if (y.pixelStride == 1) { yb.get(out, o, w); o += w } else for (c in 0 until w) out[o++] = yb.get(r * y.rowStride + c * y.pixelStride) }
            val ub = u.buffer; val vb = v.buffer
            for (r in 0 until h / 2) for (c in 0 until w / 2) {
                out[o++] = vb.get(r * v.rowStride + c * v.pixelStride)
                out[o++] = ub.get(r * u.rowStride + c * u.pixelStride)
            }
            return out
        }

        private fun shader(type: Int, src: String): Int { val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }
        fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) { kr.archsurvey.pg.diag.PgLog.e("GL link failed: ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
            return p
        }
    }
}
