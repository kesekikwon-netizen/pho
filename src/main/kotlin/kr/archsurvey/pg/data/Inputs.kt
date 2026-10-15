package kr.archsurvey.pg.data

/** 작업 이름(= 폴더 이름) 검사. 사용자가 직접 씀 */
object JobName {
    private val bad = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    fun clean(s: String) = s.trim().replace(Regex("\\s+"), " ")
    /** 문제 있으면 한 줄 한국어, 없으면 null */
    fun problem(raw: String, exists: (String) -> Boolean): String? {
        val s = clean(raw)
        return when {
            s.isEmpty() -> "이름을 써 주세요"
            s.length > 60 -> "이름이 너무 길어요 (60자까지)"
            bad.containsMatchIn(s) -> "\\ / : * ? \" < > | 는 쓸 수 없어요"
            s == "." || s == ".." || s.endsWith(".") -> "이름 끝에 점(.)은 안 돼요"
            exists(s) -> "같은 이름의 작업이 있어요"
            else -> null
        }
    }
}

/** 좌표 칸 입력 검사(국가 TM, EPSG:5185~5188 기준 대략 범위) */
object GcpCheck {
    /** "245,100.123"·"245100,123"(쉼표 소수) 모두 받아 숫자로. 못 읽으면 null */
    fun num(raw: String): Double? {
        var s = raw.trim().replace(" ", "")
        if (s.isEmpty()) return null
        if (s.count { it == ',' } == 1 && '.' !in s) s = s.replace(',', '.') else s = s.replace(",", "")
        return s.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
    /** 한 점에 대한 경고(없으면 null) */
    fun warn(e: Double, n: Double, h: Double, epsg: Int): String? {
        val tm = epsg in 5185..5188
        return when {
            tm && e > n && n < 1_000_000 && e in 100_000.0..2_000_000.0 && n in 50_000.0..1_000_000.0 -> "동E·북N 이 바뀐 것 같아요 (국가 TM 은 보통 동 < 북)"
            tm && (e < 10_000 || e > 1_000_000) -> "동E 값이 EPSG:$epsg 범위 밖 같아요"
            tm && (n < 100_000 || n > 2_000_000) -> "북N 값이 EPSG:$epsg 범위 밖 같아요"
            h < -100 || h > 3000 -> "높이 값을 확인해 주세요"
            else -> null
        }
    }
}
