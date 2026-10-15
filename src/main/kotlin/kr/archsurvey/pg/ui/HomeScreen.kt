package kr.archsurvey.pg.ui

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
fun HomeScreen(store: Store, onNew: (Session) -> Unit, onOpen: (Session) -> Unit) {
    val ctx = LocalContext.current
    var ar by remember { mutableStateOf(ArSupport.state(ctx)) }
    val sessions = remember { store.list() }
    var note by remember { mutableStateOf<String?>(null) }
    val one = rememberOneTap(store)
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("사진측량", size = FieldIvory.title, bold = true, modifier = Modifier.weight(1f))
            Choice("☀ 햇빛", Sunlight.on) { Sunlight.set(ctx, !Sunlight.on) }
            Spacer(Modifier.width(8.dp))
            ModeChip(P.ink, P.bg, P.ink)
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
        one.msg?.let { Txt(it, bold = true, color = P.fix) }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sessions.isEmpty()) item { Txt("아직 작업이 없어요", color = P.sub) }
            items(sessions, key = { it.name }) { s ->
                val sh = RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().clip(sh).background(P.card).border(1.dp, P.line, sh).releaseTap(s.name) { onOpen(s) }.padding(16.dp)) {
                    Txt(s.name, bold = true)
                    val tags = s.stills.flatMap { st -> st.tags.map { it.id } }.distinct().size
                    Txt("사진 ${s.stills.size}장 · 표적 ${tags}개 · ${if (s.gcps.isNotEmpty()) "좌표 ${s.gcps.size}점" else "로컬 좌표"}", size = 15.sp, color = P.sub)
                    if (s.stills.isNotEmpty()) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PrimaryBtn(if (one.busy == s.name) "보내는 중…" else "iTwin으로 보내기", Modifier.width(200.dp), color = P.fix) { one.run(s.name) }
                    }
                }
            }
        }
        if (Minimal.moreOpen) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            OutlineBtn("로그 공유") { Minimal.moreOpen = false; runCatching { kr.archsurvey.pg.diag.PgLog.share(ctx) }.onFailure { kr.archsurvey.pg.diag.PgLog.e("log share failed", it) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(220.dp)) {
                PrimaryBtn("새 3D 촬영", Modifier.fillMaxWidth()) { onNew(store.newSession(ar == ArSupport.State.OK)) }
            }
        }
    }
}
