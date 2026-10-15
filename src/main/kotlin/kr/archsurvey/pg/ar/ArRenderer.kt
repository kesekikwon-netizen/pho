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
    @Volatile var paused = false
    @Volatile private var shotRequested = false
    @Volatile var viewW = 1; @Volatile var viewH = 1
    var rotation = 0
    private var geomChanged = true

    private var camTex = 0
    private var bgProg = 0; private var meshProg = 0
    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadBuf = fbuf(quad); private val uvBuf = fbuf(FloatArray(8))
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16)

    private var lastDepthAt = 0L; private var lastShotAt = 0L; private var lastOverlayAt = 0L
    private var lastShotPos: FloatArray? = null; private var lastShotQ: FloatArray? = null
    private var prevPos: FloatArray? = null; private var prevQ: FloatArray? = null; private var prevT = 0L
    private var slowUntil = 0L

    fun requestShot() { shotRequested = true }

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
        meshProg = program(
            "uniform mat4 m; uniform float ps; attribute vec3 p; attribute vec4 c; varying vec4 vc; void main(){ gl_Position=m*vec4(p,1.0); gl_PointSize=ps; vc=c; }",
            "precision mediump float; varying vec4 vc; void main(){ gl_FragColor=vc; }")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height); viewW = width; viewH = height
        session.setDisplayGeometry(rotation, width, height); geomChanged = true
    }


    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (paused) return
        val frame: Frame = try { session.update() } catch (_: Throwable) { return }
        if (frame.hasDisplayGeometryChanged() || geomChanged) {
            geomChanged = false
            quadBuf.position(0); uvBuf.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadBuf, Coordinates2d.TEXTURE_NORMALIZED, uvBuf)
            quadBuf.position(0); uvBuf.position(0)
        }
        if (frame.timestamp == 0L) return
        drawBackground()
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val now = SystemClock.uptimeMillis()
        if (tracking) {
            camera.getViewMatrix(view, 0); camera.getProjectionMatrix(proj, 0, 0.05f, 30f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val pose = camera.pose
            val pos = pose.translation; val q = pose.rotationQuaternion // x,y,z,w
            // 움직임 속도(흔들림 판단): 회전 60°/s, 이동 0.8 m/s 넘으면 「천천히」
            prevPos?.let { pp ->
                val dt = (now - prevT).coerceAtLeast(1) / 1000.0
                val dv = dist(pp, pos) / dt; val dw = angDeg(prevQ!!, q) / dt
                if (dv > 0.8 || dw > 60) slowUntil = now + 600
            }
            prevPos = pos; prevQ = q; prevT = now

            if (now - lastDepthAt > 350) { lastDepthAt = now; collectPoints(frame) }
            val moved = lastShotPos == null || dist(lastShotPos!!, pos) > 0.25 || angDeg(lastShotQ!!, q) > 10
            val autoDue = auto && now - lastShotAt > 2000 && moved
            if ((shotRequested || autoDue) && now >= slowUntil) {
                shotRequested = false
                if (takeShot(frame)) { lastShotAt = now; lastShotPos = pos; lastShotQ = q }
            }
            drawMesh()
        }
        if (now - lastOverlayAt > 100) { lastOverlayAt = now; onOverlay(overlay(tracking, now < slowUntil)) }
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

    private fun drawMesh() {
        val m = engine.mesh ?: return
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

    /** 깊이(Raw → 실패 시 일반 깊이) 또는 특징점 → 세계 점 */
    private fun collectPoints(frame: Frame) {
        val camera = frame.camera
        if (depthOn) {
            try {
                val depth = frame.acquireRawDepthImage16Bits()
                val conf = try { frame.acquireRawDepthConfidenceImage() } catch (_: Throwable) { null }
                try { depthToWorld(depth, conf, camera); return } finally { depth.close(); conf?.close() }
            } catch (_: Throwable) { /* 아직 깊이 없음 → 특징점 */ }
        }
        try {
            frame.acquirePointCloud().use { pc ->
                val b = pc.points; val n = b.remaining() / 4
                if (n == 0) return
                val out = FloatArray(n * 3); var k = 0
                for (i in 0 until n) {
                    val x = b.get(); val y = b.get(); val z = b.get(); val c = b.get()
                    if (c < 0.3f) continue
                    out[3 * k] = x; out[3 * k + 1] = y; out[3 * k + 2] = z; k++
                }
                if (k > 0) engine.addPoints(out, k)
            }
        } catch (_: Throwable) { }
    }

    private fun depthToWorld(depth: Image, conf: Image?, camera: com.google.ar.core.Camera) {
        val w = depth.width; val h = depth.height
        val plane = depth.planes[0]
        val buf = plane.buffer.order(ByteOrder.nativeOrder()); val rs = plane.rowStride; val ps = plane.pixelStride
        val cbuf = conf?.planes?.get(0)?.buffer; val crs = conf?.planes?.get(0)?.rowStride ?: 0; val cps = conf?.planes?.get(0)?.pixelStride ?: 1
        val intr = camera.textureIntrinsics
        val dims = intr.imageDimensions; val f = intr.focalLength; val pp = intr.principalPoint
        val sx = w.toFloat() / dims[0]; val sy = h.toFloat() / dims[1]
        val fx = f[0] * sx; val fy = f[1] * sy; val cx = pp[0] * sx; val cy = pp[1] * sy
        val m = FloatArray(16); camera.pose.toMatrix(m, 0)
        val out = FloatArray((w / 2 + 1) * (h / 2 + 1) * 3); var k = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val mm = buf.getShort(y * rs + x * ps).toInt() and 0xffff
                val c = if (cbuf != null) (cbuf.get(y * crs + x * cps).toInt() and 0xff) else 255
                if (mm in 200..5000 && c >= 128) {
                    val d = mm / 1000f
                    val px = (x - cx) / fx * d; val py = -(y - cy) / fy * d; val pz = -d
                    out[3 * k] = m[0] * px + m[4] * py + m[8] * pz + m[12]
                    out[3 * k + 1] = m[1] * px + m[5] * py + m[9] * pz + m[13]
                    out[3 * k + 2] = m[2] * px + m[6] * py + m[10] * pz + m[14]
                    k++
                }
                x += 2
            }
            y += 2
        }
        if (k > 0) engine.addPoints(out, k)
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
        return Overlay(tracking, slow, tp, target, off, if (depthOn) "깊이" else "특징점")
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
            GLES20.glLinkProgram(p); return p
        }
    }
}
