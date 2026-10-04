package kr.archsurvey.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.archsurvey.bt.BtCompat
import kr.archsurvey.core.AntennaMath
import kr.archsurvey.device.DeviceProfile
import kr.archsurvey.device.DeviceProfiles
import kr.archsurvey.engine.LinkState
import kr.archsurvey.ntrip.NtripConfig
import kr.archsurvey.ntrip.NtripPresets
import kr.archsurvey.ntrip.NtripState

/** 시작 화면: 측량기 선택 (R10 / SP60). 선택값은 저장되며 설정에서 변경 가능 */
@Composable
fun ModelSelectScreen(vm: MainVm, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("어떤 측량기를 쓰나요?", style = Ty.big, color = T.ink)
        Text("하나를 고르세요. 나중에 설정에서 바꿀 수 있어요.", style = Ty.body, color = T.inkSub)
        for (p in DeviceProfiles.all) {
            val sel = vm.deviceId == p.id
            Card(Modifier.clickable { vm.chooseDevice(p.id); onDone() }, accent = if (sel) T.primary else T.outlineVar) {
                Text(p.displayName, style = Ty.big, color = T.ink)
                Text(p.subtitle, style = Ty.body, color = T.inkSub)
                val m = AntennaDbModel(vm, p.id)
                if (m != null) Text("높이 재는 곳: " + m, style = Ty.body, color = T.ink)
                if (sel) Text("✓ 지금 선택됨", style = Ty.bodyB, color = T.okText)
            }
        }
        StatusBanner("R10과 SP60은 높이 재는 기준점이 달라요. 고르면 안내가 나와요.", Kind.INFO)
    }
}

private fun AntennaDbModel(vm: MainVm, id: String): String? {
    val m = kr.archsurvey.data.AntennaDb.forDevice(vm.antennaDb, id) ?: return null
    return AntennaMath.instruction(if (id == DeviceProfiles.R10_ID) "R10" else "SP60", m.defaultRef).substringAfter("· ").removeSuffix("부터 재세요")
}

@Composable
fun ConnectScreen(vm: MainVm, goSurvey: () -> Unit, goNtrip: () -> Unit, goModel: () -> Unit) {
    val ctx = LocalContext.current
    val profile = vm.profile
    if (profile == null) { ModelSelectScreen(vm) {}; return }
    val link by vm.engine.link.collectAsStateWithLifecycle()
    val gnss by vm.engine.gnss.collectAsStateWithLifecycle()
    val ntrip by vm.engine.ntrip.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; vm.engine.kickReconnect(); vm.recoverPendingPhoto() }

    val btPermOk = remember(tick) { !vm.btPermissionsMissing() }
    val btOn = remember(tick) { BtCompat.isEnabled(ctx) }
    val bonded = remember(tick, btPermOk, btOn) { BtCompat.bonded(ctx) }
    val sorted = remember(bonded, profile) { profile.sortByRelevance(bonded) { it.name } }
    var permDenied by remember { mutableStateOf(false) }
    val selectedAddr = remember(tick, vm.deviceId) { mutableStateOf(vm.btAddress()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++; permDenied = res.values.any { !it }
        if (!permDenied) vm.engine.kickReconnect()
    }
    val notiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        tick++; doConnect(vm)
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    var checks by rememberSaveable(profile.id) { mutableStateOf(List(profile.preConnectChecks.size) { false }) }
    val running = link.state != LinkState.IDLE

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("연결 상태", style = Ty.big, color = T.ink)
        Text(vm.project, style = Ty.body, color = T.inkSub)

        // 시스템 배너들
        if (!btPermOk) {
            val act = ctx as? Activity
            val permanent = permDenied && act != null && BtCompat.runtimePermissions().none { ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
            Card(accent = T.float) {
                Text("'근처 기기' 권한이 필요해요", style = Ty.title, color = T.ink)
                Text(if (permanent) "권한이 막혀 있어요. 설정 > 권한 > 근처 기기 > 허용으로 바꿔 주세요. (오류 BT-103)" else "측량기를 찾고 연결하는 데 꼭 필요해요. 팝업에서 '허용'을 눌러 주세요. (오류 BT-102)", style = Ty.body, color = T.inkSub)
                Spacer(Modifier.height(8.dp))
                if (permanent) BigButton("앱 설정 열기", { BtCompat.openAppSettings(ctx) })
                else BigButton("허용하러 가기", { permLauncher.launch(BtCompat.runtimePermissions()) })
            }
        } else if (!btOn) {
            Card(accent = T.float) {
                Text("블루투스가 꺼져 있어요 (오류 BT-101)", style = Ty.title, color = T.ink)
                Spacer(Modifier.height(8.dp))
                BigButton("블루투스 켜기", {
                    try { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { BtCompat.openBluetoothSettings(ctx) }
                })
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !BtCompat.hasNotificationPermission(ctx))
            StatusBanner("알림이 꺼져 있어요. 화면을 끄면 연결 상태를 알림으로 볼 수 없어요. (측량은 가능)", Kind.WARN)
        PowerBanners(ctx, tick)

        // 측량기 카드
        val kind = when (link.state) {
            LinkState.STREAMING -> if (gnss.quality == kr.archsurvey.core.FixQuality.RTK_FIXED) Kind.OK else Kind.WARN
            LinkState.IDLE -> Kind.INFO
            LinkState.NEEDS_USER, LinkState.STALE -> Kind.ERROR
            else -> Kind.WARN
        }
        Card(accent = when (kind) { Kind.OK -> T.ok; Kind.WARN -> T.float; Kind.ERROR -> T.single; Kind.INFO -> T.none }) {
            Text(profile.displayName, style = Ty.title, color = T.ink)
            Text(profile.subtitle, style = Ty.small, color = T.inkSub)
            Spacer(Modifier.height(8.dp))
            val msg = when {
                link.state == LinkState.IDLE -> "연결 안 됨"
                link.state == LinkState.STREAMING -> "수신 중 · " + gnss.quality.labelKo + if (gnss.quality != kr.archsurvey.core.FixQuality.RTK_FIXED) " (고정될 때까지 기다리세요)" else ""
                else -> link.message
            }
            StatusBanner(msg, kind)
            if (link.repairHint) { Spacer(Modifier.height(6.dp)); StatusBanner(profile.repairHint, Kind.WARN) }
            link.error?.let { Text("(오류 ${it.code}) ${it.action}", style = Ty.small, color = T.inkSub, modifier = Modifier.padding(top = 6.dp)) }
            if (running && link.state != LinkState.STREAMING && link.state != LinkState.IDLE)
                Text("이미 저장한 측점은 그대로예요.", style = Ty.small, color = T.inkSub)
            link.strategy?.let { Text("연결 방식: $it", style = Ty.small, color = T.inkSub) }
            Spacer(Modifier.height(10.dp))
            if (running) BigButton("연결 종료", { vm.stopConnection() }, container = T.surfaceVar, content = T.ink)
        }

        // VRS 카드
        val nk = when (ntrip.state) {
            NtripState.RECEIVING -> Kind.OK; NtripState.IDLE -> Kind.INFO
            NtripState.AUTH_FAILED, NtripState.ERROR -> Kind.ERROR; else -> Kind.WARN
        }
        Card(Modifier.clickable { goNtrip() }, accent = when (nk) { Kind.OK -> T.ok; Kind.WARN -> T.float; Kind.ERROR -> T.single; Kind.INFO -> T.none }) {
            Text("국토지리정보원 VRS", style = Ty.title, color = T.ink)
            Text("${vm.ntripCfg.host} · ${vm.ntripCfg.mountPoint}", style = Ty.small, color = T.inkSub)
            Spacer(Modifier.height(8.dp))
            StatusBanner(
                when {
                    !vm.ntripEnabled -> "보정을 사용하지 않아요"
                    !vm.ntripCfg.isUsable -> "VRS 아이디를 입력하세요 (눌러서 설정)"
                    ntrip.message.isNotEmpty() -> ntrip.message
                    else -> "측량기를 연결하면 자동으로 접속해요"
                }, nk)
            Text("눌러서 서버·아이디 설정", style = Ty.small, color = T.inkSub, modifier = Modifier.padding(top = 6.dp))
        }

        if (!running) {
            SectionTitle("측량기 고르기")
            if (sorted.isEmpty()) StatusBanner("등록된 블루투스 기기가 없어요. 아래 '페어링 방법'대로 폰에 먼저 등록하세요.", Kind.INFO)
            for (b in sorted) {
                val match = profile.matchesName(b.name)
                val sel = selectedAddr.value == b.address
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) T.primaryCont else T.surface).clickable { vm.selectBt(b.address); selectedAddr.value = b.address }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel, onClick = { vm.selectBt(b.address); selectedAddr.value = b.address })
                    Column(Modifier.weight(1f)) {
                        Text((if (match) "★ " else "") + b.name, style = Ty.bodyB, color = T.ink)
                        Text(b.address + if (match) " · ${profile.displayName} 로 보여요" else "", style = Ty.small, color = T.inkSub)
                    }
                }
            }
            OutlineBigButton("폰 블루투스 설정 열기 (등록/삭제)", { BtCompat.openBluetoothSettings(ctx) }, Modifier.fillMaxWidth())

            SectionTitle("연결 전 확인")
            profile.preConnectChecks.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { checks = checks.toMutableList().also { l -> l[i] = !l[i] } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checks.getOrElse(i) { false }, onCheckedChange = { c -> checks = checks.toMutableList().also { l -> l[i] = c } })
                    Text(t, style = Ty.body, color = T.ink)
                }
            }
            val ready = btPermOk && btOn && selectedAddr.value != null
            BigButton("연결하기", {
                if (Build.VERSION.SDK_INT >= 33 && !BtCompat.hasNotificationPermission(ctx)) notiLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else doConnect(vm)
            }, enabled = ready, sub = if (!ready) (if (selectedAddr.value == null) "위에서 측량기를 먼저 고르세요" else null) else null)
        }
        BigButton("측량하러 가기", goSurvey, container = T.primary, enabled = true)

        Guide("페어링 방법 · ${profile.defaultPin.let { "PIN" }}", profile.pairingSteps + profile.pinNote)
        Guide(profile.nmeaSetupTitle, profile.nmeaSetupSteps + profile.portNote)
        OutlineBigButton("측량기 바꾸기 (${profile.displayName})", goModel, Modifier.fillMaxWidth())
        Text("※ 실기기(R10/SP60/폰) 시험을 하지 않은 버전이에요. [현장확인] 표시는 현장에서 확인하세요.", style = Ty.small, color = T.inkSub)
    }
}

private fun doConnect(vm: MainVm) { vm.connect() }

@Composable
private fun Guide(title: String, steps: List<String>) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.bodyB, color = T.ink, modifier = Modifier.weight(1f)); Text(if (open) "접기 ▲" else "보기 ▼", style = Ty.small, color = T.inkSub)
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, s -> Text(if (i < steps.size) "${i + 1}. $s" else s, style = Ty.body, color = T.ink) }
        }
    }
}

@Composable
fun PowerBanners(ctx: Context, tick: Int) {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val saver = remember(tick) { pm.isPowerSaveMode } // API 21+
    if (saver) StatusBanner("절전 모드가 켜져 있어요. 연결이 불안정할 수 있어요.", Kind.WARN,
        onClick = { runCatching { ctx.startActivity(Intent(if (Build.VERSION.SDK_INT >= 22) Settings.ACTION_BATTERY_SAVER_SETTINGS else Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
    if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        StatusBanner("배터리 최적화 때문에 화면을 끄면 연결이 끊길 수 있어요. 눌러서 '제한 없음'으로 바꿔 주세요. (삼성·샤오미는 설정 > 배터리에서 앱 절전도 꺼 주세요)", Kind.INFO) {
            try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}

/** VRS(NTRIP) 설정. 아이디/비밀번호는 사용자가 입력, 서버·포트·마운트 수정 가능 */
@Composable
fun NtripScreen(vm: MainVm, onBack: () -> Unit) {
    var host by remember { mutableStateOf(vm.ntripCfg.host) }
    var port by remember { mutableStateOf(vm.ntripCfg.port.toString()) }
    var mount by remember { mutableStateOf(vm.ntripCfg.mountPoint) }
    var user by remember { mutableStateOf(vm.ntripCfg.username) }
    var pw by remember { mutableStateOf(vm.ntripCfg.password) }
    var v2 by remember { mutableStateOf(vm.ntripCfg.useV2) }
    var enabled by remember { mutableStateOf(vm.ntripEnabled) }
    var show by remember { mutableStateOf(false) }
    val ntrip by vm.engine.ntrip.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlineBigButton("← 뒤로", onBack); Spacer(Modifier.width(12.dp)); Text("VRS 보정 설정", style = Ty.big, color = T.ink)
        }
        LabeledSwitch("VRS 보정 사용", enabled) { enabled = it }
        SectionTitle("서버 종류")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("VRS (RTS1)", host == NtripPresets.VRS.host && mount == NtripPresets.VRS.mountPoint) { host = NtripPresets.VRS.host; port = "2101"; mount = NtripPresets.VRS.mountPoint }
            Chip("FKP (RTS2)", host == NtripPresets.FKP.host) { host = NtripPresets.FKP.host; port = "2101"; mount = NtripPresets.FKP.mountPoint }
        }
        Text("서버·포트·마운트포인트는 직접 고칠 수 있어요. 국토지리정보원 공개 안내(2022-05) 기준이라 바뀌었을 수 있어요.", style = Ty.small, color = T.inkSub)
        Field("서버 주소", host) { host = it }
        Field("포트", port, KeyboardType.Number) { port = it.filter { c -> c.isDigit() } }
        Field("마운트포인트", mount) { mount = it }
        Field("VRS 아이디 (장비 1대당 1개)", user) { user = it }
        OutlinedTextField(pw, { pw = it }, label = { Text("비밀번호") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = Ty.body)
        LabeledSwitch("비밀번호 보기", show) { show = it }
        if (!vm.settings.canStorePassword) StatusBanner("이 안드로이드 버전(5.x)에서는 비밀번호를 안전하게 저장할 수 없어서, 앱을 다시 켜면 다시 입력해야 해요.", Kind.INFO)
        LabeledSwitch("NTRIP v2 방식 (보통 꺼 두세요)", v2) { v2 = it }
        StatusBanner("아이디·비밀번호는 평문 TCP(포트 2101)로 전송돼요. 공용 와이파이에서는 모바일 데이터를 쓰세요.", Kind.INFO)

        val cfg = NtripConfig(host.trim(), port.toIntOrNull() ?: 2101, mount.trim(), user.trim(), pw, v2)
        BigButton("저장하고 접속", { vm.saveNtrip(cfg, enabled); onBack() }, enabled = !enabled || cfg.isUsable)
        OutlineBigButton("마운트포인트 목록 가져오기", { vm.ntripCfg = cfg; vm.fetchMounts() }, Modifier.fillMaxWidth(), enabled = cfg.host.isNotBlank() && cfg.username.isNotBlank())
        if (vm.mountMsg.isNotEmpty()) Text(vm.mountMsg, style = Ty.body, color = T.inkSub)
        for (m in vm.mountList) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(T.surface).clickable { mount = m.mountPoint }.padding(12.dp)) {
                Column { Text(m.mountPoint, style = Ty.bodyB, color = T.ink); Text("${m.format} · ${m.navSystem}" + if (m.needsNmea) " · GGA 필요" else "", style = Ty.small, color = T.inkSub) }
            }
        }
        Text("현재 상태: ${ntrip.state} ${ntrip.message}", style = Ty.small, color = T.inkSub)
    }
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = kb), textStyle = Ty.body)
}
