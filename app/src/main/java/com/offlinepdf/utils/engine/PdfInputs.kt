package com.offlinepdf.utils.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

/** An input PDF could not be used; [error] is the classification to report. */
class PdfInputException(val error: PdfError, cause: Throwable? = null) :
    RuntimeException("unusable input PDF: $error", cause)

/** Opening and classifying input PDFs, and the error mapping shared by all ops. */
internal object PdfInputs {
    /** Save options for every PDF the engine writes. */
    const val SAVE_OPTIONS = "garbage=deduplicate,compress,objstms"

    /**
     * Opens [file] as a PDF. The caller owns the result and must `destroy()` it.
     *
     * Throws [PdfInputException] with:
     * - [PdfError.Encrypted] when the document needs a password;
     * - [PdfError.Corrupt] when it cannot be parsed, is not a PDF, or has no pages;
     * - [PdfError.Io] when the file cannot be opened (missing, unreadable).
     * On failure nothing opened is left alive.
     *
     * MuPDF picks the document handler by file name extension. A file whose name does not end in
     * ".pdf" is still handled: see [PdfInputsTest] — such files are opened as PDFs (MuPDF falls back
     * to content sniffing), and non-PDF content is reported as Corrupt.
     */
    fun open(file: File): PDFDocument {
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: RuntimeException) {
            throw classifyOpenFailure(e)
        }
        try {
            if (doc.needsPassword()) throw PdfInputException(PdfError.Encrypted)
            val pdf = doc as? PDFDocument ?: throw PdfInputException(PdfError.Corrupt)
            val pages = try {
                pdf.countPages()
            } catch (e: RuntimeException) {
                throw PdfInputException(PdfError.Corrupt, e)
            }
            if (pages <= 0) throw PdfInputException(PdfError.Corrupt)
            return pdf
        } catch (t: Throwable) {
            doc.destroy()
            throw t
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
    }
}
