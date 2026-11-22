package com.offlinepdf.utils

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FixturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun renderAll(f: File) {
        for (i in 0 until Fixtures.pageCount(f)) Fixtures.render(f, i, 100)
    }

    private fun imageDims(f: File): Triple<Int, Int, Int> {
        val img = Image(f.absolutePath)
        try {
            return Triple(img.width, img.height, img.orientation)
        } finally {
            img.destroy()
        }
    }

    @Test
    fun imagesDecodeWithRequestedSize() {
        assertEquals(Triple(64, 48, 0), imageDims(Fixtures.jpeg(tmp.root, "a.jpg", 64, 48, 1)))
        assertEquals(Triple(64, 48, 0), imageDims(Fixtures.png(tmp.root, "a.png", 64, 48, 1)))
        assertEquals(Triple(64, 48, 0), imageDims(Fixtures.pngWithAlpha(tmp.root, "b.png", 64, 48, 1)))
        // deterministic
        assertArrayEquals(
            Fixtures.jpeg(File(tmp.root, "x").apply { mkdir() }, "a.jpg", 64, 48, 1).readBytes(),
            File(tmp.root, "a.jpg").readBytes(),
        )
        val exifToMupdf = mapOf(1 to 1, 2 to 5, 3 to 3, 4 to 7, 5 to 6, 6 to 4, 7 to 8, 8 to 2)
        for (exif in 1..8) {
            val f = Fixtures.jpegWithExifOrientation(tmp.root, "e$exif.jpg", 90, 60, exif)
            assertEquals(Triple(90, 60, exifToMupdf[exif]), imageDims(f))
        }
    }

    @Test
    fun badFilesAreBad() {
        val n = Fixtures.notAnImage(tmp.root)
        assertTrue(n.name.endsWith(".jpg") && n.length() > 0)
        assertTrue(runCatching { Image(n.absolutePath).destroy() }.isFailure)
        assertTrue(Fixtures.corruptPdf(tmp.root).name.endsWith(".pdf"))
        assertEquals(0L, Fixtures.emptyPdfFile(tmp.root).length())
    }

    @Test
    fun textPdf() {
        val f = Fixtures.textPdf(tmp.root, "t.pdf", listOf("Alpha one", "Beta (two)", "Gamma"))
        val texts = Fixtures.pageTexts(f)
        assertEquals(3, texts.size)
        assertEquals("Alpha one", texts[0].trim())
        assertEquals("Beta (two)", texts[1].trim())
        assertEquals(List(3) { 595f to 842f }, Fixtures.pageSizes(f))
        val letter = Fixtures.textPdf(tmp.root, "l.pdf", listOf("x"), 612f, 792f)
        assertEquals(listOf(612f to 792f), Fixtures.pageSizes(letter))
        renderAll(f)
    }

    @Test
    fun linkPdf() {
        val f = Fixtures.linkPdf(tmp.root, "link.pdf", "https://example.com/a")
        val links = Fixtures.links(f, 0)
        assertEquals(1, links.size)
        assertEquals("https://example.com/a", links[0].first)
        assertArrayEquals(Fixtures.LINK_RECT, links[0].second, 0.5f)
        renderAll(f)
    }

    @Test
    fun encryptedPdf() {
        val f = Fixtures.encryptedPdf(tmp.root)
        val doc = Document.openDocument(f.absolutePath)
        try {
            assertTrue(doc.needsPassword())
            assertFalse(doc.authenticatePassword("wrong"))
            assertTrue(doc.authenticatePassword(Fixtures.USER_PASSWORD))
            assertEquals(2, doc.countPages())
        } finally {
            doc.destroy()
        }
        assertTrue(Fixtures.pageTexts(f)[0].contains("Secret page 1"))
        renderAll(f)
    }

    @Test
    fun photoPdf() {
        val f = Fixtures.photoPdf(tmp.root, "photo.pdf")
        assertTrue("size ${f.length()}", f.length() > 2_000_000)
        assertEquals(2, Fixtures.pageCount(f))
        val infos = Fixtures.imageInfos(f)
        assertEquals(2, infos.size)
        for (i in infos) {
            assertEquals(3200, i.width)
            assertEquals(2400, i.height)
            assertEquals("DCTDecode", i.filter)
            assertEquals("DeviceRGB", i.colorSpaceFamily)
        }
        assertEquals(listOf("Photo page 1", "Photo page 2"), Fixtures.pageTexts(f).map { it.trim() })
        renderAll(f)
    }

    @Test
    fun mixedImagesPdf() {
        val f = Fixtures.mixedImagesPdf(tmp.root)
        assertEquals(4, Fixtures.pageCount(f))
        val infos = Fixtures.imageInfos(f)
        println("mixed imageInfos: " + infos.joinToString("\n"))
        val withMask = infos.single { it.hasSMask }
        assertEquals(2400, withMask.width)
        assertTrue(infos.single { it.isMaskTarget }.let { it.width == 2400 && !it.hasSMask })
        val oneBit = infos.single { (it.bitsPerComponent == 1 || it.isImageMask) && !it.isMaskTarget }
        assertEquals(2400, oneBit.width)
        val indexed = infos.single { it.colorSpaceFamily == "Indexed" }
        assertEquals(2400, indexed.width)
        val gray = infos.single {
            it.colorSpaceFamily == "DeviceGray" && it.bitsPerComponent == 8 && !it.isMaskTarget
        }
        assertEquals("DCTDecode", gray.filter)
        assertEquals(1600, gray.height)
        assertEquals(5, infos.size)
        assertTrue(Fixtures.pageTexts(f).all { it.contains("Mixed page") })
        renderAll(f)
    }
}
