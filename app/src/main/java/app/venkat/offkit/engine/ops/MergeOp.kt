package app.venkat.offkit.engine.ops

import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Page
import app.venkat.offkit.engine.JobControl
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

        val pageCounts = pdfs.map { file ->
            if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)
            val doc = PdfInputs.open(file)
            try {
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
                            copyExternalLinks(src, i, dst, dst.countPages() - 1)
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
            dst.save(out.absolutePath, PdfInputs.SAVE_OPTIONS)
            PdfInputs.validateOutput(out, pages, job)?.let { return@toResult it }
            PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            dst.destroy()
        }
    }

    private fun copyExternalLinks(src: PDFDocument, srcIndex: Int, dst: PDFDocument, dstIndex: Int) {
        val srcPage: Page = src.loadPage(srcIndex)
        try {
            val links: Array<Link> = srcPage.links ?: return
            var dstPage: Page? = null
            try {
                for (link in links) {
                    val uri = link.uri
                    if (isExternalUri(uri)) {
                        val page = dstPage ?: dst.loadPage(dstIndex).also { dstPage = it }
                        page.createLink(link.bounds, uri).destroy()
                    }
                }
            } finally {
                links.forEach { it.destroy() }
                dstPage?.destroy()
            }
        } finally {
            srcPage.destroy()
        }
    }

    internal companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        /** True for URIs with a scheme (`https:`, `mailto:` …); false for `#…`, scheme-less or empty URIs. */
        fun isExternalUri(uri: String?): Boolean = uri != null && SCHEME.containsMatchIn(uri)
    }
}
