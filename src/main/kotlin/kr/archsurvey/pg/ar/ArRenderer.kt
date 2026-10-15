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
 * ARCore 화면: 카메라 바탕 + 깊이(또는 특징점)로 쌓이는 색칠 면.
 * - 깊이 키프레임 0.35초마다(작업 스레드에서 높이칸에 누적). 깊이 미지원이면 특징점 0.3초마다.
 * - 사진: [requestShot] 또는 자동(2초 + 25 cm/10° 움직였을 때). 빨리 움직이면 「천천히」·저장 안 함.
 */
class ArRenderer(
    private val session: Session,
    private val engine: LiveEngine,
    private val depthOn: Boolean,
    private val onOverlay: (Overlay) -> Unit,
    private val onShot: (Shot) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var auto = true
    /** 발열·저장공간 정책: 깊이 간격(0 = 끔), 사진 허용 */
    @Volatile var depthIntervalMs = 350L
    @Volatile var shotsAllowed = true
    private var lastFailure: String? = null
    @Volatile var paused = false
    @Volatile private var shotRequested = false
    @Volatile var viewW = 1; @Volatile var viewH = 1
    var rotation = 0
    private var geomChanged = true

    private var camTex = 0
    private var bgProg = 0; private var meshProg = 0; private var cloudProg = 0
    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadBuf = fbuf(quad); private val uvBuf = fbuf(FloatArray(8))
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16)

    private var lastDepthAt = 0L; private var lastShotAt = 0L; private var lastOverlayAt = 0L
    private var lastShotPos: FloatArray? = null; private var lastShotQ: FloatArray? = null
    private val speed = kr.archsurvey.pg.live.SpeedGate()
    private var lastSpeed = ""
    private var lastFrameTs = 0L
    // 진단(화면·로그): 깊이 성공/실패, 점 묶음
    @Volatile var depthOk = 0; @Volatile var depthFail = 0; @Volatile var cloudFrames = 0
    private var lastDepthErr = ""; private var glErrLogged = 0

    fun requestShot() { shotRequested = true; kr.archsurvey.pg.diag.PgLog.d("shutter pressed") }
    private var frames = 0; private var fpsAt = 0L; private var lastTracking: TrackingState? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.28f, 0.25f, 0.22f, 1f)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session.setCameraTextureName(camTex)
        vbo = 0; vboSrc = null // 새 GL 문맥: 예전 버퍼 번호는 무효
        bgProg = program(
            "attribute vec4 p; attribute vec2 uv; varying vec2 v; void main(){ gl_Position=p; v=uv; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES t; void main(){ gl_FragColor=texture2D(t,v); }")
        meshProg = program(
            "uniform mat4 m; uniform float ps; attribute vec3 p; attribute vec4 c; varying vec4 vc; void main(){ gl_Position=m*vec4(p,1.0); gl_PointSize=ps; vc=c; }",
            "precision mediump float; varying vec4 vc; void main(){ gl_FragColor=vc; }")
        cloudProg = program(
            "uniform mat4 m; uniform float k; attribute vec3 p; attribute vec4 c; varying vec4 vc; void main(){ vec4 q=m*vec4(p,1.0); gl_Position=q; gl_PointSize=clamp(k/max(q.w,0.1),3.0,14.0); vc=c; }",
            "precision mediump float; varying vec4 vc; void main(){ vec2 d=gl_PointCoord-vec2(0.5); if(dot(d,d)>0.25) discard; gl_FragColor=vc; }")
        kr.archsurvey.pg.diag.PgLog.i("gl surface created ${GLES20.glGetString(GLES20.GL_RENDERER)}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height); viewW = width; viewH = height
        session.setDisplayGeometry(rotation, width, height); geomChanged = true
    }


    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame: Frame = try { session.update() } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.w("session.update failed: ${t.javaClass.simpleName} ${t.message}"); return }
        if (frame.hasDisplayGeometryChanged() || geomChanged) {
            geomChanged = false
            quadBuf.position(0); uvBuf.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadBuf, Coordinates2d.TEXTURE_NORMALIZED, uvBuf)
            quadBuf.position(0); uvBuf.position(0)
        }
        if (frame.timestamp == 0L) return
        drawBackground()
        val newFrame = frame.timestamp != lastFrameTs; lastFrameTs = frame.timestamp
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        lastFailure = if (tracking) null else camera.trackingFailureReason.name
        if (!tracking && shotRequested) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=not_tracking ${lastFailure}") }
        val now = SystemClock.uptimeMillis()
        frames++
        if (now - fpsAt >= 5000) { if (fpsAt > 0) kr.archsurvey.pg.diag.PgLog.d("fps=%.1f tracking=${camera.trackingState} failure=${camera.trackingFailureReason} depthOk=$depthOk depthFail=$depthFail($lastDepthErr) cloud=${engine.cloudBuf?.count ?: 0} batches=${engine.batches} speed=${lastSpeed} paused=$paused auto=$auto".format(frames * 1000.0 / (now - fpsAt))); fpsAt = now; frames = 0 }
        if (camera.trackingState != lastTracking) { kr.archsurvey.pg.diag.PgLog.i("tracking ${lastTracking} -> ${camera.trackingState} reason=${camera.trackingFailureReason}"); lastTracking = camera.trackingState }
        if (tracking) {
            camera.getViewMatrix(view, 0); camera.getProjectionMatrix(proj, 0, 0.05f, 30f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val pose = camera.pose
            val pos = pose.translation; val q = pose.rotationQuaternion // x,y,z,w
            // 움직임 속도: 카메라 프레임 시각으로 0.25초 창 평균(같은 프레임 반복·손떨림 무시), 1 m/s · 90°/s 두 번 연속
            if (newFrame && speed.feed(frame.timestamp, pos, q)) kr.archsurvey.pg.diag.PgLog.d("too fast move=%.2f m/s rot=%.0f deg/s".format(speed.move, speed.rot))
            lastSpeed = "move=%.2fm/s rot=%.0fdeg/s".format(speed.move, speed.rot)
            val slow = speed.slow(frame.timestamp)

            if (!paused && newFrame && depthIntervalMs > 0 && now - lastDepthAt > depthIntervalMs) { lastDepthAt = now; collectPoints(frame) }
            val moved = lastShotPos == null || dist(lastShotPos!!, pos) > 0.25 || angDeg(lastShotQ!!, q) > 10
            val autoDue = !paused && auto && shotsAllowed && now - lastShotAt > 2000 && moved
            if (shotRequested && !shotsAllowed) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=policy(storage/thermal)") }
            if (paused && shotRequested) { shotRequested = false; kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=paused") }
            if ((shotRequested || autoDue) && slow) {
                kr.archsurvey.pg.diag.PgLog.i("shot REJECT reason=too_fast $lastSpeed manual=$shotRequested"); shotRequested = false
            } else if (shotRequested || autoDue) {
                val manual = shotRequested; shotRequested = false
                if (takeShot(frame)) { lastShotAt = now; lastShotPos = pos; lastShotQ = q; kr.archsurvey.pg.diag.PgLog.i("shot ACCEPT manual=$manual") }
                else kr.archsurvey.pg.diag.PgLog.w("shot REJECT reason=camera_image_unavailable manual=$manual")
            }
            if (engine.view3d == kr.archsurvey.pg.live.LiveEngine.View3D.MESH) drawMesh() else drawCloud()
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR && glErrLogged < 20) { glErrLogged++; kr.archsurvey.pg.diag.PgLog.e("GL error 0x${Integer.toHexString(e)} view=${engine.view3d}") }
        }
        if (now - lastOverlayAt > 100) { lastOverlayAt = now; onOverlay(overlay(tracking, tracking && speed.slow(frame.timestamp))) }
    }

    private fun drawBackground() {
        resetState()
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

    // 점구름 VBO(GL 스레드에서만 올림)
    private var vbo = 0; private var vboSrc: kr.archsurvey.pg.live.LiveEngine.Cloud? = null; private var vboCount = 0
    private var probeAt = 0L

    /** ARCore(session.update·깊이·카메라 영상)가 같은 GL 문맥을 쓰므로 그리기 전에 상태를 모두 우리 값으로 되돌림 */
    private fun resetState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDisable(GLES20.GL_CULL_FACE); GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
    }

    /** 색 점구름: VBO 로 올리고, 깊이 시험 없이 카메라 위에 항상 그림(가림 문제 배제). 가까울수록 크게 */
    private fun drawCloud() {
        val cb = engine.cloudBuf ?: return
        if (cb.count == 0 || cloudProg == 0) return
        resetState()
        if (vbo == 0) { val t = IntArray(1); GLES20.glGenBuffers(1, t, 0); vbo = t[0] }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        if (cb !== vboSrc) { cb.buf.position(0); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, cb.count * 28, cb.buf, GLES20.GL_DYNAMIC_DRAW); vboSrc = cb; vboCount = cb.count }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glUseProgram(cloudProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cloudProg, "m"), 1, false, vp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProg, "k"), viewH * 0.012f)
        val p = GLES20.glGetAttribLocation(cloudProg, "p"); val c = GLES20.glGetAttribLocation(cloudProg, "c")
        GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 28, 0)
        GLES20.glVertexAttribPointer(c, 4, GLES20.GL_FLOAT, false, 28, 12)
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(c)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vboCount)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(c)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glDepthMask(true)
        cloudFrames++
        // 5초마다 증거: CPU 로 투영해 화면 안 점 수 + 그 점 한 곳의 실제 화소 색(그려졌는지)
        val now = SystemClock.uptimeMillis()
        if (now - probeAt > 5000) { probeAt = now; probe(cb) }
    }

    private fun probe(cb: kr.archsurvey.pg.live.LiveEngine.Cloud) {
        var inside = 0; var sx = -1; var sy = -1; var want = ""
        val step = maxOf(1, cb.count / 2000)
        var i = 0
        while (i < cb.count) {
            val x = cb.buf.get(7 * i); val y = cb.buf.get(7 * i + 1); val z = cb.buf.get(7 * i + 2)
            val o = FloatArray(4); Matrix.multiplyMV(o, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
            if (o[3] > 0.05f) { val nx = o[0] / o[3]; val ny = o[1] / o[3]
                if (nx in -1f..1f && ny in -1f..1f) { inside++; if (sx < 0) { sx = ((nx + 1) / 2 * viewW).toInt(); sy = ((ny + 1) / 2 * viewH).toInt()
                    want = "%.2f,%.2f,%.2f".format(cb.buf.get(7 * i + 3), cb.buf.get(7 * i + 4), cb.buf.get(7 * i + 5)) } } }
            i += step
        }
        var got = "-"
        if (sx >= 0) { val bb = ByteBuffer.allocateDirect(4); GLES20.glReadPixels(sx.coerceIn(0, viewW - 1), sy.coerceIn(0, viewH - 1), 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
            got = "%d,%d,%d".format(bb.get(0).toInt() and 255, bb.get(1).toInt() and 255, bb.get(2).toInt() and 255) }
        kr.archsurvey.pg.diag.PgLog.d("draw probe cloud=${cb.count} onScreen~${inside * step} at=($sx,$sy) want=$want got=$got view=${viewW}x$viewH gl=0x${Integer.toHexString(GLES20.glGetError())}")
    }

    private fun drawMesh() {
        val m = engine.mesh ?: return
        resetState()
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(meshProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProg, "m"), 1, false, vp, 0)
        val p = GLES20.glGetAttribLocation(meshProg, "p"); val c = GLES20.glGetAttribLocation(meshProg, "c")
        fun draw(buf: FloatBuffer, count: Int, mode: Int, ps: Float) {
            if (count == 0) return
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "ps"), ps)
            buf.position(0); GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 28, buf)
            buf.position(3); GLES20.glVertexAttribPointer(c, 4, GLES20.GL_FLOAT, false, 28, buf)
            GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(c)
            GLES20.glDrawArrays(mode, 0, count)
            GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(c)
            buf.position(0)
        }
        // 면이 생긴 곳은 면, 아직 띄엄띄엄한 곳은 점(라이다처럼 점이 쌓이다 면이 됨)
        draw(m.pts, m.ptCount, GLES20.GL_POINTS, 6f)
        draw(m.tri, m.triCount * 3, GLES20.GL_TRIANGLES, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 깊이(Raw → 일반 깊이) 또는 특징점 → 세계 점 + 카메라 색 */
    private fun collectPoints(frame: Frame) {
        val camera = frame.camera
        val img = try { frame.acquireCameraImage() } catch (t: Throwable) { null }
        try {
            if (depthOn) {
                val depth = try { frame.acquireRawDepthImage16Bits() } catch (t: Throwable) {
                    try { frame.acquireDepthImage16Bits() } catch (t2: Throwable) { lastDepthErr = t2.javaClass.simpleName; null } }
                if (depth != null) {
                    val conf = try { frame.acquireRawDepthConfidenceImage() } catch (_: Throwable) { null }
                    try { if (depthToWorld(depth, conf, camera, img) > 0) { depthOk++; return } else lastDepthErr = "empty" }
                    catch (t: Throwable) { lastDepthErr = t.javaClass.simpleName; kr.archsurvey.pg.diag.PgLog.w("depthToWorld failed ${t.javaClass.simpleName} ${t.message}") }
                    finally { depth.close(); conf?.close() }
                }
                depthFail++
            }
            featurePoints(frame, camera, img)
        } finally { img?.close() }
    }

    private fun featurePoints(frame: Frame, camera: com.google.ar.core.Camera, img: Image?) {
        try {
            frame.acquirePointCloud().use { pc ->
                val b = pc.points; val n = b.remaining() / 4
                if (n == 0) return
                val out = FloatArray(n * 3); var k = 0
                for (i in 0 until n) {
                    val x = b.get(); val y = b.get(); val z = b.get(); val c = b.get()
                    if (c < 0.2f) continue
                    out[3 * k] = x; out[3 * k + 1] = y; out[3 * k + 2] = z; k++
                }
                if (k == 0) return
                val rgb = img?.let { colorsForWorld(out, k, camera, it) }
                engine.addColored(out, rgb, k)
            }
        } catch (t: Throwable) { kr.archsurvey.pg.diag.PgLog.w("pointcloud failed ${t.javaClass.simpleName}") }
    }

    /** 세계 점 → 카메라 영상 화소 색(특징점용) */
    private fun colorsForWorld(xyz: FloatArray, n: Int, camera: com.google.ar.core.Camera, img: Image): FloatArray {
        val intr = camera.imageIntrinsics; val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
        val sx = img.width.toFloat() / dims[0]; val sy = img.height.toFloat() / dims[1]
        val inv = FloatArray(16); camera.pose.inverse().toMatrix(inv, 0)
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

    /** 깊이 화소 → 세계 점. 내부표정은 CPU 영상(imageIntrinsics)을 깊이 크기로 줄여 씀(깊이는 CPU 영상과 같은 화각). 반환: 점 수 */
    private fun depthToWorld(depth: Image, conf: Image?, camera: com.google.ar.core.Camera, img: Image?): Int {
        val w = depth.width; val h = depth.height
        val plane = depth.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN); val rs = plane.rowStride; val ps = plane.pixelStride
        val cbuf = conf?.planes?.get(0)?.buffer; val crs = conf?.planes?.get(0)?.rowStride ?: 0; val cps = conf?.planes?.get(0)?.pixelStride ?: 1
        val intr = camera.imageIntrinsics
        val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
        val sx = w.toFloat() / dims[0]; val sy = h.toFloat() / dims[1]
        val fx = f[0] * sx; val fy = f[1] * sy; val cx = pp[0] * sx; val cy = pp[1] * sy
        val m = FloatArray(16); camera.pose.toMatrix(m, 0)
        val sampler = img?.let { YuvSampler(it) }
        val ix = if (img != null) img.width.toFloat() / w else 0f; val iy = if (img != null) img.height.toFloat() / h else 0f
        val out = FloatArray((w / 2 + 1) * (h / 2 + 1) * 3); val rgb = FloatArray(out.size); var k = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val mm = buf.getShort(y * rs + x * ps).toInt() and 0xffff
                val c = if (cbuf != null) (cbuf.get(y * crs + x * cps).toInt() and 0xff) else 255
                if (mm in 150..6000 && c >= 100) {
                    val d = mm / 1000f
                    val px = (x - cx) / fx * d; val py = -(y - cy) / fy * d; val pz = -d
                    out[3 * k] = m[0] * px + m[4] * py + m[8] * pz + m[12]
                    out[3 * k + 1] = m[1] * px + m[5] * py + m[9] * pz + m[13]
                    out[3 * k + 2] = m[2] * px + m[6] * py + m[10] * pz + m[14]
                    if (sampler != null) sampler.rgb((x * ix).toInt(), (y * iy).toInt(), rgb, 3 * k) else { rgb[3 * k] = 0.85f; rgb[3 * k + 1] = 0.92f; rgb[3 * k + 2] = 1f }
                    k++
                }
                x += 2
            }
            y += 2
        }
        if (k > 0) engine.addColored(out, rgb, k)
        return k
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

    private fun takeShot(frame: Frame): Boolean {
        val img = try { frame.acquireCameraImage() } catch (_: Throwable) { return false }
        try {
            if (img.format != ImageFormat.YUV_420_888) return false
            val w = img.width; val h = img.height
            val nv21 = yuvToNv21(img)
            val gray = ByteArray(w * h); System.arraycopy(nv21, 0, gray, 0, w * h)
            val bo = ByteArrayOutputStream(); YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), 95, bo)
            val cam = frame.camera
            val intr = cam.imageIntrinsics
            val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
            val sx = w.toDouble() / dims[0]; val sy = h.toDouble() / dims[1]
            val t = cam.pose.translation; val q = cam.pose.rotationQuaternion
            onShot(Shot(bo.toByteArray(), gray, w, h, doubleArrayOf(t[0].toDouble(), t[1].toDouble(), t[2].toDouble(), q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()),
                f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy))
            return true
        } finally { img.close() }
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
        return Overlay(tracking, slow, tp, target, off, (if (depthOn) "깊이" else "특징점") + " · 점 " + (engine.cloudBuf?.count ?: 0), lastFailure)
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
