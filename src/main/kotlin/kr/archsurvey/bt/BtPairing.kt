package kr.archsurvey.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kr.archsurvey.ui.PairEvents
import kr.archsurvey.ui.PairPlatform

/**
 * 0.1.40 앱 안 수신기 찾기·등록(사진 기준점 0.1.2 와 같은 파일).
 * - 찾기: BluetoothAdapter.startDiscovery + ACTION_FOUND / ACTION_DISCOVERY_FINISHED.
 *   12+: BLUETOOTH_SCAN(neverForLocation) · 11 이하: ACCESS_FINE_LOCATION + 위치 서비스 켜짐.
 * - 등록: cancelDiscovery → createBond. ACTION_PAIRING_REQUEST(순서 있는 방송, 높은 우선순위로 먼저 받음)에서
 *   PIN 방식이면 setPin(PIN) + abortBroadcast → 시스템 PIN 창을 건너뜀(삼성 등에서는 창이 잠깐 보였다 닫히거나 그대로 보일 수 있음).
 *   SSP 숫자 확인·동의 방식은 setPairingConfirmation 이 BLUETOOTH_PRIVILEGED 전용이라 시스템 창에서 사용자가 「등록」을 눌러야 함.
 * - 결과: ACTION_BOND_STATE_CHANGED(+ 숨은 EXTRA_REASON 이 있으면 실패 이유).
 */
class AndroidPairPlatform(
    private val ctx: Context,
    /** MainActivity 의 권한 요청기(AndroidConnectionPlatform.permissionRequester 와 같은 것) */
    private val requester: () -> ((Array<String>, (Boolean) -> Unit) -> Unit)?,
) : PairPlatform {
    override var events: PairEvents? = null
    private var scanRx: BroadcastReceiver? = null
    private var pairRx: BroadcastReceiver? = null

    private fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
    override fun hasPermission(): Boolean = perms().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    override fun requestPermission(onResult: (Boolean) -> Unit) {
        val r = requester() ?: return onResult(hasPermission())
        r(perms()) { onResult(hasPermission()) }
    }
    override fun bluetoothEnabled() = BtCompat.isEnabled(ctx)
    override fun locationOkForScan(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 || Build.VERSION.SDK_INT < 23) return true
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(true)
    }

    override fun bonded() = BtCompat.bonded(ctx).map { kr.archsurvey.bt.FoundDevice(it.name, it.address, true) }

    @SuppressLint("MissingPermission")
    override fun startScan(onFound: (FoundDevice) -> Unit, onDone: () -> Unit): Boolean {
        val a = BtCompat.adapter(ctx) ?: return false
        stopScan()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = device(i) ?: return
                        val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { d.name }.getOrNull() ?: return // 이름 없는 기기는 빼기
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                        onFound(FoundDevice(name, d.address, runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false), rssi))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { stopScan(); onDone() }
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(ctx, rx, f, ContextCompat.RECEIVER_EXPORTED)
        scanRx = rx
        return try { a.startDiscovery() } catch (e: SecurityException) { stopScan(); false }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        scanRx?.let { runCatching { ctx.unregisterReceiver(it) } }; scanRx = null
        runCatching { BtCompat.adapter(ctx)?.cancelDiscovery() }
    }

    @SuppressLint("MissingPermission")
    override fun createBond(address: String): Boolean {
        val a = BtCompat.adapter(ctx) ?: return false
        stopScan() // 찾는 중이면 등록이 실패(REASON_DISCOVERY_IN_PROGRESS)
        listenPairing()
        return try { a.getRemoteDevice(address).createBond() } catch (e: Exception) { CrashLog(e); false }
    }

    @SuppressLint("MissingPermission")
    override fun isBonded(address: String): Boolean = try {
        BtCompat.adapter(ctx)?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    private fun listenPairing() {
        if (pairRx != null) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = device(i) ?: return
                when (i.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                        val pin = events?.onPairingRequest(d.address, variant) ?: return
                        val ok = try { d.setPin(pin.toByteArray(Charsets.US_ASCII)) } catch (e: SecurityException) { false }
                        if (ok && isOrderedBroadcast) runCatching { abortBroadcast() } // 시스템 PIN 창 건너뛰기(안 되는 폰이면 창에 PIN 이 이미 들어가 있음)
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        val reason = i.getIntExtra(PairConst.EXTRA_REASON, -1).takeIf { it >= 0 }
                        events?.onBondState(d.address, st, prev, reason)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = 999 // 앱이 쓸 수 있는 가장 높은 값(시스템 설정 앱보다 먼저)
        }
        ContextCompat.registerReceiver(ctx, rx, f, ContextCompat.RECEIVER_EXPORTED)
        pairRx = rx
    }

    fun release() { stopScan(); pairRx?.let { runCatching { ctx.unregisterReceiver(it) } }; pairRx = null }

    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @Suppress("FunctionName") private fun CrashLog(e: Throwable) = kr.archsurvey.diag.CrashLog.record("수신기 등록", e)
}
