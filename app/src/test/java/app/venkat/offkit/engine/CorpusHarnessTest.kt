package app.venkat.offkit.engine

import app.venkat.offkit.engine.ops.RenderImageGate
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import javax.imageio.ImageIO

/**
 * P1 measurement (manual): benign-corpus regression check of the pre-checks. Skipped unless the
 * corpus directory (system property / default scratch path) exists. Writes one line per file.
 */
@org.junit.Ignore("manual measurement")
class CorpusHarnessTest {
    private val root = File(System.getProperty("p1.corpus") ?: "/tmp/claude-1000/-home-ubuntu-code-pdf-util/75a38076-1b5b-4d78-986c-7c571392a82e/scratchpad/corpus")
    private val label = "head"
    private val engine = MuPdfEngine()

    // ------------------------------------------------------------------ corpus generation (head only)

    private fun jpeg(seed: Int, w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(245, 240, 228); g.fillRect(0, 0, w, h)
        g.color = Color(30, 30, 30); g.font = Font(Font.SERIF, Font.PLAIN, 42)
        val rnd = Random(seed.toLong())
        var y = 200
        while (y < h - 200) { val sb = StringBuilder(); repeat(60) { sb.append(('a' + rnd.nextInt(26))); if (rnd.nextInt(6) == 0) sb.append(' ') }; g.drawString(sb.toString(), 150, y); y += 60 }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** [pages] pages, [images] distinct image objects all listed in ONE shared /XObject dict; page p draws image p % images. */
    private fun book(f: File, pages: Int, images: Int, jpegs: List<ByteArray>, w: Int, h: Int) {
        BufferedOutputStream(FileOutputStream(f), 1 shl 20).use { os ->
            var pos = 0L
            val n = 4 + images + pages * 2
            val offs = LongArray(n)
            fun w(s: String) { val b = s.toByteArray(Charsets.ISO_8859_1); os.write(b); pos += b.size }
            fun wb(b: ByteArray) { os.write(b); pos += b.size }
            w("%PDF-1.7\n")
            val pageObj = { p: Int -> 4 + images + p * 2 }
            offs[1] = pos; w("1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n")
            offs[2] = pos; w("2 0 obj\n<</Type/Pages/Kids[" + (0 until pages).joinToString(" ") { "${pageObj(it)} 0 R" } + "]/Count $pages>>\nendobj\n")
            offs[3] = pos; w("3 0 obj\n<</ProcSet[/PDF/ImageC]/XObject<<" + (0 until images).joinToString("") { "/Im$it ${4 + it} 0 R" } + ">>>>\nendobj\n")
            for (i in 0 until images) {
                val j = jpegs[i % jpegs.size]
                offs[4 + i] = pos; w("${4 + i} 0 obj\n<</Type/XObject/Subtype/Image/Width $w/Height $h/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length ${j.size}>>stream\n"); wb(j); w("\nendstream\nendobj\n")
            }
            for (p in 0 until pages) {
                val po = pageObj(p)
                val c = "q 595 0 0 842 0 0 cm /Im${p % images} Do Q"
                offs[po] = pos; w("$po 0 obj\n<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources 3 0 R/Contents ${po + 1} 0 R>>\nendobj\n")
                offs[po + 1] = pos; w("${po + 1} 0 obj\n<</Length ${c.length}>>stream\n$c\nendstream\nendobj\n")
            }
            val xref = pos
            w("xref\n0 $n\n0000000000 65535 f \n")
            for (i in 1 until n) w("%010d 00000 n \n".format(offs[i]))
            w("trailer\n<</Size $n/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    private val variants = linkedMapOf(
        "objstm" to "garbage,compress,objstms",
        "rc4-128" to "compress,encrypt=rc4-128,user-password=user,owner-password=owner",
        "aes-128" to "compress,encrypt=aes-128,user-password=user,owner-password=owner",
        "aes-256" to "compress,encrypt=aes-256,user-password=user,owner-password=owner",
        "objstm-aes-256" to "garbage,compress,objstms,encrypt=aes-256,user-password=user,owner-password=owner",
        "objstm-rc4-128" to "garbage,compress,objstms,encrypt=rc4-128,user-password=user,owner-password=owner",
        "owneronly-aes-128" to "compress,encrypt=aes-128,owner-password=owner,permissions=-3904",
        "owneronly-rc4-128" to "compress,encrypt=rc4-128,owner-password=owner,permissions=-3904",
    )

    @Test
    fun generate() {
        val src = File(root, "src"); assumeTrue(src.isDirectory)
        val gen = File(root, "gen").apply { mkdirs() }
        val books = File(root, "books").apply { mkdirs() }
        val jp = (0 until 4).map { jpeg(it, 2480, 3508) }
        book(File(books, "book40-shared4.pdf"), 40, 4, jp, 2480, 3508)
        book(File(books, "book40-shared40.pdf"), 40, 40, jp, 2480, 3508)
        book(File(books, "book120-shared120.pdf"), 120, 120, jp, 2480, 3508)
        book(File(books, "book400-shared400.pdf"), 400, 400, jp, 2480, 3508)
        val inputs = (src.listFiles()!!.toList() + File(books, "book40-shared40.pdf") + File(books, "book120-shared120.pdf")).sortedBy { it.name }
        for (f in inputs) for ((v, opts) in variants) {
            val out = File(gen, f.nameWithoutExtension + "~" + v + ".pdf")
            try {
                val d = Document.openDocument(f.path) as PDFDocument
                d.save(out.path, opts); d.destroy()
            } catch (t: Throwable) { File(root, "gen-errors.txt").appendText("${out.name}: $t\n") }
        }
    }

    // ------------------------------------------------------------------ measurement

    private fun frames(t: Throwable): String {
        var c: Throwable? = t
        val sb = StringBuilder()
        while (c != null) {
            val fr = c.stackTrace.filter { it.className.contains("offkit") && !it.className.contains("CorpusHarness") }.take(3)
            sb.append(c.javaClass.simpleName).append(if (c is PdfInputException) "(${c.error})" else "(${c.message?.take(60)})")
            fr.forEach { sb.append(" @").append(it.fileName).append(':').append(it.lineNumber) }
            c = c.cause; if (c != null) sb.append(" <- ")
        }
        return sb.toString()
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> { val t0 = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t0) / 1_000_000 }

    private fun res(r: Any): String = if (r::class.simpleName == "Ok") "Ok" else r.toString().take(80)

    private fun pw(f: File): String? = if (f.name.contains("~") && !f.name.contains("owneronly") && !f.name.contains("~objstm.")) "user" else null

    // --- version-specific hooks (edited per baseline) ---
    private fun readerOpen(f: File, p: String?): PDFDocument = PdfInputs.openForReader(f, p, null)
    private fun info(f: File, p: String?): PdfPageInfo = engine.pageInfo(f, p)
    private fun render(f: File, r: RenderRequest, out: File, p: String?): PdfRender = engine.renderPage(f, r, out, p)
    private fun text(f: File, page: Int, out: File, p: String?): String = res(engine.pageText(f, page, out, p))

    @Test
    fun measure() {
        assumeTrue(root.isDirectory)
        val files = listOf("src", "gen", "books").flatMap { File(root, it).listFiles()?.toList().orEmpty() }.filter { it.name.endsWith(".pdf") }.sortedBy { it.path }
        val outF = File(root.parentFile, "out/results-$label.txt").apply { parentFile.mkdirs(); delete() }
        val tmpOut = File(root.parentFile, "out/tile-$label.bin")
        for (f in files) {
            ScanMemo.clear()
            val p = pw(f)
            val line = StringBuilder("${f.parentFile.name}/${f.name}\t")
            // tool path
            val (tool, toolMs) = timed { try { PdfInputs.open(f).destroy(); "Ok" } catch (t: Throwable) { frames(t) } }
            line.append("tool=$tool ${toolMs}ms\t")
            // reader
            ScanMemo.clear()
            val (ro, roMs) = timed { try { readerOpen(f, p).destroy(); "Ok" } catch (t: Throwable) { frames(t) } }
            line.append("ropen=$ro ${roMs}ms\t")
            ScanMemo.clear()
            val (inf, infMs) = timed { info(f, p) }
            line.append("info=${res(inf)} ${infMs}ms\t")
            if (inf is PdfPageInfo.Ok) {
                val n = inf.pages.size
                line.append("pages=$n\t")
                val idx = listOf(0, n / 2, n - 1).distinct()
                // gate timing / reason on a directly opened doc
                val doc = try { readerOpen(f, p) } catch (t: Throwable) { null }
                for (i in idx) {
                    val (g, gMs) = timed { try { if (doc != null) { RenderImageGate.checkPage(doc, i); "Ok" } else "noDoc" } catch (t: Throwable) { frames(t) } }
                    line.append("gate[$i]=$g ${gMs}ms\t")
                }
                doc?.destroy()
                for (i in idx) {
                    val d = inf.pages[i]
                    val scale = 1080f / d.widthPt
                    val sw = RenderLimits.scaledWidth(d, scale).toInt()
                    val sh = RenderLimits.scaledHeight(d, scale).toInt()
                    val req = RenderRequest(i, scale, 0, 0, sw, minOf(sh, RenderLimits.MAX_PIXELS / sw))
                    val (r, rMs) = timed { render(f, req, tmpOut, p) }
                    line.append("render[$i]=${res(r)} ${rMs}ms\t")
                }
                for (i in idx) {
                    val (t, tMs) = timed { try { text(f, i, tmpOut, p) } catch (e: Throwable) { frames(e) } }
                    line.append("text[$i]=$t ${tMs}ms\t")
                }
            }
            tmpOut.delete()
            outF.appendText(line.toString().trimEnd() + "\n")
        }
    }
}
