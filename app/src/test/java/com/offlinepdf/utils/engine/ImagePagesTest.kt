package com.offlinepdf.utils.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.offlinepdf.utils.Fixtures
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
