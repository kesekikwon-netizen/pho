package kr.archsurvey.t0139

import kr.archsurvey.job.NewJobName
import kr.archsurvey.job.SurveyStage
import kr.archsurvey.ui.JobNames
import kr.archsurvey.ui.OneFingerNames
import kr.archsurvey.ui.OneFingerTypes
import kr.archsurvey.ui.QuickNotes
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.39 키보드 없이 고르기만 — 순수 규칙 */
class OneFinger0139Test {
    @Test fun name_isSiteStageNumberDate() {
        assertEquals("강릉 교동 · 정밀발굴 2지구 · 10-12", NewJobName.build("강릉 교동", SurveyStage.FULL, 2, "10-12"))
        assertEquals("시굴 1지구 · 10-12", NewJobName.build(null, SurveyStage.TRIAL, 1, "10-12"))
        assertEquals("표본 999지구 · 01-05", NewJobName.build("", SurveyStage.SAMPLE, 5000, "01-05"))
    }
    @Test fun name_alwaysValidJobName_longSiteShortened_badCharsGone() {
        val n = NewJobName.build("아주아주 긴 유적 이름 / 제2구역: 동쪽 사면 일대", SurveyStage.FULL, 12, "10-12")
        assertTrue(n, n.length <= NewJobName.MAX)
        assertTrue(n, n.endsWith("정밀발굴 12지구 · 10-12"))
        assertNull(JobNames.problem(n, emptyList()))
    }
    @Test fun nextNo_skipsUsedForSameSiteAndStage() {
        val ex = listOf("강릉 교동 · 정밀발굴 1지구 · 10-01", "강릉 교동 · 정밀발굴 2지구 · 10-02", "강릉 교동 · 시굴 1지구 · 09-30", "광령리 · 정밀발굴 3지구 · 10-01")
        assertEquals(3, NewJobName.nextNo("강릉 교동", SurveyStage.FULL, ex))
        assertEquals(2, NewJobName.nextNo("강릉 교동", SurveyStage.TRIAL, ex))
        assertEquals(1, NewJobName.nextNo("강릉 교동", SurveyStage.SAMPLE, ex))
        assertEquals(1, NewJobName.nextNo("광령리", SurveyStage.TRIAL, ex))
    }
    @Test fun stage_readFromLastJobName() {
        assertEquals(SurveyStage.TRIAL, SurveyStage.inName("강릉 교동 · 시굴 1지구 · 09-30"))
        assertEquals(SurveyStage.FULL, SurveyStage.inName("정밀발굴 3지구"))
        assertNull(SurveyStage.inName("작업1"))
    }
    @Test fun quickNotes_toggleInAndOut() {
        val a = QuickNotes.toggle("", "교란"); assertEquals("교란", a)
        val b = QuickNotes.toggle(a, "확인 필요"); assertEquals("교란 · 확인 필요", b)
        assertEquals("확인 필요", QuickNotes.toggle(b, "교란"))
        assertTrue(QuickNotes.has(b, "교란"))
    }
    @Test fun featureTypes_toggle_neverEmpty() {
        assertEquals(listOf("수혈", "주거지"), OneFingerTypes.toggle(listOf("수혈"), "주거지"))
        assertEquals(listOf("수혈"), OneFingerTypes.toggle(listOf("수혈", "주거지"), "주거지"))
        assertEquals(listOf("수혈"), OneFingerTypes.toggle(listOf("수혈"), "수혈"))
        assertTrue(OneFingerTypes.catalog(listOf("내가만든것")).first() == "내가만든것")
    }
    @Test fun areaAndGridChips_skipTaken() {
        assertEquals("C", OneFingerNames.areaChoices(setOf("A", "B")).first())
        assertEquals(listOf("G2", "G3"), OneFingerNames.gridChoices(setOf("G1"), 2))
        assertEquals("0.5 m", OneFingerNames.m(0.5)); assertEquals("2 m", OneFingerNames.m(2.0))
    }
    /** 남은 키보드 칸 목록이 늘지 않도록(새로 넣으면 이유를 적고 이 수를 바꿀 것) */
    @Test fun keyboardInputs_onlyKnownFallbacks() {
        val src = File("src/main/kotlin/kr/archsurvey/ui")
        val n = src.listFiles()!!.filter { it.extension == "kt" }.sumOf { f ->
            Regex("""(?<!fun )\b(Field|OutlinedTextField|BasicTextField|FormInput)\(""").findAll(f.readText()).count { m ->
                val line = f.readText().substring(0, m.range.first).substringAfterLast('\n'); !line.trimStart().startsWith("//") && !line.contains("fun ")
            }
        }
        assertTrue("키보드 칸 $n 개 — 0.1.39 기준 이하여야 함", n <= KEYBOARD_BUDGET)
    }
    companion object { const val KEYBOARD_BUDGET = 26 // 0.1.39 기준(보고서의 남은 키보드 목록) }
}
