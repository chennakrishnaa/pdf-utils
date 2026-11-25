package app.venkat.offkit.engine

import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Random

/** RDR-4 measurement (manual; writes ~200 MB under app/build/rdr4, deleted afterwards): open-only cost of PdfInputs.open. Remove @Ignore to run. */
@org.junit.Ignore("manual measurement")
class OpenCostMeasureTest {
    private val dir = File("build/rdr4").apply { mkdirs() }

    private fun photoPdf(f: File, pages: Int, imageBytes: Int) {
        val rnd = Random(1)
        val img = ByteArray(imageBytes).also { rnd.nextBytes(it) }
        BufferedOutputStream(FileOutputStream(f), 1 shl 20).use { os ->
            var pos = 0L
            val offs = LongArray(3 + pages * 3 + 1)
            fun w(s: String) { val b = s.toByteArray(Charsets.ISO_8859_1); os.write(b); pos += b.size }
            fun wb(b: ByteArray) { os.write(b); pos += b.size }
            w("%PDF-1.7\n")
            offs[1] = pos; w("1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n")
            offs[2] = pos; w("2 0 obj\n<</Type/Pages/Kids[" + (0 until pages).joinToString(" ") { "${3 + it * 3} 0 R" } + "]/Count $pages>>\nendobj\n")
            for (p in 0 until pages) {
                val pg = 3 + p * 3
                offs[pg] = pos; w("$pg 0 obj\n<</Type/Page/Parent 2 0 R/MediaBox[0 0 600 800]/Resources<</XObject<</Im0 ${pg + 2} 0 R>>>>/Contents ${pg + 1} 0 R>>\nendobj\n")
                val c = "q 600 0 0 800 0 0 cm /Im0 Do Q"
                offs[pg + 1] = pos; w("${pg + 1} 0 obj\n<</Length ${c.length}>>stream\n$c\nendstream\nendobj\n")
                offs[pg + 2] = pos; w("${pg + 2} 0 obj\n<</Subtype/Image/Width 3000/Height 4000/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length ${img.size}>>stream\n")
                wb(img); w("\nendstream\nendobj\n")
            }
            val xref = pos
            val n = 3 + pages * 3
            w("xref\n0 $n\n0000000000 65535 f \n")
            for (i in 1 until n) w("%010d 00000 n \n".format(offs[i]))
            w("trailer\n<</Size $n/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    private fun time(label: String, f: File, runs: Int = 5) {
        val ts = (0 until runs).map {
            val t0 = System.nanoTime()
            PdfInputs.open(f).destroy()
            (System.nanoTime() - t0) / 1_000_000
        }
        val scan = (0 until runs).map {
            val t0 = System.nanoTime()
            ObjStmScan.check(f)
            (System.nanoTime() - t0) / 1_000_000
        }
        ScanMemo.clear()
        val memo = (0 until runs).map {
            val t0 = System.nanoTime()
            PdfInputs.open(f, ScanMemo.keyOf(f)).destroy()
            (System.nanoTime() - t0) / 1_000_000
        }
        println("RDR4 $label ${f.length() / 1_000_000} MB: open ms $ts, ObjStmScan ms $scan, open with memo ms $memo")
    }

    @Test
    fun measure() {
        val big = File(dir, "photos.pdf")
        try {
            photoPdf(big, 100, 2_000_000)
            time("photo-200MB", big)
            // Damaged: xref offset points nowhere, so MuPDF repairs (scans the whole file).
            val dmg = File(dir, "damaged.pdf")
            big.copyTo(dmg, overwrite = true)
            big.delete()
            RandomAccessFile(dmg, "rw").use { raf -> raf.seek(raf.length() - 30); raf.write("startxref\n12345\n%%EOF\n".toByteArray()) }
            time("damaged-200MB", dmg, 3)
            dmg.delete()
            val mid = File(dir, "photos16.pdf")
            photoPdf(mid, 8, 2_000_000)
            RandomAccessFile(mid, "rw").use { raf -> raf.seek(raf.length() - 30); raf.write("startxref\n12345\n%%EOF\n".toByteArray()) }
            time("damaged-16MB", mid, 3)
            mid.delete()
            // Many object streams: 2,000 streams x 50 small objects.
            val pdf = HostilePdf()
            pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
            pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
            pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>")
            var num = 10
            for (s in 0 until 2000) {
                val members = (0 until 50).map { (num++) to "<</A $it/B [1 2 3 4 5 6 7 8]/C (some text here)>>".toByteArray() }
                pdf.objStm(110_000 + s, members)
            }
            val objstm = File(dir, "objstm.pdf").apply { writeBytes(pdf.bytes()) }
            time("objstm-2000x50", objstm)
            objstm.delete()
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
