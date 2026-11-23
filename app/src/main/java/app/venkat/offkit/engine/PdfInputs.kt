package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.File

/** An input PDF could not be used; [error] is the classification to report. */
class PdfInputException(val error: PdfError, cause: Throwable? = null) :
    RuntimeException("unusable input PDF: $error", cause)

/** Opening and classifying input PDFs, and the error mapping shared by all ops. */
internal object PdfInputs {
    /**
     * Save options for documents the engine BUILDS (Images to PDF, the new document of Merge): their
     * object graph is either the engine's own or a grafted copy, whose depth MuPDF itself caps (see
     * [ObjectGraph]), so the recursive `garbage` marking is safe and removes unused objects.
     */
    const val SAVE_OPTIONS = "garbage=deduplicate,compress,objstms"

    /**
     * Save options for an INPUT document saved after in-place edits (Compress, Add images; SA-11 /
     * SR2-4). No `garbage`: in MuPDF 1.28.5 `pdf-write.c` the only step that follows indirect
     * references is `markobj` (recursive, one native frame per link), and it runs only when
     * `do_garbage >= 1`; without it every xref entry is simply marked used, and the remaining steps
     * (object-stream unpack/gather, stream deflate, serialisation) iterate over object numbers.
     * Serialisation recurses only over DIRECT nesting, which the parser caps at the 256-slot
     * `fz_try` stack. So the save cost no longer depends on the attacker's reference depth. Price:
     * objects the input itself left unreferenced are kept (Compress overwrites image streams in
     * place, Add images only adds objects, so the ops themselves orphan nothing).
     */
    const val EDIT_SAVE_OPTIONS = "compress,objstms"

    /**
     * Opens [file] as a PDF. The caller owns the result and must `destroy()` it.
     *
     * Throws [PdfInputException] with:
     * - [PdfError.Encrypted] when the document is encrypted in any way: it needs a password, or it
     *   has only an owner password (permission restrictions, empty user password). Policy: every
     *   encrypted PDF is rejected, so no operation can strip restrictions from its output;
     * - [PdfError.Corrupt] when it cannot be parsed, is not a PDF, or has no pages;
     * - [PdfError.Io] when the file cannot be opened (missing, unreadable);
     * - [PdfError.OutOfMemory] when it is larger than [ImageLimits.MAX_INPUT_PDF_BYTES] (checked
     *   before MuPDF opens it; the user is told "Not enough memory. Try fewer or smaller files.")
     *   or has more than [ObjectGraph.MAX_OBJECTS] objects.
     * The object graph is NOT walked here (SR2-3: a walk over every reachable object costs memory
     * proportional to the references): Compress and Add images save without the recursive
     * `garbage` pass ([EDIT_SAVE_OPTIONS]), and Merge bounds what grafting touches itself
     * ([ObjectGraph.checkPages]).
     * On failure nothing opened is left alive.
     *
     * MuPDF picks the document handler by file name extension. A file whose name does not end in
     * ".pdf" is still handled: see [PdfInputsTest] — such files are opened as PDFs (MuPDF falls back
     * to content sniffing), and non-PDF content is reported as Corrupt.
     */
    fun open(file: File): PDFDocument {
        if (file.length() > ImageLimits.MAX_INPUT_PDF_BYTES) throw PdfInputException(PdfError.OutOfMemory)
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: RuntimeException) {
            throw classifyOpenFailure(e)
        }
        try {
            if (doc.needsPassword()) throw PdfInputException(PdfError.Encrypted)
            val pdf = doc as? PDFDocument ?: throw PdfInputException(PdfError.Corrupt)
            if (isEncrypted(pdf)) throw PdfInputException(PdfError.Encrypted)
            val pages = try {
                pdf.countPages()
            } catch (e: RuntimeException) {
                throw PdfInputException(PdfError.Corrupt, e)
            }
            if (pages <= 0) throw PdfInputException(PdfError.Corrupt)
            if (pdf.countObjects() > ObjectGraph.MAX_OBJECTS) throw PdfInputException(PdfError.OutOfMemory)
            return pdf
        } catch (t: Throwable) {
            doc.destroy()
            throw t
        }
    }

    /**
     * True when [pdf] uses PDF encryption, independent of password state: MuPDF reports a crypt
     * handler (`isUnencryptedPDF()` is false) or the trailer has an `/Encrypt` entry.
     */
    private fun isEncrypted(pdf: PDFDocument): Boolean {
        if (!pdf.isUnencryptedPDF) return true
        val trailer = pdf.trailer
        try {
            val encrypt = trailer.get("Encrypt")
            try {
                return !encrypt.isNull
            } finally {
                if (encrypt !== PDFObject.Null) encrypt.destroy()
            }
        } finally {
            trailer.destroy()
        }
    }

    private fun classifyOpenFailure(e: RuntimeException): PdfInputException {
        val msg = e.message.orEmpty()
        return if (msg.startsWith("cannot open")) {
            PdfInputException(PdfError.Io(e), e)
        } else {
            PdfInputException(PdfError.Corrupt, e)
        }
    }

    /**
     * Output check after a save (security audit SA-5): MuPDF can repair a damaged input and then
     * save a document whose page tree is cyclic or malformed. Reopens [out] and requires exactly
     * [expectedPages] pages, every one of which loads (each page and the document are destroyed;
     * cancellation is polled between pages). Returns null when [out] is good; otherwise deletes [out]
     * and returns `Err(Corrupt)` (or `Err(Cancelled)`).
     */
    fun validateOutput(out: File, expectedPages: Int, job: JobControl): PdfResult.Err? {
        val err = checkPages(out, expectedPages, job)
        if (err != null) out.delete()
        return err
    }

    /**
     * Null when [file] opens with exactly [expectedPages] pages and every page loads; otherwise
     * `Err(Corrupt)`, or `Err(Cancelled)` if [job] is cancelled between pages. Never modifies [file].
     * Out-of-memory failures propagate (see [isOutOfMemoryFailure]).
     */
    fun checkPages(file: File, expectedPages: Int, job: JobControl): PdfResult.Err? {
        val corrupt = PdfResult.Err(PdfError.Corrupt)
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: RuntimeException) {
            if (isOutOfMemoryFailure(e)) throw e
            return corrupt
        }
        try {
            for (i in 0 until expectedPages) {
                if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
                if (i == 0 && doc.countPages() != expectedPages) return corrupt
                doc.loadPage(i).destroy()
            }
            return if (expectedPages > 0) null else corrupt
        } catch (e: RuntimeException) {
            if (isOutOfMemoryFailure(e)) throw e
            return corrupt
        } finally {
            doc.destroy()
        }
    }

    /**
     * Runs [block], mapping a thrown [PdfInputException] to `Err(error)` and an
     * [UnsupportedImageFileException] to `Err(UnsupportedImage)`. Anything else propagates
     * (MuPdfEngine's guardedRun maps it to OutOfMemory / Io).
     */
    inline fun toResult(block: () -> PdfResult): PdfResult = try {
        block()
    } catch (e: PdfInputException) {
        PdfResult.Err(e.error)
    } catch (e: UnsupportedImageFileException) {
        PdfResult.Err(PdfError.UnsupportedImage)
    } catch (e: ImageTooLargeException) {
        PdfResult.Err(PdfError.ImageTooLarge)
    }
}
