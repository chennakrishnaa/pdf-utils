package app.venkat.offkit.reader

import app.venkat.offkit.engine.HostilePdf
import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.MuPdfEngine
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PageTextParse
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.feature.reader.SearchMath
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Scratch measurement of a search scan's cost on the host MuPDF build: a 300-page A4 PDF of dense
 * text (50 lines of about 90 characters per page, Helvetica), each page's text read through
 * `MuPdfEngine.pageText` (the op the reader's text request runs), parsed, and searched. Ignored:
 * it takes several seconds and only prints timings. Remove @Ignore to run it.
 */
@Ignore("measurement only; prints the scan cost")
class ReaderSearchCostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun scanCostPerPage() {
        val pages = 300
        val p = HostilePdf()
        p.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        p.add(2, "<</Type/Pages/Kids[${(0 until pages).joinToString(" ") { "${10 + 2 * it} 0 R" }}]/Count $pages>>")
        p.add(5, "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>")
        val words = "the quick brown fox jumps over a lazy dog while searching offline documents for words".split(" ")
        for (i in 0 until pages) {
            val content = StringBuilder("BT /F1 9 Tf 40 800 Td 11 TL ")
            for (line in 0 until 50) {
                val text = (0 until 15).joinToString(" ") { words[(i * 7 + line * 3 + it) % words.size] }
                content.append("($text page ${i + 1}) ' ")
            }
            content.append("ET")
            p.add(10 + 2 * i, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 5 0 R>>>>/Contents ${11 + 2 * i} 0 R>>")
            p.stream(11 + 2 * i, "", content.toString().latin1())
        }
        val pdf = File(tmp.root, "book.pdf").apply { writeBytes(p.bytes()) }
        val engine = MuPdfEngine()
        val query = SearchMath.parse("lazy dog")!!
        // Warm-up (first open, JIT).
        repeat(3) { engine.pageText(pdf, it, File(tmp.root, "w$it.bin"), JobControl.None) }
        var engineNs = 0L
        var parseNs = 0L
        var findNs = 0L
        var chars = 0L
        var hits = 0
        for (i in 0 until pages) {
            val out = File(tmp.root, "t$i.bin")
            val t0 = System.nanoTime()
            val r = engine.pageText(pdf, i, out, JobControl.None)
            val t1 = System.nanoTime()
            assertTrue("$r", r is PdfPageText.Ok)
            val text = (PageTextFormat.read(out) as PageTextParse.Ok).text
            val t2 = System.nanoTime()
            hits += SearchMath.find(text.text, SearchMath.lineStarts(text), query, i).size
            val t3 = System.nanoTime()
            engineNs += t1 - t0; parseNs += t2 - t1; findNs += t3 - t2; chars += text.length
            out.delete()
        }
        println(
            "SCAN COST pages=$pages chars/page=${chars / pages} hits=$hits pdfBytes=${pdf.length()} " +
                "engine=${engineNs / pages / 1000} us/page parse=${parseNs / pages / 1000} us/page " +
                "find=${findNs / pages / 1000} us/page total=${(engineNs + parseNs + findNs) / 1_000_000} ms",
        )
    }
}
