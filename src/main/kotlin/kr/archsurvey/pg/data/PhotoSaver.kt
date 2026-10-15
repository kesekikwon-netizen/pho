package kr.archsurvey.pg.data

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.0.16 사진 번호: 촬영을 받는 순간 한 번에 하나씩(원자적), 늘기만 하고 다시 쓰지 않음.
 * 시작값 = max(작업에 저장된 다음 번호, 기록·저널 최대+1, 폴더 안 IMG_/조각 파일 최대+1)
 */
class PhotoIds(start: Int) {
    private val next = AtomicInteger(maxOf(1, start))
    fun take(): Int = next.getAndIncrement()
    fun peek(): Int = next.get()

    companion object {
        private val NUM = Regex("^IMG_(\\d+)\\.jpg(\\.part)?$")
        fun fileNo(name: String): Int? = NUM.find(name)?.groupValues?.get(1)?.toIntOrNull()
        fun fileName(id: Int): String = String.format(java.util.Locale.US, "IMG_%04d.jpg", id)
        fun startFor(s: Session, photosDir: File, journal: List<Still> = emptyList()): Int {
            val rec = (s.stills + journal).maxOfOrNull { it.id } ?: 0
            val files = photosDir.listFiles().orEmpty().mapNotNull { fileNo(it.name) }.maxOrNull() ?: 0
            return maxOf(s.nextPhotoId, rec + 1, files + 1, 1)
        }
    }
}

/**
 * 0.0.16 사진 저장기: 여러 장을 동시에(기본 2 줄) 저장. 버리지 않음 — 다 끝날 때까지 [drain] 으로 기다림.
 * 촬영 속도는 막지 않음(밀림은 기존 pendingShots 로 자동 촬영만 늦춤).
 */
class PhotoSaver(threads: Int = 2) {
    val pending = AtomicInteger(0)
    private val lock = Object()
    private val exec = Executors.newFixedThreadPool(threads) { r -> Thread(r, "pg-save").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    fun submit(task: () -> Unit) {
        pending.incrementAndGet()
        exec.execute {
            try { task() } catch (t: Throwable) { runCatching { kr.archsurvey.pg.diag.PgLog.e("photo save task failed", t) } }
            finally { synchronized(lock) { pending.decrementAndGet(); lock.notifyAll() } }
        }
    }

    /** 남은 저장을 다 마칠 때까지(안내용으로 남은 장수를 알려 줌). 시간 안에 끝나면 true */
    fun drain(timeoutMs: Long = 120_000, onLeft: (Int) -> Unit = {}): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (pending.get() > 0) {
                onLeft(pending.get())
                val w = end - System.currentTimeMillis(); if (w <= 0) return false
                lock.wait(minOf(w, 200))
            }
        }
        onLeft(0); return true
    }

    fun shutdown() { exec.shutdown(); runCatching { exec.awaitTermination(30, TimeUnit.SECONDS) } }
}

/** 0.0.16 한 장 쓰기: 조각(.part) 한 번 쓰기 → EXIF 한 번 → 기록(저널) → 이름 바꿈. 기록 없는 파일(고아)을 만들지 않음 */
object PhotoWrite {
    fun save(files: SessionFiles, name: String, still: Still, jpeg: ByteArray, exif: (File) -> Unit): Boolean {
        val dir = files.photos(name)
        val f = File(dir, still.file); val part = File(dir, still.file + ".part")
        if (f.exists()) { runCatching { kr.archsurvey.pg.diag.PgLog.e("photo exists, refusing overwrite ${still.file}") }; return false }
        try {
            part.writeBytes(jpeg)
            runCatching { exif(part) }.onFailure { runCatching { kr.archsurvey.pg.diag.PgLog.e("exif failed ${still.file}", it) } }
            files.journal(name, still) // 기록 먼저: 여기서 죽으면 불러올 때 .part 를 되살림
        } catch (e: Exception) { part.delete(); return false }
        if (!part.renameTo(f)) { runCatching { part.copyTo(f, overwrite = false); part.delete() }.onFailure { return false } }
        return true
    }
}

/** JPEG 머리 읽기(순수 JVM) */
object Jpeg {
    fun complete(f: File): Boolean = runCatching { f.length() > 4 && java.io.RandomAccessFile(f, "r").use { r -> r.seek(0); val a = r.read(); val b = r.read(); r.seek(f.length() - 2); a == 0xFF && b == 0xD8 && r.read() == 0xFF && r.read() == 0xD9 } }.getOrDefault(false)
    /** SOF 의 (너비, 높이) */
    fun size(f: File): Pair<Int, Int>? = runCatching {
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream())).use { s ->
            if (s.readUnsignedByte() != 0xFF || s.readUnsignedByte() != 0xD8) return null
            while (true) {
                var m = s.readUnsignedByte(); if (m != 0xFF) return null
                do { m = s.readUnsignedByte() } while (m == 0xFF)
                if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) continue
                val len = s.readUnsignedShort()
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) { s.readUnsignedByte(); val h = s.readUnsignedShort(); val w = s.readUnsignedShort(); return w to h }
                s.skipBytes(len - 2)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }.getOrNull()
}
