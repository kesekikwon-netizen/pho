package kr.archsurvey.job

import java.io.File

/**
 * 0.1.56 위원회 M6·S5·M5: 「PC로 보내기」 저장 규칙(세 앱 같은 뿌리).
 * - 새 뿌리: `Documents/발굴조사/[폴더/]<작업>/측량/보내기/<작업>_측량.zip`
 * - 예전 `Documents/ExcavationSurvey/…` 은 옮기지 않고 그대로 둔다(읽기만).
 * - 다시 보내기 = 같은 이름 덮어쓰기. 덮기 전 직전 1개만 `보내기/이전/` 으로(「(1)」 사본 없음).
 * 순수 Kotlin(JVM 시험).
 */
object SendFolder {
    const val ROOT = "발굴조사"
    const val APP = "측량"
    const val SEND = "보내기"
    const val PREV = "이전"
    const val BUTTON = "PC로 보내기"
    const val EMPTY = "잰 점이 없어요"

    /** 작업 폴더 기록(「ExcavationSurvey/[폴더/]작업」) → 새 뿌리 아래 보내기 폴더(「발굴조사/[폴더/]작업/측량/보내기」) */
    fun sendRel(jobRel: String): String {
        val (g, f) = JobFolders.split(jobRel.removePrefix(JobFolders.PUBLIC_PARENT + "/"))
        return listOfNotNull(ROOT, g, f, APP, SEND).joinToString("/")
    }

    fun zipName(job: String): String = JobFolders.sanitize(job) + "_측량.zip"

    /** 화면에 보일 경로 한 가지(보내기 앞·완료 같은 글) */
    fun display(jobRel: String, job: String): String =
        (listOf("내 파일", JobFolders.PUBLIC_PARENT) + sendRel(jobRel).split('/') + zipName(job)).joinToString(" › ")

    /** Android 9 이하(공용 Documents 못 씀): 앱 폴더 경로 한 줄 */
    fun displayLocal(f: File): String = "앱 폴더 › " + f.path

    /** 보낼 [zip] 을 [sendDir]/[name] 으로. 같은 이름이 있으면 먼저 이전/ 으로(직전 1개만). 반환 = (새 파일, 옮긴 이전 파일 또는 null) */
    fun place(sendDir: File, zip: File, name: String): Pair<File, File?> {
        sendDir.mkdirs()
        val dst = File(sendDir, name)
        var prev: File? = null
        if (dst.isFile) {
            val pd = File(sendDir, PREV).also { it.mkdirs() }
            val p = File(pd, name)
            if (p.exists()) p.delete()
            if (!dst.renameTo(p)) { dst.copyTo(p, overwrite = true); dst.delete() }
            prev = p
        }
        zip.inputStream().use { i -> kr.archsurvey.io.AtomicFiles.copy(i, dst) }
        return dst to prev
    }

    /** zip 안 info.txt: 만든 날짜시각(파일 이름엔 날짜 없음) */
    fun infoText(job: String, points: Int, madeAt: String): String =
        "작업: $job\r\n만든 때: $madeAt\r\n점: ${points}개\r\n앱: 발굴 측량\r\n"
}
