package com.offlinepdf.utils.engine

import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import java.io.File
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

    /**
     * Appends one page to [doc] showing [image] upright (EXIF orientation applied); JPEG data is
     * embedded unchanged.
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
    fun appendImagePage(doc: PDFDocument, image: File, pageSize: PageSize, fixedSize: Rect? = null) {
        val img = try {
            Image(image.absolutePath)
        } catch (e: RuntimeException) {
            throw UnsupportedImageFileException(image, e)
        }
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
            val ref = doc.addImage(img)
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
