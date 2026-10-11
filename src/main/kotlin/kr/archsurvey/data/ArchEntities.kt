package kr.archsurvey.data

import androidx.room.*

/** 발굴 구역: 조사(작업) 안의 구획. 이름 + 지도 표시 색 번호 */
@Entity(tableName = "areas", indices = [Index("project")])
data class AreaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val project: String,
    val name: String,
    val colorIdx: Int = 0,
    val createdAtUtcMs: Long,
)

/** 그리드: 원점 O, 방향점 D(평면 좌표), 간격, 칸 수. 구역 하나에 여러 개 가능 */
@Entity(tableName = "grids", indices = [Index("project"), Index("areaId")])
data class GridEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val project: String,
    val areaId: Long,
    val name: String,
    val originN: Double,
    val originE: Double,
    val dirN: Double,
    val dirE: Double,
    val crsEpsg: Int,
    val spacingM: Double,
    val cols: Int,
    val rows: Int,
    val createdAtUtcMs: Long,
)

/** 유구 외곽: 꼭짓점 점들의 순서. closed=true 면 면(닫힘), false 면 선 */
@Entity(tableName = "outlines", indices = [Index("project")])
data class OutlineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val project: String,
    val areaId: Long? = null,
    /** E-001 형식 */
    val label: String,
    val closed: Boolean = false,
    /** false = 아직 이어서 취득 중 */
    val finished: Boolean = false,
    val createdAtUtcMs: Long,
    /** 0.1.35(DB v8): 무엇의 윤곽인가(feature 유구 / trench 트렌치 / area 조사구역, 옛 외곽은 ''), 유구 종류(수혈…), 이름 이력(줄바꿈) */
    @ColumnInfo(defaultValue = "''") val kind: String = "",
    @ColumnInfo(defaultValue = "''") val featureType: String = "",
    @ColumnInfo(defaultValue = "''") val history: String = "",
)

@Entity(
    tableName = "outline_points",
    foreignKeys = [
        ForeignKey(entity = OutlineEntity::class, parentColumns = ["id"], childColumns = ["outlineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["rowId"], childColumns = ["pointRowId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("outlineId"), Index("pointRowId")],
)
data class OutlinePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outlineId: Long,
    val pointRowId: Long,
    val orderNo: Int,
)

/** 재측량 이력: 덮어쓰기 직전의 이전 측정값을 보관한다 (사진·메모·종류는 점에 그대로 남는다) */
@Entity(
    tableName = "point_revisions",
    foreignKeys = [ForeignKey(entity = PointEntity::class, parentColumns = ["rowId"], childColumns = ["pointRowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pointRowId")],
)
data class PointRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointRowId: Long,
    /** 1 = 처음 측정한 값, 2 = 한 번 재측량 되기 전 값 ... (오래된 순) */
    val revNo: Int,
    val measuredAtUtcMs: Long,
    val lat: Double,
    val lon: Double,
    val northing: Double,
    val easting: Double,
    val crsEpsg: Int,
    val groundEllipsoidalHeight: Double?,
    val sigmaH: Double?,
    val fixQuality: Int,
    val allEpochsFixed: Boolean,
    val epochs: Int,
    val device: String,
    val cellName: String = "",
)

@Dao
abstract class ArchDao {
    // ---- 구역 ----
    @Insert abstract suspend fun insertArea(a: AreaEntity): Long
    @Query("SELECT * FROM areas WHERE project = :project ORDER BY id") abstract suspend fun areas(project: String): List<AreaEntity>
    @Query("UPDATE areas SET name = :name WHERE id = :id") abstract suspend fun renameArea(id: Long, name: String)
    @Query("DELETE FROM areas WHERE id = :id") abstract suspend fun deleteArea(id: Long)
    @Query("DELETE FROM areas WHERE project = :project") abstract suspend fun deleteAreasOf(project: String)

    // ---- 그리드 ----
    @Insert abstract suspend fun insertGrid(g: GridEntity): Long
    @Query("SELECT * FROM grids WHERE project = :project ORDER BY id") abstract suspend fun grids(project: String): List<GridEntity>
    @Query("DELETE FROM grids WHERE id = :id") abstract suspend fun deleteGrid(id: Long)
    @Query("DELETE FROM grids WHERE areaId = :areaId") abstract suspend fun deleteGridsOfArea(areaId: Long)
    @Query("DELETE FROM grids WHERE project = :project") abstract suspend fun deleteGridsOf(project: String)

    /** 구역·그리드를 지울 때 점은 지우지 않고 소속만 비운다 */
    @Query("UPDATE points SET areaId = NULL, gridId = NULL, cellName = '', cellU = NULL, cellV = NULL WHERE areaId = :areaId")
    abstract suspend fun clearPointsOfArea(areaId: Long)
    @Query("UPDATE points SET gridId = NULL, cellName = '', cellU = NULL, cellV = NULL WHERE gridId = :gridId")
    abstract suspend fun clearPointsOfGrid(gridId: Long)

    // ---- 외곽 ----
    @Insert abstract suspend fun insertOutline(o: OutlineEntity): Long
    @Insert abstract suspend fun insertOutlinePoint(p: OutlinePointEntity): Long
    @Query("SELECT * FROM outlines WHERE project = :project ORDER BY id") abstract suspend fun outlines(project: String): List<OutlineEntity>
    @Query("SELECT COUNT(*) FROM outlines WHERE project = :project") abstract suspend fun outlineCount(project: String): Int
    @Query("UPDATE outlines SET closed = :closed, finished = 1 WHERE id = :id") abstract suspend fun finishOutline(id: Long, closed: Boolean)
    @Query("DELETE FROM outlines WHERE id = :id") abstract suspend fun deleteOutline(id: Long)
    @Query("UPDATE outlines SET label = :label, history = :history, featureType = :featureType WHERE id = :id")
    abstract suspend fun renameOutline(id: Long, label: String, history: String, featureType: String)
    @Query("UPDATE outlines SET finished = 0, closed = 0 WHERE id = :id") abstract suspend fun reopenOutline(id: Long)
    @Query("DELETE FROM outlines WHERE project = :project") abstract suspend fun deleteOutlinesOf(project: String)
    @Query("DELETE FROM outline_points WHERE outlineId = :outlineId AND pointRowId = :pointRowId")
    abstract suspend fun removeOutlinePoint(outlineId: Long, pointRowId: Long)
    @Query("SELECT COALESCE(MAX(orderNo), 0) FROM outline_points WHERE outlineId = :outlineId") abstract suspend fun maxOrder(outlineId: Long): Int

    /** 살아 있는 점만, 순서대로 */
    @Query(
        "SELECT op.outlineId AS outlineId, op.orderNo AS orderNo, p.rowId AS pointRowId FROM outline_points op " +
            "JOIN points p ON p.rowId = op.pointRowId WHERE p.deletedAtUtcMs IS NULL AND op.outlineId IN (SELECT id FROM outlines WHERE project = :project) " +
            "ORDER BY op.outlineId, op.orderNo"
    )
    abstract suspend fun outlineVertices(project: String): List<OutlineVertexRow>

    @Query("SELECT op.outlineId FROM outline_points op WHERE op.pointRowId = :pointRowId LIMIT 1")
    abstract suspend fun outlineOf(pointRowId: Long): Long?

    // ---- 재측량 이력 ----
    @Insert abstract suspend fun insertRevision(r: PointRevisionEntity): Long
    @Query("SELECT * FROM point_revisions WHERE pointRowId = :rowId ORDER BY revNo") abstract suspend fun revisions(rowId: Long): List<PointRevisionEntity>
    @Query("DELETE FROM point_revisions WHERE id = :id") abstract suspend fun deleteRevision(id: Long)
    @Query("SELECT COUNT(*) FROM point_revisions WHERE pointRowId = :rowId") abstract suspend fun revisionCount(rowId: Long): Int
    @Query("SELECT pointRowId, COUNT(*) AS n FROM point_revisions WHERE pointRowId IN (SELECT rowId FROM points WHERE project = :project) GROUP BY pointRowId")
    abstract suspend fun revisionCounts(project: String): List<RevCountRow>
}

data class OutlineVertexRow(val outlineId: Long, val orderNo: Int, val pointRowId: Long)
data class RevCountRow(val pointRowId: Long, val n: Int)
