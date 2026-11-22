package com.offlinepdf.utils.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale

/** [file] could not be decoded as an image (unknown format, unreadable or missing). */
class UnsupportedImageFileException(val file: File, cause: Throwable?) :
    RuntimeException("unsupported image file: ${file.name}", cause)

/** Builds PDF pages that show one image each. Not thread-safe; see [PdfEngine]. */
internal object ImagePages {
    /** PDF's maximum page dimension in points (14400 pt = 200 in). */
    private const val MAX_PAGE_EDGE = 14400f

    /** Margin around the image on fixed paper sizes (A4, Letter). */
    private const val PAPER_MARGIN = 24f

    /** How far from the end of a JPEG / PNG file its end marker may be (camera padding, appended data). */
    const val END_MARKER_WINDOW = 64 * 1024

    private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44) // zero length + "IEND"

    private enum class Format { JPEG, PNG, OTHER }

    /**
     * Opens [file] as a MuPDF [Image] (caller must `destroy()` it) after checking that it is complete.
     *
     * Truncation rule (MuPDF decodes lazily and silently accepts a cut-off file): the format is
     * sniffed from the magic bytes, then only the last [END_MARKER_WINDOW] bytes are read.
     * - JPEG (`FF D8 FF`): the window must contain the end-of-image marker `FF D9`. Data after the
     *   EOI (padding, camera trailers) is accepted as long as the EOI is within the last 64 KiB.
     *   Known limit: a file truncated to under ~64 KiB whose EXIF thumbnail (with its own EOI) lies in
     *   the window is not detected.
     * - PNG: the window must contain an `IEND` chunk header (`00 00 00 00 "IEND"`).
     * - Other formats are not checked here.
     *
     * @throws UnsupportedImageFileException if the file is missing, unreadable, not an image MuPDF
     *   can decode, truncated, or has no pixels.
     */
    fun loadImage(file: File): Image {
        val img = try {
            Image(file.absolutePath)
        } catch (e: RuntimeException) {
            throw UnsupportedImageFileException(file, e)
        }
        try {
            if (img.width <= 0 || img.height <= 0) throw UnsupportedImageFileException(file, null)
            if (!isComplete(file)) throw UnsupportedImageFileException(file, null)
            return img
        } catch (t: Throwable) {
            img.destroy()
            throw t
        }
    }

    private fun formatOf(file: File): Format {
        val head = ByteArray(4)
        val n = RandomAccessFile(file, "r").use { it.read(head) }
        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return Format.JPEG
        val png = n >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
        return if (png) Format.PNG else Format.OTHER
    }

    private fun isComplete(file: File): Boolean {
        val format = formatOf(file)
        if (format == Format.OTHER) return true
        val tail = RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val n = minOf(len, END_MARKER_WINDOW.toLong()).toInt()
            ByteArray(n).also { raf.seek(len - n); raf.readFully(it) }
        }
        return when (format) {
            Format.JPEG -> (tail.size - 2 downTo 0).any { tail[it] == 0xFF.toByte() && tail[it + 1] == 0xD9.toByte() }
            else -> (tail.size - PNG_IEND.size downTo 0).any { i -> PNG_IEND.indices.all { tail[i + it] == PNG_IEND[it] } }
        }
    }

    /**
     * Downscales [img] so that its long edge is at most [level]'s `maxLongEdgePx` (never upscales)
     * and encodes it as JPEG at the level's quality. The STORED pixels are drawn (drawing an [Image]
     * into a [Pixmap] does not apply EXIF orientation), so the caller keeps using the original's
     * orientation when placing it. One-component images are encoded as gray, everything else as
     * RGB; transparency is flattened onto white. This one routine is used both for embedding and for
     * [estimate], so estimates are the real stream sizes. The caller must `destroy()` the result.
     */
    fun encodeJpeg(img: Image, level: CompressionLevel): Buffer {
        val scale = minOf(1.0, level.maxLongEdgePx.toDouble() / max(img.width, img.height))
        val nw = max(1, (img.width * scale).roundToInt())
        val nh = max(1, (img.height * scale).roundToInt())
        val gray = img.numberOfComponents == 1 && !img.imageMask
        val cs = if (gray) ColorSpace.DeviceGray else ColorSpace.DeviceRGB // singletons: never destroy
        val pix = Pixmap(cs, nw, nh, false)
        try {
            pix.clear(255)
            val dev = DrawDevice(pix)
            try {
                dev.fillImage(img, Matrix(nw.toFloat(), 0f, 0f, nh.toFloat(), 0f, 0f), 1f, 0)
                dev.close()
            } finally {
                dev.destroy()
            }
            return pix.asJPEG(level.jpegQuality, false)
        } finally {
            pix.destroy()
        }
    }

    /**
     * Bytes of image stream data that `addImage` embeds for [img] loaded from [file], unchanged:
     * the file length for a JPEG (embedded verbatim as DCTDecode). Anything else (PNG) is added to
     * a scratch document, where MuPDF stores raw pixels that the final save (`compress` option)
     * deflates with zlib at the default level; that deflate is reproduced here with
     * [java.util.zip.Deflater] at the same level, for the image stream plus its /SMask stream.
     */
    fun originalEmbeddedSize(img: Image, file: File): Long {
        if (formatOf(file) == Format.JPEG) return file.length()
        val scratch = PDFDocument()
        try {
            val ref = scratch.addImage(img)
            try {
                var total = savedStreamSize(ref)
                val smask = ref.get("SMask")
                if (smask !== PDFObject.Null) {
                    try {
                        if (smask.isStream) total += savedStreamSize(smask)
                    } finally {
                        smask.destroy()
                    }
                }
                return total
            } finally {
                ref.destroy()
            }
        } finally {
            scratch.destroy()
        }
    }

    /** Size of stream [obj] after a `compress` save: unfiltered data is deflated, filtered data kept. */
    private fun savedStreamSize(obj: PDFObject): Long {
        val raw = obj.readRawStream()
        val filter = obj.get("Filter")
        val filtered = filter !== PDFObject.Null
        if (filtered) filter.destroy()
        if (filtered) return raw.size.toLong()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val chunk = ByteArray(64 * 1024)
            var n = 0L
            while (!deflater.finished()) n += deflater.deflate(chunk)
            return n
        } finally {
            deflater.end()
        }
    }

    /**
     * Size estimate for [file] (see [PdfEngine.estimateImage]). The image is opened once and each
     * level is derived from that same [Image] with [encodeJpeg] (MuPDF caches the decoded pixels).
     *
     * @throws UnsupportedImageFileException if the image cannot be used.
     */
    fun estimate(file: File): PdfEstimate.Ok {
        val img = loadImage(file)
        try {
            val original = originalEmbeddedSize(img, file)
            val byLevel = CompressionLevel.entries.associateWith { level ->
                val jpeg = encodeJpeg(img, level)
                try {
                    minOf(jpeg.length.toLong(), original)
                } finally {
                    jpeg.destroy()
                }
            }
            return PdfEstimate.Ok(original, byLevel)
        } finally {
            img.destroy()
        }
    }

    /**
     * Adds the image XObject for [img] to [doc]: unchanged when [compression] is null, otherwise the
     * [encodeJpeg] version if it is strictly smaller than [originalEmbeddedSize], else unchanged.
     */
    private fun addImageObject(doc: PDFDocument, img: Image, file: File, compression: CompressionLevel?): PDFObject {
        if (compression == null) return doc.addImage(img)
        val jpeg = encodeJpeg(img, compression)
        try {
            if (jpeg.length.toLong() >= originalEmbeddedSize(img, file)) return doc.addImage(img)
            val small = Image(jpeg)
            try {
                return doc.addImage(small)
            } finally {
                small.destroy()
            }
        } finally {
            jpeg.destroy()
        }
    }

    /**
     * Appends one page to [doc] showing [image] upright (EXIF orientation applied); with
     * [compression] null, JPEG data is embedded unchanged. With a [compression] level the embedded
     * pixels may be downscaled and re-encoded (see [addImageObject]); the page box and placement
     * never depend on [compression]: they always come from the original image.
     *
     * - [fixedSize] non-null: the page box is exactly `fixedSize.width x fixedSize.height` (origin 0,0)
     *   and the image is fitted inside it without margin, centred; [pageSize] is ignored.
     * - [PageSize.FIT_IMAGE]: page box = displayed image size at 1 px = 1 pt, scaled down
     *   proportionally if the long edge exceeds 14400 pt.
     * - [PageSize.A4] / [PageSize.LETTER]: portrait, or landscape when the displayed image is wider
     *   than tall; image scaled (up or down) to fit inside a 24 pt margin, centred.
     *
     * @throws UnsupportedImageFileException if [image] cannot be decoded.
     */
    fun appendImagePage(
        doc: PDFDocument,
        image: File,
        pageSize: PageSize,
        fixedSize: Rect? = null,
        compression: CompressionLevel? = null,
    ) {
        val img = loadImage(image)
        try {
            val orientation = img.orientation
            val quarterTurn = isQuarterTurn(orientation)
            val dw = (if (quarterTurn) img.height else img.width).toFloat() // displayed width, px
            val dh = (if (quarterTurn) img.width else img.height).toFloat() // displayed height, px
            if (dw <= 0f || dh <= 0f) throw UnsupportedImageFileException(image, null)

            val pageW: Float
            val pageH: Float
            val margin: Float
            when {
                fixedSize != null -> {
                    pageW = fixedSize.x1 - fixedSize.x0
                    pageH = fixedSize.y1 - fixedSize.y0
                    margin = 0f
                }
                pageSize == PageSize.FIT_IMAGE -> {
                    val s = minOf(1f, MAX_PAGE_EDGE / maxOf(dw, dh))
                    pageW = dw * s
                    pageH = dh * s
                    margin = 0f
                }
                else -> {
                    val (shortEdge, longEdge) = if (pageSize == PageSize.A4) 595f to 842f else 612f to 792f
                    val landscape = dw > dh
                    pageW = if (landscape) longEdge else shortEdge
                    pageH = if (landscape) shortEdge else longEdge
                    margin = PAPER_MARGIN
                }
            }
            // Fit the displayed image into the box inside the margin, preserving aspect, centred.
            val boxW = pageW - 2 * margin
            val boxH = pageH - 2 * margin
            val scale = minOf(boxW / dw, boxH / dh)
            val w = dw * scale
            val h = dh * scale
            val x = (pageW - w) / 2
            val y = (pageH - h) / 2

            val cm = uprightCm(orientation, x, y, w, h)
            val ref = addImageObject(doc, img, image, compression)
            val xobjects = doc.newDictionary()
            val resources = doc.newDictionary()
            try {
                xobjects.put("Im0", ref)
                resources.put("XObject", xobjects)
                val content = "q ${cm.joinToString(" ") { num(it) }} cm /Im0 Do Q"
                val page = doc.addPage(Rect(0f, 0f, pageW, pageH), 0, resources, content)
                try {
                    doc.insertPage(-1, page)
                } finally {
                    page.destroy()
                }
            } finally {
                resources.destroy()
                xobjects.destroy()
                ref.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    /** Displayed size of page [pageIndex] of [doc] as `Rect(0, 0, w, h)` (honours /Rotate and the CropBox). */
    fun pageSizeOf(doc: PDFDocument, pageIndex: Int): Rect {
        val page = doc.loadPage(pageIndex)
        try {
            val b = page.bounds
            return Rect(0f, 0f, b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            page.destroy()
        }
    }

    /** True when MuPDF orientation [o] is a quarter turn, i.e. displayed width/height are swapped. */
    private fun isQuarterTurn(o: Int) = o != 0 && o % 2 == 0

    /**
     * PDF `cm` matrix [a b c d e f] that draws an image upright so that it exactly covers the
     * box (x, y, w, h) on the page (PDF user space: origin bottom-left, y up; w x h is the
     * DISPLAYED size, i.e. already swapped for quarter turns).
     *
     * [orientation] is MuPDF's value from Image.getOrientation(), NOT the EXIF value
     * (EXIF 1..8 -> 1,5,3,7,6,4,8,2; 0 = no EXIF). MuPDF's fz_image_orientation_matrix gives
     * a transform [a b c d e f] of the unit square in y-down image space. An image XObject maps
     * the unit square with image row 0 at v = 1 (y-up), so converting to PDF space and scaling to
     * the box gives:
     *   cm = [w*a, -h*b, -w*c, h*d, x + w*(c+e), y + h*(1-d-f)]
     * The (x, y) offset is a plain translation added to e and f. Proven for all 8 values
     * in the T1c spike (full-page case x = y = 0).
     */
    private fun uprightCm(orientation: Int, x: Float, y: Float, w: Float, h: Float): FloatArray {
        val m = when (orientation) {
            0, 1 -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
            2 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, 0f, -1f, 1f, 1f)
            4 -> floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f)
            5 -> floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f)
            6 -> floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f)
            7 -> floatArrayOf(1f, 0f, 0f, -1f, 0f, 1f)
            8 -> floatArrayOf(0f, -1f, -1f, 0f, 1f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f) // unknown value: draw as stored
        }
        val (a, b, c, d, e) = m
        val f = m[5]
        return floatArrayOf(w * a, -h * b, -w * c, h * d, x + w * (c + e), y + h * (1 - d - f))
    }

    /** Fixed-point, locale-independent number for content streams (no exponents, no decimal commas). */
    private fun num(v: Float): String {
        val r = if (v == 0f) 0f else v // avoid "-0.0000"
        return String.format(Locale.ROOT, "%.4f", r)
    }
}
