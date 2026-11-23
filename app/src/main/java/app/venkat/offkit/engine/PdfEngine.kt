package app.venkat.offkit.engine

import java.io.File

/**
 * Offline PDF operations.
 *
 * Threading: every function is blocking. Callers must invoke them off the main thread and
 * always from the same single thread (e.g. one dedicated single-thread dispatcher), because
 * the underlying native library is not safe for concurrent use.
 *
 * Output: `out` is a file path that the engine creates or overwrites. On any
 * [PdfResult.Err] the engine deletes a partially written `out`, so callers never see a
 * truncated file. On [PdfResult.Ok] `out` holds the complete document.
 *
 * Progress and cancellation go through [job]; an operation that observes
 * [JobControl.isCancelled] returns `Err(PdfError.Cancelled)`.
 */
interface PdfEngine {
    /**
     * Creates a new PDF at [out] with one page per entry in [images], in order.
     *
     * [compression] null: image data is embedded unchanged (JPEG bytes verbatim). Non-null: each
     * image is downscaled so its long edge is at most `maxLongEdgePx` (never upscaled) and
     * re-encoded as JPEG at `jpegQuality`; the re-encoded data is used only when it is smaller than
     * the unchanged data, so the output is never larger than with null. Gray stays gray,
     * transparency is flattened onto white, EXIF orientation is honoured, and page sizes are the
     * same as with null.
     */
    fun imagesToPdf(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl = JobControl.None,
        compression: CompressionLevel? = null,
    ): PdfResult

    /**
     * Size each image would add to the PDF, uncompressed and at each level. Blocking; engine thread.
     *
     * A whole document made by [imagesToPdf] is about
     * `PDF_DOCUMENT_OVERHEAD_BYTES + sum(image bytes + PDF_PAGE_OVERHEAD_BYTES)`, using
     * [PdfEstimate.Ok.original] for `compression = null` or [PdfEstimate.Ok.byLevel] for a level.
     * Unreadable, truncated or unsupported image: `Err(UnsupportedImage)`; out of memory:
     * `Err(OutOfMemory)`; anything else: `Err(Io)`.
     */
    fun estimateImage(image: File): PdfEstimate

    /** Writes a recompressed copy of [pdf] to [out] according to [level]. */
    fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl = JobControl.None): PdfResult

    /** Concatenates all pages of [pdfs], in order, into [out]. */
    fun merge(pdfs: List<File>, out: File, job: JobControl = JobControl.None): PdfResult

    /** Writes [pdf] followed by one new page per entry in [images] to [out]. */
    fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl = JobControl.None): PdfResult
}

/** Outcome of [PdfEngine.estimateImage]. */
sealed interface PdfEstimate {
    /**
     * [original]: bytes of image data embedded with `compression = null`. [byLevel]: bytes of image
     * data [PdfEngine.imagesToPdf] embeds at each level (never more than [original]).
     */
    data class Ok(val original: Long, val byLevel: Map<CompressionLevel, Long>) : PdfEstimate

    /** The image cannot be estimated; [error] is what [PdfEngine.imagesToPdf] would report. */
    data class Err(val error: PdfError) : PdfEstimate
}

/**
 * Bytes one image page adds to a PDF made by [PdfEngine.imagesToPdf] beyond its image stream data
 * (page object, content stream, image dictionary, xref share). Measured with MuPDF 1.28.5: 297 to
 * 310 bytes per page for RGB and gray JPEG pages at FIT_IMAGE and A4.
 */
const val PDF_PAGE_OVERHEAD_BYTES: Long = 300L

/**
 * Bytes a PDF made by [PdfEngine.imagesToPdf] has beyond its pages: header, catalog, page tree,
 * object stream, trailer and the shared sRGB ICC profile (measured about 2950 bytes). A document
 * that also contains gray images carries a second (gray) profile of about 2.4 KB that this
 * constant does not include.
 */
const val PDF_DOCUMENT_OVERHEAD_BYTES: Long = 3000L

/** Progress reporting and cooperative cancellation for one engine call. */
interface JobControl {
    /** Reports that [done] of [total] work units are complete. Called on the engine thread. */
    fun progress(done: Int, total: Int)

    /** Polled by the engine between work units; returning true aborts with `Err(Cancelled)`. */
    fun isCancelled(): Boolean

    /** No progress reporting, never cancelled. */
    object None : JobControl { override fun progress(done: Int, total: Int) {}; override fun isCancelled() = false }
}

/** Page size used when converting images to pages. */
enum class PageSize {
    /** Page is exactly the size of the image. */
    FIT_IMAGE,

    /** ISO A4 (595 x 842 pt), image scaled to fit. */
    A4,

    /** US Letter (612 x 792 pt), image scaled to fit. */
    LETTER,
}

/**
 * Compression strength. Images are downscaled so their long edge is at most
 * [maxLongEdgePx] pixels and re-encoded as JPEG with [jpegQuality].
 */
enum class CompressionLevel(val maxLongEdgePx: Int, val jpegQuality: Int) {
    /** Least compression: 2000 px long edge, JPEG quality 80. */
    LOW(maxLongEdgePx = 2000, jpegQuality = 80),

    /** 1500 px long edge, JPEG quality 60. */
    MEDIUM(maxLongEdgePx = 1500, jpegQuality = 60),

    /** Most compression: 1000 px long edge, JPEG quality 40. */
    HIGH(maxLongEdgePx = 1000, jpegQuality = 40),
}

/** Outcome of an engine call. */
sealed interface PdfResult {
    /** Success: [out] holds a document with [pages] pages and a size of [bytes] bytes. */
    data class Ok(val pages: Int, val bytes: Long) : PdfResult

    /** Failure; any partial output has been deleted. */
    data class Err(val error: PdfError) : PdfResult
}

/** Reasons an engine call can fail. */
sealed interface PdfError {
    /** An input PDF is password protected. */
    data object Encrypted : PdfError

    /** An input PDF could not be parsed. */
    data object Corrupt : PdfError

    /** An input image is in a format or state the engine cannot decode. */
    data object UnsupportedImage : PdfError

    /** The engine ran out of memory. */
    data object OutOfMemory : PdfError

    /** The job was cancelled through [JobControl.isCancelled]. */
    data object Cancelled : PdfError

    /** The operation is not implemented yet. */
    data object NotImplemented : PdfError

    /** Any other failure, typically file I/O; [cause] is the original throwable. */
    data class Io(val cause: Throwable) : PdfError
}
