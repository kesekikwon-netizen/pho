package kr.archsurvey.t0138

import kr.archsurvey.job.JobFolderRegistry
import kr.archsurvey.job.JobFolderWriter
import kr.archsurvey.job.JobFolders
import kr.archsurvey.job.MemoryJobFolderStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.1.38 새 작업 = 새 폴더(순수 로직 + java.io) */
class JobFolder0138Test {
    @Test fun sanitize_invalidCharsDotsSpacesLength() {
        assertEquals("광령리 3지구", JobFolders.sanitize("  광령리   3지구 "))
        assertEquals("a_b_c_d_e_f_g_h_i", JobFolders.sanitize("a\\b/c:d*e?f\"g<h>i"))
        assertEquals("a_b", JobFolders.sanitize("a|b"))
        assertEquals("유적", JobFolders.sanitize("..유적..."))
        assertEquals("작업", JobFolders.sanitize("..."))
        assertEquals("작업", JobFolders.sanitize("///"))
        assertEquals("tab_x", JobFolders.sanitize("tab\u0001x"))
        assertEquals(40, JobFolders.sanitize("가".repeat(60)).length)
    }

    @Test fun unique_appendsNumber_caseInsensitive() {
        assertEquals("A", JobFolders.unique("A", listOf("B")))
        assertEquals("A (2)", JobFolders.unique("A", listOf("a")))
        assertEquals("A (3)", JobFolders.unique("A", listOf("A", "A (2)")))
    }

    @Test fun relPathDisplaySplit() {
        assertEquals("ExcavationSurvey/광령리 3지구", JobFolders.relPath(null, "광령리 3지구"))
        assertEquals("ExcavationSurvey/광령리 유적/3지구", JobFolders.relPath("광령리 유적", "3지구"))
        assertEquals("Documents/ExcavationSurvey/3지구", JobFolders.display("ExcavationSurvey/3지구"))
        assertEquals("광령리 유적" to "3지구", JobFolders.split("ExcavationSurvey/광령리 유적/3지구"))
        assertEquals(null to "3지구", JobFolders.split("ExcavationSurvey/3지구"))
    }

    @Test fun registry_assignOnce_groups_duplicates_existingDisk() {
        val disk = HashSet<String>(listOf("ExcavationSurvey/옛작업"))
        val r = JobFolderRegistry(MemoryJobFolderStore()) { it in disk }
        assertEquals("ExcavationSurvey/3지구", r.assign("3지구"))
        assertEquals("다시 불러도 같은 폴더", "ExcavationSurvey/3지구", r.assign("3지구", "다른 그룹"))
        assertEquals("ExcavationSurvey/광령리 유적/A트렌치", r.assign("A트렌치", "광령리 유적"))
        assertEquals("ExcavationSurvey/광령리 유적/B트렌치", r.assign("B트렌치", " 광령리 유적 "))
        // 지운 작업이 남긴 폴더와 이름이 같으면 (2)
        assertEquals("ExcavationSurvey/옛작업 (2)", r.assign("옛작업"))
        // 0.1.37 이전 작업: 처음 쓸 때 정함
        assertNull(r.peek("작업1")); assertEquals("ExcavationSurvey/작업1", r.folderOf("작업1")); assertNotNull(r.peek("작업1"))
        assertEquals(listOf("광령리 유적"), r.groups())
        // 상위 폴더 이름의 금지 문자도 정리
        assertEquals("ExcavationSurvey/a_b/C", r.assign("C", "a/b"))
    }

    @Test fun registry_forgetThenSameName_getsNewFolder() {
        val disk = HashSet<String>()
        val r = JobFolderRegistry(MemoryJobFolderStore()) { it in disk }
        val first = r.assign("3지구"); disk.add(first)
        r.forget("3지구")
        assertEquals("ExcavationSurvey/3지구 (2)", r.assign("3지구"))
    }

    @Test fun writer_writeIfChanged_copyTree_snapshot() {
        val root = createTempDir("jobroot")
        try {
            val w = JobFolderWriter(root)
            val rel = "ExcavationSurvey/유적/3지구"
            assertNotNull(w.writeIfChanged(rel, null, "측점_3지구.csv", "a,b\n".toByteArray()))
            assertNull("같은 내용이면 다시 쓰지 않음", w.writeIfChanged(rel, null, "측점_3지구.csv", "a,b\n".toByteArray()))
            assertNotNull(w.writeIfChanged(rel, null, "측점_3지구.csv", "a,b\n1,2\n".toByteArray()))
            val pics = File(root.parentFile, "pics_" + System.nanoTime()).also { File(it, "P-0001").mkdirs(); File(it, ".thumbs").mkdirs() }
            File(pics, "P-0001/P-0001_a.jpg").writeBytes(ByteArray(10) { 1 })
            File(pics, ".thumbs/x.jpg").writeBytes(ByteArray(10) { 1 })
            File(pics, "P-0001/empty.jpg").writeBytes(ByteArray(0))
            assertEquals(1, w.copyTree(rel, JobFolders.PHOTOS, pics).size)
            assertEquals("같은 크기면 건너뜀", 0, w.copyTree(rel, JobFolders.PHOTOS, pics).size)
            val snap = w.snapshot(rel).map { it.relPath }.sorted()
            assertEquals(listOf("사진/P-0001_a.jpg", "측점_3지구.csv"), snap)
            assertEquals("image/jpeg", w.snapshot(rel).first { it.relPath.endsWith(".jpg") }.mime)
            pics.deleteRecursively()
        } finally { root.deleteRecursively() }
    }
}
