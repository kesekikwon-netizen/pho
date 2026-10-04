package kr.archsurvey.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 측량 포인트. 부가 속성(수량·재질 등)은 두지 않는다: 순수 측량 필드 + 간단 메모 + 라벨(code). */
@Entity(tableName = "points", indices = [Index(value = ["project", "pointId"], unique = true)])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val project: String,
    val pointId: String,
    val code: String = "",
    val memo: String = "",
    val lat: Double,
    val lon: Double,
    val ellipsoidalHeight: Double?,
    val altitudeMsl: Double?,
    val geoidSeparation: Double?,
    val northing: Double,
    val easting: Double,
    val crsEpsg: Int,
    val fixQuality: Int,
    val allEpochsFixed: Boolean,
    val satellites: Int,
    val hdop: Double?,
    val sigmaE: Double?,
    val sigmaN: Double?,
    val sigmaH: Double?,
    val sigmaU: Double?,
    val correctionAgeSec: Double?,
    val baseStationId: String?,
    val epochs: Int,
    /** 지면→안테나 위상중심(APC) 총 높이(m) = 직고(+사선환산) + 기준점→APC 오프셋. 내부 저장은 미터 */
    val antennaHeight: Double,
    /** 안테나 모델(DB id), 기준점 종류, 입력 방식, 입력 원값, 기준점→APC 오프셋(수직/수평) */
    val antennaModel: String = "",
    val antennaRefPoint: String = "",
    val antennaInputMode: String = "vertical",
    val antennaInputValue: Double = 0.0,
    val antennaPcoV: Double = 0.0,
    val antennaPcoH: Double = 0.0,
    /** 고급: 부품 길이 합산(기본 0), 앱이 더한 베이스 연장봉(m) */
    val antennaExtraPartsM: Double = 0.0,
    val antennaBaseExtM: Double = 0.0,
    /** true: 수신기에서 이미 반영됨 → 앱은 보정하지 않음 */
    val antennaReceiverApplied: Boolean = false,
    /** 지면 높이 = 수신기 고도(APC) − 안테나 높이 (타원체고/MSL 각각). 수신기 반영 시 원값 그대로 */
    val groundEllipsoidalHeight: Double? = null,
    val groundMsl: Double? = null,
    val device: String,
    val measuredAtUtcMs: Long,
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = PointEntity::class, parentColumns = ["rowId"], childColumns = ["pointRowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pointRowId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointRowId: Long,
    /** 앱 외부 파일 폴더 기준 상대경로 */
    val relPath: String,
    val thumbRelPath: String?,
    val source: String, // CAMERA | GALLERY
    val addedAtUtcMs: Long,
    val width: Int,
    val height: Int,
)

data class PointWithCount(
    @Embedded val point: PointEntity,
    val photoCount: Int,
)

@Dao
interface SurveyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPointRaw(p: PointEntity): Long

    @Query("SELECT COUNT(*) FROM points WHERE project = :project AND pointId = :pointId")
    suspend fun countId(project: String, pointId: String): Int

    @Update suspend fun updatePoint(p: PointEntity)

    @Query("UPDATE points SET memo = :memo, code = :code WHERE rowId = :rowId")
    suspend fun updateMemoCode(rowId: Long, memo: String, code: String)

    @Query("SELECT p.*, (SELECT COUNT(*) FROM photos WHERE pointRowId = p.rowId) AS photoCount FROM points p WHERE project = :project ORDER BY measuredAtUtcMs DESC")
    fun observePoints(project: String): Flow<List<PointWithCount>>

    @Query("SELECT * FROM points WHERE project = :project ORDER BY measuredAtUtcMs ASC")
    suspend fun pointsOf(project: String): List<PointEntity>

    @Query("SELECT * FROM points WHERE rowId = :rowId")
    fun observePoint(rowId: Long): Flow<PointEntity?>

    @Query("SELECT * FROM points WHERE rowId = :rowId")
    suspend fun getPoint(rowId: Long): PointEntity?

    @Query("SELECT DISTINCT project FROM points ORDER BY project")
    suspend fun projects(): List<String>

    @Query("SELECT pointId FROM points WHERE project = :project ORDER BY rowId DESC LIMIT 1")
    suspend fun lastPointId(project: String): String?

    @Delete suspend fun deletePoint(p: PointEntity)

    @Insert suspend fun insertPhoto(p: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE pointRowId = :rowId ORDER BY id ASC")
    fun observePhotos(rowId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE pointRowId = :rowId ORDER BY id ASC")
    suspend fun photosOf(rowId: Long): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE relPath = :rel")
    suspend fun countPhotoPath(rel: String): Int

    @Delete suspend fun deletePhoto(p: PhotoEntity)
}

@Database(entities = [PointEntity::class, PhotoEntity::class], version = 1, exportSchema = false)
abstract class SurveyDb : RoomDatabase() {
    abstract fun dao(): SurveyDao

    companion object {
        @Volatile private var inst: SurveyDb? = null
        fun get(ctx: Context): SurveyDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, SurveyDb::class.java, "survey.db")
                // 저장 즉시 디스크 반영: WAL + 트랜잭션 커밋 (프로세스 사망 대비)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { inst = it }
        }
    }
}

/** 점 ID 증가 규칙: 끝의 숫자를 +1 (자릿수 유지). 예) P-0042 -> P-0043, A9 -> A10 */
object PointIds {
    fun next(last: String?, prefix: String): String {
        if (last.isNullOrEmpty()) return prefix + "0001"
        val m = Regex("^(.*?)(\\d+)$").find(last) ?: return last + "1"
        val (head, num) = m.destructured
        val n = (num.toLongOrNull() ?: 0L) + 1
        return head + n.toString().padStart(num.length, '0')
    }
}
