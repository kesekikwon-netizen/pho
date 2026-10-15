package kr.archsurvey.gcp.data

import android.content.Context
import androidx.room.*
import kr.archsurvey.gcp.model.*

/** 0.1.1: 약호 없앰. 이름은 한 폰 안에서 하나뿐(폴더 이름) */
@Entity(tableName = "site", indices = [Index(value = ["name"], unique = true)])
data class SiteRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val epsg: Int, val createdAt: Long,
)

/** 대상 = 종류 + 호수. 한 유적에서 (종류, 호수) 하나뿐. [deleted] 는 쓰지 않음(지우기 없음) — 번호를 다시 쓰지 않으려고 칸만 둔다 */
@Entity(tableName = "obj", indices = [Index(value = ["siteId"]), Index(value = ["siteId", "kind", "no"], unique = true)])
data class ObjRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteId: Long, val kind: String, val no: Int, val createdAt: Long, val deleted: Boolean = false,
    /** 0.1.17(v3): 사용자가 직접 쓴 폴더 이름. 옛 행은 빈칸 */
    @ColumnInfo(defaultValue = "") val title: String = "",
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
    @Query("SELECT no FROM obj WHERE siteId = :siteId AND kind = :kind") suspend fun usedNos(siteId: Long, kind: String): List<Int>
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

@Database(entities = [SiteRow::class, ObjRow::class, SessionRow::class, ShotRow::class], version = 3, exportSchema = true)
abstract class GcpDb : RoomDatabase() {
    abstract fun dao(): GcpDao

    companion object {
        @Volatile private var inst: GcpDb? = null
        fun get(c: Context): GcpDb = inst ?: synchronized(this) {
            // 0.1.8: 파괴적 마이그레이션 없음. 알려진 모든 옛 스키마 → 현재 경로가 없으면 Room 이 멈춘다(기록을 지우지 않음)
            inst ?: Room.databaseBuilder(c.applicationContext, GcpDb::class.java, "gcp.db").addMigrations(*ALL_MIGRATIONS).build().also { inst = it }
        }

        /** 1(0.1.0: 약호 code·seq·name 있음) → 2(0.1.1: 종류+호수). 기록은 그대로 옮긴다. no = 같은 유적·종류 안 순서(seq 순) */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `site_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `epsg` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                // 이름이 겹치면(v1 은 code 만 유일) 뒤 것에 약호를 붙여 유일하게
                db.execSQL("INSERT INTO site_new (id, name, epsg, createdAt) SELECT s.id, CASE WHEN EXISTS(SELECT 1 FROM site t WHERE t.name = s.name AND t.id < s.id) THEN s.name || ' ' || s.code ELSE s.name END, s.epsg, s.createdAt FROM site s")
                db.execSQL("DROP TABLE site")
                db.execSQL("ALTER TABLE site_new RENAME TO site")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_site_name` ON `site` (`name`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `obj_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `siteId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `no` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL)")
                db.execSQL("INSERT INTO obj_new (id, siteId, kind, no, createdAt, deleted) SELECT o.id, o.siteId, o.kind, (SELECT COUNT(*) FROM obj p WHERE p.siteId = o.siteId AND p.kind = o.kind AND (p.seq < o.seq OR (p.seq = o.seq AND p.id <= o.id))), o.createdAt, o.deleted FROM obj o")
                db.execSQL("DROP TABLE obj")
                db.execSQL("ALTER TABLE obj_new RENAME TO obj")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_obj_siteId` ON `obj` (`siteId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_obj_siteId_kind_no` ON `obj` (`siteId`, `kind`, `no`)")
            }
        }
        /** 2 → 3(0.1.17): 대상에 사용자 이름 칸만 더한다. 기록은 그대로 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `obj` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
            }
        }
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

// ---- 장부 행 ↔ 모델 ----
fun SiteRow.model() = Site(id, name, epsg, createdAt)
fun ObjRow.model() = Obj(id, siteId, ObjKind.of(kind), no, createdAt, title)
fun SessionRow.model() = Session(id, objId, no, label, slots.split(',').mapNotNull { it.trim().toIntOrNull() }, createdAt, photoDoneAt, deliveredAt, changedAt, xmlSavedAt)
fun ShotRow.model() = Shot(id, sessionId, tag, e, n, h, lat, lon, sigmaH, sigmaU, epochs, measuredAt, active, poleM, apcM, device, note)
fun Session.row() = SessionRow(id, objId, no, label, slots.joinToString(","), createdAt, photoDoneAt, deliveredAt, changedAt, xmlSavedAt)
