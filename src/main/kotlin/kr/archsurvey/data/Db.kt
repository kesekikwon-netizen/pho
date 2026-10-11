package kr.archsurvey.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 측량 점. 부가 속성(수량·재질·크기·시대 등)은 두지 않는다: 측량 필드 + 위치 종류(positionType) + 메모 1칸.
 * 되돌리기는 삭제가 아니라 deletedAtUtcMs 표시(소프트 삭제)로 하고, 점 번호는 삭제된 점 것도 다시 쓰지 않는다.
 */
@Entity(tableName = "points", indices = [Index(value = ["project", "pointId"], unique = true)])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val project: String,
    val pointId: String,
    /** 위치 종류(예: 경계점, 모서리, 기준점). 자유 입력 한 칸 */
    val positionType: String = "",
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
    /** 평균 측정 메타(DB v4): 평균 시작·종료 시각(UTC ms), 보정 연령 최대·최소(초), 고정·Float 표본 수. 옛 행은 null/0 */
    val avgStartedAtUtcMs: Long? = null,
    val avgEndedAtUtcMs: Long? = null,
    val maxCorrectionAgeSec: Double? = null,
    val minCorrectionAgeSec: Double? = null,
    @ColumnInfo(defaultValue = "0") val fixedEpochs: Int = 0,
    @ColumnInfo(defaultValue = "0") val floatEpochs: Int = 0,
    /** 폰 센서 폴 기울기(°) 평균·최대(DB v5). 센서 없음·꺼짐·옛 행은 null */
    val tiltDeg: Double? = null,
    val tiltMaxDeg: Double? = null,
    /** 평균에 쓴 각 측정값 "북,동,지면높이;…"(DB v6). 옛 행은 null */
    val samplesCsv: String? = null,
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
    /** 0.1.12(DB v7): groundMsl 을 낸 지오이드 모델 id (RECEIVER/EGM96/NONE). 옛 행은 '' = 수신기 해발고 */
    @ColumnInfo(defaultValue = "''") val heightModel: String = "",
    val device: String,
    val measuredAtUtcMs: Long,
    /** 고고학(Phase 2): 발굴 구역·그리드·칸 이름과 칸 안 위치(m). 그리드를 쓰지 않은 점은 비어 있음 */
    val areaId: Long? = null,
    val gridId: Long? = null,
    @ColumnInfo(defaultValue = "''") val cellName: String = "",
    val cellU: Double? = null,
    val cellV: Double? = null,
    /** 0.1.35(DB v8) 발굴 속성: 종류 id(feature/artifact/level/…), 속한 유구(outlines.id), 유물 번호, 레벨 종류(detect/top/floor), 꼬리표(토층 A·A′, 기준점 BM-1, 말뚝 B-4). 옛 행은 비어 있음 */
    @ColumnInfo(defaultValue = "''") val digKind: String = "",
    val featureId: Long? = null,
    val artifactNo: Int? = null,
    @ColumnInfo(defaultValue = "''") val levelKind: String = "",
    @ColumnInfo(defaultValue = "''") val digTag: String = "",
    /** null = 살아 있는 점. 값이 있으면 되돌리기로 숨겨진 점 */
    val deletedAtUtcMs: Long? = null,
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

/** 음성 메모: 점당 최대 [VoiceLimit.MAX]개. AAC(m4a) 16kHz 모노 32kbps, 최대 30초 */
@Entity(
    tableName = "voice_memos",
    foreignKeys = [ForeignKey(entity = PointEntity::class, parentColumns = ["rowId"], childColumns = ["pointRowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pointRowId")],
)
data class VoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointRowId: Long,
    /** 앱 외부 파일 폴더(Recordings) 기준 상대경로 */
    val relPath: String,
    val durationMs: Long,
    val addedAtUtcMs: Long,
)

data class PointWithCount(
    @Embedded val point: PointEntity,
    val photoCount: Int,
)

@Dao
abstract class SurveyDao {
    @Query("SELECT * FROM points WHERE project = :project AND deletedAtUtcMs IS NULL AND (:areaId IS NULL OR areaId = :areaId) ORDER BY measuredAtUtcMs ASC")
    abstract suspend fun pointsOfArea(project: String, areaId: Long?): List<PointEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPointRaw(p: PointEntity): Long

    @Query("SELECT COUNT(*) FROM points WHERE project = :project AND pointId = :pointId")
    abstract suspend fun countId(project: String, pointId: String): Int

    @Update abstract suspend fun updatePoint(p: PointEntity)

    @Query("UPDATE points SET memo = :memo, positionType = :positionType WHERE rowId = :rowId")
    abstract suspend fun updateMemoType(rowId: Long, memo: String, positionType: String)

    /** 0.1.35 발굴 속성만 바꾼다(좌표·번호는 그대로) */
    @Query("UPDATE points SET featureId = :featureId, artifactNo = :artifactNo, levelKind = :levelKind, digTag = :digTag WHERE rowId = :rowId")
    abstract suspend fun updateDig(rowId: Long, featureId: Long?, artifactNo: Int?, levelKind: String, digTag: String)

    /** 0.1.35 번호 계산용: 지운 점 포함 모든 점 이름 */
    @Query("SELECT pointId FROM points WHERE project = :project")
    abstract suspend fun allPointIds(project: String): List<String>

    @Query("SELECT p.*, (SELECT COUNT(*) FROM photos WHERE pointRowId = p.rowId) AS photoCount FROM points p WHERE project = :project AND deletedAtUtcMs IS NULL ORDER BY measuredAtUtcMs DESC")
    abstract fun observePoints(project: String): Flow<List<PointWithCount>>

    @Query("SELECT * FROM points WHERE project = :project AND deletedAtUtcMs IS NULL ORDER BY measuredAtUtcMs ASC")
    abstract suspend fun pointsOf(project: String): List<PointEntity>

    @Query("SELECT * FROM points WHERE rowId = :rowId")
    abstract fun observePoint(rowId: Long): Flow<PointEntity?>

    @Query("SELECT * FROM points WHERE rowId = :rowId")
    abstract suspend fun getPoint(rowId: Long): PointEntity?

    @Query("SELECT COUNT(*) FROM points WHERE project = :project AND deletedAtUtcMs IS NULL")
    abstract suspend fun liveCount(project: String): Int

    @Query("SELECT COUNT(*) FROM photos WHERE pointRowId IN (SELECT rowId FROM points WHERE project = :project AND deletedAtUtcMs IS NULL)")
    abstract suspend fun livePhotoCount(project: String): Int

    @Query("SELECT COUNT(*) FROM voice_memos WHERE pointRowId IN (SELECT rowId FROM points WHERE project = :project AND deletedAtUtcMs IS NULL)")
    abstract suspend fun liveVoiceCount(project: String): Int

    @Query("SELECT MIN(measuredAtUtcMs) FROM points WHERE project = :project AND deletedAtUtcMs IS NULL")
    abstract suspend fun firstAt(project: String): Long?

    @Query("SELECT MAX(measuredAtUtcMs) FROM points WHERE project = :project AND deletedAtUtcMs IS NULL")
    abstract suspend fun lastAt(project: String): Long?

    @Query("SELECT COUNT(*) FROM points WHERE project = :project AND deletedAtUtcMs IS NULL AND device LIKE '%가짜%'")
    abstract suspend fun fakeCount(project: String): Int

    /** 작업의 모든 점(되돌린 점 포함)을 지운다. 사진·음성 행은 외래키 연쇄 삭제 */
    @Query("DELETE FROM points WHERE project = :project")
    abstract suspend fun deleteProject(project: String)

    @Query("SELECT DISTINCT project FROM points ORDER BY project")
    abstract suspend fun projects(): List<String>

    @Query("SELECT pointId FROM points WHERE project = :project ORDER BY rowId DESC LIMIT 1" /* 삭제된 점 포함: 번호 재사용 금지 */)
    abstract suspend fun lastPointId(project: String): String?

    @Delete abstract suspend fun deletePoint(p: PointEntity)

    @Query("SELECT * FROM points WHERE project = :project AND deletedAtUtcMs IS NULL ORDER BY rowId DESC LIMIT 1")
    abstract suspend fun latestLive(project: String): PointEntity?

    @Query("SELECT * FROM points WHERE project = :project AND deletedAtUtcMs IS NOT NULL ORDER BY deletedAtUtcMs DESC, rowId DESC LIMIT 1")
    abstract suspend fun latestHidden(project: String): PointEntity?

    @Query("UPDATE points SET deletedAtUtcMs = :at WHERE rowId = :rowId")
    abstract suspend fun hidePoint(rowId: Long, at: Long)

    @Query("UPDATE points SET deletedAtUtcMs = NULL WHERE rowId = :rowId")
    abstract suspend fun unhidePoint(rowId: Long)

    @Query("SELECT COUNT(*) FROM photos WHERE pointRowId = :rowId")
    abstract suspend fun photoCount(rowId: Long): Int

    @Insert abstract suspend fun insertPhoto(p: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE pointRowId = :rowId ORDER BY id ASC")
    abstract fun observePhotos(rowId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE pointRowId = :rowId ORDER BY id ASC")
    abstract suspend fun photosOf(rowId: Long): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE relPath = :rel")
    abstract suspend fun countPhotoPath(rel: String): Int

    @Delete abstract suspend fun deletePhoto(p: PhotoEntity)

    @Query("SELECT * FROM photos WHERE id = :id")
    abstract suspend fun photoById(id: Long): PhotoEntity?

    @Query("SELECT * FROM voice_memos WHERE id = :id")
    abstract suspend fun voiceById(id: Long): VoiceEntity?

    @Insert abstract suspend fun insertVoice(v: VoiceEntity): Long

    @Query("SELECT * FROM voice_memos WHERE pointRowId = :rowId ORDER BY id ASC")
    abstract suspend fun voicesOf(rowId: Long): List<VoiceEntity>

    @Query("SELECT COUNT(*) FROM voice_memos WHERE pointRowId = :rowId")
    abstract suspend fun voiceCount(rowId: Long): Int

    @Delete abstract suspend fun deleteVoice(v: VoiceEntity)

    @androidx.room.Transaction
    open suspend fun insertVoiceLimited(v: VoiceEntity, max: Int = VoiceLimit.MAX): Long {
        if (voiceCount(v.pointRowId) >= max) return -1L
        return insertVoice(v)
    }

    /** 점당 사진 최대 장수. 5번째는 거부한다(동시 호출에도 4장을 넘지 않도록 한 트랜잭션에서 확인) */
    @androidx.room.Transaction
    open suspend fun insertPhotoLimited(p: PhotoEntity, max: Int = PhotoLimit.MAX): Long {
        if (photoCount(p.pointRowId) >= max) return -1L
        return insertPhoto(p)
    }
}

object PhotoLimit { const val MAX = 4 }
object VoiceLimit { const val MAX = 3 }

@Database(
    entities = [
        PointEntity::class, PhotoEntity::class, VoiceEntity::class,
        AreaEntity::class, GridEntity::class, OutlineEntity::class, OutlinePointEntity::class, PointRevisionEntity::class,
    ],
    version = 8, exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3), androidx.room.AutoMigration(from = 3, to = 4), androidx.room.AutoMigration(from = 4, to = 5), androidx.room.AutoMigration(from = 5, to = 6), androidx.room.AutoMigration(from = 6, to = 7), androidx.room.AutoMigration(from = 7, to = 8)],
)
abstract class SurveyDb : RoomDatabase() {
    abstract fun dao(): SurveyDao
    abstract fun arch(): ArchDao

    companion object {
        @Volatile private var inst: SurveyDb? = null
        /** 복구 직전 등: 열린 DB 를 닫고 다음 get() 이 새로 열게 한다 */
        fun closeInstance() = synchronized(this) { inst?.close(); inst = null }

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
