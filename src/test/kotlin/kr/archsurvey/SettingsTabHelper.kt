package kr.archsurvey

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/** 0.1.46 설정이 일반·수신기·고급 칸으로 나뉨: 칸 단추가 보이면 눌러 둔다(없으면 그대로) */
fun <R : ComposeTestRule> R.settingsTab(i: Int): R {
    if (onAllNodesWithTag("settings_tab_$i").fetchSemanticsNodes().isNotEmpty()) { onNodeWithTag("settings_tab_$i").performClick(); waitForIdle() }
    return this
}
