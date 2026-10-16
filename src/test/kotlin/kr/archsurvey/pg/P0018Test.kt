package kr.archsurvey.pg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.0.18: 실기 로그(10-16 10:00–10:03) — 촬영 중 10초마다 「session 5 journal recovered N」 = 화면을 다시 그릴 때마다 작업 파일 전체를 메인 스레드에서 다시 읽음 */
class P0018Test {
    @Test fun captureRouteLoadsSessionOnceNotOnEveryRecomposition() {
        val src = File("src/main/kotlin/kr/archsurvey/pg/MainActivity.kt").readText()
        assertFalse("store.load in capture branch must be remembered", Regex("else if \\(store\\.load\\(name\\)").containsMatchIn(src))
        assertTrue(src.contains("remember(route) { runCatching { store.load(name).arcore }"))
    }
}
