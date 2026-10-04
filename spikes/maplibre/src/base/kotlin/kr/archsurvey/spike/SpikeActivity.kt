package kr.archsurvey.spike
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
/** 비교 기준(MapLibre 없음) */
class SpikeActivity : Activity() { override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(TextView(this).apply { text = "base" }) } }
