package kr.archsurvey.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File

/** 캡처·보고 파일 폴더. 여러 작업이 같은 폴더를 덮어쓰지 않게 환경변수 REPORT_DIR 로 바꿀 수 있다 */
val REPORTS_ROOT: String = System.getenv("REPORT_DIR") ?: "../build-reports"

/** 화면 캡처를 찍을 때 누를 수 있는 요소(클릭 동작이 있는 노드) 수를 build-reports/button-counts.txt 에 적는다. 스크롤로 가려진 것도 포함. */
object ButtonCount {
    private val f = File(REPORTS_ROOT + "/button-counts-${System.getProperty("btn.tag") ?: "now"}.txt")
    fun record(rule: ComposeTestRule, name: String) {
        val n = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false).fetchSemanticsNodes().size
        // 터치 크기: 가로·세로 48dp 미만인 누를 수 있는 요소(3.0x 밀도 가정)를 기록
        val small = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false).fetchSemanticsNodes()
            .filter { it.size.width / 3f < 47.5f || it.size.height / 3f < 47.5f }.map { "${it.size.width / 3f}x${it.size.height / 3f}" }
        synchronized(this) { f.appendText("$name\t$n\n"); if (small.isNotEmpty()) File(f.path.replace("button-counts", "small-targets")).appendText("$name\t$small\n") }
    }
}
