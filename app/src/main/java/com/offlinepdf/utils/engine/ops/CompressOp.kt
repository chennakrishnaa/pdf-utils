package com.offlinepdf.utils.engine.ops

import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import java.io.File

/**
 * Implementation of [com.offlinepdf.utils.engine.PdfEngine.compress].
 *
 * Stub: always returns [PdfError.NotImplemented] and does not touch MuPDF.
 * Uncaught-throwable mapping and deletion of a partial `out` on error are done by the
 * caller ([com.offlinepdf.utils.engine.MuPdfEngine]).
 */
class CompressOp {
    @Suppress("UNUSED_PARAMETER")
    fun run(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        PdfResult.Err(PdfError.NotImplemented)
}
