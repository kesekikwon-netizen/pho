package kr.archsurvey.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kr.archsurvey.core.Vec3
import kr.archsurvey.data.SettingsStore
import kr.archsurvey.ui.TiltPlatform

/** 폰 가속도 센서(TYPE_ACCELEROMETER). 화면이 보일 때만 받는다(MainActivity onResume/onPause). 센서가 없으면 available=false */
class AndroidTiltPlatform(ctx: Context, private val s: SettingsStore) : TiltPlatform {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listener: SensorEventListener? = null

    override val available: Boolean get() = sensor != null
    override var enabled: Boolean
        get() = s.tiltEnabled
        set(v) { s.tiltEnabled = v }
    override var limitDeg: Double
        get() = s.tiltLimitDeg
        set(v) { s.tiltLimitDeg = v }
    override var policyId: String
        get() = s.tiltPolicy
        set(v) { s.tiltPolicy = v }
    override var reference: Vec3?
        get() = s.tiltReference?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
        set(v) { s.tiltReference = v?.let { "${it.x},${it.y},${it.z}" } }

    override fun start(onSample: (Vec3) -> Unit) {
        val m = sm ?: return; val se = sensor ?: return
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { onSample(Vec3(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        m.registerListener(l, se, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() { listener?.let { sm?.unregisterListener(it) }; listener = null }
}
