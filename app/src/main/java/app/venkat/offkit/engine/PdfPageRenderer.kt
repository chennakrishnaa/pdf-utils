package app.venkat.offkit.engine

import java.io.File
import kotlin.math.ceil

/**
 * Read-only page access for the in-app reader. Blocking; same threading rules as [PdfEngine]: never
 * on the main thread, one call at a time per instance, `job.isCancelled()` honoured where possible.
 *
 * In the app this is the `RemoteEngine` (UI process), which runs every call in the `:engine`
 * process and checks the reply; the `:engine` process runs `MuPdfEngine`. Encrypted and corrupt
 * files give [PdfError.Encrypted] / [PdfError.Corrupt] as for the other operations.
 */
interface PdfPageRenderer {
    /**
     * Page count and page sizes of [pdf], in page order. A document with more than
     * [RenderLimits.MAX_PAGES] pages gives `Err(OutOfMemory)`.
     */
    fun pageInfo(pdf: File, job: JobControl = JobControl.None): PdfPageInfo

    /**
     * [pageInfo] of a PDF that may be encrypted: [password] is tried when the PDF needs one
     * (null or empty: none given). A PDF that needs a password gives `Err(Encrypted)` without one
     * and `Err(WrongPassword)` with a wrong one; a PDF with only an owner password (empty user
     * password) opens without one. A [password] that fails [ReaderPassword.check] gives
     * `Err(Io(IllegalArgumentException))`. The default (renderers without password support) passes
     * a null password on and answers `Err(Encrypted)` to any other.
     */
    fun pageInfo(pdf: File, password: String?, job: JobControl = JobControl.None): PdfPageInfo =
        if (password.isNullOrEmpty()) pageInfo(pdf, job) else PdfPageInfo.Err(PdfError.Encrypted)

    /**
     * Renders part of one page of [pdf] into [out] as raw pixels (see [RenderRequest] for the
     * geometry and [PdfRender.Ok] for the byte layout). An invalid [request]
     * ([RenderLimits.check] with the page's size) gives `Err(Io(IllegalArgumentException(reason)))`;
     * a page index past the end gives the same. On any `Err`, [out] does not exist.
     */
    fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl = JobControl.None): PdfRender

    /** [renderPage] with a [password], as in the password [pageInfo]. */
    fun renderPage(pdf: File, request: RenderRequest, out: File, password: String?, job: JobControl = JobControl.None): PdfRender =
        if (password.isNullOrEmpty()) renderPage(pdf, request, out, job) else PdfRender.Err(PdfError.Encrypted).also { out.delete() }

    /**
     * Extracts the text of page [page] (0-based) of [pdf] with the box of every character, into
     * [out] as a [PageTextFormat] file (read it with [PageTextFormat.read]). Coordinates are in the
     * space of [PageDims]. A page without text (a scan) is `Ok(0, false)`; text past
     * [PageTextFormat.MAX_UNITS] units is dropped and the result is `truncated`. A page index out of
     * range gives `Err(Io(IllegalArgumentException))`. On any `Err`, [out] does not exist.
     *
     * The default (for renderers that have no text) is `Err(Io(UnsupportedOperationException))`.
     */
    fun pageText(pdf: File, page: Int, out: File, job: JobControl = JobControl.None): PdfPageText {
        out.delete()
        return PdfPageText.Err(PdfError.Io(UnsupportedOperationException("no page text")))
    }

    /** [pageText] with a [password], as in the password [pageInfo]. */
    fun pageText(pdf: File, page: Int, out: File, password: String?, job: JobControl = JobControl.None): PdfPageText =
        if (password.isNullOrEmpty()) pageText(pdf, page, out, job) else PdfPageText.Err(PdfError.Encrypted).also { out.delete() }
}

/**
 * The password the reader may send with a call (a documented exception to "only paths cross the
 * process boundary": it travels in the request Bundle, never in a file). Checked by the client
 * before sending and by the engine before use.
 *
 * Cap: MuPDF 1.28.5 (`pdf-crypt.c`) truncates an R5/R6 (AES-256) password to 127 bytes of UTF-8
 * and an R2-R4 one to 32 bytes after conversion; the JNI hands MuPDF the Java string as modified
 * UTF-8 (`GetStringUTFChars`). So a password is refused when its modified-UTF-8 form is longer than
 * [MAX_UTF8_BYTES] (127; this also caps it at 127 UTF-16 units) or when it contains U+0000.
 * Each UTF-16 unit, surrogate halves included, counts its modified-UTF-8 size (1-3 bytes), so a
 * character outside the BMP counts 6: an upper bound on what MuPDF receives whether the runtime
 * passes it as two 3-byte surrogates (HotSpot) or as one 4-byte sequence.
 *
 * Known gap (U1): a password with a character outside the BMP matches an R5/R6 file encrypted
 * with real UTF-8 only if the runtime's JNI hands MuPDF 4-byte UTF-8 (HotSpot does not; see
 * `EncryptedFixturesTest`). For R2-R4 MuPDF drops characters outside PDFDocEncoding.
 */
object ReaderPassword {
    const val MAX_UTF8_BYTES = 127

    /** Why [password] is refused, or null when it may be used. Never includes the password. */
    fun check(password: String): String? {
        if (password.length > MAX_UTF8_BYTES) return "password longer than $MAX_UTF8_BYTES units"
        var bytes = 0
        for (c in password) {
            if (c == '\u0000') return "password contains U+0000"
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3 // BMP and each surrogate half (modified UTF-8)
            }
        }
        return if (bytes > MAX_UTF8_BYTES) "password longer than $MAX_UTF8_BYTES bytes" else null
    }
}

/** Displayed size of one page (its rotation applied), in PDF points (1/72 inch). Both finite and > 0. */
data class PageDims(val widthPt: Float, val heightPt: Float)

/** Outcome of [PdfPageRenderer.pageInfo]. */
sealed interface PdfPageInfo {
    /**
     * One [PageDims] per page, at most [RenderLimits.MAX_PAGES] entries. [encrypted]: the PDF uses
     * encryption (password or owner-password only). [canPrint] / [canCopy]: the PDF's permission
     * bits as MuPDF reports them after opening (`Document.hasPermission`): always true for an
     * unencrypted PDF and for one opened with its OWNER password (MuPDF `pdf_has_permission`:
     * owner access grants everything); otherwise its /P bits. Advisory: the reader does not
     * enforce them.
     */
    data class Ok(
        val pages: List<PageDims>,
        val encrypted: Boolean = false,
        val canPrint: Boolean = true,
        val canCopy: Boolean = true,
    ) : PdfPageInfo

    data class Err(val error: PdfError) : PdfPageInfo
}

/**
 * One tile of one page. [page] is 0-based. The page is laid out at [scale] pixels per PDF point
 * with its top-left corner at (0, 0), so the scaled page is [RenderLimits.scaledWidth] x
 * [RenderLimits.scaledHeight] pixels (`ceil(widthPt * scale)` x `ceil(heightPt * scale)`); the
 * output is its pixel rectangle `[left, left + width) x [top, top + height)`, which must lie inside
 * the scaled page. Limits: [RenderLimits].
 */
data class RenderRequest(val page: Int, val scale: Float, val left: Int, val top: Int, val width: Int, val height: Int)

/** Outcome of [PdfPageRenderer.renderPage]. */
sealed interface PdfRender {
    /**
     * `out` holds exactly `width * height * 4` bytes: RGBA, 8 bits per channel, rows top to bottom,
     * no header, every pixel fully opaque (the page is drawn on white). This is the byte layout of
     * an Android `ARGB_8888` bitmap buffer (`Bitmap.copyPixelsFromBuffer`). [width] and [height]
     * equal the request's.
     */
    data class Ok(val width: Int, val height: Int) : PdfRender

    /** `out` does not exist. */
    data class Err(val error: PdfError) : PdfRender
}

/** Bounds of [PdfPageRenderer] requests and replies, enforced on both sides of the process boundary. */
object RenderLimits {
    /** Most pages [PdfPageRenderer.pageInfo] reports; a larger document is `Err(OutOfMemory)`. */
    const val MAX_PAGES = 50_000

    /** Most pixels (`width * height`) in one [RenderRequest]: 32 MB of RGBA. */
    const val MAX_PIXELS = 8_000_000

    /** Largest [RenderRequest.scale] (pixels per PDF point; 16 is about 1150 dpi). */
    const val MAX_SCALE = 16f

    /**
     * Width in pixels of a page [dims] laid out at [scale]: `ceil(widthPt * scale)`, computed in
     * Double. Every party (engine, client, UI) must use this, so the pixel count never differs by one.
     */
    fun scaledWidth(dims: PageDims, scale: Float): Long = scaled(dims.widthPt, scale)

    /** Height in pixels of a page [dims] laid out at [scale]: `ceil(heightPt * scale)`, as [scaledWidth]. */
    fun scaledHeight(dims: PageDims, scale: Float): Long = scaled(dims.heightPt, scale)

    private fun scaled(pt: Float, scale: Float): Long = ceil(pt.toDouble() * scale.toDouble()).toLong()

    /**
     * Why [request] is invalid, or null if it is valid. Invalid: `page < 0`; `scale` not finite or
     * not in `(0, MAX_SCALE]`; `width` or `height < 1`; `left` or `top < 0`;
     * `width * height > MAX_PIXELS` (computed in Long); and, when the page's [dims] are known, a
     * rectangle that extends past the scaled page ([scaledWidth] x [scaledHeight]).
     */
    fun check(request: RenderRequest, dims: PageDims?): String? {
        val r = request
        return when {
            r.page < 0 -> "page ${r.page} < 0"
            !r.scale.isFinite() || r.scale <= 0f || r.scale > MAX_SCALE -> "scale ${r.scale} not in (0, $MAX_SCALE]"
            r.width < 1 || r.height < 1 -> "size ${r.width}x${r.height} is empty"
            r.left < 0 || r.top < 0 -> "origin ${r.left},${r.top} is negative"
            r.width.toLong() * r.height.toLong() > MAX_PIXELS -> "size ${r.width}x${r.height} exceeds $MAX_PIXELS pixels"
            dims != null && (
                r.left.toLong() + r.width > scaledWidth(dims, r.scale) ||
                    r.top.toLong() + r.height > scaledHeight(dims, r.scale)
                ) -> "rectangle extends past the ${scaledWidth(dims, r.scale)}x${scaledHeight(dims, r.scale)} page"
            else -> null
        }
    }
}
