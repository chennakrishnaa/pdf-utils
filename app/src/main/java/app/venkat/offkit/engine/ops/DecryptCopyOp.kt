package app.venkat.offkit.engine.ops

import app.venkat.offkit.engine.EngineStack
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.ObjectGraph
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.ScanMemo
import app.venkat.offkit.engine.classifyFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Implementation of [app.venkat.offkit.engine.PdfPageRenderer.decryptCopy]: the unencrypted copy
 * the reader prints a protected PDF from.
 *
 * Pre-checks: [PdfInputs.openForReader] with the [ScanMemo] key, as every reader call (size,
 * object-stream scan pass 1 on the raw file, authentication, pass 2 on decrypted data, page and
 * object counts); then, because a full save loads and keeps every object, the tools' whole-document
 * walks before a save ([ObjectGraph.checkAnnots] with the reader's per-page cap, since the output
 * check loads every page, and [ObjectGraph.checkObjects]); then a free-space check (at least the
 * input's size plus [SPACE_MARGIN_BYTES] free in [out]'s folder; the output can be somewhat larger
 * than the input since object streams are written out as plain objects).
 *
 * Save: [SAVE_OPTIONS] only (`encrypt=none`; MuPDF 1.28.5 `pdf_parse_write_options`; the older
 * `decrypt` option is deprecated). No `decompress`, `compress`, `garbage`, `objstms`,
 * `compress-images` or `compress-fonts`, so `pdf-write.c` `writeobject` takes `copystream` for every
 * stream: `pdf_load_raw_stream_number` reads the stored bytes through the decryption filter only
 * (`pdf_open_raw_filter`: no decode filter is built), and they are written back as they are. No
 * image or font is decoded. Object streams are inflated, as at open (`preloadobjstms`), but they
 * were measured by both scan passes first; without `objstms` they are not written back (their
 * objects become plain objects), so the output holds no object stream. Then
 * [PdfInputs.validateOutput] reopens the output and loads every page.
 *
 * Known gap: a stream with its own `/Crypt` filter (`pdf_stream_has_crypt`) is read without the
 * document's decryption and copied as stored; a non-Identity crypt filter (very rare) leaves that
 * stream unreadable in the copy.
 *
 * The save is one native call and cannot be cancelled; cancellation is polled before it and in the
 * output check. On any failure [out] is deleted.
 */
class DecryptCopyOp {
    fun run(pdf: File, out: File, job: JobControl, password: String? = null): PdfResult {
        outRefusal(pdf, out)?.let { return PdfResult.Err(PdfError.Io(IOException(it))) }
        val result = try {
            out.delete()
            EngineStack.run { PdfInputs.toResult { copy(pdf, out, job, password) } }
        } catch (t: Throwable) {
            PdfResult.Err(classifyFailure(t))
        }
        if (result !is PdfResult.Ok) {
            try { out.delete() } catch (ignored: Throwable) { /* best effort */ }
        }
        return result
    }

    private fun copy(pdf: File, out: File, job: JobControl, password: String?): PdfResult {
        if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
        val memo = ScanMemo.keyOf(pdf, password)
        val doc = PdfInputs.openForReader(pdf, password, memo)
        try {
            if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
            val budget = ObjectGraph.Budget()
            PdfInputs.checkGraph { ObjectGraph.checkAnnots(doc, budget, ObjectGraph.MAX_READER_ANNOTS_PER_PAGE) }
            PdfInputs.checkGraph { ObjectGraph.checkObjects(doc, budget) }
            val pages = doc.countPages()
            val folder = out.absoluteFile.parentFile ?: return PdfResult.Err(PdfError.Io(IOException("No folder")))
            if (folder.usableSpace < pdf.length() + SPACE_MARGIN_BYTES) {
                return PdfResult.Err(PdfError.Io(IOException("Not enough free space")))
            }
            if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
            try {
                doc.save(out.absolutePath, SAVE_OPTIONS)
            } catch (e: RuntimeException) {
                out.delete() // a partial file; run() maps the failure (OutOfMemory or Io)
                throw e
            }
            PdfInputs.validateOutput(out, pages, job)?.let { return it }
            return PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            doc.destroy()
        }
    }

    companion object {
        /**
         * Why [out] must not be written (and, first, deleted) for [pdf], or null if it may: it is
         * the same file as [pdf] after canonicalisation, or it already exists as something other
         * than a regular file (a folder, a link, a device). Z3-2: without this, `out == pdf` would
         * delete the source.
         */
        fun outRefusal(pdf: File, out: File): String? {
            try {
                if (pdf.canonicalFile == out.canonicalFile) return "the copy would replace its source"
            } catch (e: IOException) {
                return "cannot resolve path"
            }
            val path = out.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "the copy's path is not a regular file"
            }
            return null
        }

        /** MuPDF save options: write without encryption, everything else as stored. */
        const val SAVE_OPTIONS = "encrypt=none"

        /** Free space required beyond the input's size before saving (16 MiB). */
        const val SPACE_MARGIN_BYTES = 16L shl 20
    }
}
