package app.venkat.offkit.engine

import app.venkat.offkit.engine.ops.AppendImagesOp
import app.venkat.offkit.engine.ops.CompressOp
import app.venkat.offkit.engine.ops.ImagesToPdfOp
import app.venkat.offkit.engine.ops.MergeOp
import java.io.File

/**
 * [PdfEngine] backed by MuPDF. Each operation is implemented by its own op class in
 * [app.venkat.offkit.engine.ops]; this class only adds the common error handling of
 * [guardedRun]. See [PdfEngine] for the threading contract.
 */
class MuPdfEngine : PdfEngine {
    private val imagesToPdfOp = ImagesToPdfOp()
    private val compressOp = CompressOp()
    private val mergeOp = MergeOp()
    private val appendImagesOp = AppendImagesOp()

    override fun imagesToPdf(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl,
        compression: CompressionLevel?,
    ): PdfResult = guardedRun(out) { imagesToPdfOp.run(images, pageSize, out, job, compression) }

    override fun estimateImage(image: File): PdfEstimate = try {
        ImagePages.estimate(image)
    } catch (e: UnsupportedImageFileException) {
        PdfEstimate.Err(PdfError.UnsupportedImage)
    } catch (e: ImageTooLargeException) {
        PdfEstimate.Err(PdfError.ImageTooLarge)
    } catch (t: Throwable) {
        PdfEstimate.Err(classifyFailure(t))
    }

    override fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        guardedRun(out) { compressOp.run(pdf, level, out, job) }

    override fun merge(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        guardedRun(out) { mergeOp.run(pdfs, out, job) }

    override fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult =
        guardedRun(out) { appendImagesOp.run(pdf, images, out, job) }
}

/**
 * Runs [block] and normalises its outcome: an uncaught throwable is mapped by [classifyFailure],
 * and on any [PdfResult.Err] (returned or mapped) a partially written [out] is deleted.
 */
internal fun guardedRun(out: File, block: () -> PdfResult): PdfResult {
    val result = try {
        // SA-11: MuPDF recursion depth is bounded by ObjectGraph, and runs on a large stack anyway.
        EngineStack.run(block)
    } catch (t: Throwable) {
        PdfResult.Err(classifyFailure(t))
    }
    if (result is PdfResult.Err) {
        try {
            out.delete()
        } catch (ignored: Throwable) {
            // Best effort: the error result is what matters to the caller.
        }
    }
    return result
}

/**
 * True when [t] is an out-of-memory failure: a Java [OutOfMemoryError], or a MuPDF
 * [RuntimeException] carrying one (a JNI allocation such as `NewByteArray` in `readRawStream`
 * comes back as `RuntimeException("java.lang.OutOfMemoryError: …")`) or a native `malloc (…) failed`.
 */
internal fun isOutOfMemoryFailure(t: Throwable): Boolean {
    if (t is OutOfMemoryError) return true
    val msg = t.message ?: return false
    // fitz/memory.c: "malloc (%zu bytes) failed", "calloc (%zu x %zu bytes) failed", "realloc (…) failed".
    return msg.contains("OutOfMemoryError") || msg.startsWith("malloc (") || msg.startsWith("calloc (") ||
        msg.startsWith("realloc (")
}

/** MuPDF messages of page-tree failures (typically during `graftPage` or `loadPage` of a damaged PDF). */
private val PAGE_TREE_FAILURES = listOf(
    "malformed page tree",
    "cycle in page tree",
    "cannot find page",
    "source object number out of range",
    "cycle in parent chain", // SR3-5: inherited-key lookup in Add images (pageSizeOf / appendImagePage)
)

/**
 * Error kind for a throwable that escaped an operation (security audit SA-4): out of memory
 * ([isOutOfMemoryFailure]) → [PdfError.OutOfMemory]; an image that cannot be decoded →
 * [PdfError.UnsupportedImage]; an image over [ImageLimits] → [PdfError.ImageTooLarge]; a broken page
 * tree → [PdfError.Corrupt]; an unusable input PDF → its [PdfInputException.error]; anything else →
 * [PdfError.Io].
 */
internal fun classifyFailure(t: Throwable): PdfError = when {
    isOutOfMemoryFailure(t) -> PdfError.OutOfMemory
    t is ImageTooLargeException -> PdfError.ImageTooLarge
    t is UnsupportedImageFileException -> PdfError.UnsupportedImage
    t is PdfInputException -> t.error
    t is RuntimeException && PAGE_TREE_FAILURES.any { t.message.orEmpty().contains(it) } -> PdfError.Corrupt
    else -> PdfError.Io(t)
}
