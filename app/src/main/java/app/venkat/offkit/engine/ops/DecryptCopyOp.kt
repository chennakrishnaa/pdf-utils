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
 * input's size plus [SPACE_MARGIN_BYTES] free in [out]'s folder: a healthy copy is at least about
 * the input's size, and the margin is left free for the device; more is not required, so a healthy
 * file still copies on a nearly full phone if it fits).
 *
 * Output bound (Z3-1): the copy may not exceed [maxOutputBytes] (twice the input plus
 * [OUTPUT_MARGIN_BYTES]). MuPDF copies each stream's raw bytes from its offset for `/Length` bytes
 * and then on to the next `endstream` token or the end of the file, and streams may overlap, so N
 * stream objects without `endstream` are each written as "the rest of the file" (output about N x
 * the input). The save cannot be interrupted from inside, so the bound is enforced while it runs by
 * `RemoteEngine`'s output watch (it polls [out]'s size from the UI process and kills the engine
 * past the cap, see `EngineLimits.decryptOutputSampleMs`), and here again after the save (an
 * oversized copy is deleted: `Err(OutOfMemory)`). Not checked before the save: the raw length MuPDF
 * will copy depends on where the next `endstream` token is, not on `/Length`, and the Java API
 * exposes no stream offsets, so no cheap sum over the object walk bounds it.
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
class DecryptCopyOp(private val outputMarginBytes: Long = OUTPUT_MARGIN_BYTES) {
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
            if (out.length() > maxOutputBytes(pdf.length(), outputMarginBytes)) {
                out.delete()
                return PdfResult.Err(PdfError.OutOfMemory)
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

        /** Free space required beyond the input's size before saving (16 MiB); also left free by the output watch. */
        const val SPACE_MARGIN_BYTES = 16L shl 20

        /**
         * Margin of the output cap beyond twice the input (160 MiB). A healthy copy is about the
         * input's size (raw streams are copied as stored; twice the input covers plain objects whose
         * binary strings are rewritten in hex), plus the objects that were inside object streams,
         * now written out uncompressed: at most the scan's 64 MiB of inflated object-stream data,
         * up to twice that if it is mostly binary strings rewritten in hex (128 MiB), plus a classic
         * xref table of 20 bytes per object (at most `ObjectGraph.MAX_OBJECTS` = 200,000: 4 MB),
         * plus slack. Measured: a 740 KB AES-256 file with 20,000 objects in object streams copies
         * to 7.6 MB (`DecryptOutputBoundTest`); the encrypted fixtures copy under 2 x input.
         */
        const val OUTPUT_MARGIN_BYTES = 160L shl 20

        /** Largest decrypted copy allowed for an input of [inputBytes]: `2 x input + margin`. */
        fun maxOutputBytes(inputBytes: Long, marginBytes: Long = OUTPUT_MARGIN_BYTES): Long =
            2 * inputBytes.coerceAtLeast(0) + marginBytes
    }
}
