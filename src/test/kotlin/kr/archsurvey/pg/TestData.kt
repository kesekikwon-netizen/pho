package kr.archsurvey.pg

import java.io.File

object TestData {
    val root = File(System.getProperty("pg.root") ?: ".")
    /** testdata/gray/<이름>.pgm (tools/to_pgm.py 로 만든 8비트 밝기) */
    fun gray(f: File): Triple<ByteArray, Int, Int> {
        val pgm = File(root, "testdata/gray/" + f.name.substringAfter("--").substringBeforeLast('.') + ".pgm")
        val b = pgm.readBytes()
        var i = 0
        fun token(): String {
            while (b[i].toInt().toChar().isWhitespace()) i++
            val s = StringBuilder(); while (!b[i].toInt().toChar().isWhitespace()) s.append(b[i++].toInt().toChar()); return s.toString()
        }
        check(token() == "P5"); val w = token().toInt(); val h = token().toInt(); token(); i++
        return Triple(b.copyOfRange(i, i + w * h), w, h)
    }
    fun target(n: Int): File = File(root, "targets").listFiles()!!.first { it.name.endsWith("AprilTags_$n.png") }
}
