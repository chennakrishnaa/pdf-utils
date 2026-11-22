package com.offlinepdf.utils.engine.ops

import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import java.io.File

/**
 * Implementation of [com.offlinepdf.utils.engine.PdfEngine.merge].
 *
 * Stub: always returns [PdfError.NotImplemented] and does not touch MuPDF.
 * Uncaught-throwable mapping and deletion of a partial `out` on error are done by the
 * caller ([com.offlinepdf.utils.engine.MuPdfEngine]).
 */
class MergeOp {
    @Suppress("UNUSED_PARAMETER")
    fun run(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        PdfResult.Err(PdfError.NotImplemented)
}
