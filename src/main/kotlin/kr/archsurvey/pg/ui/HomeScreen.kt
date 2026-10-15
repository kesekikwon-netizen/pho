package kr.archsurvey.pg.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.pg.ar.ArSupport
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Store
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.ui.Minimal
import kr.archsurvey.ui.ModeChip
import kr.archsurvey.ui.Sunlight
import kr.archsurvey.ui.releaseTap

@Composable
fun HomeScreen(store: Store, onNew: (arcore: Boolean) -> Unit, onOpen: (Session) -> Unit, onGcp: (Session) -> Unit = {}, onResume: (Session) -> Unit = {}) {
    val ctx = LocalContext.current
    var ar by remember { mutableStateOf(ArSupport.state(ctx)) }
    var sessions by remember { mutableStateOf(store.list()) }
    var manage by remember { mutableStateOf<Session?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val one = rememberOneTap(store)
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("사진측량", size = FieldIvory.title, bold = true, modifier = Modifier.weight(1f))
            Choice("☀ 햇빛", Sunlight.on) { Sunlight.set(ctx, !Sunlight.on) }
            Spacer(Modifier.width(8.dp))
            // 0.0.7: 미니멀/일반 고르기 없앰(항상 일반)
            androidx.compose.foundation.layout.Box(Modifier.padding(start = 4.dp).releaseTap("더 보기") { Minimal.moreOpen = !Minimal.moreOpen }.padding(10.dp)) { Txt("⋯", size = 24.sp, bold = true) }
        }
        Spacer(Modifier.height(8.dp))
        when (ar) {
            ArSupport.State.OK -> if (!Minimal.on) Txt("실시간 3D 가능 · 찍는 대로 면이 쌓여요", color = P.sub)
            ArSupport.State.NEED_INSTALL -> Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("실시간 3D 를 쓰려면 Google Play AR 서비스가 필요해요", color = P.float, modifier = Modifier.weight(1f))
                OutlineBtn("설치") { (ctx as? Activity)?.let { if (ArSupport.requestInstall(it)) ar = ArSupport.State.OK else note = kr.archsurvey.pg.diag.Msg.AR_DECLINED } }
            }
            ArSupport.State.UNSUPPORTED -> Txt("이 폰은 실시간 3D 가 안 돼요 · 사진·표적·iTwin 내보내기는 돼요", color = P.float)
        }
        note?.let { Txt(it, color = P.float) }
        one.msg?.let { Txt(it, bold = true, color = if (one.path != null) P.fix else P.error) }
        one.path?.let { Txt(it, size = 16.sp, bold = true) }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sessions.isEmpty()) item { // S6: 빈 홈 3단계
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("처음이면 이렇게", bold = true)
                    for ((i, t) in listOf("▣  표적(AprilTag)을 유구 둘레에 3개 이상 놓기", "↻  ＋ 새 작업 → 이름 쓰고 → 천천히 돌며 찍기", "⇪  결과에서 「iTwin으로 보내기」 → PC 로").withIndex())
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt("${i + 1}", size = 26.sp, bold = true, color = P.primary); Spacer(Modifier.width(12.dp)); Txt(t)
                        }
                }
            }
            items(sessions, key = { it.name }) { s ->
                val sh = RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().clip(sh).background(P.card).border(1.dp, P.line, sh).pointerInput(s.name) { detectTapGestures(onTap = { onOpen(s) }, onLongPress = { manage = s }) }.padding(16.dp)) {
                    Txt(s.name, bold = true)
                    val tags = s.stills.flatMap { st -> st.tags.map { it.id } }.distinct().size
                    Txt("사진 ${s.stills.size}장 · 표적 ${tags}개 · ${if (s.gcps.isNotEmpty()) "좌표 ${s.gcps.size}점" else "로컬 좌표"}", size = 15.sp, color = P.sub)
                    if (s.stills.isEmpty()) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OutlineBtn("이어 찍기") { onResume(s) } }
                    else androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                        OutlineBtn(if (s.gcps.isEmpty()) "좌표 입력" else "좌표 ${s.gcps.size}점") { onGcp(s) }
                        OutlineBtn("이어 찍기") { onResume(s) }
                        PrimaryBtn(if (one.busy == s.name) "보내는 중…" else "iTwin으로 보내기", Modifier.width(170.dp)) { one.run(s.name) }
                    }
                }
            }
        }
        if (Minimal.moreOpen) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            OutlineBtn("로그 공유") { Minimal.moreOpen = false; runCatching { kr.archsurvey.pg.diag.PgLog.share(ctx) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("log share failed", it) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(220.dp)) {
                PrimaryBtn("＋ 새 작업", Modifier.fillMaxWidth()) { onNew(ar == ArSupport.State.OK) }
            }
        }
    }
    manage?.let { m ->
        var newName by remember(m.name) { mutableStateOf(m.name) }
        var err by remember(m.name) { mutableStateOf<String?>(null) }
        var confirmDel by remember(m.name) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { manage = null },
            title = { androidx.compose.material3.Text(if (confirmDel) "「${m.name}」 를 지울까요? 사진도 지워져요" else "작업 관리") },
            text = { if (!confirmDel) Column {
                androidx.compose.material3.OutlinedTextField(newName, { newName = it; err = null }, singleLine = true, label = { androidx.compose.material3.Text("새 이름") })
                err?.let { Txt(it, size = 14.sp, color = P.error) }
            } },
            confirmButton = { androidx.compose.material3.TextButton({
                if (confirmDel) { store.files.delete(m.name); kr.archsurvey.pg.diag.PgLog.i("job deleted"); sessions = store.list(); manage = null }
                else { val e = if (newName.trim() == m.name) null else store.files.rename(m.name, newName); if (e != null) err = e else { sessions = store.list(); manage = null } }
            }) { androidx.compose.material3.Text(if (confirmDel) "지우기" else "이름 바꾸기") } },
            dismissButton = { androidx.compose.material3.TextButton({ if (confirmDel) confirmDel = false else confirmDel = true }) { androidx.compose.material3.Text(if (confirmDel) "취소" else "지우기…") } })
    }
}
