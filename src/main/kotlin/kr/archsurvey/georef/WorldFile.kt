package kr.archsurvey.georef

import java.io.File

/**
 * 월드파일(.tfw 등) 6줄: A(열당 X) D(열당 Y 회전) B(행당 X 회전) E(행당 Y, 보통 음수) C F(좌상단 화소 **중심**).
 * 변환은 GDAL 규약(화소 모서리)으로 바꿔 돌려준다: 모서리 = 중심 − (A+B)/2, 중심 − (D+E)/2.
 */
object WorldFile {
    sealed class Result {
        data class Ok(val affine: Affine) : Result()
        data class Bad(val message: String) : Result()
    }

    fun parse(text: String): Result {
        val v = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (v.size < 6) return Result.Bad("월드파일은 6줄이 필요해요(${v.size}줄)")
        val n = DoubleArray(6)
        for (i in 0 until 6) n[i] = v[i].replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Result.Bad("월드파일 ${i + 1}줄이 숫자가 아니에요")
        val (a, d, b, e, c, f) = n.toList()
        if (a == 0.0 && b == 0.0 || d == 0.0 && e == 0.0) return Result.Bad("월드파일의 화소 크기가 0이에요")
        return Result.Ok(Affine(a, b, c - a / 2 - b / 2, d, e, f - d / 2 - e / 2))
    }
    private operator fun <T> List<T>.component6() = this[5]

    /** 영상 파일 옆의 월드파일 후보 이름들(대소문자 무시로 찾음). */
    fun sidecarNames(imageName: String): List<String> {
        val dot = imageName.lastIndexOf('.'); if (dot < 0) return emptyList()
        val base = imageName.substring(0, dot); val ext = imageName.substring(dot + 1).lowercase()
        val e = when (ext) {
            "tif", "tiff" -> listOf("tfw", "tifw", "wld")
            "jpg", "jpeg" -> listOf("jgw", "jpgw", "jpegw", "wld")
            "png" -> listOf("pgw", "pngw", "wld")
            "gif" -> listOf("gfw", "gifw", "wld")
            "bmp" -> listOf("bpw", "bmpw", "wld")
            else -> listOf("wld")
        }
        return e.map { "$base.$it" }
    }

    fun findSidecar(image: File, extensions: List<String>): File? {
        val dir = image.absoluteFile.parentFile ?: return null
        val names = dir.list() ?: return null
        for (cand in extensions) names.firstOrNull { it.equals(cand, ignoreCase = true) }?.let { return File(dir, it) }
        return null
    }
    fun findWorldFile(image: File) = findSidecar(image, sidecarNames(image.name))
    fun findPrj(image: File): File? {
        val dot = image.name.lastIndexOf('.'); val base = if (dot < 0) image.name else image.name.substring(0, dot)
        return findSidecar(image, listOf("$base.prj"))
    }
}
