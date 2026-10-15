package kr.archsurvey.pg.diag

import kr.archsurvey.pg.tag.TagDetection

/** P0.1 #5 사진 품질 게이트(저장 전). 값은 시작 제안 — 현장에서 조정 */
object Quality {
    const val SHARP_MIN = 12.0       // 4배 줄인 밝기의 라플라시안 분산
    const val DARK_MEAN = 35; const val BRIGHT_MEAN = 225
    const val CLIP_FRAC = 0.30       // 0–5 또는 250–255 화소 비율
    const val TAG_MIN_PX = 35.0      // KB0044805: AprilTag 최소 약 35 px
    const val TAG_GOOD_PX = 60.0

    data class Result(val ok: Boolean, val reason: String?, val sharpness: Double, val mean: Int, val clipped: Double) {
        val message: String? get() = when (reason) {
            "blur" -> "흔들렸어요 · 멈췄다 찍어 주세요"
            "dark" -> "너무 어두워요 · 밝은 곳에서 찍어 주세요"
            "bright" -> "너무 밝아요 · 햇빛을 등지고 찍어 주세요"
            "clipped" -> "빛이 너무 세요 · 그늘을 만들어 주세요"
            else -> null
        }
    }

    fun check(gray: ByteArray, w: Int, h: Int): Result {
        if (w < 16 || h < 16 || gray.size < w * h) return Result(false, "bad", 0.0, 0, 0.0)
        val s = 4; val sw = w / s; val sh = h / s
        val small = IntArray(sw * sh)
        var sum = 0L; var clip = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            val v = gray[(y * s) * w + x * s].toInt() and 255
            small[y * sw + x] = v; sum += v; if (v <= 5 || v >= 250) clip++
        }
        val n = sw * sh; val mean = (sum / n).toInt(); val cf = clip.toDouble() / n
        var ls = 0.0; var ls2 = 0.0; var m = 0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val l = (4 * small[i] - small[i - 1] - small[i + 1] - small[i - sw] - small[i + sw]).toDouble()
            ls += l; ls2 += l * l; m++
        }
        val varL = if (m == 0) 0.0 else ls2 / m - (ls / m) * (ls / m)
        val reason = when {
            mean < DARK_MEAN -> "dark"; mean > BRIGHT_MEAN -> "bright"; cf > CLIP_FRAC -> "clipped"; varL < SHARP_MIN -> "blur"; else -> null
        }
        return Result(reason == null, reason, varL, mean, cf)
    }

    /** 검은 네모 한 변(px, 네 변 평균) */
    fun tagPx(t: TagDetection): Double {
        val c = t.corners
        return (0 until 4).sumOf { k -> val j = (k + 1) % 4; Math.hypot(c[2 * j] - c[2 * k], c[2 * j + 1] - c[2 * k + 1]) } / 4
    }
    /** 35 px 미만 표적은 버림(iTwin 도 못 씀), 반환: (쓰는 표적, 버린 표적) */
    fun filterTags(tags: List<TagDetection>): Pair<List<TagDetection>, List<TagDetection>> = tags.partition { tagPx(it) >= TAG_MIN_PX }
}
