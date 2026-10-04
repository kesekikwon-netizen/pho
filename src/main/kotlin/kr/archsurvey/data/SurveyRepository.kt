package kr.archsurvey.data

import androidx.room.withTransaction

/** 사진 추가 결과. 5번째 사진은 LimitReached. */
sealed interface PhotoAddResult {
    data class Added(val id: Long) : PhotoAddResult
    data object LimitReached : PhotoAddResult
}

/**
 * 점 저장·되돌리기·사진 제한을 한곳에 모은 저장소 (WP-6).
 * - 저장은 즉시 디스크(WAL, 트랜잭션).
 * - 되돌리기: 가장 최근 살아 있는 점을 지우지 않고 숨긴다(소프트 삭제). 사진·기록은 그대로 남는다.
 * - 다시 실행: 가장 최근에 숨긴 점을 되살린다.
 * - 점 번호는 숨긴 점의 번호도 다시 쓰지 않는다.
 */
class SurveyRepository(private val db: SurveyDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.dao()

    suspend fun nextPointId(project: String, prefix: String = "P-"): String =
        PointIds.next(dao.lastPointId(project), prefix)

    /** 점 번호가 이미 있으면(숨긴 점 포함) 예외 대신 다음 번호로 올려 저장한다. 저장된 rowId 반환 */
    suspend fun savePoint(p: PointEntity): Long = db.withTransaction {
        var cand = p
        while (dao.countId(cand.project, cand.pointId) > 0) cand = cand.copy(pointId = PointIds.next(cand.pointId, "P-"))
        dao.insertPointRaw(cand)
    }

    suspend fun addPhoto(photo: PhotoEntity): PhotoAddResult {
        val id = dao.insertPhotoLimited(photo, PhotoLimit.MAX)
        return if (id < 0) PhotoAddResult.LimitReached else PhotoAddResult.Added(id)
    }

    /** 가장 최근 살아 있는 점을 숨기고 돌려준다. 없으면 null */
    suspend fun undoLast(project: String): PointEntity? = db.withTransaction {
        val p = dao.latestLive(project) ?: return@withTransaction null
        dao.hidePoint(p.rowId, clock())
        p
    }

    /** 가장 최근에 숨긴 점을 되살린다. 없으면 null */
    suspend fun redoLast(project: String): PointEntity? = db.withTransaction {
        val p = dao.latestHidden(project) ?: return@withTransaction null
        dao.unhidePoint(p.rowId)
        p
    }

    suspend fun livePoints(project: String): List<PointEntity> = dao.pointsOf(project)
}
