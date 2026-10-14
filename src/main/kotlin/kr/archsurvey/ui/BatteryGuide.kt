package kr.archsurvey.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * 0.1.48(측량) · 0.1.10(사진 기준점) 같은 파일. 삼성 배터리 「제한 없음」 안내 카드.
 * 한 번 누르면 시스템 창(「허용」)이 바로 뜬다. 이미 제한 없음이면 카드를 보이지 않는다.
 */
object BatteryGuide {
    const val TITLE = "배터리 「제한 없음」으로 바꿔 주세요"
    const val BODY = "폰이 오래 두면 측량 연결을 끊을 수 있어요. 한 번만 바꾸면 돼요."
    const val BUTTON = "바꾸기"

    fun unrestricted(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 ||
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)

    @SuppressLint("BatteryLife")
    fun open(ctx: Context) {
        val ok = Build.VERSION.SDK_INT >= 23 && runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun BatteryGuideCard(modifier: Modifier = Modifier, ink: Color = FieldIvory.ink, sub: Color = FieldIvory.inkSub, accent: Color = FieldIvory.primary, card: Color = FieldIvory.primarySoft) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(!BatteryGuide.unrestricted(ctx)) }
    LifecycleResumeEffect(Unit) { show = !BatteryGuide.unrestricted(ctx); onPauseOrDispose { } }
    if (!show) return
    val sh = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().clip(sh).background(card).border(1.5.dp, accent.copy(alpha = 0.5f), sh).padding(12.dp).testTag("battery_card"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(BatteryGuide.TITLE, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(BatteryGuide.BODY, color = sub, fontSize = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.heightIn(min = 56.dp).widthIn(min = 88.dp).clip(RoundedCornerShape(14.dp)).background(accent)
            .hClickable(onClickLabel = BatteryGuide.BUTTON) { BatteryGuide.open(ctx) }.testTag("battery_fix"), contentAlignment = Alignment.Center) {
            Text(BatteryGuide.BUTTON, color = FieldIvory.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
