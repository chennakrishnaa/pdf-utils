package com.offlinepdf.utils.engine

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
    /** Creates a new PDF at [out] with one page per entry in [images], in order. */
    fun imagesToPdf(images: List<File>, pageSize: PageSize, out: File, job: JobControl = JobControl.None): PdfResult

    /** Writes a recompressed copy of [pdf] to [out] according to [level]. */
    fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl = JobControl.None): PdfResult

    /** Concatenates all pages of [pdfs], in order, into [out]. */
    fun merge(pdfs: List<File>, out: File, job: JobControl = JobControl.None): PdfResult

    /** Writes [pdf] followed by one new page per entry in [images] to [out]. */
    fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl = JobControl.None): PdfResult
}

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
