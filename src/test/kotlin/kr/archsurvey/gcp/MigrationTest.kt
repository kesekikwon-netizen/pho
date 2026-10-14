package kr.archsurvey.gcp

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.archsurvey.gcp.data.GcpDb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 0.1.8: 모든 옛 스키마(schemas/…/N.json) → 현재 버전, 기록이 하나도 사라지지 않아야 한다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val schemaDir = File("schemas/kr.archsurvey.gcp.data.GcpDb")

    /** 내보낸 스키마 JSON 그대로 옛 DB 를 만든다 */
    private fun createFromSchema(name: String, version: Int, fill: (SQLiteDatabase) -> Unit) {
        val f = ctx.getDatabasePath(name); f.parentFile?.mkdirs(); f.delete()
        val j = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        val ents = j.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i); val t = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
            val idx = e.optJSONArray("indices") ?: continue
            for (k in 0 until idx.length()) db.execSQL(idx.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", t))
        }
        val setup = j.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        fill(db)
        db.version = version
        db.close()
    }

    @Test fun everyPastSchemaHasAMigrationPath() {
        val cur = Room.inMemoryDatabaseBuilder(ctx, GcpDb::class.java).allowMainThreadQueries().build().let { val v = it.openHelper.writableDatabase.version; it.close(); v }
        assertEquals("최신 스키마 JSON 이 있어야 함", cur, schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max())
        val past = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.filter { it < cur }
        assertTrue(past.isNotEmpty())
        for (v in past) {
            var at = v
            while (at < cur) { at = GcpDb.ALL_MIGRATIONS.firstOrNull { it.startVersion == at }?.endVersion ?: fail("no migration from $at") as Int }
        }
    }

    @Test fun migrate1to2KeepsEveryRecord() = runBlocking {
        createFromSchema("m12.db", 1) { db ->
            db.execSQL("INSERT INTO site VALUES (1,'강릉 유적','GN',5187,1000)")
            db.execSQL("INSERT INTO site VALUES (2,'강릉 유적','GN2',5187,2000)")   // v1 은 이름 중복 허용
            db.execSQL("INSERT INTO obj VALUES (10,1,2,'A2','PIT','수혈 2','','',1100,0)")
            db.execSQL("INSERT INTO obj VALUES (11,1,1,'A1','PIT','수혈 1','','',1050,0)")
            db.execSQL("INSERT INTO obj VALUES (12,1,1,'B1','TRENCH','트렌치 1','','',1200,0)")
            db.execSQL("INSERT INTO session VALUES (100,10,1,'검출면','1,2,3,4',1100,NULL,NULL,1300,NULL)")
            db.execSQL("INSERT INTO shot VALUES (1000,100,3,574001.76,194883.51,45.1,37.75,128.9,0.005,0.01,10,1300,1,1.6,0.06,'R10','')")
        }
        val room = Room.databaseBuilder(ctx, GcpDb::class.java, "m12.db").addMigrations(*GcpDb.ALL_MIGRATIONS).allowMainThreadQueries().build()
        val dao = room.dao()
        val sites = dao.sites()
        assertEquals(2, sites.size)
        assertEquals(setOf("강릉 유적", "강릉 유적 GN2"), sites.map { it.name }.toSet())
        val objs = dao.objs(1).associateBy { it.id }
        assertEquals(3, objs.size)
        assertEquals(2, objs[10]!!.no); assertEquals(1, objs[11]!!.no); assertEquals(1, objs[12]!!.no)
        assertEquals("1,2,3,4", dao.session(100)!!.slots)
        val sh = dao.shotsOf(100).single()
        assertEquals(574001.76, sh.e, 1e-9); assertEquals(3, sh.tag); assertTrue(sh.active)
        // 마이그레이션 뒤에도 새 기록이 들어간다(인덱스·AUTOINCREMENT 정상)
        assertTrue(dao.insertObj(kr.archsurvey.gcp.data.ObjRow(siteId = 1, kind = "PIT", no = 3, createdAt = 5)) > 12)
        room.close()
    }
}
