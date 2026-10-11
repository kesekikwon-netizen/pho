package kr.archsurvey.t0135

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kr.archsurvey.data.RoomArchStore
import kr.archsurvey.data.RoomPointStore
import kr.archsurvey.data.SurveyDb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.1.35 DB v7 → v8: 0.1.34 에서 쓰던 DB 파일을 그대로(schemas/7.json 의 CREATE 문) 만들고 점·외곽을 넣은 뒤
 * 새 앱(Room v8)으로 열어 점·이름·외곽이 그대로 열리는지, 새 칸은 비어 있는지 본다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration0135Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun createV7(file: File) {
        val schema = JSONObject(File("schemas/kr.archsurvey.data.SurveyDb/7.json").readText()).getJSONObject("database")
        file.parentFile?.mkdirs(); file.delete()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val ents = schema.getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i); val name = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", name))
            val idx = e.optJSONArray("indices")
            if (idx != null) for (k in 0 until idx.length()) db.execSQL(idx.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getString("identityHash")}')")
        // 0.1.34 점 3개: 표준 번호 2개 + 다른 이름 1개(옛 작업에서 들여온 이름)
        fun pt(id: String, type: String, at: Long) = db.execSQL(
            "INSERT INTO points (project,pointId,positionType,memo,lat,lon,ellipsoidalHeight,altitudeMsl,geoidSeparation,northing,easting,crsEpsg," +
                "fixQuality,allEpochsFixed,satellites,hdop,sigmaE,sigmaN,sigmaH,sigmaU,correctionAgeSec,baseStationId,epochs,fixedEpochs,floatEpochs," +
                "antennaHeight,antennaModel,antennaRefPoint,antennaInputMode,antennaInputValue,antennaPcoV,antennaPcoH,antennaExtraPartsM,antennaBaseExtM," +
                "antennaReceiverApplied,groundEllipsoidalHeight,groundMsl,heightModel,device,measuredAtUtcMs,cellName) VALUES " +
                "('광령리',  '$id', '$type', '메모', 37.5, 127.1, 80.0, 52.0, 28.0, 500000.0, 200000.0, 5187, 4, 1, 20, 0.7, 0.005, 0.006, 0.008, 0.015, 1.0, '0001', 3, 3, 0," +
                " 1.799, 'TRIMBLE_R10', 'bottom_of_quick_release', 'vertical', 1.6, 0.199, 0.0, 0.0, 0.0, 0, 78.2, 50.2, 'EGM96', 'R10', $at, 'B-3')")
        pt("P-0001", "유구 경계점", 1000); pt("P-0002", "유물 출토 위치", 2000); pt("구-07", "기타", 3000)
        db.execSQL("INSERT INTO outlines (project, areaId, label, closed, finished, createdAtUtcMs) VALUES ('광령리', NULL, 'E-001', 1, 1, 500)")
        db.execSQL("INSERT INTO outline_points (outlineId, pointRowId, orderNo) VALUES (1, 1, 1)")
        db.version = 7
        db.close()
    }

    @Test fun v7DatabaseOpensWithPointsAndOutlinesIntact() = runBlocking {
        val f = ctx.getDatabasePath("migr0135.db")
        createV7(f)
        val db = Room.databaseBuilder(ctx, SurveyDb::class.java, f.absolutePath).allowMainThreadQueries().build()
        try {
            val store = RoomPointStore(db, { "광령리" }, { "R10" })
            val pts = store.loadAll()
            assertEquals(listOf("P-0001", "P-0002", "구-07"), pts.map { it.name })   // 옛 이름 그대로
            assertEquals(listOf("유구 경계점", "유물 출토 위치", "기타"), pts.map { it.type })
            assertTrue(pts.all { it.digKind == "" && it.featureId == null && it.artifactNo == null && it.levelKind == "" && it.digTag == "" })
            assertEquals("EGM96", pts[0].heightModel)   // 기존 점의 지오이드는 바꾸지 않음
            assertEquals(50.2, pts[0].groundMslM!!, 1e-9)
            assertEquals("B-3", pts[1].cellName)
            assertEquals(2, store.maxSeq())   // 다음 번호는 P-0003 (다른 이름 「구-07」에 끌려가지 않음)
            val arch = RoomArchStore(db, { "광령리" }).load()
            assertEquals(1, arch.outlines.size)
            assertEquals("E-001", arch.outlines[0].label)
            assertEquals("", arch.outlines[0].kind)
            assertEquals(listOf(1L), arch.outlines[0].pointRowIds)
            assertEquals(8, db.openHelper.readableDatabase.version)
        } finally { db.close() }
    }

    @Test fun newDigFieldsRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, SurveyDb::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomPointStore(db, { "작업" }, { "R10" })
            val arch = RoomArchStore(db, { "작업" })
            val fid = arch.startFeature(null, "가-1", kind = "feature", featureType = "수혈")
            val p = kr.archsurvey.t0135.DigFixtures.point("P-0001").copy(digKind = "artifact", featureId = fid, artifactNo = 13, levelKind = "", digTag = "")
            val row = store.save(p)
            val back = store.loadAll().single()
            assertEquals("artifact", back.digKind); assertEquals(fid, back.featureId); assertEquals(13, back.artifactNo)
            arch.renameFeature(fid, "2호 수혈", "가-1\n2호 수혈", "수혈")
            val o = arch.load().outlines.single()
            assertEquals("2호 수혈", o.label); assertEquals("feature", o.kind); assertEquals("수혈", o.featureType)
            assertEquals(listOf("가-1", "2호 수혈"), kr.archsurvey.dig.FeatureNaming.history(o.history))
            assertEquals(fid, store.loadAll().single().featureId) // 이름을 바꿔도 점은 그대로
            store.updateDig(row, featureId = null, artifactNo = 13, levelKind = "floor", digTag = "")
            assertEquals("floor", store.loadAll().single().levelKind)
            arch.finishOutline(fid, true); arch.reopenOutline(fid)
            assertFalse(arch.load().outlines.single().finished)
        } finally { db.close() }
    }
}
