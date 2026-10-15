package kr.archsurvey.pg.tag

/** AprilTag 3 (BSD-2, AprilRobotics) JNI. libpgapriltag.so = native/pg_apriltag_jni.c + third_party/apriltag */
object AprilTagNative {
    @Volatile private var loaded: Boolean? = null
    fun available(): Boolean {
        loaded?.let { return it }
        val ok = try { System.loadLibrary("pgapriltag"); true } catch (_: Throwable) { false }
        loaded = ok; return ok
    }
    /** 결과: 표적당 12개 [id, hamming, cx, cy, x0,y0, x1,y1, x2,y2, x3,y3] (원본 화소, 왼쪽 위 화소 중심 = 0,0 에 가까운 AprilTag 관례) */
    @JvmStatic external fun detect(gray: ByteArray, w: Int, h: Int, family: Int, decimate: Float, threads: Int, maxHamming: Int): DoubleArray
}

/** 표적 계열. iTwin Capture 「Library: AprilTags」 = tag36h11 (사용자 표적 1~4 를 디코딩해 확인) */
enum class TagFamily(val code: Int, val short: String) { TAG36H11(0, "36h11"), TAG25H9(1, "25h9"), TAG16H5(2, "16h5"), STANDARD41H12(3, "41h12") }

data class TagDetection(
    val id: Int, val hamming: Int, val cx: Double, val cy: Double,
    /** 4 꼭짓점 (x0,y0..x3,y3) */
    val corners: DoubleArray,
) {
    /** iTwin Capture·사진 기준점 앱 이름 「Target #N」 */
    val name: String get() = TargetNaming.name(id)
    val target: Int get() = TargetNaming.targetNo(id)
}

/**
 * iTwin Capture Modeler 가 인쇄하는 표적 「Target #N, Library: AprilTags」 은 tag36h11 의 id = N − 1 이다
 * (targets/AprilTags_1..4.png → id 0..3, hamming 0, AprilTag 3 로 디코딩). 사진 기준점 앱 TXT 도 「Target #N」.
 */
object TargetNaming {
    /**
     * 기준점 이름 형식(설정, 선택만). 기본 TARGET(사진 기준점 앱·iTwin 표적 그림과 같음).
     * Bentley KB0044805 는 「AprilTag #X」 — Flex 에서 X 가 tag id(0부터)인지 표적 번호(1부터)인지 [미검증] → 둘 다 고를 수 있게.
     */
    enum class Style(val label: String) { TARGET("Target #N"), APRILTAG_N("AprilTag #N"), APRILTAG_ID("AprilTag #ID(N−1)") }
    @Volatile var style: Style = Style.TARGET
    fun targetNo(tagId: Int) = tagId + 1
    fun tagId(targetNo: Int) = targetNo - 1
    fun name(tagId: Int, s: Style = style) = when (s) {
        Style.TARGET -> "Target #${targetNo(tagId)}"
        Style.APRILTAG_N -> "AprilTag #${targetNo(tagId)}"
        Style.APRILTAG_ID -> "AprilTag #$tagId"
    }
    private val re = Regex("""^\s*(Target\s*#|AprilTag\s*#|TAG_36h11_)\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)
    /** 이름 → 표적 번호 N. 「TAG_36h11_07」 은 tag id(+1). 「AprilTag #k」 는 지금 형식이 ID 면 +1 */
    fun parseTargetNo(name: String, s: Style = style): Int? {
        val m = re.find(name) ?: return null
        val n = m.groupValues[2].toInt()
        val pre = m.groupValues[1].lowercase()
        return when {
            pre.startsWith("tag_") -> n + 1
            pre.startsWith("apriltag") && s == Style.APRILTAG_ID -> n + 1
            else -> n
        }
    }
}

object TagDetector {
    /** [gray] 8비트 밝기 w×h. 기본: tag36h11, 오류 허용 0비트(오검출 막기 — 16h5 같은 작은 계열은 흙·돌에서 거짓 검출이 많다) */
    fun detect(gray: ByteArray, w: Int, h: Int, family: TagFamily = TagFamily.TAG36H11, decimate: Float = 2f, threads: Int = 4, maxHamming: Int = 1): List<TagDetection> {
        if (!AprilTagNative.available()) return emptyList()
        val r = AprilTagNative.detect(gray, w, h, family.code, decimate, threads, maxHamming)
        val out = ArrayList<TagDetection>(r.size / 12)
        var i = 0
        while (i + 12 <= r.size) {
            out += TagDetection(r[i].toInt(), r[i + 1].toInt(), r[i + 2], r[i + 3], r.copyOfRange(i + 4, i + 12))
            i += 12
        }
        // 가장자리에 걸린 표적(흰 테가 잘림)은 중심이 치우친다(합성 시험에서 1.8 px) → 흰 테 대부분(한 변의 1/10)까지 화면 안일 때만
        out.retainAll { d ->
            val xs = (0 until 4).map { d.corners[2 * it] }; val ys = (0 until 4).map { d.corners[2 * it + 1] }
            val m = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) / 10
            xs.min() - m >= 0 && ys.min() - m >= 0 && xs.max() + m <= w - 1 && ys.max() + m <= h - 1
        }
        // 같은 id 가 두 번 나오면(반사 등) 오류 비트가 적은 것 하나만
        return out.groupBy { it.id }.map { (_, l) -> l.minBy { it.hamming } }.sortedBy { it.id }
    }
}
