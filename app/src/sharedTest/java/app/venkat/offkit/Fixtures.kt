package app.venkat.offkit

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.Deflater

/**
 * Deterministic test inputs generated in code (no binary fixture files), plus read-back helpers.
 * Compiled into both JVM unit tests and androidTest: imports only java.*, kotlin.* and
 * com.artifex.mupdf.fitz.*. Every builder takes a target directory and returns the created file.
 * Not thread-safe (MuPDF objects are used on the calling thread only).
 */
object Fixtures {
    const val USER_PASSWORD = "userpw"
    const val OWNER_PASSWORD = "ownerpw"

    const val A4_WIDTH = 595f
    const val A4_HEIGHT = 842f

    /** 0xRRGGBB colours painted in the four quadrants of the *displayed* (upright) image. */
    const val RED = 0xFF0000
    const val GREEN = 0x00FF00
    const val BLUE = 0x0000FF
    const val YELLOW = 0xFFFF00

    /** Rectangle of the link made by [linkPdf], in MuPDF page coordinates (origin top-left): x0, y0, x1, y1. */
    val LINK_RECT: FloatArray get() = floatArrayOf(72f, 100f, 320f, 140f)

    private const val SAVE_OPTIONS = "garbage=deduplicate,compress,objstms"

    /* ------------------------------------------------------------------ images */

    /** Photo-like noisy RGB JPEG (quality 90), [width]x[height] px. */
    fun jpeg(dir: File, name: String = "photo.jpg", width: Int = 400, height: Int = 300, seed: Long = 1L): File =
        File(dir, name).also { it.writeBytes(encodeJpeg(ppm(width, height, photoPixels(width, height, seed)), 90)) }

    /** Photo-like noisy RGB PNG (no alpha). */
    fun png(dir: File, name: String = "photo.png", width: Int = 400, height: Int = 300, seed: Long = 1L): File =
        File(dir, name).also { it.writeBytes(encodePng(ppm(width, height, photoPixels(width, height, seed)))) }

    /** RGB PNG with an alpha channel (alpha ramps 0..255 left to right; colours noisy). */
    fun pngWithAlpha(dir: File, name: String = "alpha.png", width: Int = 400, height: Int = 300, seed: Long = 1L): File {
        val px = photoPixels(width, height, seed)
        val raw = pam(width, height, 4, "RGB_ALPHA") { x, y ->
            val c = px(x, y)
            intArrayOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff, if (width > 1) 255 * x / (width - 1) else 255)
        }
        return File(dir, name).also { it.writeBytes(encodePng(raw)) }
    }

    /**
     * JPEG of [width]x[height] STORED pixels carrying EXIF Orientation [exifOrientation] (1..8).
     * The pixels are laid out so that, once the orientation is applied, the displayed image is upright
     * with quadrants TL = [RED], TR = [GREEN], BL = [BLUE], BR = [YELLOW]. The displayed size is
     * [width]x[height] for orientations 1..4 and [height]x[width] for 5..8 (use a non-square size).
     */
    fun jpegWithExifOrientation(dir: File, name: String, width: Int, height: Int, exifOrientation: Int): File {
        require(exifOrientation in 1..8)
        val transposed = exifOrientation >= 5
        val dw = if (transposed) height else width // displayed W
        val dh = if (transposed) width else height // displayed H
        val raw = ppm(width, height) { x, y ->
            // Displayed position (X, Y) of stored pixel (x, y) under each EXIF orientation.
            val (dx, dy) = when (exifOrientation) {
                1 -> x to y
                2 -> (dw - 1 - x) to y
                3 -> (dw - 1 - x) to (dh - 1 - y)
                4 -> x to (dh - 1 - y)
                5 -> y to x
                6 -> (dw - 1 - y) to x
                7 -> (dw - 1 - y) to (dh - 1 - x)
                else -> y to (dh - 1 - x)
            }
            val left = dx < dw / 2
            val top = dy < dh / 2
            when {
                top && left -> RED
                top -> GREEN
                left -> BLUE
                else -> YELLOW
            }
        }
        val jpeg = withExifOrientation(encodeJpeg(raw, 95), exifOrientation)
        return File(dir, name).also { it.writeBytes(jpeg) }
    }

    /** Noisy 8-bit gray JPEG (quality 90, one component), [width]x[height] px. */
    fun grayJpeg(dir: File, name: String = "gray.jpg", width: Int = 400, height: Int = 300, seed: Long = 1L): File {
        val px = photoPixels(width, height, seed)
        return File(dir, name).also { it.writeBytes(encodeJpeg(pgm(width, height) { x, y -> px(x, y) shr 8 and 0xff }, 90)) }
    }

    /** Copy of [file] next to it (name prefixed "truncated-") keeping the first [keepFraction] of its bytes. */
    fun truncated(file: File, keepFraction: Double): File {
        require(keepFraction in 0.0..1.0)
        val bytes = file.readBytes()
        return File(file.parentFile, "truncated-" + file.name).also {
            it.writeBytes(bytes.copyOf((bytes.size * keepFraction).toInt()))
        }
    }

    /**
     * Copy of [file] next to it (name prefixed "trailer<n>-") with [trailerBytes] random bytes appended,
     * like the MP4 of a "motion photo". The trailer deliberately also contains JPEG SOI/EOI markers
     * and PNG `IEND` chunk headers, which must not confuse the end-of-image detection.
     */
    fun withTrailer(file: File, trailerBytes: Int, seed: Long = 7L): File {
        val trailer = randomBytes(trailerBytes, seed)
        val decoys = listOf(
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()),
            byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()),
        )
        val rnd = Random(seed + 1)
        if (trailerBytes >= 16) {
            repeat(1 + trailerBytes / 512) { k ->
                val d = decoys[k % decoys.size]
                d.copyInto(trailer, rnd.nextInt(trailerBytes - d.size + 1))
            }
            decoys[0].copyInto(trailer, trailerBytes - 2) // ends like a JPEG
        }
        return File(file.parentFile, "trailer$trailerBytes-" + file.name).also { it.writeBytes(file.readBytes() + trailer) }
    }

    /**
     * Noisy RGB JPEG whose first segment is an EXIF APP1 that also carries a complete 32x24 JPEG
     * thumbnail (with its own `FF D8` ... `FF D9`), as cameras write it.
     */
    fun jpegWithExifThumbnail(dir: File, name: String = "thumb.jpg", width: Int = 640, height: Int = 480, seed: Long = 1L): File {
        val main = encodeJpeg(ppm(width, height, photoPixels(width, height, seed)), 90)
        val thumb = encodeJpeg(ppm(32, 24, photoPixels(32, 24, seed + 1)), 80)
        val withExif = withExifOrientation(main, 1) // SOI, APP1 (length at bytes 4..5), rest
        val app1Len = (withExif[4].toInt() and 0xFF shl 8) or (withExif[5].toInt() and 0xFF)
        val len = app1Len + thumb.size
        require(len <= 0xFFFF)
        val head = withExif.copyOfRange(0, 4 + app1Len).also { it[4] = (len shr 8).toByte(); it[5] = len.toByte() }
        return File(dir, name).also { it.writeBytes(head + thumb + withExif.copyOfRange(4 + app1Len, withExif.size)) }
    }

    /** ASCII planted in the GPS IFD of [jpegWithMetadata] (GPSProcessingMethod). */
    const val PLANTED_GPS = "PLANTED-GPS-51.4778N-0.0014W"

    /** ASCII planted in every other metadata segment / chunk of [jpegWithMetadata] and [pngWithMetadata]. */
    const val PLANTED_TEXT = "PLANTED-CAMERA-DETAILS-QX7"

    /**
     * Like [jpegWithExifOrientation] (quadrant colours, EXIF [exifOrientation]), but as a phone writes
     * it, with metadata before the JFIF segment: a full EXIF APP1 (IFD0 with Make and Orientation, a
     * GPS IFD with latitude and [PLANTED_GPS]), an XMP APP1, an MPF APP2, an APP13 Photoshop/IPTC
     * segment, an APP0 JFXX, an APP15 and a COM segment, all containing [PLANTED_TEXT]. The XMP and
     * APP13 are padded to [paddingBytes] each so the metadata is large relative to the image.
     */
    fun jpegWithMetadata(
        dir: File,
        name: String,
        width: Int = 300,
        height: Int = 200,
        exifOrientation: Int = 6,
        paddingBytes: Int = 20_000,
    ): File {
        val base = jpegWithExifOrientation(dir, "base-$name", width, height, exifOrientation).readBytes()
        val minimalLen = (base[4].toInt() and 0xFF shl 8) or (base[5].toInt() and 0xFF) // minimal APP1 at 2
        val rest = base.copyOfRange(4 + minimalLen, base.size)
        val text = PLANTED_TEXT.toByteArray(Charsets.ISO_8859_1)
        val pad = ByteArray(paddingBytes) { ' '.code.toByte() }
        val segs = listOf(
            jpegSegment(0xE1, fullExif(exifOrientation)),
            jpegSegment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><tiff:Model>") + text + ascii("</tiff:Model>") + pad + ascii("</x:xmpmeta>")),
            jpegSegment(0xE2, ascii("MPF\u0000MM\u0000*") + text),
            jpegSegment(0xED, ascii("Photoshop 3.0\u00008BIM\u0004\u0004") + text + pad),
            jpegSegment(0xE0, ascii("JFXX\u0000\u0013") + text),
            jpegSegment(0xEF, text),
            jpegSegment(0xFE, text),
        )
        val out = ByteArrayOutputStream()
        out.write(base, 0, 2)
        segs.forEach { out.write(it) }
        out.write(rest)
        File(dir, "base-$name").delete()
        return File(dir, name).also { it.writeBytes(out.toByteArray()) }
    }

    /**
     * [jpeg] with an Adobe APP14 (version 100, transform 1 = YCbCr, as Photoshop writes it) and an
     * APP2 `ICC_PROFILE` segment (a synthetic, invalid profile that MuPDF ignores with a warning)
     * inserted after SOI, followed by a COM segment with [PLANTED_TEXT]. Only the COM is metadata.
     */
    fun jpegWithAdobeAndIcc(dir: File, name: String, width: Int = 320, height: Int = 240, seed: Long = 1L): File {
        val base = jpeg(dir, "base-$name", width, height, seed).readBytes()
        File(dir, "base-$name").delete()
        val adobe = jpegSegment(0xEE, ascii("Adobe") + byteArrayOf(0, 100, 0, 0, 0, 0, 1))
        val icc = jpegSegment(0xE2, ascii("ICC_PROFILE\u0000") + byteArrayOf(1, 1) + ByteArray(128) { (it * 7).toByte() })
        val com = jpegSegment(0xFE, PLANTED_TEXT.toByteArray(Charsets.ISO_8859_1))
        return File(dir, name).also { it.writeBytes(base.copyOfRange(0, 2) + adobe + icc + com + base.copyOfRange(2, base.size)) }
    }

    /**
     * [png] with `tEXt`, `iTXt` and `eXIf` chunks (valid CRCs) inserted after `IHDR`, each containing
     * [PLANTED_TEXT] (the eXIf one in a minimal big-endian TIFF with an ImageDescription).
     */
    fun pngWithMetadata(dir: File, name: String, width: Int = 300, height: Int = 200, seed: Long = 1L): File {
        val base = png(dir, "base-$name", width, height, seed).readBytes()
        File(dir, "base-$name").delete()
        val text = PLANTED_TEXT.toByteArray(Charsets.ISO_8859_1)
        val desc = text + byteArrayOf(0)
        val tiff = java.nio.ByteBuffer.allocate(8 + 2 + 12 + 4 + desc.size)
            .put(ascii("MM")).putShort(42).putInt(8)
            .putShort(1).putShort(0x010E).putShort(2).putInt(desc.size).putInt(26) // ImageDescription
            .putInt(0).put(desc).array()
        val chunks = pngChunk("tEXt", ascii("Comment\u0000") + text) +
            pngChunk("iTXt", ascii("Description\u0000\u0000\u0000\u0000\u0000") + text) +
            pngChunk("eXIf", tiff)
        val ihdrEnd = 8 + 25
        return File(dir, name).also { it.writeBytes(base.copyOfRange(0, ihdrEnd) + chunks + base.copyOfRange(ihdrEnd, base.size)) }
    }

    /** One JPEG marker segment: `FF` [marker], 2-byte length, [payload]. */
    fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        require(len <= 0xFFFF)
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /**
     * Segments of a JPEG before its first start-of-scan, as (marker, offset of `FF`, offset after
     * the payload), plus the offset of the SOS marker itself (-1 if none). An independent, simple
     * walker for tests: assumes no fill bytes or garbage between segments.
     */
    fun jpegHeaderSegments(jpeg: ByteArray): Pair<List<Triple<Int, Int, Int>>, Int> {
        val segs = ArrayList<Triple<Int, Int, Int>>()
        var p = 2
        while (p + 4 <= jpeg.size) {
            require(jpeg[p] == 0xFF.toByte()) { "no marker at $p" }
            val m = jpeg[p + 1].toInt() and 0xFF
            if (m == 0xDA) return segs to p
            val len = (jpeg[p + 2].toInt() and 0xFF shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            segs.add(Triple(m, p, p + 2 + len))
            p += 2 + len
        }
        return segs to -1
    }

    private fun fullExif(orientation: Int): ByteArray {
        val make = ascii("FixtureCam ") + PLANTED_TEXT.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val method = ascii("ASCII\u0000\u0000\u0000") + PLANTED_GPS.toByteArray(Charsets.ISO_8859_1)
        val ifd0 = 8
        val gps = ifd0 + 2 + 3 * 12 + 4
        val data = gps + 2 + 3 * 12 + 4
        val makeAt = data
        val latAt = makeAt + make.size
        val methodAt = latAt + 24
        val b = java.nio.ByteBuffer.allocate(methodAt + method.size)
        b.put(ascii("MM")).putShort(42).putInt(ifd0)
        b.putShort(3)
        b.putShort(0x010F).putShort(2).putInt(make.size).putInt(makeAt) // Make
        b.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0) // Orientation
        b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(gps) // GPS IFD pointer
        b.putInt(0)
        b.putShort(3)
        b.putShort(0x0001).putShort(2).putInt(2).put(ascii("N\u0000\u0000\u0000")) // GPSLatitudeRef
        b.putShort(0x0002).putShort(5).putInt(3).putInt(latAt) // GPSLatitude
        b.putShort(0x001B).putShort(7).putInt(method.size).putInt(methodAt) // GPSProcessingMethod
        b.putInt(0)
        b.put(make)
        b.putInt(51).putInt(1).putInt(28).putInt(1).putInt(4012).putInt(100)
        b.put(method)
        return ascii("Exif\u0000\u0000") + b.array()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typed = ascii(type) + data
        val crc = java.util.zip.CRC32().also { it.update(typed) }.value
        return java.nio.ByteBuffer.allocate(4 + typed.size + 4).putInt(data.size).put(typed).putInt(crc.toInt()).array()
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** 4 KiB of random bytes named like a JPEG. */
    fun notAnImage(dir: File, name: String = "not-an-image.jpg"): File =
        File(dir, name).also { it.writeBytes(randomBytes(4096, 11L)) }

    /* ------------------------------------------------------------------ PDFs */

    /** 4 KiB of random bytes named like a PDF. */
    fun corruptPdf(dir: File, name: String = "corrupt.pdf"): File =
        File(dir, name).also { it.writeBytes(randomBytes(4096, 12L)) }

    /** A zero-byte file named like a PDF. */
    fun emptyPdfFile(dir: File, name: String = "empty.pdf"): File =
        File(dir, name).also { it.writeBytes(ByteArray(0)) }

    /** One page per entry of [pageTexts] (Helvetica 24 pt, extractable), page size [pageWidth]x[pageHeight] pt. */
    fun textPdf(
        dir: File,
        name: String,
        pageTexts: List<String>,
        pageWidth: Float = A4_WIDTH,
        pageHeight: Float = A4_HEIGHT,
    ): File {
        val f = File(dir, name)
        buildPdf { doc -> for (t in pageTexts) addPage(doc, pageWidth, pageHeight, null, t) }.save(f)
        return f
    }

    /** One page of [quadrantPdf]: [width]x[height] pt, MediaBox origin ([originX], [originY]), `/Rotate` [rotate]. */
    data class QuadrantPage(
        val width: Float,
        val height: Float,
        val rotate: Int = 0,
        val originX: Float = 0f,
        val originY: Float = 0f,
    )

    /**
     * One page per entry of [pages], each with four solid vector rectangles (`rg … re f`) in the
     * quadrants of the UNROTATED page: top-left [RED], top-right [GREEN], bottom-left [BLUE],
     * bottom-right [YELLOW] ("top" = larger PDF y). Each rectangle is inset by `m = min(w, h) / 10`
     * from the page edges and from the centre lines, so a band of width m along the edges and a
     * cross of width 2m through the centre stay white.
     */
    fun quadrantPdf(dir: File, name: String, pages: List<QuadrantPage>): File {
        val f = File(dir, name)
        buildPdf { doc ->
            for (p in pages) {
                val (w, h, rot, ox, oy) = p
                val m = minOf(w, h) / 10f
                val hw = w / 2f
                val hh = h / 2f
                fun rect(rgb: String, x: Float, y: Float) =
                    "$rgb rg ${num(ox + x)} ${num(oy + y)} ${num(hw - 2 * m)} ${num(hh - 2 * m)} re f\n"
                val content = rect("1 0 0", m, hh + m) + rect("0 1 0", hw + m, hh + m) +
                    rect("0 0 1", m, m) + rect("1 1 0", hw + m, m)
                val res = doc.newDictionary()
                try {
                    val page = doc.addPage(Rect(ox, oy, ox + w, oy + h), rot, res, content)
                    try { doc.insertPage(-1, page) } finally { page.destroy() }
                } finally {
                    res.destroy()
                }
            }
        }.save(f)
        return f
    }

    /**
     * One [size]x[size] pt page whose content is [fills] full-page solid fills (alternating red and
     * green, ending on green): cheap to store (it deflates well), slow to draw at a large scale.
     */
    fun manyFillsPdf(dir: File, name: String, fills: Int, size: Float = 500f): File {
        val f = File(dir, name)
        buildPdf { doc ->
            val sb = StringBuilder(fills * 24)
            for (i in 0 until fills) {
                sb.append(if ((fills - i) % 2 == 1) "0 1 0 rg " else "1 0 0 rg ")
                sb.append("0 0 ${num(size)} ${num(size)} re f\n")
            }
            val res = doc.newDictionary()
            try {
                val page = doc.addPage(Rect(0f, 0f, size, size), 0, res, sb.toString())
                try { doc.insertPage(-1, page) } finally { page.destroy() }
            } finally {
                res.destroy()
            }
        }.save(f)
        return f
    }

    /**
     * One empty white 200x200 pt page carrying a Square annotation over MuPDF page rect
     * (50, 50)-(150, 150) (origin top-left) with blue border and blue interior, appearance generated.
     */
    fun squareAnnotationPdf(dir: File, name: String): File {
        val f = File(dir, name)
        val built = buildPdf { doc ->
            val res = doc.newDictionary()
            try {
                val pageObj = doc.addPage(Rect(0f, 0f, 200f, 200f), 0, res, "")
                try { doc.insertPage(-1, pageObj) } finally { pageObj.destroy() }
            } finally {
                res.destroy()
            }
            val page = doc.loadPage(0) as com.artifex.mupdf.fitz.PDFPage
            try {
                val annot = page.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_SQUARE)
                annot.rect = Rect(50f, 50f, 150f, 150f)
                annot.color = floatArrayOf(0f, 0f, 1f)
                annot.interiorColor = floatArrayOf(0f, 0f, 1f)
                annot.update()
                page.update()
            } finally {
                page.destroy()
            }
        }
        built.save(f)
        return f
    }

    /**
     * One page with a text line and one image XObject whose dictionary says [dictWidth]x[dictHeight],
     * 8 bpc DeviceRGB, `/Filter /[filter]`, and whose stream is exactly [data] (not checked).
     */
    fun rawImagePdf(dir: File, name: String, dictWidth: Int, dictHeight: Int, filter: String, data: ByteArray): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addRawPage(doc, imageDict(dictWidth, dictHeight, 8, "/DeviceRGB", "/$filter"), data, 400, 300, "Raw image")
        }.save(f)
        return f
    }

    /**
     * Two [pageWidth]x[pageHeight] pt pages for the reader: page 1 draws [jpeg] (stored as is,
     * `/DCTDecode`, DeviceRGB, dictionary size [dictWidth]x[dictHeight]) over the full page and has no
     * text; page 2 is plain: one vector [BLUE] rectangle covering the middle half of the page in both
     * directions (x from w/4 to 3w/4, y from h/4 to 3h/4), white elsewhere.
     */
    fun jpegThenPlainPdf(
        dir: File,
        name: String,
        jpeg: ByteArray,
        dictWidth: Int,
        dictHeight: Int,
        pageWidth: Float = 400f,
        pageHeight: Float = 300f,
    ): File {
        val f = File(dir, name)
        buildPdf { doc ->
            val ref = addRaw(doc, imageDict(dictWidth, dictHeight, 8, "/DeviceRGB", "/DCTDecode"), jpeg)
            try { addPage(doc, pageWidth, pageHeight, ref, null) } finally { ref.destroy() }
            val res = doc.newDictionary()
            try {
                val content = "0 0 1 rg ${num(pageWidth / 4)} ${num(pageHeight / 4)} ${num(pageWidth / 2)} ${num(pageHeight / 2)} re f\n"
                val page = doc.addPage(Rect(0f, 0f, pageWidth, pageHeight), 0, res, content)
                try { doc.insertPage(-1, page) } finally { page.destroy() }
            } finally {
                res.destroy()
            }
        }.save(f)
        return f
    }

    /** [jpeg] with the dimensions of its first SOF [marker] segment replaced (the walk stops at SOS). */
    fun patchSof(jpeg: ByteArray, marker: Int, width: Int, height: Int): ByteArray {
        val out = jpeg.copyOf()
        var i = 2
        while (i + 4 < out.size) {
            val m = out[i + 1].toInt() and 0xFF
            val len = ((out[i + 2].toInt() and 0xFF) shl 8) or (out[i + 3].toInt() and 0xFF)
            if (m == marker) {
                out[i + 5] = (height shr 8).toByte(); out[i + 6] = height.toByte()
                out[i + 7] = (width shr 8).toByte(); out[i + 8] = width.toByte()
                return out
            }
            require(m != 0xDA) { "no SOF ${marker.toString(16)} before SOS" }
            i += 2 + len
        }
        error("no SOF found")
    }

    /**
     * A tiny PNG whose IHDR declares [width]x[height] (unsigned 32-bit), [bitDepth], [colorType] and
     * [interlace], with a few bytes of zlib data (decompression bomb shape of SA-3). Never decode it.
     */
    fun pngDeclaring(width: Long, height: Long, bitDepth: Int = 8, colorType: Int = 2, interlace: Int = 0): ByteArray {
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width.toInt()).putInt(height.toInt())
            .put(bitDepth.toByte()).put(colorType.toByte()).put(0).put(0).put(interlace.toByte()).array()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        out.write(pngChunk("IHDR", ihdr))
        out.write(pngChunk("IDAT", deflate(ByteArray(64))))
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** One A4 text page with an external URI link [uri] at [LINK_RECT]. */
    fun linkPdf(dir: File, name: String, uri: String): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Link page")
            val page = doc.loadPage(0)
            try {
                val r = LINK_RECT
                page.createLink(Rect(r[0], r[1], r[2], r[3]), uri).destroy()
            } finally {
                page.destroy()
            }
        }.save(f)
        return f
    }

    /** Two text pages ("Secret page 1", "Secret page 2"), AES-256 encrypted; opening requires [USER_PASSWORD]. */
    fun encryptedPdf(dir: File, name: String = "encrypted.pdf"): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Secret page 1")
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Secret page 2")
        }.save(f, "garbage=deduplicate,compress,encrypt=aes-256,user-password=$USER_PASSWORD,owner-password=$OWNER_PASSWORD")
        return f
    }

    /**
     * Two text pages ("Restricted page 1", "Restricted page 2"), AES-256 encrypted with only an owner password
     * ([OWNER_PASSWORD]) and an empty user password: it opens without a password (`needsPassword()` is false) but
     * carries an /Encrypt dictionary with permission restrictions.
     */
    fun ownerPasswordPdf(dir: File, name: String = "owner-only.pdf"): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Restricted page 1")
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Restricted page 2")
        }.save(f, "garbage=deduplicate,compress,encrypt=aes-256,owner-password=$OWNER_PASSWORD,permissions=-44")
        return f
    }

    /**
     * [pages] pages, each a full-page noisy RGB JPEG (quality 92, [imageWidth]x[imageHeight] px, different seed per
     * page, DCTDecode / DeviceRGB) drawn at 1 px = 1 pt, plus the extractable text "Photo page N" (N from 1).
     * With the defaults the file is several MB.
     */
    fun photoPdf(dir: File, name: String, pages: Int = 2, imageWidth: Int = 3200, imageHeight: Int = 2400): File {
        val f = File(dir, name)
        buildPdf { doc ->
            for (i in 0 until pages) {
                val jpeg = encodeJpeg(ppm(imageWidth, imageHeight, photoPixels(imageWidth, imageHeight, 100L + i)), 92)
                val ref = addRaw(doc, imageDict(imageWidth, imageHeight, 8, "/DeviceRGB", "/DCTDecode"), jpeg)
                try {
                    addPage(doc, imageWidth.toFloat(), imageHeight.toFloat(), ref, "Photo page ${i + 1}")
                } finally {
                    ref.destroy()
                }
            }
        }.save(f)
        return f
    }

    /**
     * Four pages, one image each (drawn full page at 1 px = 1 pt) plus the text "Mixed page N":
     *  - page 1: RGB PNG with alpha via addImage, 2400x1600 -> FlateDecode, ICCBased RGB, with /SMask
     *    (the SMask is a separate 8-bit DeviceGray image, reported with isMaskTarget = true);
     *  - page 2: 1-bit DeviceGray image, 2400x1600, BitsPerComponent 1, FlateDecode (hand-built);
     *  - page 3: Indexed image ([/Indexed /DeviceRGB 3 <lookup>]), 2400x1600, 8 bpc, FlateDecode (hand-built);
     *  - page 4: gray JPEG, 2400x1600, DCTDecode, /DeviceGray, 8 bpc (hand-built).
     */
    fun mixedImagesPdf(dir: File, name: String = "mixed.pdf"): File {
        val f = File(dir, name)
        val w = 2400
        val h = 1600
        buildPdf { doc ->
            // (a) RGB + soft mask
            val alphaPng = encodePng(pam(w, h, 4, "RGB_ALPHA") { x, y ->
                intArrayOf(40 + 180 * x / w, 200 - 150 * y / h, 120, 255 * x / w)
            })
            val img = Image(alphaPng)
            try {
                val ref = doc.addImage(img)
                try { addPage(doc, w.toFloat(), h.toFloat(), ref, "Mixed page 1") } finally { ref.destroy() }
            } finally {
                img.destroy()
            }
            // (b) 1-bit gray: checkerboard of 40 px cells
            val rowBytes = (w + 7) / 8
            val bits = ByteArray(rowBytes * h)
            for (y in 0 until h) for (x in 0 until w) {
                if (((x / 40) + (y / 40)) % 2 == 0) {
                    val i = y * rowBytes + x / 8
                    bits[i] = (bits[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            addRawPage(doc, imageDict(w, h, 1, "/DeviceGray", "/FlateDecode"), deflate(bits), w, h, "Mixed page 2")
            // (c) Indexed, 4 palette entries in horizontal bands
            val lookup = byteArrayOf(255.toByte(), 0, 0, 0, 160.toByte(), 0, 0, 0, 255.toByte(), 240.toByte(), 240.toByte(), 240.toByte())
            val idx = ByteArray(w * h) { i -> ((i / w) * 4 / h + (i % w) / 600).mod(4).toByte() }
            val indexedCs = listOf("/Indexed", "/DeviceRGB", 3, lookup)
            addRawPage(doc, imageDict(w, h, 8, indexedCs, "/FlateDecode"), deflate(idx), w, h, "Mixed page 3")
            // (d) gray JPEG
            val rnd = Random(21L)
            val grayJpeg = encodeJpeg(pgm(w, h) { x, y -> clamp((x + y) * 255 / (w + h) + rnd.nextInt(41) - 20) }, 92)
            addRawPage(doc, imageDict(w, h, 8, "/DeviceGray", "/DCTDecode"), grayJpeg, w, h, "Mixed page 4")
        }.save(f)
        return f
    }

    /* ------------------------------------------------------------------ read-back */

    data class ImageInfo(
        val objectNumber: Int,
        val width: Int,
        val height: Int,
        /** 0 when absent (e.g. some ImageMasks). */
        val bitsPerComponent: Int,
        /** Filter name, or array elements joined by a space; null when absent. */
        val filter: String?,
        /** Colour space name, or the first element of a colour space array (e.g. "Indexed", "ICCBased"); null when absent. */
        val colorSpaceFamily: String?,
        val hasSMask: Boolean,
        val isImageMask: Boolean,
        /** True when another image references this one as its /SMask or /Mask. */
        val isMaskTarget: Boolean,
        /** Length of the encoded (raw) stream data. */
        val rawLength: Int,
        /** Colour components of the decoded image as MuPDF loads it (1 = gray, 3 = RGB, 4 = CMYK). */
        val components: Int = 0,
    )

    /** RGB of a rendered page, row-major, 0xRRGGBB per pixel. */
    class RenderedPage(val width: Int, val height: Int, val rgb: IntArray) {
        fun at(x: Int, y: Int): Int = rgb[y * width + x]
    }

    /** Samples near each corner (inset 5% of each dimension) of a rendered page, 0xRRGGBB. */
    data class CornerColors(val topLeft: Int, val topRight: Int, val bottomLeft: Int, val bottomRight: Int)

    fun pageCount(file: File): Int = withDoc(file) { it.countPages() }

    fun pageTexts(file: File): List<String> = withDoc(file) { doc ->
        (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                val st = page.toStructuredText()
                try { st.asText() } finally { st.destroy() }
            } finally {
                page.destroy()
            }
        }
    }

    /** Displayed page sizes in points (width, height), honouring /Rotate and the CropBox. */
    fun pageSizes(file: File): List<Pair<Float, Float>> = withDoc(file) { doc ->
        (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                val b = page.bounds
                (b.x1 - b.x0) to (b.y1 - b.y0)
            } finally {
                page.destroy()
            }
        }
    }

    /** Links of page [pageIndex]: (uri, [x0, y0, x1, y1] in MuPDF page coordinates). */
    fun links(file: File, pageIndex: Int): List<Pair<String, FloatArray>> = withDoc(file) { doc ->
        val page = doc.loadPage(pageIndex)
        try {
            val links = page.links ?: emptyArray()
            links.map { l ->
                val b = l.bounds
                val r = l.uri to floatArrayOf(b.x0, b.y0, b.x1, b.y1)
                l.destroy()
                r
            }
        } finally {
            page.destroy()
        }
    }

    /** Every image XObject stream in the file, in object-number order. */
    fun imageInfos(file: File): List<ImageInfo> = withDoc(file) { d ->
        val doc = d as PDFDocument
        val maskTargets = HashSet<Int>()
        val images = ArrayList<Int>()
        for (num in 1 until doc.countObjects()) {
            val obj = doc.newIndirect(num, 0)
            try {
                if (!obj.isStream || nameOf(obj, "Subtype") != "Image") continue
                images.add(num)
                for (k in listOf("SMask", "Mask")) {
                    val v = obj.get(k)
                    if (v.isIndirect) maskTargets.add(v.asIndirect())
                    destroyObj(v)
                }
            } finally {
                obj.destroy()
            }
        }
        images.map { num ->
            val obj = doc.newIndirect(num, 0)
            try {
                ImageInfo(
                    objectNumber = num,
                    width = intOf(obj, "Width"),
                    height = intOf(obj, "Height"),
                    bitsPerComponent = intOf(obj, "BitsPerComponent"),
                    filter = obj.get("Filter").use { f ->
                        when {
                            f.isName -> f.asName()
                            f.isArray -> (0 until f.size()).joinToString(" ") { i -> f.get(i).use { it.asName() } }
                            else -> null
                        }
                    },
                    colorSpaceFamily = obj.get("ColorSpace").use { cs ->
                        when {
                            cs.isName -> cs.asName()
                            cs.isArray -> cs.get(0).use { it.asName() }
                            else -> null
                        }
                    },
                    hasSMask = obj.get("SMask").use { !it.isNull },
                    isImageMask = obj.get("ImageMask").use { it.isBoolean && it.asBoolean() },
                    isMaskTarget = num in maskTargets,
                    rawLength = obj.readRawStream().size,
                    components = doc.loadImage(obj).let { img -> try { img.numberOfComponents } finally { img.destroy() } },
                )
            } finally {
                obj.destroy()
            }
        }
    }

    /** Renders page [pageIndex] so that its longer edge is about [maxEdgePx] pixels (RGB, white background). */
    fun render(file: File, pageIndex: Int, maxEdgePx: Int = 300): RenderedPage = withDoc(file) { doc ->
        val page = doc.loadPage(pageIndex)
        try {
            val b = page.bounds
            val scale = maxEdgePx / maxOf(b.x1 - b.x0, b.y1 - b.y0)
            val pix = page.toPixmap(Matrix(scale, 0f, 0f, scale, 0f, 0f), ColorSpace.DeviceRGB, false)
            try {
                val w = pix.width
                val h = pix.height
                val s = pix.samples
                val stride = pix.stride
                val n = pix.numberOfComponents + (if (pix.alpha) 1 else 0)
                val rgb = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val o = y * stride + x * n
                    rgb[y * w + x] = ((s[o].toInt() and 0xff) shl 16) or ((s[o + 1].toInt() and 0xff) shl 8) or (s[o + 2].toInt() and 0xff)
                }
                RenderedPage(w, h, rgb)
            } finally {
                pix.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    /** Colours sampled 5% in from each corner of the rendered page. */
    fun renderCornerColors(file: File, pageIndex: Int): CornerColors {
        val r = render(file, pageIndex, 200)
        val dx = maxOf(1, r.width / 20)
        val dy = maxOf(1, r.height / 20)
        return CornerColors(
            r.at(dx, dy), r.at(r.width - 1 - dx, dy),
            r.at(dx, r.height - 1 - dy), r.at(r.width - 1 - dx, r.height - 1 - dy),
        )
    }

    /** True when every channel of [a] and [b] differs by at most [tolerance]. */
    fun colorNear(a: Int, b: Int, tolerance: Int = 60): Boolean =
        (0..2).all { k -> kotlin.math.abs((a shr (8 * k) and 0xff) - (b shr (8 * k) and 0xff)) <= tolerance }

    /** Raw (still encoded) stream data of every image XObject in [file], in object-number order. */
    fun rawImageStreams(file: File): List<ByteArray> = withDoc(file) { d ->
        val doc = d as PDFDocument
        imageInfos(file).map { info -> doc.newIndirect(info.objectNumber, 0).use { it.readRawStream() } }
    }

    /**
     * Every stream of [file], raw and decoded (decoding failures give only the raw data), so a
     * search sees inside Flate-compressed streams and object streams too.
     */
    fun allStreamData(file: File): List<ByteArray> = withDoc(file) { d ->
        val doc = d as PDFDocument
        val out = ArrayList<ByteArray>()
        for (num in 1 until doc.countObjects()) {
            doc.newIndirect(num, 0).use { obj ->
                if (obj.isStream) {
                    out.add(obj.readRawStream())
                    try { out.add(obj.readStream()) } catch (_: RuntimeException) { }
                }
            }
        }
        out
    }

    /** True when [needle] occurs in [file]'s bytes or in any of its streams, raw or decoded. */
    fun pdfContains(file: File, needle: ByteArray): Boolean =
        indexOf(file.readBytes(), needle) >= 0 || allStreamData(file).any { indexOf(it, needle) >= 0 }

    /** First index of [needle] in [hay], or -1. */
    fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /* ------------------------------------------------------------------ internals */

    private class Built(val doc: PDFDocument) {
        fun save(f: File, options: String = SAVE_OPTIONS) {
            try { doc.save(f.absolutePath, options) } finally { doc.destroy() }
        }
    }

    private inline fun buildPdf(block: (PDFDocument) -> Unit): Built {
        val doc = PDFDocument()
        try {
            block(doc)
        } catch (t: Throwable) {
            doc.destroy()
            throw t
        }
        return Built(doc)
    }

    private inline fun <T> withDoc(file: File, block: (Document) -> T): T {
        val doc = Document.openDocument(file.absolutePath)
        try {
            if (doc.needsPassword()) doc.authenticatePassword(USER_PASSWORD)
            return block(doc)
        } finally {
            doc.destroy()
        }
    }

    private inline fun <T> PDFObject.use(block: (PDFObject) -> T): T = try { block(this) } finally { destroyObj(this) }

    private fun destroyObj(o: PDFObject) { if (o !== PDFObject.Null) o.destroy() }

    private fun nameOf(obj: PDFObject, key: String): String? = obj.get(key).use { if (it.isName) it.asName() else null }

    private fun intOf(obj: PDFObject, key: String): Int = obj.get(key).use { if (it.isNumber) it.asInteger() else 0 }

    private fun imageDict(w: Int, h: Int, bpc: Int, colorSpace: Any, filter: String): Map<String, Any> = mapOf(
        "Type" to "/XObject", "Subtype" to "/Image", "Width" to w, "Height" to h,
        "BitsPerComponent" to bpc, "ColorSpace" to colorSpace, "Filter" to filter,
    )

    private fun addRawPage(doc: PDFDocument, dict: Map<String, Any>, data: ByteArray, w: Int, h: Int, text: String) {
        val ref = addRaw(doc, dict, data)
        try { addPage(doc, w.toFloat(), h.toFloat(), ref, text) } finally { ref.destroy() }
    }

    /**
     * Appends a [w]x[h] pt page. If [image] is non-null it is drawn over the full page as /Im0;
     * if [text] is non-null it is written in Helvetica 24 pt near the top left.
     */
    private fun addPage(doc: PDFDocument, w: Float, h: Float, image: PDFObject?, text: String?) {
        val res = doc.newDictionary()
        val toDestroy = ArrayList<PDFObject>()
        var font: Font? = null
        try {
            val sb = StringBuilder()
            if (image != null) {
                val xo = doc.newDictionary().also { toDestroy.add(it) }
                xo.put("Im0", image)
                res.put("XObject", xo)
                sb.append("q ${num(w)} 0 0 ${num(h)} 0 0 cm /Im0 Do Q\n")
            }
            if (text != null) {
                font = Font("Helvetica")
                val fontRef = doc.addSimpleFont(font, Font.SIMPLE_ENCODING_LATIN).also { toDestroy.add(it) }
                val fonts = doc.newDictionary().also { toDestroy.add(it) }
                fonts.put("F1", fontRef)
                res.put("Font", fonts)
                val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                sb.append("BT /F1 24 Tf 72 ${num(h - 100f)} Td ($escaped) Tj ET\n")
            }
            val page = doc.addPage(Rect(0f, 0f, w, h), 0, res, sb.toString())
            try { doc.insertPage(-1, page) } finally { page.destroy() }
        } finally {
            for (o in toDestroy) o.destroy()
            res.destroy()
            font?.destroy()
        }
    }

    private fun num(f: Float): String = String.format(Locale.ROOT, "%.4f", f)

    /** Adds an encoded stream with a hand-made dictionary; returns the indirect ref (caller destroys). */
    private fun addRaw(doc: PDFDocument, dict: Map<String, Any>, data: ByteArray): PDFObject {
        val d = obj(doc, dict)
        val buf = Buffer()
        try {
            buf.writeBytes(data)
            // Binding bug: PDFDocument.addObject returns a dangling wrapper; addRawStream is safe.
            return doc.addRawStream(buf, d)
        } finally {
            buf.destroy()
            d.destroy()
        }
    }

    /**
     * Builds a direct PDF object: Int, Float, Boolean, "/Name", other String (PDF string),
     * ByteArray (byte string), List (array), Map (dictionary). Caller destroys the result.
     */
    private fun obj(doc: PDFDocument, v: Any): PDFObject = when (v) {
        is Int -> doc.newInteger(v)
        is Float -> doc.newReal(v)
        is Boolean -> doc.newBoolean(v)
        is ByteArray -> doc.newByteString(v)
        is String -> if (v.startsWith("/")) doc.newName(v.substring(1)) else doc.newString(v)
        // Binding bug: PDFObject.push(PDFObject) double-frees; append with put(size(), o).
        is List<*> -> doc.newArray().also { a -> for (e in v) { val o = obj(doc, e!!); a.put(a.size(), o); o.destroy() } }
        is Map<*, *> -> doc.newDictionary().also { d -> for ((k, e) in v) { val o = obj(doc, e!!); d.put(k as String, o); o.destroy() } }
        else -> error("unsupported ${v.javaClass}")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater(6)
        try {
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!def.finished()) out.write(chunk, 0, def.deflate(chunk))
            return out.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun randomBytes(n: Int, seed: Long): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun clamp(v: Int) = v.coerceIn(0, 255)

    /** Photo-like pixel source: gradient with reddish top-left, bluish bottom-right, plus seeded noise. */
    private fun photoPixels(w: Int, h: Int, seed: Long): (Int, Int) -> Int {
        val rnd = Random(seed)
        return { x, y ->
            var r = 90 + 100 * x / w
            val g = 110
            var b = 90 + 100 * y / h
            if (x < w / 2 && y < h / 2) { r += 60; b -= 40 }
            if (x >= w / 2 && y >= h / 2) { b += 60; r -= 40 }
            val nz = rnd.nextInt(81) - 40
            (clamp(r + nz) shl 16) or (clamp(g + rnd.nextInt(81) - 40) shl 8) or clamp(b + nz)
        }
    }

    /** Binary PPM (P6). [px] returns 0xRRGGBB; must be called in row-major order (y outer). */
    private fun ppm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
        val header = "P6\n$w $h\n255\n".toByteArray()
        val out = ByteArray(header.size + w * h * 3)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) {
            val c = px(x, y)
            out[i++] = (c shr 16).toByte(); out[i++] = (c shr 8).toByte(); out[i++] = c.toByte()
        }
        return out
    }

    private fun pgm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
        val header = "P5\n$w $h\n255\n".toByteArray()
        val out = ByteArray(header.size + w * h)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) out[i++] = px(x, y).toByte()
        return out
    }

    private fun pam(w: Int, h: Int, depth: Int, tupl: String, px: (Int, Int) -> IntArray): ByteArray {
        val header = "P7\nWIDTH $w\nHEIGHT $h\nDEPTH $depth\nMAXVAL 255\nTUPLTYPE $tupl\nENDHDR\n".toByteArray()
        val out = ByteArray(header.size + w * h * depth)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) for (v in px(x, y)) out[i++] = v.toByte()
        return out
    }

    private fun encodeJpeg(raw: ByteArray, quality: Int): ByteArray {
        val img = Image(raw)
        try {
            val pix = img.toPixmap()
            try {
                val buf = pix.asJPEG(quality, false)
                try { return buf.asByteArray() } finally { buf.destroy() }
            } finally {
                pix.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    private fun encodePng(raw: ByteArray): ByteArray {
        val img = Image(raw)
        try {
            val pix = img.toPixmap()
            try {
                val buf = pix.asPNG()
                try { return buf.asByteArray() } finally { buf.destroy() }
            } finally {
                pix.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    /**
     * Inserts a minimal big-endian EXIF APP1 segment (IFD0: Orientation = [exif]) directly after SOI.
     * MuPDF only inspects the first APP marker, so it must come first.
     */
    private fun withExifOrientation(jpeg: ByteArray, exif: Int): ByteArray {
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val payload = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, // TIFF header, IFD0 at 8
            0, 1, // 1 entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, exif.toByte(), 0, 0, // Orientation SHORT x1
            0, 0, 0, 0, // next IFD = none
        )
        val len = payload.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
    }

}
