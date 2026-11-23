package app.venkat.filetools.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import app.venkat.filetools.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImagePagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun build(name: String, block: (PDFDocument) -> Unit): File {
        val f = File(tmp.root, name)
        val doc = PDFDocument()
        try {
            block(doc)
            doc.save(f.absolutePath, PdfInputs.SAVE_OPTIONS)
        } finally {
            doc.destroy()
        }
        return f
    }

    private fun single(name: String, image: File, size: PageSize, fixed: Rect? = null) =
        build(name) { ImagePages.appendImagePage(it, image, size, fixed) }

    private fun assertSize(w: Float, h: Float, actual: Pair<Float, Float>) {
        assertEquals(w, actual.first, 0.01f)
        assertEquals(h, actual.second, 0.01f)
    }

    private fun isWhite(c: Int) = Fixtures.colorNear(c, 0xFFFFFF, 8)

    @Test
    fun exifOrientationsRenderUpright() {
        for (exif in listOf(1, 3, 6, 8)) {
            val img = Fixtures.jpegWithExifOrientation(tmp.root, "o$exif.jpg", 300, 200, exif)
            val pdf = single("o$exif.pdf", img, PageSize.FIT_IMAGE)
            val size = Fixtures.pageSizes(pdf).single()
            if (exif >= 5) assertSize(200f, 300f, size) else assertSize(300f, 200f, size)
            val c = Fixtures.renderCornerColors(pdf, 0)
            assertTrue("exif $exif TL ${hex(c.topLeft)}", Fixtures.colorNear(c.topLeft, Fixtures.RED))
            assertTrue("exif $exif TR ${hex(c.topRight)}", Fixtures.colorNear(c.topRight, Fixtures.GREEN))
            assertTrue("exif $exif BL ${hex(c.bottomLeft)}", Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
            assertTrue("exif $exif BR ${hex(c.bottomRight)}", Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
        }
    }

    @Test
    fun exifOrientationOnPaperIsUprightAndLandscape() {
        // stored 300x200 with EXIF 6 displays as 200x300 portrait
        val img = Fixtures.jpegWithExifOrientation(tmp.root, "o6.jpg", 300, 200, 6)
        val pdf = single("o6a4.pdf", img, PageSize.A4)
        assertSize(595f, 842f, Fixtures.pageSizes(pdf).single())
    }

    @Test
    fun fitImageCapsLongEdgeAt14400() {
        // 20000x100 px: page must be scaled down to 14400 x 72
        val img = Fixtures.jpeg(tmp.root, "wide.jpg", 20000, 100, 3)
        val pdf = single("wide.pdf", img, PageSize.FIT_IMAGE)
        assertSize(14400f, 72f, Fixtures.pageSizes(pdf).single())
    }

    @Test
    fun paperSizesWithMargin() {
        val portrait = Fixtures.jpeg(tmp.root, "p.jpg", 300, 400, 1)
        val landscape = Fixtures.jpeg(tmp.root, "l.jpg", 400, 300, 2)
        val cases = listOf(
            Triple(PageSize.A4, portrait, 595f to 842f),
            Triple(PageSize.A4, landscape, 842f to 595f),
            Triple(PageSize.LETTER, portrait, 612f to 792f),
            Triple(PageSize.LETTER, landscape, 792f to 612f),
        )
        for ((i, case) in cases.withIndex()) {
            val (size, img, expected) = case
            val pdf = single("paper$i.pdf", img, size)
            assertSize(expected.first, expected.second, Fixtures.pageSizes(pdf).single())
            // render at 1 px = 1 pt
            val r = Fixtures.render(pdf, 0, maxOf(expected.first, expected.second).toInt())
            val m = 20 // strictly inside the 24 pt margin
            for (x in 0 until r.width) for (y in listOf(0, m, r.height - 1 - m, r.height - 1)) {
                assertTrue("case $i margin ($x,$y)", isWhite(r.at(x, y)))
            }
            for (y in 0 until r.height) for (x in listOf(0, m, r.width - 1 - m, r.width - 1)) {
                assertTrue("case $i margin ($x,$y)", isWhite(r.at(x, y)))
            }
            assertFalse("case $i centre", isWhite(r.at(r.width / 2, r.height / 2)))
            // the image touches the margin box on its limiting side: just inside 24 pt is not white
            val limitingIsWidth = (expected.first - 48) / img.let { if (it === portrait) 300f else 400f } <
                (expected.second - 48) / (if (img === portrait) 400f else 300f)
            if (limitingIsWidth) assertFalse(isWhite(r.at(27, r.height / 2))) else assertFalse(isWhite(r.at(r.width / 2, 27)))
        }
    }

    @Test
    fun fixedSizeGivesExactPageBox() {
        val img = Fixtures.jpeg(tmp.root, "p.jpg", 300, 400, 1)
        val pdf = single("fixed.pdf", img, PageSize.A4, Rect(0f, 0f, 500f, 300f))
        assertSize(500f, 300f, Fixtures.pageSizes(pdf).single())
        val r = Fixtures.render(pdf, 0, 500)
        // 300x400 fitted into 500x300 -> 225x300, centred: x from 137.5 to 362.5
        assertTrue(isWhite(r.at(100, 150)))
        assertTrue(isWhite(r.at(400, 150)))
        assertFalse(isWhite(r.at(250, 150)))
        assertFalse(isWhite(r.at(250, 2)))
    }

    @Test
    fun jpegBytesEmbeddedUnchanged() {
        val img = Fixtures.jpeg(tmp.root, "p.jpg", 300, 200, 5)
        val pdf = single("same.pdf", img, PageSize.A4)
        val doc = Document.openDocument(pdf.absolutePath) as PDFDocument
        try {
            val infos = Fixtures.imageInfos(pdf)
            assertEquals(1, infos.size)
            assertEquals("DCTDecode", infos[0].filter)
            val obj = doc.newIndirect(infos[0].objectNumber, 0)
            try {
                assertArrayEquals(img.readBytes(), obj.readRawStream())
            } finally {
                obj.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun pngWithAlphaWorks() {
        val img = Fixtures.pngWithAlpha(tmp.root, "a.png", 120, 80)
        val pdf = single("alpha.pdf", img, PageSize.FIT_IMAGE)
        assertSize(120f, 80f, Fixtures.pageSizes(pdf).single())
        assertTrue(Fixtures.imageInfos(pdf).any { it.hasSMask })
        Fixtures.render(pdf, 0)
    }

    @Test
    fun multiplePagesAppendInOrder() {
        val a = Fixtures.jpeg(tmp.root, "a.jpg", 100, 50, 1)
        val b = Fixtures.png(tmp.root, "b.png", 60, 90, 2)
        val pdf = build("two.pdf") {
            ImagePages.appendImagePage(it, a, PageSize.FIT_IMAGE)
            ImagePages.appendImagePage(it, b, PageSize.FIT_IMAGE)
        }
        assertEquals(listOf(100f to 50f, 60f to 90f), Fixtures.pageSizes(pdf))
    }

    @Test
    fun notAnImageThrows() {
        val bad = Fixtures.notAnImage(tmp.root)
        for (f in listOf(bad, File(tmp.root, "missing.jpg"))) {
            val doc = PDFDocument()
            try {
                ImagePages.appendImagePage(doc, f, PageSize.A4)
                fail("expected UnsupportedImageFileException for ${f.name}")
            } catch (e: UnsupportedImageFileException) {
                assertEquals(f, e.file)
                assertEquals(0, doc.countPages())
            } finally {
                doc.destroy()
            }
        }
    }

    private fun assertRejected(f: File) {
        val doc = PDFDocument()
        try {
            ImagePages.appendImagePage(doc, f, PageSize.FIT_IMAGE)
            fail("expected UnsupportedImageFileException for ${f.name}")
        } catch (e: UnsupportedImageFileException) {
            assertEquals(f, e.file)
            assertEquals(0, doc.countPages())
        } finally {
            doc.destroy()
        }
        try {
            ImagePages.loadImage(f).destroy()
            fail("loadImage accepted ${f.name}")
        } catch (e: UnsupportedImageFileException) {
            // expected
        }
    }

    private fun assertAccepted(f: File) {
        ImagePages.loadImage(f).destroy()
        val doc = PDFDocument()
        try {
            ImagePages.appendImagePage(doc, f, PageSize.FIT_IMAGE)
            assertEquals(1, doc.countPages())
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun truncatedJpegIsRejected() {
        val jpeg = Fixtures.jpeg(tmp.root, "big.jpg", width = 1600, height = 1200)
        assertTrue("fixture should be large", jpeg.length() > 3 * 64 * 1024)
        assertRejected(Fixtures.truncated(jpeg, 1.0 / 3))
        assertRejected(File(tmp.root, "cut90.jpg").also { it.writeBytes(jpeg.readBytes().let { b -> b.copyOf(b.size * 9 / 10) }) })
        val cut2 = File(tmp.root, "cut2.jpg").also { it.writeBytes(jpeg.readBytes().let { b -> b.copyOf(b.size - 2) }) }
        assertRejected(cut2)
        // A small JPEG (whole file inside the window) cut just before its EOI is rejected too.
        val small = Fixtures.jpeg(tmp.root, "small.jpg", width = 64, height = 48)
        assertRejected(File(tmp.root, "small-cut.jpg").also { it.writeBytes(small.readBytes().let { b -> b.copyOf(b.size - 2) }) })
    }

    @Test
    fun truncatedPngIsRejected() {
        assertRejected(Fixtures.truncated(Fixtures.png(tmp.root, "p.png", width = 800, height = 600), 0.5))
        val alpha = Fixtures.pngWithAlpha(tmp.root, "a.png", width = 200, height = 100)
        assertRejected(File(tmp.root, "a-cut.png").also { it.writeBytes(alpha.readBytes().let { b -> b.copyOf(b.size - 12) }) })
    }

    @Test
    fun jpegWithTrailingGarbageAfterEoiIsAccepted() {
        val jpeg = Fixtures.jpeg(tmp.root, "j.jpg", width = 640, height = 480)
        val padded = File(tmp.root, "padded.jpg").also { it.writeBytes(jpeg.readBytes() + ByteArray(100) { i -> (i * 7 + 1).toByte() }) }
        assertAccepted(padded)
        val pdf = single("padded.pdf", padded, PageSize.FIT_IMAGE)
        assertEquals(jpeg.length().toInt(), Fixtures.imageInfos(pdf).single().rawLength)
    }

    private fun assertSameRender(expected: File, actual: File) {
        val a = Fixtures.render(expected, 0)
        val b = Fixtures.render(actual, 0)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertArrayEquals(a.rgb, b.rgb)
    }

    @Test
    fun jpegWithLongTrailerIsAcceptedAndTrailerNotEmbedded() {
        val jpeg = Fixtures.jpeg(tmp.root, "m.jpg", width = 640, height = 480)
        val plainPdf = single("m.pdf", jpeg, PageSize.FIT_IMAGE)
        for (n in listOf(1024, 70 * 1024, 3 * 1024 * 1024)) {
            val trailed = Fixtures.withTrailer(jpeg, n, seed = n.toLong())
            assertEquals(jpeg.length() + n, trailed.length())
            assertEquals(jpeg.length(), ImagePages.jpegEnd(trailed))
            assertAccepted(trailed)
            val pdf = single("m$n.pdf", trailed, PageSize.FIT_IMAGE)
            assertEquals(1, Fixtures.pageCount(pdf))
            val info = Fixtures.imageInfos(pdf).single()
            assertEquals("DCTDecode", info.filter)
            assertEquals("trailer $n embedded", jpeg.length().toInt(), info.rawLength)
            assertSameRender(plainPdf, pdf)
        }
    }

    @Test
    fun pngWithLongTrailerIsAcceptedAndTrailerNotEmbedded() {
        val png = Fixtures.png(tmp.root, "p.png", width = 300, height = 200)
        val trailed = Fixtures.withTrailer(png, 70 * 1024)
        assertEquals(png.length(), ImagePages.pngEnd(trailed))
        assertAccepted(trailed)
        val plainPdf = single("p.pdf", png, PageSize.FIT_IMAGE)
        val pdf = single("pt.pdf", trailed, PageSize.FIT_IMAGE)
        assertEquals(Fixtures.imageInfos(plainPdf).single().rawLength, Fixtures.imageInfos(pdf).single().rawLength)
        assertEquals(plainPdf.length(), pdf.length())
        assertSameRender(plainPdf, pdf)
    }

    @Test
    fun trailerWithoutAnyEndMarkerIsAccepted() {
        // 70 KiB with no FF D9 / IEND at all: the old 64 KiB tail-window check rejected these
        val trailer = ByteArray(70 * 1024) { 0x55 }
        val jpeg = Fixtures.jpeg(tmp.root, "n.jpg", width = 320, height = 240)
        val png = Fixtures.png(tmp.root, "n.png", width = 120, height = 90)
        for (src in listOf(jpeg, png)) {
            val trailed = File(tmp.root, "plain-trailer-" + src.name).also { it.writeBytes(src.readBytes() + trailer) }
            assertAccepted(trailed)
            val plainPdf = single("np-${src.name}.pdf", src, PageSize.FIT_IMAGE)
            val pdf = single("nt-${src.name}.pdf", trailed, PageSize.FIT_IMAGE)
            assertEquals(Fixtures.imageInfos(plainPdf).single().rawLength, Fixtures.imageInfos(pdf).single().rawLength)
            assertSameRender(plainPdf, pdf)
        }
        assertEquals(jpeg.length().toInt(), Fixtures.imageInfos(File(tmp.root, "nt-n.jpg.pdf")).single().rawLength)
    }

    @Test
    fun exifOrientation6WithTrailerIsUpright() {
        val img = Fixtures.withTrailer(Fixtures.jpegWithExifOrientation(tmp.root, "o6.jpg", 300, 200, 6), 70 * 1024)
        val pdf = single("o6t.pdf", img, PageSize.FIT_IMAGE)
        assertSize(200f, 300f, Fixtures.pageSizes(pdf).single())
        val c = Fixtures.renderCornerColors(pdf, 0)
        assertTrue(Fixtures.colorNear(c.topLeft, Fixtures.RED))
        assertTrue(Fixtures.colorNear(c.topRight, Fixtures.GREEN))
        assertTrue(Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
        assertTrue(Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
    }

    @Test
    fun truncatedJpegWithExifThumbnailIsRejected() {
        val jpeg = Fixtures.jpegWithExifThumbnail(tmp.root)
        assertAccepted(jpeg)
        val bytes = jpeg.readBytes()
        val app1End = 4 + ((bytes[4].toInt() and 0xFF) shl 8 or (bytes[5].toInt() and 0xFF))
        // the APP1 really contains a thumbnail EOI (what fooled the old tail-window check)
        assertTrue((4 until app1End - 1).any { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xD9.toByte() })
        for (keep in listOf(app1End + 500, bytes.size / 2, bytes.size - 2)) {
            assertTrue(keep in (app1End + 1) until bytes.size)
            assertRejected(File(tmp.root, "thumb-cut$keep.jpg").also { it.writeBytes(bytes.copyOf(keep)) })
        }
    }

    /* ------------------------------------------------------- JPEG walker on hand-built streams */

    private fun ff(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun seg(marker: Int, payload: ByteArray) =
        ff(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private fun end(bytes: ByteArray) = ImagePages.jpegEnd(bytes.inputStream())

    @Test
    fun walkerHandlesRestartsFillStuffingAndMultipleScans() {
        val soi = ff(0xFF, 0xD8)
        val app1 = seg(0xE1, ff(0x45, 0x78, 0xFF, 0xD8, 0xFF, 0xD9, 0x00)) // thumbnail-like EOI inside APP1
        val dht = seg(0xC4, ff(0x00, 0xFF, 0xD9, 0x12)) // EOI-like bytes inside a table payload
        val sos = seg(0xDA, ff(1, 1, 0, 0, 63, 0))
        val scan1 = ff(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0xFF, 0xD1, 0x78, 0xFF, 0x00)
        val dnl = seg(0xDC, ff(0x01, 0x00))
        val scan2 = ff(0x9A, 0xFF, 0xD7, 0xBC, 0xFF, 0x00)
        val fillThenEoi = ff(0xFF, 0xFF, 0xFF, 0xD9)
        val image = soi + ff(0xFF, 0xFF) + app1 + dht + sos + scan1 + dnl + dht + sos + scan2 + fillThenEoi
        assertEquals(image.size.toLong(), end(image))
        assertEquals(image.size.toLong(), end(image + ff(0xFF, 0xD9, 0x00, 0xFF, 0xD9)))
        // every strict prefix is truncated
        for (k in 2 until image.size) assertEquals("prefix $k", null, end(image.copyOf(k)))
        // invalid segment length; missing SOI
        assertEquals(null, end(soi + ff(0xFF, 0xE0, 0x00, 0x01) + ff(0xFF, 0xD9)))
        assertEquals(null, end(ff(0xFF, 0xD9)))
    }

    @Test
    fun walkerIsFastOnLargeFiles() {
        // 20 MB of entropy data with every FF stuffed (worst realistic case for the byte scan)
        val size = 20 * 1024 * 1024
        val data = ByteArray(size)
        java.util.Random(3).nextBytes(data)
        for (i in 0 until size - 1) if (data[i] == 0xFF.toByte()) data[i + 1] = 0
        data[size - 1] = 0x11
        val head = ff(0xFF, 0xD8) + seg(0xDA, ff(1, 1, 0, 0, 63, 0))
        val f = File(tmp.root, "big-synthetic.jpg").also { it.writeBytes(head + data + ff(0xFF, 0xD9)) }
        val trailed = Fixtures.withTrailer(f, 1024)
        val t0 = System.nanoTime()
        val e = ImagePages.jpegEnd(trailed)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("jpegEnd on ${trailed.length()} bytes: $ms ms")
        assertEquals(f.length(), e)
        assertTrue("took $ms ms", ms < 5000) // typically ~60 ms; loose bound for a shared build machine
    }

    @Test
    fun allImageFixturesStillLoad() {
        val files = listOf(
            Fixtures.jpeg(tmp.root),
            Fixtures.png(tmp.root),
            Fixtures.pngWithAlpha(tmp.root),
            Fixtures.grayJpeg(tmp.root),
        ) + (1..8).map { Fixtures.jpegWithExifOrientation(tmp.root, "exif$it.jpg", 120, 80, it) }
        for (f in files) assertAccepted(f)
    }

    @Test
    fun pageSizeOfHonoursRotate() {
        val src = Fixtures.textPdf(tmp.root, "t.pdf", listOf("a", "b"))
        val rotated = File(tmp.root, "rot.pdf")
        var doc = Document.openDocument(src.absolutePath) as PDFDocument
        try {
            val pageObj = doc.findPage(1)
            pageObj.put("Rotate", 90)
            pageObj.destroy()
            doc.save(rotated.absolutePath, PdfInputs.SAVE_OPTIONS)
        } finally {
            doc.destroy()
        }
        doc = PdfInputs.open(rotated)
        try {
            val r0 = ImagePages.pageSizeOf(doc, 0)
            val r1 = ImagePages.pageSizeOf(doc, 1)
            assertArrayEquals(floatArrayOf(0f, 0f, 595f, 842f), floatArrayOf(r0.x0, r0.y0, r0.x1, r0.y1), 0.01f)
            assertArrayEquals(floatArrayOf(0f, 0f, 842f, 595f), floatArrayOf(r1.x0, r1.y0, r1.x1, r1.y1), 0.01f)
            // appending with the rotated page's displayed size gives that exact page box
            ImagePages.appendImagePage(doc, Fixtures.jpeg(tmp.root, "x.jpg", 50, 50, 1), PageSize.A4, r1)
            val out = File(tmp.root, "appended.pdf")
            doc.save(out.absolutePath, PdfInputs.SAVE_OPTIONS)
            assertEquals(842f to 595f, Fixtures.pageSizes(out)[2])
        } finally {
            doc.destroy()
        }
    }

    private fun hex(c: Int) = "%06X".format(c)
}
