package kr.archsurvey.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File

/** 화면 캡처를 찍을 때 누를 수 있는 요소(클릭 동작이 있는 노드) 수를 build-reports/button-counts.txt 에 적는다. 스크롤로 가려진 것도 포함. */
object ButtonCount {
    private val f = File("../build-reports/button-counts-${System.getProperty("btn.tag") ?: "now"}.txt")
    fun record(rule: ComposeTestRule, name: String) {
        val n = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false).fetchSemanticsNodes().size
        synchronized(this) { f.appendText("$name\t$n\n") }
    }
}
