package kr.archsurvey.job

/** 0.1.39 조사 단계(작업 이름에 들어감) */
enum class SurveyStage(val labelKo: String) {
    TRIAL("시굴"), SAMPLE("표본"), FULL("정밀발굴");
    companion object {
        /** 작업 이름에 단계 글자가 있으면 그것(마지막에 쓴 단계를 이어 쓰려고) */
        fun inName(name: String?): SurveyStage? = name?.let { n -> entries.sortedByDescending { it.labelKo.length }.firstOrNull { it.labelKo in n } }
    }
}

/**
 * 0.1.39 키보드 없이 고르기만으로 만드는 작업 이름(현장 지시: 한 손가락·선택만).
 * 「강릉 교동 · 정밀발굴 2지구 · 10-12」 = [유적] · [단계] [번호]지구 · [월-일]. 유적이 없으면 앞부분 생략.
 * 작업명 규칙(30자, \ / : * ? " < > | 없음)을 지킨다 — 길면 유적 이름을 줄인다.
 */
object NewJobName {
    const val MAX = 30
    const val MAX_NO = 999

    fun build(site: String?, stage: SurveyStage, no: Int, mmdd: String): String {
        val tail = "${stage.labelKo} ${no.coerceIn(1, MAX_NO)}지구 · $mmdd"
        val s = site?.replace(Regex("[\\\\/:*?\"<>|]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (s.isEmpty()) return tail
        val room = MAX - tail.length - 3
        return if (room <= 0) tail else "${s.take(room).trim()} · $tail"
    }

    /** 같은 유적·단계에서 아직 안 쓴 가장 작은 지구 번호(1부터) */
    fun nextNo(site: String?, stage: SurveyStage, existing: Collection<String>): Int {
        val s = site?.trim().orEmpty()
        val re = Regex(Regex.escape(stage.labelKo) + " (\\d{1,3})지구")
        val used = existing.filter { s.isEmpty() || it.startsWith("$s ·") || it.startsWith(s.take(10)) }
            .mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = 1
        while (n in used && n < MAX_NO) n++
        return n
    }

    /** 이름이 기존 작업과 겹치면(같은 날 같은 지구) 「(2)」 */
    fun unique(name: String, existing: Collection<String>): String = JobFolders.unique(name, existing)
}
