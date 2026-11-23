package app.venkat.filetools.engine.ops

import app.venkat.filetools.engine.ImagePages
import app.venkat.filetools.engine.JobControl
import app.venkat.filetools.engine.PageSize
import app.venkat.filetools.engine.PdfError
import app.venkat.filetools.engine.PdfInputs
import app.venkat.filetools.engine.PdfResult
import java.io.File

/**
 * Implementation of [app.venkat.filetools.engine.PdfEngine.appendImages].
 *
 * Opens [pdf] with [PdfInputs.open] and modifies that document in memory: for each image, in
 * order, one page is appended at the end. Every new page has the displayed size of the
 * ORIGINAL last page (measured once, before anything is appended) and shows the image upright,
 * fitted inside without margin, centred, aspect preserved (see [ImagePages.appendImagePage]).
 * The result is written to `out` with a full, non-incremental save, so the original pages keep
 * their links, annotations, form fields and bookmarks. The input file is never written.
 *
 * Reports `progress(i, n)` after each image and checks cancellation before each image and before
 * saving. An empty [images] list yields `Err(Io(IllegalArgumentException))`; an undecodable image
 * yields `Err(UnsupportedImage)`. `out` is only written by the final save, so an early `Err` never
 * creates it; uncaught-throwable mapping and deletion of a partial `out` are done by the caller
 * ([app.venkat.filetools.engine.MuPdfEngine]).
 */
class AppendImagesOp {
    fun run(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult = PdfInputs.toResult {
        if (images.isEmpty()) return PdfResult.Err(PdfError.Io(IllegalArgumentException("no images")))
        val total = images.size
        val doc = PdfInputs.open(pdf)
        try {
            val original = doc.countPages()
            val lastSize = ImagePages.pageSizeOf(doc, original - 1)
            images.forEachIndexed { i, image ->
                if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
                ImagePages.appendImagePage(doc, image, PageSize.FIT_IMAGE, fixedSize = lastSize)
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
