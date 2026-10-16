package kr.archsurvey.pg.diag

import kr.archsurvey.pg.data.Gcp
import kr.archsurvey.pg.geo.V3

/** 사용자에게 보일 한 줄(한국어) + 복구 정책. Android 없이 단위시험 가능한 순수 함수만 */
object Msg {
    const val AR_DECLINED = "AR 설치를 취소했어요 · 사진만 모드로 찍을 수 있어요"
    const val AR_UNSUPPORTED = "이 폰은 실시간 3D 가 안 돼요 · 사진·표적·내보내기는 돼요"
    const val AR_OPEN_FAIL = "AR 을 열지 못했어요 · 사진만 모드로 바꿨어요"
    const val CAM_DENIED = "촬영하려면 카메라 허용이 필요해요"
    const val CAM_BUSY = "다른 앱이 카메라를 쓰고 있어요 · 닫고 다시 들어오세요"
    const val NO_DEPTH = "깊이 미지원 폰 · 점으로 표시해요"
    const val STORAGE_LOW = "저장 공간이 부족해요 · 사진을 더 찍을 수 없어요"
    const val SHOT_SAVE_FAIL = "사진을 저장하지 못했어요 · 저장 공간을 확인하세요"
    const val EXPORT_SPACE = "내보낼 공간이 부족해요 · 사진 크기만큼 비워 주세요"
    /** S8: 「1.2 GB 더 필요해요」 */
    fun spaceNeed(bytes: Long): String { val gb = maxOf(bytes, 0L) / 1e9; return if (gb >= 0.1) "저장 공간이 %.1f GB 더 필요해요".format(java.util.Locale.US, gb) else "저장 공간이 %d MB 더 필요해요".format(java.util.Locale.US, maxOf(1L, bytes / 1_000_000)) }
    const val EXPORT_FAIL = "내보내기에 실패했어요 · 다시 눌러 주세요"
    const val NATIVE_MISSING = "표적 인식 부품을 못 불러왔어요 · 사진은 계속 저장돼요"
    const val SESSION_RESTORED = "앞서 저장된 작업을 다시 열었어요"
    const val SESSION_BROKEN = "작업 파일이 손상돼 백업으로 열었어요"
    const val MEMORY_LOW = "메모리가 부족해 3D 를 가볍게 줄였어요"
    const val TOO_BIG = "작업 범위가 너무 넓어요 · 새 작업으로 나눠 찍으세요"

    /** ARCore TrackingFailureReason 이름 → 안내 */
    fun tracking(reason: String?): String = when (reason) {
        "INSUFFICIENT_LIGHT" -> "너무 어두워요 · 밝은 곳에서 찍어 주세요"
        "EXCESSIVE_MOTION" -> "천천히 움직여 주세요"
        "INSUFFICIENT_FEATURES" -> "무늬가 없는 곳이에요 · 흙·돌이 보이게 비춰 주세요"
        "CAMERA_UNAVAILABLE" -> CAM_BUSY
        "BAD_STATE" -> "AR 이 멈췄어요 · 뒤로 갔다 다시 들어오세요"
        else -> "폰을 천천히 움직여 주세요"
    }
}

/** 발열 단계(PowerManager.THERMAL_STATUS_*: 0 없음 … 3 SEVERE, 4 CRITICAL …) → 처리 속도 */
data class Thermal(val depthMs: Long, val meshMs: Long, val autoShot: Boolean, val message: String?)
object ThermalPolicy {
    /** P0.1 #6: 열 여유(getThermalHeadroom, 1.0 = 조절 시작) 를 단계로 합침. NaN·음수(미지원)는 무시 */
    fun combine(status: Int, headroom: Float): Int {
        // 0.0.19: 열 여유는 예측값일 뿐(실기: 상태 0~2, 피부 39 °C 인데 0.95~0.97 → 예전엔 3 「뜨거워요」로 올림).
        // OS 기준(0.9 = MODERATE)까지만 반영하고 SEVERE 이상은 OS 가 직접 알릴 때만
        if (headroom.isNaN() || headroom < 0f) return status
        val h = if (headroom >= 0.9f) 2 else 0
        return maxOf(status, h)
    }
    /** 0.0.19: 사진·점군은 멈추지 않음. 뜨거우면 갱신 간격만 늘리고 안내 */
    fun of(status: Int): Thermal = when {
        status >= 4 -> Thermal(2000, 3000, true, "폰이 매우 뜨거워요 · 그늘에서 잠시 쉬면 좋아요(촬영은 계속돼요)")
        status == 3 -> Thermal(1000, 2000, true, "폰이 뜨거워요 · 3D 갱신을 조금 늦췄어요(촬영은 계속돼요)")
        status == 2 -> Thermal(700, 1000, true, null)
        else -> Thermal(350, 500, true, null)
    }
}

/** 7변수 맞춤 가능 여부 */
object FitCheck {
    enum class R { OK, NO_GCP, LT3, NOT_SEEN, COLLINEAR }
    fun check(gcps: List<Gcp>, tagPos: Map<Int, V3>): Pair<R, String?> {
        if (gcps.isEmpty()) return R.NO_GCP to null
        val common = gcps.filter { it.target in tagPos }
        if (gcps.size < 3) return R.LT3 to "좌표가 ${gcps.size}점뿐이에요 · 3점 이상 필요해요"
        if (common.size < 3) return R.NOT_SEEN to "좌표 있는 표적이 ${common.size}개만 보였어요 · 3개 이상 2장씩 찍어 주세요"
        val p = common.map { V3(it.e, it.n, it.h) }
        val c = p.reduce { a, b -> a + b } * (1.0 / p.size)
        var area = 0.0
        val q = p.map { it - c }
        for (i in q.indices) for (j in i + 1 until q.size) area = maxOf(area, q[i].cross(q[j]).norm())
        val span = q.maxOf { it.norm() }
        if (span < 1e-6 || area / (span * span) < 0.02) return R.COLLINEAR to "표적이 한 줄로 놓였어요 · 줄 밖에 표적 하나를 더 놓아 주세요"
        return R.OK to null
    }
}

/** 저장 공간 기준 */
object StoragePolicy {
    const val MIN_FREE_FOR_SHOT = 150L * 1024 * 1024
    fun canShoot(free: Long) = free >= MIN_FREE_FOR_SHOT
    /** 내보내기 = 사진 복사 + 여유 20 MB */
    fun canExport(free: Long, photoBytes: Long) = free >= photoBytes + 20L * 1024 * 1024
}
