package kr.archsurvey.t0159

import kotlinx.coroutines.runBlocking
import kr.archsurvey.photo.*
import org.junit.Assert.*
import org.junit.Test

class PhotoRules0159Test {
    @Test fun dirUsesNewRootWithGroup() {
        assertEquals("발굴조사/발굴 측량/유적/1호분/사진", PhotoLayout.dirRel("ExcavationSurvey/유적/1호분"))
        assertEquals("발굴조사/발굴 측량/작업A/사진", PhotoLayout.dirRel("ExcavationSurvey/작업A"))
        assertEquals("Documents/발굴조사/발굴 측량/작업A/사진/", PhotoLayout.publicRelative("ExcavationSurvey/작업A"))
    }

    @Test fun namesArePointUnderscoreN() {
        assertEquals("P-0001_1.jpg", PhotoLayout.nextName("P-0001", emptyList()))
        assertEquals("P-0001_3.jpg", PhotoLayout.nextName("P-0001", listOf("P-0001_1.jpg", "P-0001_2.jpg", "P-0002_7.jpg")))
        assertEquals("구_07_1.jpg", PhotoLayout.nextName("구/07", emptyList()))
        // 제한 없음: 100장째도 이름이 나온다
        val many = (1..99).map { "A_$it.jpg" }
        assertEquals("A_100.jpg", PhotoLayout.nextName("A", many))
    }

    @Test fun exportPathPerPoint() {
        assertEquals("photos/P-0003/P-0003_2.jpg", PhotoLayout.exportPath("P-0003", "발굴조사/발굴 측량/x/사진/P-0003_2.jpg"))
    }

    private val info = PhotoPointInfo("구-07", 37.5, 127.25, 528155.5148, 206982.1646, 5186, 100.0416, true, 0.012, 0.025, 4)

    @Test fun exifCarriesNameCoordsSigmaFix() {
        val t = PhotoExif.tags(info)
        assertEquals("37/1,30/1,0/10000", t["GPSLatitude"]); assertEquals("N", t["GPSLatitudeRef"])
        assertEquals("127/1,15/1,0/10000", t["GPSLongitude"])
        assertEquals("100042/1000", t["GPSAltitude"])
        assertTrue(t["ImageDescription"]!!.all { it.code in 0x20..0x7e })
        assertTrue(t["ImageDescription"]!!.contains("FIX"))
        val d = PhotoExif.description(info)
        assertTrue(d, d.contains("구-07") && d.contains("N 528155.515") && d.contains("σH 1.2cm") && d.contains("σV 2.5cm") && d.endsWith("FIX"))
        val x = PhotoExif.xmp(info)
        assertTrue(x.contains("as:Point=\"구-07\"") && x.contains("as:Fix=\"FIX\"") && x.contains("as:SigmaH=\"0.0120\""))
        assertEquals("FLOAT", PhotoExif.fixLabel(5))
        val xa = PhotoExif.xmpAscii(info)
        assertTrue(xa.all { it.code < 0x80 }); assertTrue(xa.contains("as:Point=\"&#xAD6C;-07\""))
    }

    @Test fun gapLabel() {
        assertEquals("측정 후 12초", PhotoGap.label(1_000, 13_000))
        assertEquals("측정 후 3분 5초", PhotoGap.label(0 + 1, 1 + 185_000))
        assertEquals("측정 전 2분", PhotoGap.label(200_000, 80_000))
        assertEquals("측정 시각 없음", PhotoGap.label(0, 5))
        assertTrue(PhotoGap.suspicious(1, 1 + 31 * 60_000L)); assertFalse(PhotoGap.suspicious(1, 60_000))
    }

    class MemOps : PhotoFileOps {
        val files = LinkedHashMap<String, ByteArray>(); var corruptCopy = false; var failCopy = false
        override fun exists(rel: String) = rel in files
        override fun list(dirRel: String) = files.keys.filter { it.startsWith("$dirRel/") }.map { it.substringAfterLast('/') }
        override fun copy(fromRel: String, toRel: String) {
            if (failCopy) { files[toRel] = byteArrayOf(1); throw java.io.IOException("disk") }
            val b = files.getValue(fromRel); files[toRel] = if (corruptCopy) b.copyOf(b.size - 1) else b.copyOf()
        }
        override fun sha256(rel: String) = files[rel]?.let { PhotoMigration.sha256(it) }
        override fun size(rel: String) = files[rel]?.size?.toLong() ?: -1
        override fun delete(rel: String) { files.remove(rel) }
    }

    private val dir = "발굴조사/발굴 측량/작업/사진"

    @Test fun migrationCopiesVerifiesThenDeletes() = runBlocking {
        val ops = MemOps()
        ops.files["작업/P-0001/P-0001_20261001_1.jpg"] = byteArrayOf(1, 2, 3)
        ops.files["작업/P-0001/P-0001_20261001_2.jpg"] = byteArrayOf(4, 5)
        val db = HashMap<Long, String>()
        val rows = listOf(PhotoRow(1, "P-0001", "작업/P-0001/P-0001_20261001_1.jpg"), PhotoRow(2, "P-0001", "작업/P-0001/P-0001_20261001_2.jpg"),
            PhotoRow(3, "P-0002", "$dir/P-0002_1.jpg"), PhotoRow(4, "P-0009", "작업/gone.jpg"))
        val r = PhotoMigration(ops) { id, rel -> db[id] = rel }.run(rows, dir)
        assertEquals(MigrationReport(2, 1, 0, 1), r)
        assertEquals("$dir/P-0001_1.jpg", db[1]); assertEquals("$dir/P-0001_2.jpg", db[2])
        assertArrayEquals(byteArrayOf(1, 2, 3), ops.files["$dir/P-0001_1.jpg"])
        assertFalse(ops.exists("작업/P-0001/P-0001_20261001_1.jpg"))
    }

    @Test fun migrationKeepsOldOnBadCopyOrDbFailure() = runBlocking {
        val ops = MemOps(); ops.files["a/x.jpg"] = byteArrayOf(9, 9, 9); ops.corruptCopy = true
        val db = HashMap<Long, String>()
        val r = PhotoMigration(ops) { id, rel -> db[id] = rel }.run(listOf(PhotoRow(1, "A", "a/x.jpg")), dir)
        assertEquals(1, r.failed); assertTrue(ops.exists("a/x.jpg")); assertTrue(db.isEmpty()); assertEquals(1, ops.files.size)

        val ops2 = MemOps(); ops2.files["a/x.jpg"] = byteArrayOf(9)
        val r2 = PhotoMigration(ops2) { _, _ -> error("db locked") }.run(listOf(PhotoRow(1, "A", "a/x.jpg")), dir)
        assertEquals(1, r2.failed); assertTrue(ops2.exists("a/x.jpg")); assertEquals(1, ops2.files.size)

        val ops3 = MemOps(); ops3.files["a/x.jpg"] = byteArrayOf(9); ops3.failCopy = true
        val r3 = PhotoMigration(ops3) { _, _ -> }.run(listOf(PhotoRow(1, "A", "a/x.jpg")), dir)
        assertEquals(1, r3.failed); assertEquals(setOf("a/x.jpg"), ops3.files.keys)
    }

    @Test fun migrationAvoidsExistingNames() = runBlocking {
        val ops = MemOps(); ops.files["$dir/A_1.jpg"] = byteArrayOf(1); ops.files["old/A.jpg"] = byteArrayOf(2)
        val db = HashMap<Long, String>()
        PhotoMigration(ops) { id, rel -> db[id] = rel }.run(listOf(PhotoRow(5, "A", "old/A.jpg")), dir)
        assertEquals("$dir/A_2.jpg", db[5]); assertArrayEquals(byteArrayOf(1), ops.files["$dir/A_1.jpg"])
    }
}
