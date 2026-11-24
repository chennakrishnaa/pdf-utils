package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.Parcel
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

/** Round-trips of every wire type, through a real Parcel as binder would do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineWireTest {
    private fun viaParcel(b: Bundle): Bundle {
        val p = Parcel.obtain()
        try {
            p.writeBundle(b)
            p.setDataPosition(0)
            return p.readBundle(javaClass.classLoader)!!
        } finally {
            p.recycle()
        }
    }

    private val exactErrors = listOf(
        PdfError.Encrypted,
        PdfError.Corrupt,
        PdfError.UnsupportedImage,
        PdfError.OutOfMemory,
        PdfError.ImageTooLarge,
        PdfError.Cancelled,
        PdfError.NotImplemented,
        PdfError.EngineCrashed,
    )

    @Test
    fun everyResultRoundTrips() {
        val all = listOf<PdfResult>(PdfResult.Ok(3, 12345L), PdfResult.Ok(0, Long.MAX_VALUE)) +
            exactErrors.map { PdfResult.Err(it) }
        for (r in all) assertEquals(r, EngineWire.decodeResult(viaParcel(EngineWire.encodeResult(r))))
    }

    @Test
    fun everyEstimateRoundTrips() {
        val all = listOf<PdfEstimate>(
            PdfEstimate.Ok(1000, CompressionLevel.entries.associateWith { it.ordinal * 100L + 1 }),
            PdfEstimate.Ok(5, emptyMap()),
            PdfEstimate.Ok(7, mapOf(CompressionLevel.MEDIUM to 3L)),
        ) + exactErrors.map { PdfEstimate.Err(it) }
        for (e in all) assertEquals(e, EngineWire.decodeEstimate(viaParcel(EngineWire.encodeEstimate(e))))
    }

    @Test
    fun ioCarriesClassAndMessage() {
        val r = EngineWire.decodeResult(viaParcel(EngineWire.encodeResult(PdfResult.Err(PdfError.Io(FileNotFoundException("no such file"))))))
        val cause = ((r as PdfResult.Err).error as PdfError.Io).cause
        assertTrue(cause is IOException)
        assertEquals("java.io.FileNotFoundException: no such file", cause.message)

        val e = EngineWire.decodeEstimate(viaParcel(EngineWire.encodeEstimate(PdfEstimate.Err(PdfError.Io(IllegalStateException())))))
        val c2 = ((e as PdfEstimate.Err).error as PdfError.Io).cause
        assertTrue(c2 is IOException)
        assertEquals("java.lang.IllegalStateException", c2.message)
    }

    @Test
    fun everyRequestRoundTrips() {
        val all = listOf(
            EngineRequest.ImagesToPdf(listOf("/a", "/b"), PageSize.LETTER, "/o", null),
            EngineRequest.ImagesToPdf(listOf("/a"), PageSize.FIT_IMAGE, "/o", CompressionLevel.HIGH),
            EngineRequest.Estimate("/img"),
            EngineRequest.Compress("/p", CompressionLevel.MEDIUM, "/o"),
            EngineRequest.Merge(listOf("/1", "/2", "/3"), "/o"),
            EngineRequest.AppendImages("/p", listOf("/i"), "/o"),
        )
        for (r in all) assertEquals(r, EngineWire.decodeRequest(viaParcel(EngineWire.encodeRequest(r))))
    }

    @Test
    fun malformedReplyIsIoNotCrash() {
        val r = EngineWire.decodeResult(Bundle())
        assertTrue((r as PdfResult.Err).error is PdfError.Io)
        val e = EngineWire.decodeEstimate(Bundle().apply { putString("kind", "err"); putString("error", "Nope") })
        assertTrue((e as PdfEstimate.Err).error is PdfError.Io)
    }
}
