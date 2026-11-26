package app.venkat.offkit.engine.remote

import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** EngineWire encoding of the page-text request and reply. */
@RunWith(RobolectricTestRunner::class)
class PageTextWireTest {
    @Test
    fun requestRoundTripAndNegativePageRefused() {
        val r = EngineRequest.PageText("/j/in.pdf", 3, "/j/text.bin")
        assertEquals(r, EngineWire.decodeRequest(EngineWire.encodeRequest(r)))
        assertEquals(listOf("/j/in.pdf", "/j/text.bin"), r.paths)
        val bad = EngineWire.encodeRequest(r).apply { putInt("page", -1) }
        assertTrue(runCatching { EngineWire.decodeRequest(bad) }.isFailure)
        val noPage = EngineWire.encodeRequest(r).apply { remove("page") }
        assertTrue(runCatching { EngineWire.decodeRequest(noPage) }.isFailure)
    }

    @Test
    fun replyRoundTripAndMalformedReplies() {
        for (v in listOf(PdfPageText.Ok(0, false), PdfPageText.Ok(12, true), PdfPageText.Err(PdfError.Corrupt), PdfPageText.Err(PdfError.Cancelled))) {
            assertEquals(v, EngineWire.decodePageText(EngineWire.encodePageText(v)))
        }
        fun malformed(p: PdfPageText) = p is PdfPageText.Err && p.error is PdfError.Io
        val ok = EngineWire.encodePageText(PdfPageText.Ok(5, false))
        assertTrue(malformed(EngineWire.decodePageText(ok.deepCopy().apply { putInt("units", PageTextFormat.MAX_UNITS + 1) })))
        assertTrue(malformed(EngineWire.decodePageText(ok.deepCopy().apply { putInt("units", -1) })))
        assertTrue(malformed(EngineWire.decodePageText(ok.deepCopy().apply { remove("units") })))
        assertTrue(malformed(EngineWire.decodePageText(ok.deepCopy().apply { putInt("truncated", 7) })))
        assertTrue(malformed(EngineWire.decodePageText(ok.deepCopy().apply { putString("kind", "renderOk") })))
        // A render reply is not a text reply.
        assertTrue(malformed(EngineWire.decodePageText(EngineWire.encodeRender(app.venkat.offkit.engine.PdfRender.Ok(1, 1)))))
    }
}
