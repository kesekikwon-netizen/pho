package kr.archsurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.archsurvey.map.MapBase
import kr.archsurvey.map.VWorldAccess
import kr.archsurvey.map.VWorldDefaults

/** V월드 키 저장소. Android = 암호화 SharedPreferences. 비면 제품 기본 키 폴백. 원문은 로그·화면에 내지 않는다 */
interface MapKeyPlatform {
    var key: String
    var domain: String
    var lineOnly: Boolean
    fun access(): VWorldAccess = VWorldAccess(key, domain, lineOnly)
    /** 저장이 비면 제품 기본 키를 Pref 에 심는다(Android). 메모리 저장소는 no-op */
    fun ensureBuiltinKey() {}
    /** 타일 한두 장을 받아 보고 결과 문구(키 가림) */
    fun test(a: VWorldAccess): String = "이 빌드에서는 시험할 수 없어요"
}

class MemoryMapKeyPlatform(override var key: String = "", override var domain: String = "", override var lineOnly: Boolean = false) : MapKeyPlatform

/** 0.1.12 환경설정 → V월드 지적도: API 키(사용자 입력)·도메인·지적 스타일·연결 시험 */
class VWorldSettingsState(private val p: MapKeyPlatform, private val scope: CoroutineScope, private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
    /** 지도 그릴 때마다 암호화 저장소를 읽지 않도록 메모리에 둔다 */
    var access by mutableStateOf(runCatching { p.access() }.getOrDefault(VWorldAccess("", "")))
        private set
    var keyInput by mutableStateOf("")
    var domainInput by mutableStateOf(access.domain)
    var lineOnly by mutableStateOf(access.lineOnly)
    var showKey by mutableStateOf(false)
    var testing by mutableStateOf(false); private set
    var testResult by mutableStateOf<String?>(null); private set
    /** 0.1.14 시험(타일 받기) 끝 멘트 — SurveyController 가 완료/오류 알림으로 연결 */
    var onResult: ((Boolean, String) -> Unit)? = null
    val hasKey: Boolean get() = access.key.isNotBlank()

    fun load() {
        p.ensureBuiltinKey()
        access = runCatching { p.access() }.getOrDefault(access)
        keyInput = ""; domainInput = access.domain; lineOnly = access.lineOnly; testResult = null
    }

    /** 저장. 키 칸이 비어 있으면 기존 키를 그대로 둔다 */
    fun save(): String {
        val k = keyInput.trim()
        if (k.isNotEmpty()) {
            if (k.length < 8 || k.any { it.isWhitespace() }) return "키 모양이 이상해요. V월드에서 받은 인증키를 그대로 붙여 넣으세요."
            p.key = k
        }
        p.domain = domainInput.trim(); p.lineOnly = lineOnly
        access = VWorldAccess(if (k.isNotEmpty()) k else access.key, domainInput.trim(), lineOnly)
        keyInput = ""
        TileImages.clear()
        return if (hasKey) "V월드 설정을 저장했어요" else "도메인을 저장했어요. 키를 넣어야 지도가 보여요"
    }

    fun clearKey() {
        p.key = ""
        p.ensureBuiltinKey()
        access = runCatching { p.access() }.getOrDefault(access.copy(key = ""))
        keyInput = ""
        TileImages.clear()
    }

    fun test() {
        if (testing) return
        val a = if (keyInput.isNotBlank()) VWorldAccess(keyInput.trim(), domainInput.trim(), lineOnly) else access.copy(domain = domainInput.trim(), lineOnly = lineOnly)
        testing = true; testResult = null
        scope.launch {
            val r = try { withContext(io) { p.test(a) } } catch (e: Exception) { UserError.of("vworld:test", e, "시험에 실패했어요", "인터넷 연결을 확인하세요") }
            testResult = r; testing = false
            onResult?.invoke(VWorldTestText.isOk(r), "V월드 연결 시험 · $r")
        }
    }
}

@Composable
fun VWorldScreen(c: SurveyController) {
    val t = T; val v = c.vworld
    val keyPlaceholder = if (v.hasKey) VWorldDefaults.maskedKeyPlaceholder(v.access.key) else "새 키 붙여넣기"
    ToolScaffold("V월드 지적도 · 배경맵", "vworld_screen", onBack = { c.hub.back() }, bottom = {
        PrimaryBar("저장", "vworld_save") { v.save().let { m -> c.ui.result(!m.startsWith("키 모양"), m) } }
    }) {
        Card {
            Text("API 인증키", style = Ty.label, color = t.ink)
            Text(VWorldDefaults.keyStatusKo(v.access.key),
                style = Ty.small, color = if (v.hasKey) t.inkSub else t.bad, modifier = Modifier.testTag("vworld_key_state"))
            Text("아래 칸이 비어 있어도 위 상태의 키가 쓰여요. 새 키를 넣을 때만 입력하세요(원문은 저장 후 가려져요).", style = Ty.small, color = t.inkSub)
            Field("인증키(새 키 입력)", v.keyInput, "vworld_key", hide = !v.showKey, placeholder = keyPlaceholder) { v.keyInput = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Btn(if (v.showKey) "가리기" else "보기", Modifier.fillMaxWidth().testTag("vworld_show")) { v.showKey = !v.showKey } }
                if (v.hasKey) Box(Modifier.weight(1f)) { Btn("키 지우기", Modifier.fillMaxWidth().testTag("vworld_clear")) {
                    c.ui.ask(ConfirmRequest("직접 넣은 키를 지울까요?", "지우면 제품 기본 키로 돌아가요. 배경맵은 기본 키로 계속 쓸 수 있어요.", "지우기") { v.clearKey() })
                } }
            }
            Field("도메인", v.domainInput, "vworld_domain", kb = KeyboardType.Uri) { v.domainInput = it }
            Text("① vworld.kr 오픈API 키 발급 시 활용 API에 WMS/2D 데이터를 포함하세요. ② 서비스 URL을 DEFAULT로 두거나, 발급한 그 URL을 위 도메인 칸에 그대로 넣으세요(공식 WMS 폴백용). ③ 지적선은 다른 GIS와 같은 2DCache(LP_PA_CBND_*) 요청을 먼저 쓰고, 실패 시 공식 api.vworld.kr WMS로 한 번 더 시도해요. 위성만 되고 지적이 안 되면 요청 주소·레이어·도메인을 확인하세요(내장키 …4783도 다른 앱에선 될 수 있어요).", style = Ty.small, color = t.inkSub)
        }
        Card {
            Text("지적선 모양", style = Ty.label, color = t.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("기본 (면+선)", !v.lineOnly, Modifier.weight(1f).testTag("vworld_style_fill")) { v.lineOnly = false }
                Choice("선만", v.lineOnly, Modifier.weight(1f).testTag("vworld_style_line")) { v.lineOnly = true }
            }
            Text("기본은 면+필지번호(다른 GIS와 같은 주황색 필지). 위성 위에 경계만 필요하면 ‘선만’. 지적은 가까이 확대했을 때(줌 ${kr.archsurvey.map.MapLayers.CADASTRAL_MIN_ZOOM} 이상) 받아요.", style = Ty.small, color = t.inkSub)
        }
        Card {
            Text("이 작업의 배경맵", style = Ty.label, color = t.ink)
            for (b in MapBase.entries) Choice(b.labelKo, c.mapBase == b, Modifier.fillMaxWidth().testTag("vworld_base_${b.id}")) { c.changeMapBase(b) }
            Text("‘위성 + 지적선’ = 위성(WMTS) 위에 연속지적 WMS 선. ‘위성만’은 지적선이 없어요. 지적도는 참고용 — 측량 좌표(TM)와 몇 m 어긋날 수 있어요.", style = Ty.small, color = t.inkSub)
        }
        Btn(if (v.testing) "시험 중…" else "연결 시험 (타일 받아 보기)", Modifier.fillMaxWidth().testTag("vworld_test")) { v.test() }
        v.testResult?.let { Text(it, style = Ty.body, color = t.ink, modifier = Modifier.testTag("vworld_test_result")) }
        Text("타일은 화면에 보이는 동안 메모리에만 두고 폰에 저장하지 않아요(V월드 약관). 데이터 요금이 들 수 있어요. 출처: V월드(국토교통부).", style = Ty.small, color = t.inkSub)
    }
}


/** 0.1.14·0.1.23·0.1.24 V월드 시험 결과 문구 → 성공 여부(WMTS + WMS 직성공|폴백 성공) */
object VWorldTestText {
    /** WMTS 성공이고, 지적 줄이 `지적도(WMS): 성공` 또는 `지적도(WMS): 폴백 성공`으로 시작하면 OK. */
    fun isOk(r: String): Boolean {
        if (!r.contains("배경지도(WMTS): 성공")) return false
        val cad = r.lineSequence().firstOrNull { it.startsWith("지적도(WMS):") } ?: return false
        val after = cad.removePrefix("지적도(WMS):").trimStart()
        return after.startsWith("성공") || after.startsWith("폴백 성공")
    }
}
