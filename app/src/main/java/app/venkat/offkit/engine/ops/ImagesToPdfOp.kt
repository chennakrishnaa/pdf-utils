package app.venkat.offkit.engine.ops

import com.artifex.mupdf.fitz.PDFDocument
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.ImagePages
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfResult
import java.io.File

/**
 * Implementation of [app.venkat.offkit.engine.PdfEngine.imagesToPdf].
 *
 * Builds a new document with one page per image, in order (see [ImagePages.appendImagePage]
 * for page sizing, EXIF orientation and JPEG pass-through). With a non-null `compression` level each
 * image is downscaled and re-encoded as JPEG when that makes it smaller; page sizes do not change. Reports `progress(i, n)` after each
 * image and checks cancellation before each image and before saving. Only one decoded image is
 * alive at a time.
 *
 * An empty [images] list is a caller bug and yields `Err(Io(IllegalArgumentException))`; an
 * undecodable image yields `Err(UnsupportedImage)`. Uncaught-throwable mapping and deletion of a
 * partial `out` on error are done by the caller ([app.venkat.offkit.engine.MuPdfEngine]);
 * `out` is only written by the final save, so an early `Err` never creates it.
 */
class ImagesToPdfOp {
    fun run(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl,
        compression: CompressionLevel? = null,
    ): PdfResult = PdfInputs.toResult {
        if (images.isEmpty()) return PdfResult.Err(PdfError.Io(IllegalArgumentException("no images")))
        val total = images.size
        val doc = PDFDocument()
        try {
            images.forEachIndexed { i, image ->
                if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
                ImagePages.appendImagePage(doc, image, pageSize, compression = compression)
                job.progress(i + 1, total)
            }
            if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
            val pages = doc.countPages()
            doc.save(out.absolutePath, PdfInputs.SAVE_OPTIONS)
            PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            doc.destroy()
        }
    }
}
