package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.Parcel
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        PdfError.EngineUnavailable,
        PdfError.TimedOut,
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
            EngineRequest.PageInfo("/p"),
            EngineRequest.RenderPage("/p", RenderRequest(4, 2.5f, 10, 20, 300, 400), "/o"),
            EngineRequest.RenderPage("/p", RenderRequest(0, RenderLimits.MAX_SCALE, 0, 0, 4_000, 2_000), "/o"),
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

    // ------------------------------------------------ reader

    private val render = EngineRequest.RenderPage("/p", RenderRequest(1, 1.5f, 2, 3, 40, 50), "/o")

    private fun rejected(b: Bundle): String? = try {
        EngineWire.decodeRequest(viaParcel(b))
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "rejected"
    }

    @Test
    fun malformedOrOverLimitRenderRequestIsRejected() {
        for (key in listOf("pdf", "out", "page", "scale", "left", "top", "width", "height")) {
            val b = EngineWire.encodeRequest(render).apply { remove(key) }
            if (rejected(b) == null) fail("accepted without $key")
        }
        val over = listOf(
            RenderRequest(-1, 1f, 0, 0, 1, 1),
            RenderRequest(0, Float.NaN, 0, 0, 1, 1),
            RenderRequest(0, Float.POSITIVE_INFINITY, 0, 0, 1, 1),
            RenderRequest(0, 0f, 0, 0, 1, 1),
            RenderRequest(0, 17f, 0, 0, 1, 1),
            RenderRequest(0, 1f, -1, 0, 1, 1),
            RenderRequest(0, 1f, 0, 0, 0, 1),
            RenderRequest(0, 1f, 0, 0, 65_536, 65_536),
            RenderRequest(0, 1f, 0, 0, 4_000, 2_001),
        )
        for (r in over) {
            if (rejected(EngineWire.encodeRequest(render.copy(request = r))) == null) fail("accepted $r")
        }
        if (rejected(EngineWire.encodeRequest(EngineRequest.PageInfo("/p")).apply { remove("pdf") }) == null) {
            fail("accepted page info without pdf")
        }
    }

    @Test
    fun pageInfoAndRenderRepliesRoundTrip() {
        val infos = listOf<PdfPageInfo>(
            PdfPageInfo.Ok(listOf(PageDims(595f, 842f), PageDims(842f, 595f), PageDims(0.5f, 14_400f))),
            PdfPageInfo.Ok(List(RenderLimits.MAX_PAGES) { PageDims(612f, 792f) }),
        ) + exactErrors.map { PdfPageInfo.Err(it) }
        for (p in infos) assertEquals(p, EngineWire.decodePageInfo(viaParcel(EngineWire.encodePageInfo(p))))
        val renders = listOf<PdfRender>(PdfRender.Ok(256, 512)) + exactErrors.map { PdfRender.Err(it) }
        for (r in renders) assertEquals(r, EngineWire.decodeRender(viaParcel(EngineWire.encodeRender(r))))
        val io = EngineWire.decodeRender(viaParcel(EngineWire.encodeRender(PdfRender.Err(PdfError.Io(IllegalArgumentException("bad"))))))
        assertEquals("java.lang.IllegalArgumentException: bad", ((io as PdfRender.Err).error as PdfError.Io).cause.message)
        // A generic error reply (what the server sends when the request did not decode) is understood.
        val generic = EngineWire.encodeResult(PdfResult.Err(PdfError.Corrupt))
        assertEquals(PdfPageInfo.Err(PdfError.Corrupt), EngineWire.decodePageInfo(generic))
        assertEquals(PdfRender.Err(PdfError.Corrupt), EngineWire.decodeRender(generic))
    }

    private fun isIo(p: PdfPageInfo) = assertTrue("$p", (p as PdfPageInfo.Err).error is PdfError.Io)

    @Test
    fun malformedOrOverLimitPageInfoReplyIsIo() {
        fun info(sizes: FloatArray?) = Bundle().apply { putString("kind", "infoOk"); sizes?.let { putFloatArray("sizes", it) } }
        isIo(EngineWire.decodePageInfo(viaParcel(info(null))))
        isIo(EngineWire.decodePageInfo(viaParcel(info(floatArrayOf(595f, 842f, 595f)))))
        isIo(EngineWire.decodePageInfo(viaParcel(info(FloatArray((RenderLimits.MAX_PAGES + 1) * 2) { 100f }))))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, -0f)) {
            isIo(EngineWire.decodePageInfo(viaParcel(info(floatArrayOf(595f, 842f, 100f, bad)))))
        }
        isIo(EngineWire.decodePageInfo(Bundle()))
        isIo(EngineWire.decodePageInfo(EngineWire.encodeRender(PdfRender.Ok(1, 1)))) // wrong reply type
        val r1 = EngineWire.decodeRender(Bundle().apply { putString("kind", "renderOk"); putInt("width", 5) })
        assertTrue("$r1", (r1 as PdfRender.Err).error is PdfError.Io)
        val r2 = EngineWire.decodeRender(EngineWire.encodePageInfo(PdfPageInfo.Ok(listOf(PageDims(1f, 1f)))))
        assertTrue("$r2", (r2 as PdfRender.Err).error is PdfError.Io)
    }
}
