package app.venkat.offkit.engine

import app.venkat.offkit.engine.ops.RenderImageGate
import app.venkat.offkit.engine.ops.checkPageAnnots
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Pixmap
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T1 measurement (manual): runs every PDF of a public corpus through the reader's real path
 * (pageInfo = open + object-stream scan + page tree; then per page, up to [MAX_PAGES_PER_FILE]:
 * renderPage = annots + image gate + draw; pageText) and records outcome, `PdfError`, stage and the
 * offkit source lines that refused. Mode `stock` re-runs the non-Ok files through plain MuPDF
 * (no Offkit gates) as the baseline.
 *
 * Skipped unless the environment variable `OFFKIT_CORPUS2` names the corpus directory (Gradle
 * does not forward `-D` properties to the test JVM). Other variables:
 * `OFFKIT_CORPUS2_OUT` (results directory), `OFFKIT_CORPUS2_MODE` (`reader` | `stock`),
 * `OFFKIT_CORPUS2_PASSWORDS` (optional TSV `file<TAB>password`).
 *
 * Crash / hang safety: rows are appended one per file; a marker names the file in progress; a
 * watchdog writes a TIMEOUT / MEM row and halts the JVM when a file passes [HARD_MS] or the JVM's
 * RSS passes [RSS_LIMIT]. A rerun turns a leftover marker into a CRASH row and skips files that have
 * a row; `done-<mode>` is written when every file has one. See scripts in the T1 report.
 */
class ReaderCorpusTest {
    private val corpus = System.getenv("OFFKIT_CORPUS2")?.let(::File)
    private val outDir = File(System.getenv("OFFKIT_CORPUS2_OUT") ?: (corpus?.parentFile?.path ?: ".") + "/out2")
    private val mode = System.getenv("OFFKIT_CORPUS2_MODE") ?: "reader"
    private val engine = MuPdfEngine()

    private companion object {
        const val MAX_PAGES_PER_FILE = 30
        const val HARD_MS = 240_000L
        const val RSS_LIMIT = 3L shl 30
        const val OP_MS = 30_000L
        const val INFO_MS = 60_000L
        const val TARGET_WIDTH_PX = 1080f
        val HEADER = listOf(
            "file", "bytes", "outcome", "stage", "error", "reason", "pages", "checked", "pageFails",
            "blank", "blankWithText", "textUnits", "infoMs", "maxRenderMs", "maxRenderPage", "maxTextMs",
            "totalMs", "peakRssMb", "note",
        ).joinToString("\t")
    }

    // ------------------------------------------------------------------ bookkeeping

    @Volatile private var current: String? = null
    @Volatile private var startedAt = 0L
    @Volatile private var peakRss = 0L

    private fun rss(): Long = try {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
            .split(Regex("\\s+"))[1].toLong() * 1024
    } catch (e: Exception) { 0L }

    private fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ').take(400)

    private fun row(out: File, vararg cols: Any?) {
        out.appendText(cols.joinToString("\t") { clean(it?.toString()) } + "\n")
    }

    private fun startWatchdog(out: File, marker: File) {
        Thread {
            while (true) {
                Thread.sleep(200)
                val f = current ?: continue
                val r = rss()
                if (r > peakRss) peakRss = r
                val el = System.currentTimeMillis() - startedAt
                val why = when {
                    el > HARD_MS -> "TIMEOUT"
                    r > RSS_LIMIT -> "MEM"
                    else -> null
                } ?: continue
                row(out, f, File(f).length(), why, "watchdog", "", "elapsed=${el}ms rss=${r shr 20}MB", "", "", "", "", "", "", "", "", "", "", el, r shr 20, "JVM halted")
                marker.delete()
                Runtime.getRuntime().halt(3)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun deadline(ms: Long) = object : JobControl {
        val end = System.nanoTime() + ms * 1_000_000
        override fun progress(done: Int, total: Int) {}
        override fun isCancelled() = System.nanoTime() > end
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t0) / 1_000_000
    }

    /** Exception chain with up to 6 offkit frames each (the caller of refuse()/tooLarge() is the rule). */
    private fun frames(t: Throwable): String {
        var c: Throwable? = t
        val sb = StringBuilder()
        while (c != null) {
            val fr = c.stackTrace.filter { it.className.contains("offkit") && !it.className.contains("CorpusTest") }.take(6)
            sb.append(c.javaClass.simpleName).append(if (c is PdfInputException) "(${c.error})" else "(${c.message?.take(100)})")
            fr.forEach { sb.append(" @").append(it.fileName).append(':').append(it.lineNumber) }
            c = c.cause; if (c != null) sb.append(" <- ")
        }
        return sb.toString()
    }

    private fun files(): List<File> = corpus!!.walkTopDown().filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }.sortedBy { it.path }.toList()

    private fun passwords(): Map<String, String> = System.getenv("OFFKIT_CORPUS2_PASSWORDS")?.let { p ->
        File(p).readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    } ?: emptyMap()

    private fun runAll(inputs: List<File>, out: File, perFile: (File) -> Unit) {
        val marker = File(outDir, "inprogress-$mode")
        if (!out.exists()) out.writeText(HEADER + "\n")
        val done = out.readLines().drop(1).map { it.substringBefore('\t') }.toHashSet()
        if (marker.exists()) {
            val f = marker.readText().trim()
            if (f !in done) { row(out, f, File(f).length(), "CRASH", "jvm", "", "JVM died during this file"); done.add(f) }
            marker.delete()
        }
        startWatchdog(out, marker)
        for (f in inputs) {
            if (f.path in done) continue
            marker.writeText(f.path)
            peakRss = rss(); startedAt = System.currentTimeMillis(); current = f.path
            perFile(f)
            current = null
            marker.delete()
        }
        File(outDir, "done-$mode").writeText("ok\n")
    }

    private fun blank(f: File): Boolean = f.inputStream().buffered(1 shl 16).use { s ->
        var b = s.read()
        while (b == 0xFF) b = s.read()
        b < 0
    }

    private fun request(i: Int, d: PageDims): RenderRequest {
        val scale = minOf(TARGET_WIDTH_PX / d.widthPt, RenderLimits.MAX_SCALE)
        val sw = RenderLimits.scaledWidth(d, scale).toInt().coerceAtLeast(1)
        val sh = RenderLimits.scaledHeight(d, scale).toInt().coerceAtLeast(1)
        return RenderRequest(i, scale, 0, 0, sw, minOf(sh, RenderLimits.MAX_PIXELS / sw))
    }

    // ------------------------------------------------------------------ reader mode

    /** Re-runs the open stage directly to find which rule refused (the ops only return the PdfError). */
    private fun diagnoseOpen(f: File, pw: String?): Pair<String, String> {
        ScanMemo.clear()
        if (f.length() > ImageLimits.MAX_INPUT_PDF_BYTES) return "size" to "MAX_INPUT_PDF_BYTES"
        val doc = try { PdfInputs.openForReader(f, pw, null) } catch (t: Throwable) { return "open" to frames(t) }
        try {
            val n = doc.countPages()
            if (n > RenderLimits.MAX_PAGES) return "info" to "RenderLimits.MAX_PAGES n=$n"
            try { PdfInputs.checkGraph { ObjectGraph.checkAnnots(doc) } } catch (t: Throwable) { return "info.annots" to frames(t) }
            for (i in 0 until n) {
                try { doc.loadPage(i).use { p -> pageDimsOf(p.bounds) ?: return "info.page" to "page $i bounds ${p.bounds}" } } catch (t: Throwable) { return "info.page" to "page $i ${frames(t)}" }
            }
            return "info" to "not reproduced"
        } finally { doc.destroy() }
    }

    private inline fun <R> com.artifex.mupdf.fitz.Page.use(block: (com.artifex.mupdf.fitz.Page) -> R): R = try { block(this) } finally { destroy() }

    private fun pageDimsOf(b: com.artifex.mupdf.fitz.Rect) = app.venkat.offkit.engine.ops.pageDimsOf(b)

    /** Re-runs a page's pre-checks and a plain draw / text on a directly opened doc to name the rule. */
    private fun diagnosePage(f: File, pw: String?, i: Int, text: Boolean): Pair<String, String> {
        ScanMemo.clear()
        val doc = try { PdfInputs.openForReader(f, pw, null) } catch (t: Throwable) { return "page.open" to frames(t) }
        try {
            try { checkPageAnnots(doc, i) } catch (t: Throwable) { return "page.annots" to frames(t) }
            try { PdfInputs.checkGraph { RenderImageGate.checkPage(doc, i) } } catch (t: Throwable) { return "page.gate" to frames(t) }
            try {
                doc.loadPage(i).use { p ->
                    if (text) p.toStructuredText().let { st -> st.asText(); st.destroy() }
                    else p.toPixmap(Matrix(0.5f), ColorSpace.DeviceRGB, false, true).destroy()
                }
            } catch (t: Throwable) { return (if (text) "page.text" else "page.draw") to frames(t) }
            return (if (text) "page.text" else "page.draw") to "not reproduced (timeout/cancel?)"
        } finally { doc.destroy() }
    }

    @Test
    fun reader() {
        assumeTrue(corpus?.isDirectory == true && mode == "reader")
        outDir.mkdirs()
        val pws = passwords()
        val out = File(outDir, "reader.tsv")
        val tmp = File(outDir, "tile-reader.bin")
        runAll(files(), out) { f ->
            val pw = pws[f.name]
            val t0 = System.currentTimeMillis()
            ScanMemo.clear()
            val (info, infoMs) = timed { engine.pageInfo(f, pw, deadline(INFO_MS)) }
            if (info !is PdfPageInfo.Ok) {
                val err = (info as PdfPageInfo.Err).error
                val (stage, reason) = if (err is PdfError.Cancelled) "info" to "info over ${INFO_MS}ms" else diagnoseOpen(f, pw)
                row(out, f.path, f.length(), "REFUSED", stage, err, reason, "", "", "", "", "", "", infoMs, "", "", "", System.currentTimeMillis() - t0, peakRss shr 20, if (pw != null) "password" else "")
                return@runAll
            }
            val n = info.pages.size
            val checked = minOf(n, MAX_PAGES_PER_FILE)
            val fails = LinkedHashMap<String, MutableList<Int>>()
            var blank = 0; var blankText = 0; var units = 0L
            var maxR = 0L; var maxRp = -1; var maxT = 0L
            val notes = ArrayList<String>()
            if (pw != null) notes += "password"
            if (info.encrypted) notes += "encrypted"
            for (i in 0 until checked) {
                val req = request(i, info.pages[i])
                RenderLimits.check(req, info.pages[i])?.let { notes += "p$i harness-request:$it" }
                val (r, rMs) = timed { engine.renderPage(f, req, tmp, pw, deadline(OP_MS)) }
                if (rMs > maxR) { maxR = rMs; maxRp = i }
                var isBlank = false
                if (r is PdfRender.Ok) { isBlank = blank(tmp); if (isBlank) blank++ }
                else {
                    val e = (r as PdfRender.Err).error
                    val (st, why) = if (e is PdfError.Cancelled) "render" to "over ${OP_MS}ms" else diagnosePage(f, pw, i, text = false)
                    fails.getOrPut("render|$e|$st|$why") { ArrayList() }.add(i)
                }
                tmp.delete()
                val (t, tMs) = timed { engine.pageText(f, i, tmp, pw, deadline(OP_MS)) }
                if (tMs > maxT) maxT = tMs
                if (t is PdfPageText.Ok) { units += t.units; if (isBlank && t.units > 0) blankText++ }
                else {
                    val e = (t as PdfPageText.Err).error
                    val (st, why) = if (e is PdfError.Cancelled) "text" to "over ${OP_MS}ms" else diagnosePage(f, pw, i, text = true)
                    fails.getOrPut("text|$e|$st|$why") { ArrayList() }.add(i)
                }
                tmp.delete()
            }
            val failedPages = fails.values.flatten().toSet().size
            val outcome = when { fails.isEmpty() -> "OK"; failedPages >= checked -> "ALL_PAGES_FAIL"; else -> "SOME_PAGES_FAIL" }
            val first = fails.entries.firstOrNull()
            val parts = first?.key?.split("|", limit = 4)
            val reason = fails.entries.joinToString(" ;; ") { (k, v) -> "${k.split("|", limit = 4).let { it[3] }} [${idx(v)}]" }
            row(out, f.path, f.length(), outcome, parts?.let { it[0] + "/" + it[2] } ?: "", parts?.get(1) ?: "", reason, n, checked, failedPages,
                blank, blankText, units, infoMs, maxR, maxRp, maxT, System.currentTimeMillis() - t0, peakRss shr 20, notes.joinToString(";"))
        }
    }

    private fun idx(v: List<Int>) = if (v.size <= 6) v.joinToString(",") else v.take(6).joinToString(",") + ",..(${v.size})"

    // ------------------------------------------------------------------ stock MuPDF baseline

    @Test
    fun stock() {
        assumeTrue(corpus?.isDirectory == true && mode == "stock")
        outDir.mkdirs()
        val pws = passwords()
        val readerRows = File(outDir, "reader.tsv").readLines().drop(1).map { it.split("\t") }
        val bad = readerRows.filter { it.size > 2 && (it[2] != "OK" || (it.getOrNull(9)?.toIntOrNull() ?: 0) > 0) }.map { File(it[0]) }.filter { it.isFile }
        val out = File(outDir, "stock.tsv")
        runAll(bad, out) { f ->
            val t0 = System.currentTimeMillis()
            val pw = pws[f.name]
            val notes = ArrayList<String>()
            val doc = try { Document.openDocument(f.path) } catch (t: Throwable) {
                row(out, f.path, f.length(), "STOCK_OPEN_FAIL", "open", "", frames(t), "", "", "", "", "", "", "", "", "", "", System.currentTimeMillis() - t0, peakRss shr 20, "")
                return@runAll
            }
            try {
                if (doc.needsPassword()) {
                    if (pw == null || !doc.authenticatePassword(pw)) {
                        row(out, f.path, f.length(), "STOCK_NEEDS_PASSWORD", "open", "", if (pw == null) "no password known" else "known password rejected", "", "", "", "", "", "", "", "", "", "", System.currentTimeMillis() - t0, peakRss shr 20, "")
                        return@runAll
                    }
                    notes += "password"
                }
                val n = try { doc.countPages() } catch (t: Throwable) {
                    row(out, f.path, f.length(), "STOCK_PAGES_FAIL", "countPages", "", frames(t), "", "", "", "", "", "", "", "", "", "", System.currentTimeMillis() - t0, peakRss shr 20, "")
                    return@runAll
                }
                if ((doc as? PDFDocument)?.wasRepaired() == true) notes += "repaired"
                val checked = minOf(n, MAX_PAGES_PER_FILE)
                var fails = 0; var blank = 0; var blankText = 0; var units = 0L; var maxR = 0L; var maxRp = -1; var maxT = 0L
                val firstErr = StringBuilder()
                for (i in 0 until checked) {
                    var isBlank = false
                    val (ok, rMs) = timed {
                        try {
                            doc.loadPage(i).use { p ->
                                val b = p.bounds
                                val w = (b.x1 - b.x0).coerceAtLeast(1f)
                                val req = request(i, PageDims(w, (b.y1 - b.y0).coerceAtLeast(1f)))
                                val s = req.scale
                                val pix = Pixmap(ColorSpace.DeviceRGB, 0, 0, req.width, req.height, false)
                                try {
                                    pix.clear(255)
                                    val dev = DrawDevice(pix)
                                    val cookie = Cookie()
                                    try { p.run(dev, Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s), cookie); dev.close() } finally { dev.destroy(); cookie.destroy() }
                                    isBlank = pix.samples.all { it == 0xFF.toByte() }
                                } finally { pix.destroy() }
                            }
                            true
                        } catch (t: Throwable) { if (firstErr.isEmpty()) firstErr.append("p$i render ").append(frames(t)); false }
                    }
                    if (rMs > maxR) { maxR = rMs; maxRp = i }
                    if (isBlank) blank++
                    val (tu, tMs) = timed {
                        try { doc.loadPage(i).use { p -> val st = p.toStructuredText(); try { st.asText().count { !it.isWhitespace() } } finally { st.destroy() } } }
                        catch (t: Throwable) { if (firstErr.isEmpty()) firstErr.append("p$i text ").append(frames(t)); -1 }
                    }
                    if (tMs > maxT) maxT = tMs
                    if (!ok || tu < 0) fails++
                    if (tu > 0) { units += tu; if (isBlank) blankText++ }
                }
                val outcome = when { n <= 0 -> "STOCK_NO_PAGES"; fails == 0 -> "STOCK_OK"; fails >= checked -> "STOCK_ALL_PAGES_FAIL"; else -> "STOCK_SOME_PAGES_FAIL" }
                row(out, f.path, f.length(), outcome, "", "", firstErr, n, checked, fails, blank, blankText, units, "", maxR, maxRp, maxT, System.currentTimeMillis() - t0, peakRss shr 20, notes.joinToString(";"))
            } finally { doc.destroy() }
        }
    }
}
