package kr.archsurvey.job

/**
 * 0.1.56 위원회 M7(X-7·G-1): 기준점(표적) 이름 규칙 — 세 앱 같음.
 * 파일(CSV·DXF·Shape 등) = 「Target #N」, 화면 = 「표적 #N」. 설정 없음.
 */
object TargetNames {
    private val any = Regex("""^\s*(?:Target|표적|target|TARGET)\s*#?\s*(\d+)\s*$""")
    fun file(n: Int) = "Target #$n"
    fun screen(n: Int) = "표적 #$n"
    fun numberOf(name: String): Int? = any.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
    /** 파일에 쓸 이름(표적 이름이 아니면 그대로) */
    fun toFile(name: String): String = numberOf(name)?.let { file(it) } ?: name
    /** 화면에 보일 이름(표적 이름이 아니면 그대로) */
    fun toScreen(name: String): String = numberOf(name)?.let { screen(it) } ?: name
}
