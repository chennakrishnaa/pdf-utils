package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.ops.RenderImageGate
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Pixmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RDR-3: [RenderImageGate] refuses, before `Page.run`, pages whose images are proven over the
 * reader's limits, and lets ordinary large images render.
 *
 * Safe by construction: every bomb below is refused by the gate without decoding, and each call is
 * asserted to return within [FAST_MS]. Without the gate (`RenderPageOp` not calling it), the
 * `absurdDeclaredSize` case returns `Ok` (the test then fails on Ok vs Err), and the 65500² DCT
 * bombs are the SA-2/SA-6 shapes that peaked at 1-3.9 GB: they are never rendered ungated here.
 */
class RenderImageGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()
    private val bomb = EngineSecurityTest.progressiveJpegDeclaring(65500, 65500)

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }

    /** One 200×200 page with [resources] and content [content]; [more] adds objects (numbers ≥ 10). */
    private fun page(resources: String, content: String, annots: String = "", more: HostilePdf.() -> Unit): ByteArray {
        val pdf = HostilePdf()
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
        pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources $resources/Contents 4 0 R$annots>>")
        pdf.stream(4, "", content.latin1())
        pdf.more()
        return pdf.bytes()
    }

    private val drawIm0 = "q 200 0 0 200 0 0 cm /Im0 Do Q"
    private val drawFm0 = "/Fm0 Do"

    private fun HostilePdf.dctImage(num: Int, data: ByteArray, w: Int = 8, h: Int = 8, cs: String = "/DeviceRGB", filter: String = "/DCTDecode") =
        stream(num, "/Type/XObject/Subtype/Image/Width $w/Height $h/ColorSpace$cs/BitsPerComponent 8/Filter$filter", data, flate = false)

    private fun HostilePdf.form(num: Int, resources: String, content: String) =
        stream(num, "/Type/XObject/Subtype/Form/BBox[0 0 200 200]/Resources $resources", content.latin1())

    private fun assertRefused(name: String, bytes: ByteArray) {
        val pdf = file("$name.pdf", bytes)
        assertTrue("$name pageInfo unaffected", engine.pageInfo(pdf) is PdfPageInfo.Ok)
        val out = File(tmp.root, "$name.rgba").apply { writeText("stale") }
        val t0 = System.nanoTime()
        val r = engine.renderPage(pdf, RenderRequest(0, 1f, 0, 0, 200, 200), out, JobControl.None)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(name, PdfRender.Err(PdfError.OutOfMemory), r)
        assertFalse("$name: out must not exist", out.exists())
        assertTrue("$name took $ms ms", ms < FAST_MS)
    }

    private fun assertRenders(name: String, pdf: File, scale: Float = 1f) {
        val dims = (engine.pageInfo(pdf) as PdfPageInfo.Ok).pages[0]
        val w = minOf(RenderLimits.scaledWidth(dims, scale).toInt(), 1000)
        val h = minOf(RenderLimits.scaledHeight(dims, scale).toInt(), 1000)
        val out = File(tmp.root, "$name.rgba")
        val r = engine.renderPage(pdf, RenderRequest(0, scale, 0, 0, w, h), out, JobControl.None)
        assertEquals(name, PdfRender.Ok(w, h), r)
        assertEquals(w.toLong() * h * 4, out.length())
    }

    // ------------------------------------------------------------------ bombs

    @Test
    fun progressiveJpegBomb_onThePage() {
        assertRefused("dct", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) { dctImage(10, bomb) })
    }

    @Test
    fun dctBomb_behindFlateOrOtherPrefixFilters_andAbbreviations() {
        val fl = HostilePdf.deflate(bomb)
        assertRefused("fl-dct", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) { dctImage(10, fl, filter = "[/FlateDecode/DCTDecode]") })
        assertRefused("ahx-dct", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            dctImage(10, (bomb.joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ">").latin1(), filter = "[/AHx/DCT]")
        })
        assertRefused("dct-abbrev", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/W 8/H 8/CS/RGB/BPC 8/F/DCT", bomb, flate = false)
        })
        assertRefused("dct-not-last", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) { dctImage(10, bomb, filter = "[/DCTDecode/FlateDecode]") })
    }

    @Test
    fun absurdDeclaredSize() {
        // 60000×60000 RGB = 10.8 GB decoded; the stream itself is a few bytes.
        assertRefused("huge-dims", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 60000/Height 60000/ColorSpace/DeviceRGB/BitsPerComponent 8", ByteArray(64))
        })
        assertRefused("huge-edge", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 70000/Height 1/ColorSpace/DeviceGray/BitsPerComponent 1", ByteArray(64))
        })
    }

    @Test
    fun bombThroughFormNestedFormsCycleSoftMaskAnnotationPatternAndType3() {
        assertRefused("form", page("<</XObject<</Fm0 11 0 R>>>>", drawFm0) {
            form(11, "<</XObject<</Im0 10 0 R>>>>", drawIm0); dctImage(10, bomb)
        })
        assertRefused("nested", page("<</XObject<</Fm0 11 0 R>>>>", drawFm0) {
            form(11, "<</XObject<</Fm1 12 0 R>>>>", "/Fm1 Do")
            form(12, "<</XObject<</Fm2 13 0 R>>>>", "/Fm2 Do")
            form(13, "<</XObject<</Im0 10 0 R>>>>", drawIm0)
            dctImage(10, bomb)
        })
        assertRefused("cycle", page("<</XObject<</Fm0 11 0 R>>>>", drawFm0) {
            form(11, "<</XObject<</Fm1 12 0 R>>>>", "/Fm1 Do")
            form(12, "<</XObject<</Fm0 11 0 R/Im0 10 0 R>>>>", "/Fm0 Do $drawIm0")
            dctImage(10, bomb)
        })
        assertRefused("form-inherits", page("<</XObject<</Fm0 11 0 R/Im0 10 0 R>>>>", drawFm0) {
            stream(11, "/Subtype/Form/BBox[0 0 200 200]", drawIm0.latin1()); dctImage(10, bomb)
        })
        assertRefused("smask", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/ColorSpace/DeviceRGB/BitsPerComponent 8/SMask 12 0 R", ByteArray(192))
            dctImage(12, bomb, cs = "/DeviceGray")
        })
        assertRefused("annotation-ap", page("<<>>", "", annots = "/Annots[20 0 R]") {
            add(20, "<</Type/Annot/Subtype/Square/Rect[0 0 100 100]/AP<</N 11 0 R>>>>")
            form(11, "<</XObject<</Im0 10 0 R>>>>", drawIm0); dctImage(10, bomb)
        })
        assertRefused("annotation-ap-states", page("<<>>", "", annots = "/Annots[20 0 R]") {
            add(20, "<</Type/Annot/Subtype/Square/Rect[0 0 100 100]/AS/On/AP<</N<</On 11 0 R/Off 11 0 R>>>>>>")
            form(11, "<</XObject<</Im0 10 0 R>>>>", drawIm0); dctImage(10, bomb)
        })
        assertRefused("pattern", page("<</Pattern<</P0 11 0 R>>>>", "/Pattern cs /P0 scn 0 0 200 200 re f") {
            stream(11, "/Type/Pattern/PatternType 1/PaintType 1/TilingType 1/BBox[0 0 10 10]/XStep 10/YStep 10/Resources<</XObject<</Im0 10 0 R>>>>", "/Im0 Do".latin1())
            dctImage(10, bomb)
        })
        assertRefused("softmask-group", page("<</ExtGState<</G0<</SMask<</S/Luminosity/G 11 0 R>>>>>>>>", "/G0 gs 0 0 200 200 re f") {
            form(11, "<</XObject<</Im0 10 0 R>>>>", drawIm0); dctImage(10, bomb)
        })
        assertRefused("type3", page("<</Font<</T3 12 0 R>>>>", "BT /T3 12 Tf (a) Tj ET") {
            add(12, "<</Type/Font/Subtype/Type3/FontBBox[0 0 1 1]/FontMatrix[1 0 0 1 0 0]/FirstChar 97/LastChar 97/Widths[1]" +
                "/Encoding<</Differences[97/a]>>/CharProcs<</a 13 0 R>>/Resources<</XObject<</Im0 10 0 R>>>>>>")
            stream(13, "", "1 0 0 0 1 1 d1 /Im0 Do".latin1())
            dctImage(10, bomb)
        })
    }

    @Test
    fun nestingBeyondTheBudgetIsRefused() {
        val depth = RenderImageGate.MAX_NESTING + 5
        assertRefused("deep", page("<</XObject<</Fm0 100 0 R>>>>", drawFm0) {
            for (i in 0 until depth) form(100 + i, "<</XObject<</Fm0 ${101 + i} 0 R>>>>", "/Fm0 Do")
            form(100 + depth, "<<>>", "")
        })
    }

    @Test
    fun thousandsOfXObjectEntries() {
        val n = RenderImageGate.MAX_VISITS + 5_000
        val dict = (0 until n).joinToString("") { "/I$it 10 0 R" }
        val content = (0 until n).joinToString(" ") { "/I$it Do" }
        assertRefused("many", page("<</XObject<<$dict>>>>", content) {
            stream(10, "/Subtype/Image/Width 1/Height 1/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(1))
        })
    }

    @Test
    fun jpxWhoseCodestreamDeclaresAHugeImage() {
        // SOC, SIZ: Lsiz 41, Rsiz 0, Xsiz=Ysiz=60000, offsets 0, tiles, Csiz 3 (+ 3 component triples).
        val siz = java.nio.ByteBuffer.allocate(4 + 2 + 2 + 32 + 2 + 9 + 2)
            .put(byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)).putShort(47).putShort(0)
            .putInt(60000).putInt(60000).putInt(0).putInt(0).putInt(60000).putInt(60000).putInt(0).putInt(0)
            .putShort(3).put(byteArrayOf(7, 1, 1, 7, 1, 1, 7, 1, 1)).put(byteArrayOf(0xFF.toByte(), 0xD9.toByte())).array()
        assertEquals(Triple(60000L, 60000L, 3L), RenderImageGate.jpxSize(siz))
        assertRefused("jpx", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/Filter/JPXDecode", siz, flate = false)
        })
        assertRefused("jpx-no-siz", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/Filter/JPXDecode", ByteArray(100), flate = false)
        })
    }

    @Test
    fun predictorRowAndIccProfileBombs() {
        assertRefused("predictor", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/ColorSpace/DeviceRGB/BitsPerComponent 8" +
                "/DecodeParms<</Predictor 15/Columns 4194000/Colors 32/BitsPerComponent 16>>", ByteArray(192))
        })
        assertRefused("icc", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/ColorSpace[/ICCBased 11 0 R]/BitsPerComponent 8", ByteArray(192))
            stream(11, "/N 3", ByteArray(8 shl 20)) // inflates to 8 MiB > 4 MiB
        })
    }

    // ------------------------------------------------------------------ F4 re-review (RF-1 .. RF-7)

    /** 32 benign entries plus the bomb under [key] (PDF name syntax, without the slash), drawn by [use]. */
    private fun oddName(key: String, use: String = key): ByteArray {
        val dict = (0 until 32).joinToString("") { "/I$it 11 0 R" } + "/$key 10 0 R"
        return page("<</XObject<<$dict>>>>", "q 200 0 0 200 0 0 cm /I1 Do /$use Do Q") {
            stream(11, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
            dctImage(10, bomb)
        }
    }

    @Test
    fun rf1_narrowingNeverMissesANameMuPdfResolves() {
        val long = "A".repeat(5000) // MuPDF truncates both to 4,095 bytes
        val cases = listOf(
            "ctl-byte" to oddName("Im\u0001X"),
            "utf8" to oddName("ImÃ©"), // bytes C3 A9: "Imé" once UTF-8 decoded
            "hash00" to oddName("Bomb#00"),
            "long" to oddName(long),
            "long-truncated-use" to oddName(long, "A".repeat(4095) + "B".repeat(10)),
        )
        val missed = cases.filter { (name, bytes) -> runCatching { assertRefused(name, bytes) }.isFailure }.map { it.first }
        assertEquals("cases where the bomb was not refused", emptyList<String>(), missed)
    }

    @Test
    fun rf2_resourceLessFormIsCheckedUnderEveryParent() {
        val r1 = (0 until 33).joinToString("") { "/P$it 13 0 R" } + "/Fx 11 0 R/Fy 12 0 R"
        val r2 = (0 until 33).joinToString("") { "/Q$it 13 0 R" } + "/Fx 11 0 R/BOMB 10 0 R"
        assertRefused("rf2", page("<</XObject<<$r1>>>>", "/Fx Do /Fy Do") {
            stream(11, "/Subtype/Form/BBox[0 0 200 200]", "/BOMB Do".latin1()) // no /Resources
            form(12, "<</XObject<<$r2>>>>", "/Fx Do")
            stream(13, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
            dctImage(10, bomb)
        })
    }

    @Test
    fun rf3_jpxSizeIsReadWhereOpenjpegReadsIt() {
        fun siz(w: Int, h: Int) = java.nio.ByteBuffer.allocate(4 + 2 + 2 + 32 + 2 + 9)
            .put(byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)).putShort(47).putShort(0)
            .putInt(w).putInt(h).putInt(0).putInt(0).putInt(w).putInt(h).putInt(0).putInt(0)
            .putShort(3).put(byteArrayOf(7, 1, 1, 7, 1, 1, 7, 1, 1)).array()
        fun box(type: String, body: ByteArray) =
            java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.latin1()).put(body).array()
        val sig = box("jP  ", byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A))
        val ftyp = box("ftyp", "jp2 \u0000\u0000\u0000\u0000jp2 ".latin1())
        val jp2 = sig + ftyp + box("xml ", siz(8, 8)) + box("jp2c", siz(60000, 60000))
        assertEquals(Triple(60000L, 60000L, 3L), RenderImageGate.jpxSize(jp2))
        // jp2c with a 64-bit length (XLBox) and a last box of length 0 (to the end of the data).
        val xl = java.nio.ByteBuffer.allocate(16).putInt(1).put("jp2c".latin1()).putLong(16L + siz(5, 6).size).array() + siz(5, 6)
        assertEquals(Triple(5L, 6L, 3L), RenderImageGate.jpxSize(sig + xl))
        val zero = java.nio.ByteBuffer.allocate(8).putInt(0).put("jp2c".latin1()).array() + siz(7, 9)
        assertEquals(Triple(7L, 9L, 3L), RenderImageGate.jpxSize(sig + zero))
        assertEquals(null, RenderImageGate.jpxSize(ByteArray(3) + siz(8, 8))) // junk before a bare SOC
        assertEquals(null, RenderImageGate.jpxSize(sig + box("jp2c", ByteArray(50)))) // jp2c without SOC+SIZ
        assertRefused("jp2-fake-xml", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/Filter/JPXDecode", jp2, flate = false)
        })
    }

    @Test
    fun sharedLargeDictionaryRevisitedByResourceLessFormsStillRenders() {
        val n = 2000
        val dict = (0 until n).joinToString("") { "/I$it 11 0 R" } + (0 until 10).joinToString("") { "/F$it ${20 + it} 0 R" }
        val bytes = page("<</XObject 30 0 R>>", (0 until 10).joinToString(" ") { "/F$it Do" }) {
            add(30, "<<$dict>>")
            stream(11, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
            for (k in 0 until 10) stream(20 + k, "/Subtype/Form/BBox[0 0 200 200]", "q 20 0 0 20 0 0 cm /I${k * 7} Do Q".latin1())
        }
        assertRenders("shared-2000", file("shared.pdf", bytes))
    }

    @Test
    fun rf5_duplicateContentStreamsAreScannedOnceAndCancelIsHonoured() {
        // 16 MiB of distinct short names, ~100 KB deflated, listed 100 times in /Contents.
        val names = StringBuilder()
        var i = 0
        while (names.length < (16 shl 20) - 16) names.append("/n").append(i++).append(' ')
        val dict = (0 until 40).joinToString("") { "/I$it 11 0 R" }
        val pdf = HostilePdf()
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
        pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<</XObject<<$dict>>>>/Contents[" +
            (0 until 100).joinToString(" ") { "4 0 R" } + "]>>")
        pdf.stream(4, "", names.toString().latin1())
        pdf.stream(11, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
        val f = file("dup.pdf", pdf.bytes())
        val doc = PdfInputs.open(f)
        try {
            val t0 = System.nanoTime()
            RenderImageGate.checkPage(doc, 0)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("dup contents took $ms ms", ms < FAST_MS)
            val e = runCatching { RenderImageGate.checkPage(doc, 0) { true } }.exceptionOrNull()
            assertTrue("cancel: $e", e is PdfInputException && e.error == PdfError.Cancelled)
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun rf7_flatePredictorInFrontOfDctIsRefused() {
        val jpeg = flatJpeg(ColorSpace.DeviceRGB, 8, 8)
        assertRefused("fl-pred-dct", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 8/Height 8/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter[/FlateDecode/DCTDecode]" +
                "/DecodeParms[<</Predictor 12/Columns 8>> null]", HostilePdf.deflate(jpeg), flate = false)
        })
    }

    @Test
    fun rf6_iccProfileBombReachedThroughColorSpaceResources() {
        assertRefused("cs-resource-icc", page("<</ColorSpace<</CS0[/ICCBased 11 0 R]>>>>", "/CS0 cs 1 0 0 sc 0 0 10 10 re f") {
            stream(11, "/N 3", ByteArray(8 shl 20))
        })
    }

    // ------------------------------------------------------------------ H5-1 (resource-stack fallback)

    /** 33 benign entries (> NARROW_ABOVE) plus [extra]; image 13 benign, 10 the declared 65000² Flate image. */
    private fun bigDict(extra: String) = (0 until 33).joinToString("") { "/I$it 13 0 R" } + extra

    private fun HostilePdf.benignAndBig() {
        stream(13, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
        // Declared size only (a few bytes of data): never the DCT bombs, which must not render ungated.
        stream(10, "/Subtype/Image/Width 65000/Height 65000/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(64))
    }

    @Test
    fun h5_1_formWithOwnResourcesFallsBackToThePageDictionary() {
        // MuPDF's pdf_lookup_resource walks the whole resource stack: /Big, missing from Fm1's own
        // (empty) /Resources, is found in the page's dictionary, which never names it.
        assertRefused("h5-1", page("<</XObject<<${bigDict("/Fm1 11 0 R/Big 10 0 R")}>>>>", "/Fm1 Do") {
            form(11, "<<>>", "/Big Do"); benignAndBig()
        })
    }

    @Test
    fun h5_1_twoFormsDeep() {
        assertRefused("h5-1-own-own", page("<</XObject<<${bigDict("/Fm1 11 0 R/Big 10 0 R")}>>>>", "/Fm1 Do") {
            form(11, "<</XObject<</Fm2 12 0 R>>>>", "/Fm2 Do"); form(12, "<<>>", "/Big Do"); benignAndBig()
        })
        assertRefused("h5-1-own-resourceless", page("<</XObject<<${bigDict("/Fm1 11 0 R/Big 10 0 R")}>>>>", "/Fm1 Do") {
            form(11, "<</XObject<</Fm2 12 0 R>>>>", "/Fm2 Do")
            stream(12, "/Subtype/Form/BBox[0 0 200 200]", "/Big Do".latin1()) // no /Resources: runs on Fm1's stack
            benignAndBig()
        })
    }

    @Test
    fun h5_1_patternPaintedInsideAFormSeesTheFormsDictionary() {
        // The pattern is defined on the page but painted inside Fm1, so its content runs on the stack
        // P0 → Fm1 → page and /Big resolves in Fm1's (narrowable) dictionary.
        assertRefused("h5-1-pattern", page(
            "<</XObject<</Fm1 11 0 R>>/Pattern<</P0 12 0 R>>>>", "/Fm1 Do",
        ) {
            form(11, "<</XObject<<${bigDict("/Big 10 0 R")}>>>>", "/Pattern cs /P0 scn 0 0 200 200 re f")
            stream(12, "/Type/Pattern/PatternType 1/PaintType 1/TilingType 1/BBox[0 0 10 10]/XStep 10/YStep 10/Resources<<>>", "/Big Do".latin1())
            benignAndBig()
        })
    }

    @Test
    fun h5_1_sharedDictionaryWithNestedFormsThatHaveOwnResourcesStillRenders() {
        // 40 page XObjects (narrowed) with an unused bomb, two levels of forms with their own resources.
        val dict = (0 until 40).joinToString("") { "/I$it 13 0 R" } + "/Fm1 11 0 R/Unused 14 0 R"
        val bytes = page("<</XObject<<$dict>>>>", "q 20 0 0 20 0 0 cm /I1 Do Q /Fm1 Do") {
            form(11, "<</XObject<</Fm2 12 0 R/J0 13 0 R>>>>", "q 20 0 0 20 0 0 cm /J0 Do Q /Fm2 Do")
            form(12, "<</XObject<</K0 13 0 R>>>>", "q 20 0 0 20 0 0 cm /K0 Do /I2 Do Q")
            stream(13, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
            dctImage(14, bomb)
        }
        assertRenders("h5-1-benign", file("h5-1-benign.pdf", bytes))
    }

    // ------------------------------------------------------------------ legitimate documents

    private fun flatJpeg(cs: ColorSpace, w: Int, h: Int): ByteArray {
        val pix = Pixmap(cs, w, h, false)
        try {
            pix.clear(128)
            val buf = pix.asJPEG(85, false)
            try { return buf.asByteArray() } finally { buf.destroy() }
        } finally {
            pix.destroy()
        }
    }

    @Test
    fun ordinaryLargeImagesRender() {
        assertRenders("rgb-4000x6000", file("rgb.pdf", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            dctImage(10, flatJpeg(ColorSpace.DeviceRGB, 4000, 6000), 4000, 6000)
        }))
        assertRenders("cmyk-4000x6000", file("cmyk.pdf", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            dctImage(10, flatJpeg(ColorSpace.DeviceCMYK, 4000, 6000), 4000, 6000, cs = "/DeviceCMYK")
        }))
        // 600 dpi A4 1-bit Flate scan and an 8-bit gray one, and an ICC-based RGB image with a
        // small (bogus) profile, which MuPDF replaces by its alternate.
        assertRenders("scan-1bit", file("scan1.pdf", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 4960/Height 7016/ColorSpace/DeviceGray/BitsPerComponent 1", ByteArray(620 * 7016))
        }))
        assertRenders("scan-gray", file("scan8.pdf", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 4960/Height 7016/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4960 * 7016))
        }))
        assertRenders("icc", file("icc.pdf", page("<</XObject<</Im0 10 0 R>>>>", drawIm0) {
            stream(10, "/Subtype/Image/Width 100/Height 100/ColorSpace[/ICCBased 11 0 R]/BitsPerComponent 8", ByteArray(30000))
            stream(11, "/N 3/Alternate/DeviceRGB", ByteArray(600))
        }))
        assertRenders("indexed-cmyk-16bit", file("idx.pdf", page("<</XObject<</Im0 10 0 R/Im1 11 0 R>>>>", "$drawIm0 /Im1 Do") {
            stream(10, "/Subtype/Image/Width 300/Height 300/ColorSpace[/Indexed/DeviceCMYK 1<00000000FFFFFFFF>]/BitsPerComponent 8", ByteArray(90000))
            stream(11, "/Subtype/Image/Width 300/Height 300/ColorSpace/DeviceRGB/BitsPerComponent 16", ByteArray(540000))
        }))
        assertRenders("photo-pdf", Fixtures.photoPdf(tmp.root, "photos.pdf", pages = 1))
    }

    @Test
    fun formCycleWithoutBombStillRenders() {
        assertRenders("cycle-ok", file("cycle-ok.pdf", page("<</XObject<</Fm0 11 0 R>>>>", drawFm0) {
            form(11, "<</XObject<</Fm1 12 0 R>>>>", "/Fm1 Do")
            form(12, "<</XObject<</Fm0 11 0 R>>>>", "/Fm0 Do")
        }))
    }

    @Test
    fun sharedResourceDictionaryIsNarrowedToTheNamesThePageUses() {
        // 100 entries (> NARROW_ABOVE); the bomb is only reachable under a name this page never uses.
        val dict = (0 until 100).joinToString("") { "/I$it 11 0 R" } + "/Bomb 10 0 R"
        val build = { content: String ->
            page("<</XObject<<$dict>>>>", content) {
                stream(11, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4))
                dctImage(10, bomb)
            }
        }
        assertRenders("narrowed", file("narrowed.pdf", build("q 200 0 0 200 0 0 cm /I3 Do Q")))
        assertRefused("narrowed-uses-bomb", build("q 200 0 0 200 0 0 cm /I3 Do /Bomb Do Q"))
        assertRefused("narrowed-escaped-name", build("q 200 0 0 200 0 0 cm /B#6fmb Do Q"))
    }

    /** Overhead of the walk on a normal page (printed; asserted loosely so it is not flaky). */
    @Test
    fun walkOverheadOnAPhotoPage() {
        val pdf = Fixtures.photoPdf(tmp.root, "overhead.pdf", pages = 1, imageWidth = 4000, imageHeight = 3000)
        val doc = PdfInputs.open(pdf)
        try {
            repeat(5) { RenderImageGate.checkPage(doc, 0) }
            val n = 50
            val t0 = System.nanoTime()
            repeat(n) { RenderImageGate.checkPage(doc, 0) }
            val us = (System.nanoTime() - t0) / 1000 / n
            println("RDR-3 walk overhead, photo page ${pdf.length() / 1024} KB: $us us per check")
            assertTrue("$us us", us < 200_000)
        } finally {
            doc.destroy()
        }
    }

    private companion object {
        const val FAST_MS = 2_000L
    }
}
