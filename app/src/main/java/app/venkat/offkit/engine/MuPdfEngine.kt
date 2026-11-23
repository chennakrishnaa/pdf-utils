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
    } catch (oom: OutOfMemoryError) {
        PdfEstimate.Err(PdfError.OutOfMemory)
    } catch (t: Throwable) {
        PdfEstimate.Err(PdfError.Io(t))
    }

    override fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        guardedRun(out) { compressOp.run(pdf, level, out, job) }

    override fun merge(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        guardedRun(out) { mergeOp.run(pdfs, out, job) }

    override fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult =
        guardedRun(out) { appendImagesOp.run(pdf, images, out, job) }
}

/**
 * Runs [block] and normalises its outcome: an uncaught [OutOfMemoryError] becomes
 * `Err(OutOfMemory)`, any other [Throwable] becomes `Err(Io(cause))`, and on any
 * [PdfResult.Err] (returned or mapped) a partially written [out] is deleted.
 */
internal inline fun guardedRun(out: File, block: () -> PdfResult): PdfResult {
    val result = try {
        block()
    } catch (oom: OutOfMemoryError) {
        PdfResult.Err(PdfError.OutOfMemory)
    } catch (t: Throwable) {
        PdfResult.Err(PdfError.Io(t))
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
