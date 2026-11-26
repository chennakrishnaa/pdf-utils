package app.venkat.offkit.engine

import app.venkat.offkit.engine.HostilePdf.Companion.deflate
import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.HostilePdf.Companion.repeated
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * W1: [ObjStmScan] accepts one ASCII filter (`ASCII85Decode`/`A85`, `ASCIIHexDecode`/`AHx`) in an
 * object stream's chain, alone or before a single Flate, and still measures the decoded size.
 */
class ObjStmAsciiTest {
    private val engine = MuPdfEngine()

    /** Filter value to encoder, for every accepted spelling. */
    private val accepted: List<Pair<String, (ByteArray) -> ByteArray>> = listOf(
        "[/ASCII85Decode/FlateDecode]" to { b -> a85(deflate(b)) },
        "[/A85 /Fl]" to { b -> a85(deflate(b)) },
        "[/ASCIIHexDecode/FlateDecode]" to { b -> ahx(deflate(b)) },
        "[/AHx/Fl]" to { b -> ahx(deflate(b)) },
        "/ASCII85Decode" to { b -> a85(b) },
        "[/A85]" to { b -> a85(b) },
        "/ASCIIHexDecode" to { b -> ahx(b) },
        "/AHx" to { b -> ahx(b) },
    )

    @Test
    fun asciiObjectStreams_openInReaderAndToolPaths() {
        for ((filter, enc) in accepted) {
            val f = write("ok.pdf", inCatalogStream(repeated("null ", 1000), filter, enc))
            ObjStmScan.check(f)
            PdfInputs.open(f).destroy()
            assertTrue(filter, engine.pageInfo(f) is PdfPageInfo.Ok)
            assertTrue(filter, engine.merge(listOf(f), out("m.pdf")) is PdfResult.Ok)
            assertTrue(filter, engine.compress(f, CompressionLevel.HIGH, out("c.pdf")) is PdfResult.Ok)
        }
    }

    @Test
    fun asciiWrappedBombs_areRefusedLikeUnwrapped() {
        for ((filter, enc) in accepted.filter { it.first.contains("Fl") }) {
            // 17.5 MB decoded > 16 MiB.
            val bomb = write("bomb.pdf", inCatalogStream(repeated("null ", 3_500_000), filter, enc))
            assertTooLarge("$filter: scan") { ObjStmScan.check(bomb) }
            assertTooLarge("$filter: tool path") { PdfInputs.open(bomb).destroy() }
            assertEquals(filter, PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb))
            assertEquals(filter, PdfResult.Err(PdfError.OutOfMemory), engine.compress(bomb, CompressionLevel.HIGH, out("c.pdf")))
            assertEquals(filter, PdfResult.Err(PdfError.OutOfMemory), engine.merge(listOf(bomb), out("m.pdf")))
        }
        // A85 alone: 17 MiB of NULs written as `z` is 4.4 MB raw.
        val z = write("z-bomb.pdf", inCatalogStream(ByteArray(17 shl 20), "/A85", { b -> a85(b) }))
        assertTrue(z.length() < 5_000_000)
        assertTooLarge("z bomb") { PdfInputs.open(z).destroy() }
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(z))
    }

    @Test
    fun asciiObjectStreams_perStreamAndTotalLimits() {
        val big = repeated("1 0 R ", 50_000) // 300 KB decoded
        for ((filter, enc) in accepted) {
            val f = write("v.pdf", inCatalogStream(big, filter, enc))
            assertTooLarge(filter) { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
            ObjStmScan.check(f, maxStream = 400_000, maxTotal = 1_000_000)
            // Several streams adding up over the total.
            val pdf = HostilePdf()
            basicPage(pdf)
            for (i in 0 until 5) pdf.objStm(20 + i, listOf(30 + i to repeated("null ", 40_000)), encode = { b -> filter to enc(b) })
            val many = write("many.pdf", pdf.bytes())
            ObjStmScan.check(many, maxStream = 300_000, maxTotal = 1_100_000)
            assertTooLarge("$filter: total") { ObjStmScan.check(many, maxStream = 300_000, maxTotal = 900_000) }
        }
    }

    /** MuPDF's white space includes BS and DEL, which the PDF lexer's does not: they must not end the data. */
    @Test
    fun mupdfWhiteSpaceInsideTheData_doesNotHideABomb() {
        val sprinkle = { s: ByteArray -> ByteArrayOutputStream().also { o -> s.forEachIndexed { i, b -> o.write(b.toInt()); if (i % 7 == 3) o.write(if (i % 2 == 0) 8 else 0x7F) } }.toByteArray() }
        for ((filter, enc) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "[/A85/Fl]" to { b -> sprinkle(a85(deflate(b))) },
            "[/AHx/Fl]" to { b -> sprinkle(ahx(deflate(b))) },
        )) {
            val f = write("ws.pdf", inCatalogStream(repeated("1 0 R ", 50_000), filter, enc))
            assertTooLarge(filter) { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
            ObjStmScan.check(f, maxStream = 400_000, maxTotal = 1_000_000)
        }
    }

    @Test
    fun unterminatedAsciiData_isStillMeasured() {
        val strip = { s: ByteArray, n: Int -> s.copyOf(s.size - n) }
        for ((filter, enc) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "[/A85/Fl]" to { b -> strip(a85(deflate(b)), 2) }, // no `~>`
            "[/AHx/Fl]" to { b -> strip(ahx(deflate(b)), 1) }, // no `>`
            "/A85" to { b -> strip(a85(b), 2) },
        )) {
            val f = write("unterminated.pdf", inCatalogStream(repeated("1 0 R ", 50_000), filter, enc))
            assertTooLarge(filter) { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
            val ok = write("unterminated-ok.pdf", inCatalogStream(repeated("null ", 1000), filter, enc))
            ObjStmScan.check(ok)
        }
    }

    @Test
    fun zGroups_areCountedAsFourBytes() {
        // 60,000 `z` = 240 KB decoded from 60 KB.
        val pdf = HostilePdf()
        basicPage(pdf)
        pdf.add(20, "<</Type/ObjStm/N 1/First 4/Filter/A85/Length 60002>>stream\n" + "z".repeat(60_000) + "~>\nendstream")
        val f = write("z.pdf", pdf.bytes())
        assertTooLarge { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
        ObjStmScan.check(f, maxStream = 250_000, maxTotal = 1_000_000)
    }

    @Test
    fun unacceptedChains_areRefused() {
        val data = repeated("null ", 10)
        for ((filter, enc) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "[/A85/A85/Fl]" to { b -> a85(a85(deflate(b))) },
            "[/AHx/A85]" to { b -> ahx(a85(b)) },
            "[/A85/AHx]" to { b -> a85(ahx(b)) },
            "[/Fl/A85]" to { b -> deflate(a85(b)) },
            "[/A85/Fl/Fl]" to { b -> a85(deflate(deflate(b))) },
            "[/Fl/Fl]" to { b -> deflate(deflate(b)) },
            "[/A85/RunLengthDecode]" to { b -> a85(b) },
            "[/LZWDecode/Fl]" to { b -> deflate(b) },
            "[/A85/Crypt]" to { b -> a85(b) },
            "[/A85 9 0 R]" to { b -> a85(b) },
            "[/A85(Fl)]" to { b -> a85(b) },
            " 9 0 R" to { b -> a85(b) },
        )) {
            val f = write("refused.pdf", inCatalogStream(data, filter, enc))
            assertTooLarge(filter) { ObjStmScan.check(f) }
        }
    }

    // ------------------------------------------------------------------ W4: where the data starts

    /**
     * W4: MuPDF (`pdf_parse_ind_obj`) skips spaces after `stream`, then consumes ONE byte whatever it
     * is (plus an LF after a CR). Keyword to whether MuPDF's start is the first data byte here
     * (false: it starts on a byte of the delimiter, `>` after `\r` or LF after TAB).
     */
    private val starts: List<Pair<String, Boolean>> = listOf(
        "stream>" to true, "stream <" to true, "stream\r>" to false, "stream\n" to true, "stream\r\n" to true,
        "stream \n" to true, "stream(" to true, "stream%" to true, "stream  \r\n" to true, "stream\t\n" to false,
        "s#74ream\n" to true, // MuPDF's lexer decodes `#xx` in keywords too
    )

    private val bombFilters: List<Pair<String, (ByteArray) -> ByteArray>> = listOf(
        "[/AHx/Fl]" to { b -> ahx(deflate(b)) },
        "[/A85/Fl]" to { b -> a85(deflate(b)) },
        "/FlateDecode" to { b -> deflate(b) },
    )

    @Test
    fun streamStartVariants_bombsAreRefusedAndBenignFilesOpen() {
        for ((start, exact) in starts) for ((filter, enc) in bombFilters) {
            val what = "${start.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")} $filter"
            val bomb = write("start-bomb.pdf", inCatalogStream(repeated("null ", 3_500_000), filter, enc, start))
            if (exact) {
                assertTooLarge("$what: scan") { ObjStmScan.check(bomb) }
                assertEquals(what, PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb))
                val ok = write("start-ok.pdf", inCatalogStream(repeated("null ", 1000), filter, enc, start))
                ObjStmScan.check(ok)
                PdfInputs.open(ok).destroy()
                assertTrue(what, engine.pageInfo(ok) is PdfPageInfo.Ok)
            } else {
                // MuPDF starts inside the delimiter: whatever it decodes, the scan measures no less.
                val n = mupdfDecoded(bomb, 5)
                if (n > 0) assertTooLarge("$what: $n") { ObjStmScan.check(bomb, maxStream = n - 1L) }
                else runCatching { ObjStmScan.check(bomb) }
            }
        }
    }

    /**
     * Differential: for every start variant, the scan's decoded size of the object stream is at
     * least MuPDF's `readStream` size (a limit one byte under MuPDF's size refuses), and exactly it
     * where MuPDF's start is the first data byte (a limit of exactly MuPDF's size passes).
     */
    @Test
    fun streamStartVariants_scanMeasuresWhatMuPdfDecodes() {
        val truncated: (ByteArray) -> ByteArray = { b -> deflate(b).let { it.copyOf(it.size - 6) } }
        for ((start, exact) in starts) for ((label, enc) in bombFilters + ("truncated" to truncated)) {
            val filter = if (label == "truncated") "/FlateDecode" else label
            val what = "${start.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")} $label"
            val f = write("start-diff.pdf", inCatalogStream(repeated("1 0 R ", 50_000), filter, enc, start))
            val n = mupdfDecoded(f, 5)
            if (exact) assertTrue("$what: MuPDF read $n", n > 290_000)
            // A truncated zlib stream: the scan inflates on past MuPDF's `/Length` into the bytes
            // after the data (`endstream`...), so it may measure a few bytes MORE (a superset).
            if (exact && label != "truncated") {
                val e = runCatching { ObjStmScan.check(f, maxStream = n.toLong()) }.exceptionOrNull()
                assertTrue("$what: scan measured more than MuPDF's $n: $e", e == null)
            }
            if (n > 0) assertTooLarge("$what: $n") { ObjStmScan.check(f, maxStream = n - 1L) }
        }
    }

    // ------------------------------------------------------------------ W5: `obj` spelled with #xx

    /** W5: spellings of the `obj` keyword MuPDF 1.28.5 accepts in a header (`lex_name` decodes `#xx` in keywords). */
    private val objSpellings = listOf("obj", "o#62j", "#6fbj", "ob#6a", "#6f#62#6a", "#6F#62#6A", "o#62#6A")

    /** W5: lookalikes MuPDF does not read as `obj` (`#00` is not decoded; `#6` lacks a digit; `#20` adds a space). */
    private val notObj = listOf("obj#00", "o#6", "#6fbj#20", "o#62", "#6f#62#6a#6a", "o#42j")

    private val xrefKinds = listOf("classic", "stream", "repair")

    /** MuPDF loads an object whose header is `5 0 o#62j` (etc.) from a table, an xref stream and on repair. */
    @Test
    fun escapedObjHeaders_mupdfLoadsTheObject() {
        for (kw in objSpellings) for (xref in xrefKinds) {
            val f = write("w5-load.pdf", escapedHeaderPdf(kw, xref, repeated("1 0 R ", 50_000)))
            val doc = Document.openDocument(f.path) as PDFDocument
            try {
                assertEquals("$kw $xref", 1, doc.countPages())
                assertEquals("$kw $xref: repaired", xref == "repair", doc.wasRepaired())
            } finally {
                doc.destroy()
            }
            assertEquals("$kw $xref", 300_006, mupdfDecoded(f, 5))
        }
    }

    @Test
    fun escapedObjHeaders_bombsAreRefusedAndBenignFilesOpen() {
        for (kw in objSpellings) for (xref in xrefKinds) {
            val what = "$kw $xref"
            val bomb = write("w5-bomb.pdf", escapedHeaderPdf(kw, xref, repeated("null ", 3_500_000))) // 17.5 MB > 16 MiB
            assertTooLarge("$what: scan") { ObjStmScan.check(bomb) }
            assertTooLarge("$what: reader") { PdfInputs.openForReader(bomb, null, null).destroy() }
            assertTooLarge("$what: tool path") { PdfInputs.open(bomb).destroy() }
            val ok = write("w5-ok.pdf", escapedHeaderPdf(kw, xref, repeated("null ", 1000)))
            // The header's `5 0` is recorded for pass 2 (encrypted files).
            assertEquals(what, listOf(ObjStmScan.Ref(5, 0)), ObjStmScan.check(ok).flate)
            PdfInputs.openForReader(ok, null, null).destroy()
        }
    }

    /**
     * Differential: for every accepted spelling, xref kind and `endstream` spelling, the scan
     * measures exactly MuPDF's `readStream` size (a limit of MuPDF's size passes, one under refuses).
     */
    @Test
    fun escapedObjHeaders_scanMeasuresWhatMuPdfDecodes() {
        for (kw in objSpellings) for (xref in xrefKinds) for (end in listOf("endstream", "end#73tream", "#65ndstream")) {
            val what = "$kw $xref $end"
            val f = write("w5-diff.pdf", escapedHeaderPdf(kw, xref, repeated("1 0 R ", 50_000), end))
            val n = mupdfDecoded(f, 5)
            assertEquals(what, 300_006, n)
            val e = runCatching { ObjStmScan.check(f, maxStream = n.toLong()) }.exceptionOrNull()
            assertTrue("$what: scan measured more than MuPDF's $n: $e", e == null)
            assertTooLarge("$what: $n") { ObjStmScan.check(f, maxStream = n - 1L) }
        }
    }

    /**
     * The object number in front of `obj` as MuPDF lexes it, vs. the scan's `refBefore`: the ref
     * recorded for pass 2 is MuPDF's own `5 0` or none (`-1`, refused in pass 2: fails closed),
     * never another object's; and the bomb is refused by pass 1 whatever the number looks like.
     */
    @Test
    fun headerNumberSpellings_refIsMuPdfsOrNone() {
        val nums = listOf("7 /5 0", "7(5 0", "7<5 0", "+5 0", "05 0", "5 00", "5 +0", "5-3 0", "5 0%c\n", "-5 0", "5\n0", "00000000005 0")
        val sb = StringBuilder()
        for (num in nums) for (kw in listOf("obj", "o#62j")) for (xref in xrefKinds) {
            val what = "${num.replace("\n", "\\n")} $kw $xref"
            val bomb = write("w5-num-bomb.pdf", escapedHeaderPdf(kw, xref, repeated("null ", 3_500_000), num = num))
            assertTooLarge("$what: scan") { ObjStmScan.check(bomb) }
            val ok = write("w5-num.pdf", escapedHeaderPdf(kw, xref, repeated("1 0 R ", 50_000), num = num))
            val refs = ObjStmScan.check(ok).flate
            val n = mupdfDecoded(ok, 5)
            val other = listOf(0, 3, 7).associateWith { mupdfDecoded(ok, it) }.filterValues { it == 300_006 }
            sb.append("W5num $what\tmupdf(5 0)=$n\tother=$other\tref=$refs\n")
            assertEquals(what, 1, refs.size)
            assertTrue("$what: $refs", refs[0] == ObjStmScan.Ref(-1, -1) || refs[0] == ObjStmScan.Ref(5, 0))
            assertTrue("$what: MuPDF loads it as $other", other.isEmpty())
        }
        println(sb)
    }

    /** W5 follow-up: names with `#00` and short or bad escapes lex to the same characters as MuPDF's `lex_name`. */
    @Test
    fun nameEscapes_scanLexesWhatMuPdfLexes() {
        val names = listOf(
            "N#00", "Fl#00", "s#00tream", "A#0", "B#6", "C#g1", "D#00F", "E#000", "F#01x", "G#4e#00", "H#30#30",
            "I##41", "J#4", "K#0#41", "L#6F#62#6a", "M#7e", "O#", "P#0g", "Q#ff", "R#00#00",
        )
        val dict = names.withIndex().joinToString("") { (i, n) -> "/K$i /$n" }
        val pdf = HostilePdf()
        basicPage(pdf)
        pdf.add(6, "<<$dict>>")
        val bytes = pdf.bytes()
        val f = write("names.pdf", bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        val doc = Document.openDocument(f.path) as PDFDocument
        try {
            val o = doc.newIndirect(6, 0)
            try {
                names.forEachIndexed { i, n ->
                    val at = text.indexOf("/K$i /$n") + "/K$i /".length
                    val mupdf = o.get("K$i").asName()
                    assertEquals(n, mupdf, ObjStmScan.lexToken(f, at.toLong()))
                }
                // Spot checks of the rule itself.
                assertEquals("N#00", o.get("K0").asName())
                assertEquals("I#A", o.get("K11").asName())
                assertEquals("00", o.get("K10").asName().substring(1))
            } finally {
                o.destroy()
            }
        } finally {
            doc.destroy()
        }
        // `#` and one hex digit at the very end of the file: MuPDF keeps the `#` and drops the digit.
        for ((tail, want) in listOf("/A#6" to "A#", "/A#" to "A#", "/A#0" to "A#", "/A#g" to "A#g", "/A#41" to "AA")) {
            val e = write("tail.pdf", "%PDF-1.7\n$tail".latin1())
            assertEquals(tail, want, ObjStmScan.lexToken(e, 10))
        }
    }

    /** `s#00tream` is not the `stream` keyword (for MuPDF nor the scan) and `/Fl#00` is not Flate. */
    @Test
    fun nulEscapes_inKeywordsAndFilters() {
        val pdf = HostilePdf()
        basicPage(pdf)
        val data = deflate(repeated("null ", 3_500_000))
        pdf.add(20, "<</Type/ObjStm/N 1/First 4/Filter/FlateDecode/Length ${data.size}>>s#00tream\n".latin1().let { String(it + data + "\nendstream".latin1(), Charsets.ISO_8859_1) })
        val f = write("nul-stream.pdf", pdf.bytes())
        val doc = Document.openDocument(f.path) as PDFDocument
        try {
            val o = doc.newIndirect(20, 0)
            try { assertTrue("MuPDF: not a stream", !o.isStream) } finally { o.destroy() }
        } finally {
            doc.destroy()
        }
        assertEquals(0, mupdfDecoded(f, 20))
        ObjStmScan.check(f) // nor a stream for the scan: the 17.5 MB inflate is not measured
        val bomb = write("nul-filter.pdf", inCatalogStream(repeated("null ", 1000), "/Fl#00", { b -> deflate(b) }))
        assertTooLarge("/Fl#00 is an unknown filter: refused") { ObjStmScan.check(bomb) }
    }

    /** Lookalikes MuPDF does not take for `obj`: it cannot load the object, from any xref kind. */
    @Test
    fun objLookalikes_mupdfDoesNotLoadTheObject() {
        for (kw in notObj) for (xref in xrefKinds) {
            val f = write("w5-not.pdf", escapedHeaderPdf(kw, xref, repeated("1 0 R ", 50_000)))
            assertEquals("$kw $xref", 0, mupdfDecoded(f, 5))
        }
    }

    /**
     * W5: catalog, pages, page, contents, then object 5, a Flate object stream holding [payload]
     * whose header is `5 0 [kw]`; [xref] is `classic` (table), `stream` (xref stream 7) or
     * `repair` (no xref at all: MuPDF rebuilds it by lexing the file).
     */
    private fun escapedHeaderPdf(kw: String, xref: String, payload: ByteArray, endstream: String = "endstream", num: String = "5 0"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n".latin1())
        val offs = IntArray(8)
        fun obj(n: Int, body: ByteArray, k: String = "obj") {
            offs[n] = out.size()
            out.write((if (n == 5) "$num $k\n" else "$n 0 $k\n").latin1()); out.write(body); out.write("\nendobj\n".latin1())
        }
        obj(1, "<</Type/Catalog/Pages 2 0 R>>".latin1())
        obj(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>".latin1())
        obj(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>".latin1())
        obj(4, "<</Length 5>>stream\nBT ET\nendstream".latin1())
        val head = "6 0\n".latin1()
        val data = deflate(head + payload)
        obj(5, "<</Type/ObjStm/N 1/First ${head.size}/Filter/FlateDecode/Length ${data.size}>>stream\n".latin1() + data + "\n$endstream".latin1(), kw)
        when (xref) {
            "classic" -> {
                val at = out.size()
                val sb = StringBuilder("xref\n0 6\n0000000000 65535 f\r\n")
                for (n in 1..5) sb.append("%010d 00000 n\r\n".format(offs[n]))
                sb.append("trailer\n<</Size 6/Root 1 0 R>>\nstartxref\n$at\n%%EOF\n")
                out.write(sb.toString().latin1())
            }
            "stream" -> {
                val at = out.size()
                val rows = java.nio.ByteBuffer.allocate(8 * 7)
                for (n in 0 until 8) when (n) {
                    0 -> rows.put(0).putInt(0).putShort(-1)
                    6 -> rows.put(0).putInt(0).putShort(0)
                    7 -> rows.put(1).putInt(at).putShort(0)
                    else -> rows.put(1).putInt(offs[n]).putShort(0)
                }
                val x = deflate(rows.array())
                out.write("7 0 obj\n<</Type/XRef/Size 8/W[1 4 2]/Root 1 0 R/Filter/FlateDecode/Length ${x.size}>>stream\n".latin1())
                out.write(x)
                out.write("\nendstream\nendobj\nstartxref\n$at\n%%EOF\n".latin1())
            }
            else -> out.write("trailer\n<</Size 6/Root 1 0 R>>\n%%EOF\n".latin1())
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ the decoder alone

    @Test
    fun decoder_endOfDataAndBadBytes() {
        val cases = listOf(
            // A85
            Triple(true, "87cURD]j7BEbo80~>", "Hello world!"),
            Triple(true, "87cURD]j7BEbo8", "Hello wo"), // EOF without `~>`: the partial group is dropped
            Triple(true, "87cUR~x", "Hell"), // `~` ends whatever follows
            Triple(true, "87cURD~>", "Hell"), // count 1 at EOD: nothing
            Triple(true, "87cURDZ~>", "Hello"), // count 2: 1 byte
            Triple(true, "z87cUR~>", "\u0000\u0000\u0000\u0000Hell"),
            Triple(true, "87z", ""), // `z` inside a group: bad data, ends
            Triple(true, "87cURv87cUR~>", "Hell"), // `v` is bad data
            Triple(true, "87\b\u007fcU\u0000R \n\r\t\u000c87cUR~>", "HellHell"), // MuPDF white space, BS and DEL included
            Triple(true, "s8W-!~>", "ÿÿÿÿ"),
            Triple(true, "uuuuu~>", null), // overflowing group: 4 bytes (wrapped)
            // AHx
            Triple(false, "48 65\n6c6C6f>", "Hello"),
            Triple(false, "48656c6c6>", "Hell`"), // odd final digit at `>`
            Triple(false, "48656c6c6", "Hell"), // odd digit at EOF: dropped
            Triple(false, "4865zz6c>", "He"), // garbage ends
            Triple(false, "48\b\u007f65>", "He"),
        )
        for ((a85, input, expected) in cases) {
            val got = decodeAll(a85, input.latin1(), inChunk = 1000, outSize = 1024)
            if (expected != null) assertEquals("$a85 $input", expected, String(got, Charsets.ISO_8859_1))
            else assertEquals(input, 4, got.size)
        }
    }

    @Test
    fun decoder_roundTripsAtEveryChunking() {
        val rnd = Random(7)
        repeat(300) {
            val data = ByteArray(rnd.nextInt(0, 70)) { if (rnd.nextInt(4) == 0) 0 else rnd.nextInt(256).toByte() }
            for ((a85, enc) in listOf(true to a85(data), false to ahx(data))) {
                for ((inChunk, outSize) in listOf(1 to 4, 3 to 5, 7 to 9, 1000 to 1024)) {
                    assertArrayEquals("$a85 $inChunk/$outSize", data, decodeAll(a85, enc, inChunk, outSize))
                }
            }
        }
    }

    /**
     * Differential check against MuPDF itself: for random ASCII data (white space, `z`, `~`, `>`,
     * bad bytes), MuPDF never decodes MORE than the scan's decoder (the scan must not under-measure),
     * and exactly as much when the data has no bad byte.
     */
    @Test
    fun decoder_neverDecodesLessThanMuPdf() {
        val rnd = Random(11)
        val a85Alphabet = "!!#5Kus8W-z~> \n\b\u007fv{\u0080".latin1()
        val ahxAlphabet = "00aF9e > \n\b\u007fzg\u0080".latin1()
        var differed = 0
        repeat(200) { round ->
            for ((a85, alphabet) in listOf(true to a85Alphabet, false to ahxAlphabet)) {
                val data = ByteArray(rnd.nextInt(1, 400)) { alphabet[rnd.nextInt(alphabet.size)] }
                val mine = decodeAll(a85, data, 1000, 1024)
                val pdf = HostilePdf()
                basicPage(pdf)
                pdf.add(20, "<</Filter/${if (a85) "A85" else "AHx"}/Length ${data.size}>>stream\n".latin1().let { String(it + data + "\nendstream".latin1(), Charsets.ISO_8859_1) })
                val f = write("diff.pdf", pdf.bytes())
                val doc = Document.openDocument(f.path) as PDFDocument
                val theirs = try {
                    val o = doc.newIndirect(20, 0)
                    try { o.readStream() } finally { o.destroy() }
                } finally {
                    doc.destroy()
                }
                assertTrue("round $round a85=$a85: MuPDF ${theirs.size} > scan ${mine.size}", theirs.size <= mine.size)
                if (!hasBadByte(a85, data)) assertArrayEquals("round $round a85=$a85", mine, theirs)
                if (theirs.size != mine.size) differed++
            }
        }
        println("W1 differential: $differed of 400 differed (MuPDF dropping its buffer at bad data)")
    }

    // ------------------------------------------------------------------ helpers

    /** Whether MuPDF meets bad data (a throw) before the end-of-data marker in [data]. */
    private fun hasBadByte(a85: Boolean, data: ByteArray): Boolean {
        var count = 0
        for (b in data) {
            val c = b.toInt() and 0xFF
            val white = c == 0 || c == 8 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 || c == 0x7F
            when {
                white -> {}
                a85 && c in '!'.code..'u'.code -> count = (count + 1) % 5
                a85 && c == 'z'.code -> if (count != 0) return true
                a85 && c == '~'.code -> return false
                !a85 && (c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code) -> {}
                !a85 && c == '>'.code -> return false
                else -> return true
            }
        }
        return false
    }

    private fun decodeAll(a85: Boolean, input: ByteArray, inChunk: Int, outSize: Int): ByteArray {
        val dec = ObjStmScan.AsciiDecoder(a85)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(outSize)
        var pos = 0
        while (pos < input.size && !dec.ended) {
            val end = minOf(input.size, pos + inChunk)
            var p = pos
            while (p < end && !dec.ended) {
                val used = dec.decode(input, p, end, buf, 0)
                out.write(buf, 0, dec.produced)
                p += used
            }
            pos = p
        }
        return out.toByteArray()
    }

    private fun inCatalogStream(extra: ByteArray, filter: String, enc: (ByteArray) -> ByteArray, start: String = "stream\n"): ByteArray {
        val pdf = HostilePdf()
        pdf.stream(4, "", "BT ET".latin1())
        pdf.objStm(
            5,
            listOf(
                1 to "<</Type/Catalog/Pages 2 0 R>>".latin1(),
                2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>".latin1(),
                3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>".latin1(),
                6 to extra,
            ),
            encode = { b -> filter to enc(b) },
            start = start,
        )
        return pdf.bytes()
    }

    /** MuPDF's decoded size of object [num] in [f] (0 when it cannot read it). */
    private fun mupdfDecoded(f: File, num: Int): Int {
        val doc = Document.openDocument(f.path) as PDFDocument
        try {
            val o = doc.newIndirect(num, 0)
            try {
                return runCatching { o.readStream().size }.getOrDefault(0)
            } finally {
                o.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    private fun basicPage(pdf: HostilePdf) {
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
        pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>")
        pdf.stream(4, "", "BT ET".latin1())
    }

    private fun assertTooLarge(what: String = "", block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("$what: expected OutOfMemory, got $e", e is PdfInputException && e.error == PdfError.OutOfMemory)
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }
    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private lateinit var dir: File

        /** ASCII85 as Adobe writes it: `z` for a zero group, lines of 75, partial final group, `~>`. */
        fun a85(data: ByteArray): ByteArray {
            val sb = StringBuilder()
            var line = 0
            fun put(c: Char) { sb.append(c); if (++line == 75) { sb.append('\n'); line = 0 } }
            var i = 0
            while (i < data.size) {
                val n = minOf(4, data.size - i)
                var w = 0L
                for (k in 0 until 4) w = (w shl 8) or (if (k < n) (data[i + k].toLong() and 0xFF) else 0L)
                if (n == 4 && w == 0L) {
                    put('z')
                } else {
                    val cs = CharArray(5)
                    for (k in 4 downTo 0) { cs[k] = ('!'.code + (w % 85).toInt()).toChar(); w /= 85 }
                    for (k in 0..n) put(cs[k])
                }
                i += 4
            }
            sb.append("~>")
            return sb.toString().latin1()
        }

        /** ASCIIHex in lines of 64 digits, ending in `>`. */
        fun ahx(data: ByteArray): ByteArray {
            val sb = StringBuilder(data.size * 2 + data.size / 32 + 2)
            data.forEachIndexed { i, b ->
                sb.append("%02x".format(b.toInt() and 0xFF))
                if (i % 32 == 31) sb.append('\n')
            }
            sb.append('>')
            return sb.toString().latin1()
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("objstm-ascii").toFile()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
