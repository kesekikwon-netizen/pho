package kr.archsurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.archsurvey.BuildConfig
import kr.archsurvey.core.HeightUnit
import kr.archsurvey.core.KoreanTmZone

@Composable
fun SettingsScreen(vm: MainVm, goModel: () -> Unit, goNtrip: () -> Unit, goAntenna: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설정", style = Ty.big, color = T.ink)
        Card {
            Text("측량기", style = Ty.title, color = T.ink)
            Text(vm.profile?.displayName ?: "선택 안 됨", style = Ty.body, color = T.inkSub)
            OutlineBigButton("측량기 모델 바꾸기", goModel, Modifier.fillMaxWidth())
            OutlineBigButton("안테나 높이 설정", goAntenna, Modifier.fillMaxWidth())
            OutlineBigButton("VRS(NTRIP) 설정", goNtrip, Modifier.fillMaxWidth())
        }
        Card {
            Text("조사", style = Ty.title, color = T.ink)
            OutlinedTextField(vm.project, { vm.project = it.take(40) }, label = { Text("프로젝트(조사명)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = Ty.body)
            OutlinedTextField(vm.idPrefix, { vm.idPrefix = it.take(8) }, label = { Text("점 ID 접두어") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = Ty.body)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("평균 횟수 ${vm.epochs}회", style = Ty.bodyB, color = T.ink, modifier = Modifier.weight(1f))
                StepButton("−", "평균 횟수 줄이기") { vm.epochs = (vm.epochs - 1).coerceAtLeast(1) }
                Spacer(Modifier.width(8.dp))
                StepButton("+", "평균 횟수 늘리기") { vm.epochs = (vm.epochs + 1).coerceAtMost(60) }
            }
            LabeledSwitch("RTK 고정일 때만 측량 허용", vm.requireFixed) { vm.requireFixed = it }
            Text("좌표계", style = Ty.bodyB, color = T.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KoreanTmZone.entries.forEach { z -> Chip(z.labelKo.substringBefore(" ("), vm.tmZone == z) { vm.tmZone = z } }
            }
            Text(vm.tmZone.labelKo, style = Ty.small, color = T.inkSub)
            Text("높이 단위", style = Ty.bodyB, color = T.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HeightUnit.entries.forEach { u -> Chip(u.label, vm.heightUnit == u) { vm.updateHeightUnit(u) } } }
        }
        Card {
            Text("알림·화면", style = Ty.title, color = T.ink)
            LabeledSwitch("효과음", vm.soundOn) { vm.soundOn = it }
            LabeledSwitch("진동", vm.vibrationOn) { vm.vibrationOn = it }
            LabeledSwitch("측량 중 화면 켜 두기", vm.keepScreenOn) { vm.keepScreenOn = it }
        }
        Card {
            Text("배경 지도 (V-World)", style = Ty.title, color = T.ink)
            OutlinedTextField(vm.mapKey, { vm.mapKey = it.trim() }, label = { Text("API 키") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = Ty.body)
            OutlinedTextField(vm.mapDomain, { vm.mapDomain = it.trim() }, label = { Text("등록한 도메인(지적도 WMS용)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), textStyle = Ty.body)
            Text("타일은 저장하지 않고 보는 동안만 내려받아요.", style = Ty.small, color = T.inkSub)
        }
        BigButton("설정 저장", { vm.saveBasics(); vm.toast = "저장했어요" })
        Text("버전 ${BuildConfig.VERSION_NAME}\n실기기 시험 전 빌드입니다. 수치 상수(안테나 오프셋 등)는 제조사 문서 기준이며 현장 검증이 필요합니다.", style = Ty.small, color = T.inkSub)
    }
}
