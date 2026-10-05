package kr.archsurvey.bt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** 버전별 권한/어댑터 헬퍼. 모든 BT API 호출 전에 hasConnectPermission() 가드를 거친다. */
object BtCompat {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** 런타임으로 요청해야 하는 블루투스 권한. API 31+: CONNECT + SCAN 함께(cancelDiscovery 의 SecurityException 방지). 그 외: 설치 시 자동. */
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    fun hasConnectPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /** Android 13+ 알림 권한 */
    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    fun hasNotificationPermission(ctx: Context): Boolean {
        val p = notificationPermission() ?: return true
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(ctx: Context): BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= 18)
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        else @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

    data class BondedDevice(val name: String, val address: String)

    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<BondedDevice> {
        if (!hasConnectPermission(ctx)) return emptyList()
        val a = adapter(ctx) ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().map { BondedDevice(it.name ?: it.address, it.address) }
        } catch (e: SecurityException) { emptyList() }
    }

    fun isEnabled(ctx: Context): Boolean = try { adapter(ctx)?.isEnabled == true } catch (e: SecurityException) { false }

    fun openBluetoothSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

enum class Strategy { SECURE_SPP, INSECURE_SPP, SDP_UUID, REFLECT_CH1 }

data class Attempt(val strategy: Strategy, val uuid: UUID? = null)

/**
 * RFCOMM(SPP) 전송 계층. 한 인스턴스 = 한 소켓. close() 는 멱등. 4단계 폴백, 성공한 방식을 반환.
 * [exclude]: BT-130(소켓 OK·무음)으로 판명된 방식은 다음 치유에서 빼고 SECURE/INSECURE SPP 부터 다시 시도한다.
 */
class BtTransport(
    private val ctx: Context,
    private val address: String,
    private val preferred: Strategy? = null,
    private val exclude: Set<Strategy> = emptySet(),
) : kr.archsurvey.engine.LinkTransport {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    override lateinit var input: InputStream; private set
    override lateinit var output: OutputStream; private set
    var usedStrategy: Strategy? = null; private set
    override val strategyName: String? get() = usedStrategy?.name

    override fun connect() = connect(preferred)

    /** 블로킹. IO 스레드에서 호출. preferred: 지난번 *데이터까지* 성공한 방식(있으면 먼저 시도). exclude는 건너뜀. */
    @SuppressLint("MissingPermission")
    fun connect(preferred: Strategy?) {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) throw BtConnectException(BtError.NO_BLUETOOTH)
        if (!BtCompat.hasConnectPermission(ctx)) throw BtConnectException(BtError.PERMISSION_NEARBY)
        val adapter = BtCompat.adapter(ctx) ?: throw BtConnectException(BtError.NO_BLUETOOTH)
        if (!adapter.isEnabled) throw BtConnectException(BtError.BT_OFF)
        val dev: BluetoothDevice = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            throw BtConnectException(BtError.NO_DEVICE_SELECTED, e)
        }
        // 연결 전 검색 중지. SCAN 권한이 없으면 SecurityException -> 무시하고 계속
        try { adapter.cancelDiscovery() } catch (_: SecurityException) { }

        val plan = buildPlan(dev, preferred)
        var lastErr: Exception? = null
        for (a in plan) {
            if (closed) throw BtConnectException(BtError.SOCKET_CLOSED)
            var s: BluetoothSocket? = null
            try {
                s = open(dev, a) ?: continue
                socket = s
                s.connect() // 블로킹 최대 ~12초
                if (closed) { runCatching { s.close() }; throw BtConnectException(BtError.SOCKET_CLOSED) }
                input = s.inputStream
                output = s.outputStream
                usedStrategy = a.strategy
                return
            } catch (e: BtConnectException) {
                throw e
            } catch (e: SecurityException) {
                runCatching { s?.close() }
                throw BtConnectException(BtError.PERMISSION_NEARBY, e)
            } catch (e: Exception) {
                lastErr = e
                runCatching { s?.close() }
                if (closed) throw BtConnectException(BtError.SOCKET_CLOSED, e)
                try { Thread.sleep(400) } catch (_: InterruptedException) { throw BtConnectException(BtError.SOCKET_CLOSED, e) }
            }
        }
        val reason = BtError.classify(lastErr)
        throw BtConnectException(if (plan.size > 1 && reason == BtError.UNKNOWN) BtError.ALL_FAILED else reason, lastErr)
    }

    @SuppressLint("MissingPermission")
    private fun open(dev: BluetoothDevice, a: Attempt): BluetoothSocket? = when (a.strategy) {
        Strategy.SECURE_SPP -> dev.createRfcommSocketToServiceRecord(BtCompat.SPP_UUID)
        Strategy.INSECURE_SPP -> dev.createInsecureRfcommSocketToServiceRecord(BtCompat.SPP_UUID)
        Strategy.SDP_UUID -> dev.createRfcommSocketToServiceRecord(a.uuid)
        Strategy.REFLECT_CH1 -> try {
            // 숨은 API: 막혀 있으면 건너뜀. 최근 Android 에서의 동작은 [현장확인]
            dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as? BluetoothSocket
        } catch (e: Exception) { null }
    }

    @SuppressLint("MissingPermission")
    private fun buildPlan(dev: BluetoothDevice, preferred: Strategy?): List<Attempt> {
        val plan = mutableListOf(Attempt(Strategy.SECURE_SPP), Attempt(Strategy.INSECURE_SPP))
        val sdp = try { dev.uuids?.map { it.uuid }.orEmpty() } catch (_: SecurityException) { emptyList() }
            .filter { it != BtCompat.SPP_UUID }
            .sortedByDescending { it.toString().startsWith("0000110") }
            .map { Attempt(Strategy.SDP_UUID, it) }
        plan += sdp
        plan += Attempt(Strategy.REFLECT_CH1)
        // 무음 채널로 판명된 방식은 제외(모두 제외되면 원본 유지 — 연결 자체는 시도해야 함)
        val filtered = plan.filter { it.strategy !in exclude }.ifEmpty { plan }
        val pref = preferred?.takeUnless { it in exclude }
        return if (pref == null) filtered else filtered.sortedBy { if (it.strategy == pref) 0 else 1 }
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
    }
}
