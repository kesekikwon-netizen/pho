package kr.archsurvey.gcp.data

import android.content.Context
import androidx.room.*
import kr.archsurvey.gcp.model.*

@Entity(tableName = "site", indices = [Index(value = ["code"], unique = true)])
data class SiteRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val code: String, val epsg: Int, val createdAt: Long,
)

/** 대상. [deleted] 는 쓰지 않음(0.1.0 에는 지우기 없음) — 번호를 다시 쓰지 않으려고 칸만 둔다 */
@Entity(tableName = "obj", indices = [Index(value = ["siteId"]), Index(value = ["code"], unique = true)])
data class ObjRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteId: Long, val seq: Int, val code: String, val kind: String, val name: String,
    val oldNames: String = "", val operator: String = "", val createdAt: Long, val deleted: Boolean = false,
)

@Entity(tableName = "session", indices = [Index(value = ["objId"])])
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objId: Long, val no: Int, val label: String, val slots: String,
    val createdAt: Long, val photoDoneAt: Long? = null, val deliveredAt: Long? = null, val changedAt: Long, val xmlSavedAt: Long? = null,
)

@Entity(tableName = "shot", indices = [Index(value = ["sessionId"])])
data class ShotRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long, val tag: Int, val e: Double, val n: Double, val h: Double, val lat: Double, val lon: Double,
    val sigmaH: Double?, val sigmaU: Double?, val epochs: Int, val measuredAt: Long, val active: Boolean,
    val poleM: Double, val apcM: Double, val device: String, val note: String,
)

@Dao
interface GcpDao {
    @Query("SELECT * FROM site ORDER BY createdAt DESC") suspend fun sites(): List<SiteRow>
    @Query("SELECT * FROM site WHERE id = :id") suspend fun site(id: Long): SiteRow?
    @Insert suspend fun insertSite(r: SiteRow): Long

    @Query("SELECT * FROM obj WHERE siteId = :siteId AND deleted = 0") suspend fun objs(siteId: Long): List<ObjRow>
    @Query("SELECT seq FROM obj WHERE siteId = :siteId AND operator = :op") suspend fun seqs(siteId: Long, op: String): List<Int>
    @Query("SELECT * FROM obj WHERE id = :id") suspend fun obj(id: Long): ObjRow?
    @Insert suspend fun insertObj(r: ObjRow): Long
    @Update suspend fun updateObj(r: ObjRow)

    @Query("SELECT s.* FROM session s JOIN obj o ON o.id = s.objId WHERE o.siteId = :siteId") suspend fun sessions(siteId: Long): List<SessionRow>
    @Query("SELECT * FROM session WHERE id = :id") suspend fun session(id: Long): SessionRow?
    @Insert suspend fun insertSession(r: SessionRow): Long
    @Update suspend fun updateSession(r: SessionRow)
    @Query("UPDATE session SET deliveredAt = :at WHERE id IN (:ids)") suspend fun markDelivered(ids: List<Long>, at: Long)

    @Query("SELECT sh.* FROM shot sh JOIN session s ON s.id = sh.sessionId JOIN obj o ON o.id = s.objId WHERE o.siteId = :siteId") suspend fun shots(siteId: Long): List<ShotRow>
    @Query("SELECT * FROM shot WHERE sessionId = :sessionId") suspend fun shotsOf(sessionId: Long): List<ShotRow>
    @Insert suspend fun insertShot(r: ShotRow): Long
    @Query("UPDATE shot SET active = 0 WHERE sessionId = :sessionId AND tag = :tag") suspend fun deactivate(sessionId: Long, tag: Int)

    @Transaction
    suspend fun replaceShot(r: ShotRow): Long { deactivate(r.sessionId, r.tag); return insertShot(r) }
}

@Database(entities = [SiteRow::class, ObjRow::class, SessionRow::class, ShotRow::class], version = 1, exportSchema = true)
abstract class GcpDb : RoomDatabase() {
    abstract fun dao(): GcpDao

    companion object {
        @Volatile private var inst: GcpDb? = null
        fun get(c: Context): GcpDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, GcpDb::class.java, "gcp.db").build().also { inst = it }
        }
    }
}

// ---- 장부 행 ↔ 모델 ----
fun SiteRow.model() = Site(id, name, code, epsg, createdAt)
fun ObjRow.model() = Obj(id, siteId, seq, code, ObjKind.of(kind), name, oldNames.split('\n').filter { it.isNotBlank() }, createdAt, operator)
fun SessionRow.model() = Session(id, objId, no, label, slots.split(',').mapNotNull { it.trim().toIntOrNull() }, createdAt, photoDoneAt, deliveredAt, changedAt, xmlSavedAt)
fun ShotRow.model() = Shot(id, sessionId, tag, e, n, h, lat, lon, sigmaH, sigmaU, epochs, measuredAt, active, poleM, apcM, device, note)
fun Session.row() = SessionRow(id, objId, no, label, slots.joinToString(","), createdAt, photoDoneAt, deliveredAt, changedAt, xmlSavedAt)
