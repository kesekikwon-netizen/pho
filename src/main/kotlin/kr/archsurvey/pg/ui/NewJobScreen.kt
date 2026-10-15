package kr.archsurvey.pg.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.archsurvey.pg.data.JobName
import kr.archsurvey.ui.FieldIvory
import kr.archsurvey.pg.data.Session
import kr.archsurvey.pg.data.Store

/** 새 작업: 이름 칸 하나(바로 입력) + 만들기. 이름 = 폴더 이름 */
@Composable
fun NewJobScreen(store: Store, arcore: Boolean, onCreated: (Session) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    BackHandler { onCancel() }
    LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
    fun create() {
        val p = JobName.problem(text) { store.dir(it).exists() }
        if (p != null) { err = p; return }
        runCatching { store.newSession(text, arcore) }.onSuccess { kb?.hide(); onCreated(it) }
            .onFailure { err = it.message ?: "만들지 못했어요"; kr.archsurvey.pg.diag.PgLog.e("job create failed", it) }
    }
    Column(Modifier.fillMaxSize().background(P.bg).safeDrawingPadding().imePadding().padding(20.dp)) {
        Txt("새 작업", size = FieldIvory.title, bold = true)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it; err = null },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text("작업 이름 (폴더 이름)") }, placeholder = { Text("예: 회암사지 3호 건물지") },
            singleLine = true, textStyle = TextStyle(fontSize = 22.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { create() }),
            isError = err != null,
        )
        Spacer(Modifier.height(8.dp))
        Txt(err ?: "다운로드/사진측량/ 에 이 이름으로 저장돼요", size = 15.sp, color = if (err != null) P.error else P.sub)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlineBtn("취소") { kb?.hide(); onCancel() }
            Spacer(Modifier.weight(1f))
            PrimaryBtn("만들기", Modifier.width(200.dp).heightIn(min = 64.dp)) { create() }
        }
    }
}
