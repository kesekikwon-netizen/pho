package kr.archsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.archsurvey.device.LinkState
import kr.archsurvey.device.LinkStatus
import kr.archsurvey.engine.NtripStatus
import kr.archsurvey.ntrip.NtripState

/** 0.1.14 기다려야 하는 작업 하나. expectSec = 보통 걸리는 시간(게이지 눈금용, 넘으면 95%에서 멈춤) */
data class BusyOp(val id: String, val label: String, val expectSec: Int, val progress: Float? = null)

/**
 * 0.1.14 대기열: 연결·치유·VRS 접속·마운트 목록·내보내기·백업/복구·V월드 시험·작업 처리.
 * 각 기능의 상태 값에서 계산(별도 큐 상태 없음 → 화면과 어긋날 일이 없다).
 * 연결 진행 창이 떠 있으면 연결 항목은 그 창이 보여 주므로 빼고, 닫으면(계속 연결) 여기로 온다.
 */
object BusyOps {
    fun active(c: SurveyController, link: LinkStatus?, ntrip: NtripStatus?): List<BusyOp> {
        val out = ArrayList<BusyOp>()
        val cc = c.connection
        if (cc != null && cc.connectRequested && !(cc.showProgress)) {
            when (link?.state) {
                LinkState.CONNECTING -> out += BusyOp("bt", "수신기 블루투스 연결 중" + (link.attempt.takeIf { it > 1 }?.let { " · ${it}번째" } ?: ""), 15)
                LinkState.RECONNECTING -> out += BusyOp("bt", "수신기 다시 연결 중 (치유·재시도)" + (link.attempt.takeIf { it > 0 }?.let { " · ${it}번째" } ?: ""), 20)
                LinkState.CONNECTED_NO_DATA -> out += BusyOp("gga", "위치(GGA) 기다리는 중 · NMEA 켜기", 20)
                else -> {}
            }
            if (cc.form.enabled) when (ntrip?.state) {
                NtripState.CONNECTING -> out += BusyOp("vrs", "VRS 서버 접속 중 · ${cc.form.mount}", 10)
                NtripState.RECONNECTING -> out += BusyOp("vrs", "VRS 다시 접속 중 · ${cc.form.mount}", 15)
                NtripState.CONNECTED -> out += BusyOp("vrs", "VRS 접속됨 · 보정 데이터 기다리는 중", 10)
                else -> {}
            }
        }
        if (cc != null && cc.mountLoad == MountLoad.LOADING && !cc.mountPickerOpen) out += BusyOp("mount", "마운트 목록 받는 중 (다운로드)", 15)
        if (c.exporter?.running == true) out += BusyOp("export", "내보내기 파일 만드는 중", 10)
        c.backup?.let { b -> if (b.running) out += BusyOp("backup", (b.runningLabel.ifBlank { "백업" }) + " 처리 중", 15) }
        if (c.vworld.testing) out += BusyOp("vworld", "V월드 타일 받는 중 (연결 시험)", 8)
        if (c.jobs?.busy == true) out += BusyOp("job", "작업 처리 중", 5)
        return out
    }

    /** 경과/예상으로 만든 게이지(실제 진행률이 없을 때). 끝나기 전엔 95%를 넘지 않는다 */
    fun gauge(op: BusyOp, elapsedSec: Long): Float =
        op.progress?.coerceIn(0f, 1f) ?: (elapsedSec.toFloat() / op.expectSec.coerceAtLeast(1)).coerceIn(0.04f, 0.95f)
}

/** 화면 아래(주 버튼 위) 대기열 카드: "대기열 2 · 지금: 내보내기 파일 만드는 중 · 4초" + 게이지. 터치를 막지 않는다 */
@Composable
fun BusyQueueBar(c: SurveyController) {
    val cc = c.connection
    val link = cc?.link?.collectAsState()?.value
    val ntrip = cc?.ntrip?.collectAsState()?.value
    val ops = BusyOps.active(c, link, ntrip)
    if (ops.isEmpty()) return
    val t = T
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.raised).border(1.dp, t.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("busy_queue"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ops.size > 1) Text("대기열 ${ops.size}개 진행 중", style = Ty.small, color = t.inkSub, modifier = Modifier.testTag("busy_queue_count"))
            for ((i, op) in ops.withIndex()) key(op.id) {
                val start = remember { c.nowMs() }
                val sec = rememberElapsedSec(start) { c.nowMs() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (i == 0) "●" else "○", style = Ty.small, color = if (i == 0) t.accent else t.inkSub)
                    Text(op.label, style = Ty.body, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("busy_op_${op.id}"))
                    Text("${sec}초", style = Ty.small, color = t.inkSub)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.card).testTag("busy_gauge_${op.id}")) {
                    Box(Modifier.fillMaxWidth(BusyOps.gauge(op, sec)).fillMaxHeight().background(t.accent))
                }
            }
        }
    }
}
