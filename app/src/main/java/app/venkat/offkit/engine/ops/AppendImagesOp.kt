package app.venkat.offkit.engine.ops

import app.venkat.offkit.engine.ImageLimits
import app.venkat.offkit.engine.ImagePages
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.ObjectGraph
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfResult
import java.io.File

/**
 * Implementation of [app.venkat.offkit.engine.PdfEngine.appendImages].
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
 * ([app.venkat.offkit.engine.MuPdfEngine]).
 */
class AppendImagesOp {
    fun run(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult = PdfInputs.toResult {
        if (images.isEmpty()) return PdfResult.Err(PdfError.Io(IllegalArgumentException("no images")))
        if (images.size > ImageLimits.MAX_IMAGES_PER_JOB) return PdfResult.Err(PdfError.OutOfMemory)
        val total = images.size
        val doc = PdfInputs.open(pdf)
        try {
            // SR2-3: the save loads every object; refuse inputs whose objects exceed the walk budget.
            // SR3-3 first: validation loadPage()s every page, building all of its annotations.
            val budget = ObjectGraph.Budget()
            PdfInputs.checkGraph { ObjectGraph.checkAnnots(doc, budget) }
            PdfInputs.checkGraph { ObjectGraph.checkObjects(doc, budget) }
            val original = doc.countPages()
            val lastSize = ImagePages.pageSizeOf(doc, original - 1)
            images.forEachIndexed { i, image ->
                if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
                ImagePages.appendImagePage(doc, image, PageSize.FIT_IMAGE, fixedSize = lastSize)
                job.progress(i + 1, total)
            }
            if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
            val pages = doc.countPages()
            doc.save(out.absolutePath, PdfInputs.EDIT_SAVE_OPTIONS)
            PdfInputs.validateOutput(out, pages, job)?.let { return it }
            PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            doc.destroy()
        }
    }
}
