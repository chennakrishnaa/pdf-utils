package app.venkat.offkit.engine.ops

import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Page
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.ObjectGraph
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfResult
import java.io.File

/**
 * Implementation of [app.venkat.offkit.engine.PdfEngine.merge].
 *
 * Pass 1 opens every input with [PdfInputs.open] (one at a time) to classify it and count its
 * pages, so an encrypted, corrupt or missing input fails before any output is produced.
 * Pass 2 grafts every page of every input, in order, into a new document (one graft map per
 * source, only one source open at a time) and re-creates the source page's external links
 * (URIs with a scheme such as `https:` or `mailto:`) on the grafted page; internal links
 * (`#page=…` or scheme-less) are dropped, as are form fields, comments and bookmarks, because
 * grafting does not carry annotations or outlines.
 *
 * Progress is reported once per grafted page with the total page count of all inputs;
 * cancellation is checked before each page. Uncaught-throwable mapping and deletion of a
 * partial `out` on error are done by the caller ([app.venkat.offkit.engine.MuPdfEngine]).
 */
class MergeOp {
    fun run(pdfs: List<File>, out: File, job: JobControl): PdfResult = PdfInputs.toResult {
        if (pdfs.isEmpty()) {
            return@toResult PdfResult.Err(PdfError.Io(IllegalArgumentException("merge needs at least one PDF")))
        }

        // SR3-4: one reference budget for the whole job (the copies accumulate in one document).
        val budget = ObjectGraph.Budget()
        val pageCounts = pdfs.map { file ->
            if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)
            val doc = PdfInputs.open(file)
            try {
                // SA-11 / SR2-3: bound what grafting this input's pages will touch.
                budget.newWalk()
                PdfInputs.checkGraph { ObjectGraph.checkPages(doc, budget) }
                doc.countPages()
            } finally {
                doc.destroy()
            }
        }
        val total = pageCounts.sum()

        val dst = PDFDocument()
        try {
            var done = 0
            for (file in pdfs) {
                val src = PdfInputs.open(file)
                try {
                    val map = dst.newPDFGraftMap()
                    try {
                        val count = src.countPages()
                        for (i in 0 until count) {
                            if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)
                            map.graftPage(-1, src, i)
                            if (!copyExternalLinks(src, i, dst, dst.countPages() - 1, job)) {
                                return@toResult PdfResult.Err(PdfError.Cancelled)
                            }
                            done++
                            job.progress(done, total)
                        }
                    } finally {
                        map.destroy()
                    }
                } finally {
                    src.destroy()
                }
            }
            if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)
            val pages = dst.countPages()
            dst.save(out.absolutePath, saveOptions(dst.countObjects()))
            PdfInputs.validateOutput(out, pages, job)?.let { return@toResult it }
            PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            dst.destroy()
        }
    }

    /**
     * Re-creates up to [MAX_LINKS_PER_PAGE] external links of source page [srcIndex] on [dstIndex]
     * (SR3-3: each `createLink` costs time proportional to the links already on the page, so the
     * copy is quadratic; the cap bounds it, and the source page's annotation count was bounded by
     * [ObjectGraph.checkPages] before this `loadPage`). False if [job] was cancelled meanwhile.
     */
    private fun copyExternalLinks(src: PDFDocument, srcIndex: Int, dst: PDFDocument, dstIndex: Int, job: JobControl): Boolean {
        val srcPage: Page = src.loadPage(srcIndex)
        try {
            val links: Array<Link> = srcPage.links ?: return true
            var dstPage: Page? = null
            try {
                var copied = 0
                for (link in links) {
                    if (copied >= MAX_LINKS_PER_PAGE) break
                    if (job.isCancelled()) return false
                    val uri = link.uri
                    if (isExternalUri(uri)) {
                        val page = dstPage ?: dst.loadPage(dstIndex).also { dstPage = it }
                        page.createLink(link.bounds, uri).destroy()
                        copied++
                    }
                }
                return true
            } finally {
                links.forEach { it.destroy() }
                dstPage?.destroy()
            }
        } finally {
            srcPage.destroy()
        }
    }

    internal companion object {
        /**
         * Largest merged document saved with `garbage=deduplicate` (SR3-4). MuPDF's duplicate removal
         * compares every object with every earlier one, in one uncancellable call: measured on the
         * host, 2,000 distinct small streams 0.2 s, 5,000 1.2 s, 40,000 107 s. Above this the merged
         * document is saved with `garbage=compact` (unused objects still removed, renumbered, 20,000
         * streams in 0.5 s), so identical objects from different inputs are no longer shared.
         */
        const val MAX_DEDUP_OBJECTS = 5_000

        /** Save options for Merge's new document of [objects] objects. */
        fun saveOptions(objects: Int): String =
            if (objects <= MAX_DEDUP_OBJECTS) PdfInputs.SAVE_OPTIONS else "garbage=compact,compress,objstms"

        /** Most external links copied per page (SR3-3); real pages have tens. */
        const val MAX_LINKS_PER_PAGE = 1_000

        private val SCHEME =Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        /** True for URIs with a scheme (`https:`, `mailto:` …); false for `#…`, scheme-less or empty URIs. */
        fun isExternalUri(uri: String?): Boolean = uri != null && SCHEME.containsMatchIn(uri)
    }
}
