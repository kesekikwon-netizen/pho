package kr.archsurvey.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kr.archsurvey.App
import kr.archsurvey.feedback.FeedbackEvent
import kr.archsurvey.feedback.Severity

private enum class Tab(val label: String, val icon: String) {
    CONNECT("연결", "📡"), SURVEY("측량", "📍"), MAP("지도", "🗺"), LIST("목록", "📋"), SETTINGS("설정", "⚙")
}

private sealed interface Overlay {
    data object None : Overlay
    data object Model : Overlay
    data object Ntrip : Overlay
    data class Point(val rowId: Long) : Overlay
}

@Composable
fun AppRoot() {
    SurveyTheme {
        val vm: MainVm = viewModel()
        val ctx = LocalContext.current
        val app = ctx.applicationContext as App
        var tab by rememberSaveable { mutableStateOf(Tab.CONNECT) }
        var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
        var antennaDlg by remember { mutableStateOf(false) }
        var flash by remember { mutableStateOf<FeedbackEvent?>(null) }

        // 시작 시: 모델 미선택이면 선택 화면, 선택돼 있으면 연결 탭. 촬영 중 죽은 사진 복구.
        LaunchedEffect(Unit) {
            if (vm.deviceId == null) overlay = Overlay.Model
            vm.recoverPendingPhoto()
        }
        // 햅틱·소리(FeedbackPlayer) + 시각 피드백(화면 테두리/배너)
        LaunchedEffect(Unit) {
            vm.engine.events.collect { e ->
                app.feedback.play(e)
                flash = e
                delay(2500)
                if (flash === e) flash = null
            }
        }
        val keepOn = vm.keepScreenOn && tab == Tab.SURVEY
        val view = LocalView.current
        DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

        val border by animateColorAsState(when (flash?.severity) {
            Severity.OK -> T.fixed; Severity.WARN -> T.float; Severity.ERROR -> T.single; null -> Color.Transparent
        }, label = "flash")

        BackHandler(enabled = overlay != Overlay.None || tab != Tab.CONNECT) {
            if (overlay != Overlay.None && !(overlay == Overlay.Model && vm.deviceId == null)) overlay = Overlay.None
            else if (overlay == Overlay.None) tab = Tab.CONNECT
        }

        Box(Modifier.fillMaxSize().background(T.bg).border(6.dp, border)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // 알림 배너 (스크린리더에도 읽힘)
                flash?.let { e ->
                    StatusBanner(e.messageKo, when (e.severity) { Severity.OK -> Kind.OK; Severity.WARN -> Kind.WARN; Severity.ERROR -> Kind.ERROR },
                        Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Assertive })
                }
                vm.toast?.let { t ->
                    StatusBanner(t, Kind.INFO, Modifier.padding(8.dp)) { vm.toast = null }
                    LaunchedEffect(t) { delay(3000); if (vm.toast == t) vm.toast = null }
                }
                Box(Modifier.weight(1f)) {
                    when (val o = overlay) {
                        Overlay.Model -> ModelSelectScreen(vm) { overlay = Overlay.None; tab = Tab.CONNECT }
                        Overlay.Ntrip -> NtripScreen(vm) { overlay = Overlay.None }
                        is Overlay.Point -> PointDetailScreen(vm, o.rowId) { overlay = Overlay.None }
                        Overlay.None -> when (tab) {
                            Tab.CONNECT -> ConnectScreen(vm, goSurvey = { tab = Tab.SURVEY }, goNtrip = { overlay = Overlay.Ntrip }, goModel = { overlay = Overlay.Model })
                            Tab.SURVEY -> SurveyScreen(vm) { tab = Tab.CONNECT }
                            Tab.MAP -> MapScreen(vm)
                            Tab.LIST -> ListScreen(vm) { overlay = Overlay.Point(it) }
                            Tab.SETTINGS -> SettingsScreen(vm, { overlay = Overlay.Model }, { overlay = Overlay.Ntrip }, { antennaDlg = true })
                        }
                    }
                }
                if (overlay == Overlay.None || overlay is Overlay.Point) {
                    NavigationBar(containerColor = T.surface) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(selected = tab == t && overlay == Overlay.None, onClick = { overlay = Overlay.None; tab = t },
                                icon = { Text(t.icon, style = Ty.title) }, label = { Text(t.label, style = Ty.small) })
                        }
                    }
                }
            }
        }

        // 모델 선택/변경 직후 기준점 안내 시트 (확인 필수)
        if (vm.showModelNotice && vm.profile != null) {
            val m = vm.antennaModel
            BigDialog("측정 기준점 안내", null, "확인", { vm.dismissModelNotice() }) {
                if (m != null) {
                    ReferenceBanner(vm)
                    Spacer(Modifier.height(8.dp))
                    ReferenceDiagram(vm.deviceId, vm.antenna.refPointId)
                }
                Spacer(Modifier.height(8.dp))
                Text("R10과 SP60은 높이 재는 기준점이 달라 같은 폴 높이여도 안테나 위상중심이 약 8cm 차이납니다. 앱이 자동 보정하니 선택한 기기의 기준점부터 재 주세요.", style = Ty.body, color = T.ink)
            }
        }
        // 모델 변경 시 사용자 입력 덮어쓰기 확인
        vm.pendingModelChange?.let {
            BigDialog("안테나 높이를 기본값으로 바꿀까요?", null, "기본값으로 바꾸기", { vm.confirmModelChange(true) }, "내 값 유지", { vm.confirmModelChange(false) }) {
                Text("직접 입력한 높이가 있어요. 새 모델의 기본값을 다시 제안할까요? (기준점은 새 모델에 맞게 바뀝니다)", style = Ty.body)
            }
        }
        if (antennaDlg) AntennaDialog(vm) { antennaDlg = false }
    }
}
