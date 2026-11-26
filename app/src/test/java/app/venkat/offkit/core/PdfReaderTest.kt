package app.venkat.offkit.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import java.io.FileNotFoundException
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfReaderTest {
    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var engineDispatcher: ExecutorCoroutineDispatcher
    private lateinit var reader: PdfReader

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        engineDispatcher = Executors.newSingleThreadExecutor { Thread(it, ENGINE_THREAD) }.asCoroutineDispatcher()
        reader = PdfReader(app, PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main))
    }

    @After
    fun tearDown() {
        engineDispatcher.close()
    }

    private fun pdfUri(name: String = "book.pdf") = CoreTestSupport.contentUri(app, name, "%PDF-1.7 test".toByteArray())

    private fun readerDirs() = File(app.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith("reader-") }

    private fun drain() = runBlocking { kotlinx.coroutines.withContext(engineDispatcher) {} }

    @Test
    fun passwordRetryKeepsOneCopyAndSendsThePasswordWithEveryCall() = runBlocking {
        fake.readerPassword = "pw1"
        fake.readerCanCopy = false
        val first = reader.open(pdfUri(), keepLockedCopy = true) as ReaderOpenResult.Failed
        assertEquals(PdfError.Encrypted, first.error)
        val locked = requireNotNull(first.locked)
        assertEquals(1, readerDirs().size)
        val copy = locked.file.readBytes()
        val wrong = reader.unlock(locked, "nope") as ReaderOpenResult.Failed
        assertEquals(PdfError.WrongPassword, wrong.error)
        assertTrue(wrong.locked === locked)
        val doc = (reader.unlock(locked, "pw1") as ReaderOpenResult.Opened).document
        assertEquals(1, readerDirs().size) // never copied again
        assertTrue(copy.contentEquals(doc.file.readBytes()))
        assertTrue(doc.isEncrypted && !doc.canCopy && doc.canPrint)
        assertFalse(doc.toString().contains("pw1"))
        reader.render(doc, RenderRequest(0, 1f, 0, 0, 10, 10))
        reader.text(doc, 0)
        assertEquals(listOf(null, "nope", "pw1", "pw1", "pw1"), fake.readerPasswords.toList())
        reader.close(doc)
        drain()
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun givingUpDeletesTheLockedCopy() = runBlocking {
        fake.readerPassword = "pw1"
        val locked = requireNotNull((reader.open(pdfUri(), keepLockedCopy = true) as ReaderOpenResult.Failed).locked)
        assertEquals(1, readerDirs().size)
        reader.discard(locked)
        drain()
        assertTrue(readerDirs().isEmpty())
        assertEquals(ReaderOpenResult.Failed(PdfError.Cancelled), reader.unlock(locked, "pw1"))
        // Without keepLockedCopy nothing is kept, as before.
        assertEquals(ReaderOpenResult.Failed(PdfError.Encrypted), reader.open(pdfUri()))
        drain()
        assertTrue(readerDirs().isEmpty())
    }

    private fun openOk(uri: Uri = pdfUri()): ReaderDocument = when (val r = runBlocking { reader.open(uri) }) {
        is ReaderOpenResult.Opened -> r.document
        is ReaderOpenResult.Failed -> throw AssertionError("open failed: ${r.error}")
    }

    @Test
    fun openCopiesIntoReaderDirAndReportsNameAndPages() {
        fake.readerPages = listOf(PageDims(595f, 842f), PageDims(842f, 595f))
        val doc = openOk(pdfUri("Report 2026.pdf"))
        assertEquals("Report 2026.pdf", doc.name)
        assertEquals(fake.readerPages, doc.pages)
        val dirs = readerDirs()
        assertEquals(1, dirs.size)
        assertEquals(dirs.single().canonicalFile, doc.dir.canonicalFile)
        assertEquals("%PDF-1.7 test", doc.file.readText())
        assertEquals(doc.file, fake.readerCalls.single().pdf)
    }

    @Test
    fun encryptedAndCorruptFailAndLeaveNothing() {
        for (error in listOf(PdfError.Encrypted, PdfError.Corrupt)) {
            fake.readerError = error
            assertEquals(ReaderOpenResult.Failed(error), runBlocking { reader.open(pdfUri()) })
            assertTrue(readerDirs().isEmpty())
        }
    }

    @Test
    fun noPagesIsCorrupt() {
        fake.readerPages = emptyList()
        assertEquals(ReaderOpenResult.Failed(PdfError.Corrupt), runBlocking { reader.open(pdfUri()) })
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun unreadableOrNonContentUriIsIo() {
        val missing = Uri.parse("content://app.venkat.offkit.test/missing/x.pdf")
        val r1 = runBlocking { reader.open(missing) }
        assertTrue(r1.toString(), r1 is ReaderOpenResult.Failed && r1.error is PdfError.Io)
        val r2 = runBlocking { reader.open(Uri.fromFile(File(app.cacheDir, "x.pdf"))) }
        assertTrue(r2.toString(), r2 is ReaderOpenResult.Failed && r2.error is PdfError.Io)
        assertTrue(readerDirs().isEmpty())
        assertTrue("engine never called", fake.readerCalls.isEmpty())
    }

    @Test
    fun closeDeletesTheDirectory() {
        val doc = openOk()
        assertTrue(doc.dir.isDirectory)
        reader.close(doc)
        CoreTestSupport.idleUntil(message = "reader dir deleted") { !doc.dir.exists() }
        assertEquals(ReaderTile.Err(PdfError.Cancelled), runBlocking { reader.render(doc, RenderRequest(0, 1f, 0, 0, 10, 10)) })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tileBecomesBitmapWithEngineColourAndFileIsDeleted() {
        val doc = openOk()
        for (page in 0..2) {
            val tile = runBlocking { reader.render(doc, RenderRequest(page, 0.5f, 3, 4, 40, 30)) }
            val bitmap = (tile as ReaderTile.Ok).bitmap
            assertEquals(40, bitmap.width)
            assertEquals(30, bitmap.height)
            // The tile's bytes land in the bitmap unchanged (copyPixelsFromBuffer, no conversion).
            val raw = java.nio.ByteBuffer.allocate(40 * 30 * 4)
            bitmap.copyPixelsToBuffer(raw)
            val argb = fake.readerColor(page)
            val rgba = byteArrayOf((argb shr 16).toByte(), (argb shr 8).toByte(), argb.toByte(), 0xFF.toByte())
            for (i in 0 until 40 * 30) for (c in 0..3) assertEquals(rgba[c], raw.get(i * 4 + c))
            // On Android, ARGB_8888 memory is R, G, B, A, so the colour reads back exactly. The
            // Robolectric host's native Skia may store B, G, R, A instead: then it reads back swapped.
            val expected = if (hostStoresRgba()) argb else swapRedBlue(argb)
            assertEquals(expected, bitmap.getPixel(0, 0))
            assertEquals(expected, bitmap.getPixel(39, 29))
        }
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
    }

    @Test
    fun invalidRequestNeverReachesTheEngine() {
        val doc = openOk()
        val calls = fake.readerCalls.size
        val tile = runBlocking { reader.render(doc, RenderRequest(0, 1f, 0, 0, 596, 10)) }
        assertTrue(tile.toString(), tile is ReaderTile.Err && tile.error is PdfError.Io)
        assertEquals(calls, fake.readerCalls.size)
    }

    @Test
    fun wrongLengthTileIsRejectedAndDeleted() {
        val doc = openOk()
        // An engine that claims success but writes one byte too many.
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                val r = fake.renderPage(pdf, request, out, job)
                out.appendBytes(byteArrayOf(0))
                return r
            }
        }
        val tile = runBlocking { reader.render(doc, RenderRequest(0, 0.5f, 0, 0, 20, 20)) }
        assertTrue(tile.toString(), tile is ReaderTile.Err && tile.error is PdfError.Io)
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
    }

    @Test
    fun wrongReplySizeIsRejected() {
        val doc = openOk()
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                val r = fake.renderPage(pdf, request.copy(width = request.width - 1), out, job)
                return r
            }
        }
        val tile = runBlocking { reader.render(doc, RenderRequest(0, 0.5f, 0, 0, 20, 20)) }
        assertTrue(tile.toString(), tile is ReaderTile.Err && tile.error is PdfError.Io)
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
    }

    @Test
    fun tileToBitmapChecksLength() {
        val f = File(app.cacheDir, "t.rgba")
        f.writeBytes(ByteArray(4 * 4 * 4 - 1))
        try {
            PdfReader.tileToBitmap(f, 4, 4)
            fail("short tile accepted")
        } catch (e: IOException) {
            // Expected.
        }
        f.writeBytes(ByteArray(4 * 4 * 4 + 4))
        try {
            PdfReader.tileToBitmap(f, 4, 4)
            fail("long tile accepted")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun everyEngineCallRunsOnTheEngineThread() {
        val threads = CopyOnWriteArrayList<String>()
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo {
                threads += Thread.currentThread().name.substringBefore(" @")
                return fake.pageInfo(pdf, job)
            }

            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                threads += Thread.currentThread().name.substringBefore(" @")
                return fake.renderPage(pdf, request, out, job)
            }
        }
        val doc = openOk()
        runBlocking { reader.render(doc, RenderRequest(0, 0.1f, 0, 0, 10, 10)) }
        assertEquals(listOf(ENGINE_THREAD, ENGINE_THREAD), threads.toList())
    }

    @Test
    fun uiSourcesNeverDecodeImages() {
        val roots = listOf("src/main/java/app/venkat/offkit", "app/src/main/java/app/venkat/offkit")
            .map(::File).first { it.isDirectory }
        val scanned = listOf(File(roots, "core/PdfReader.kt")) +
            File(roots, "feature/reader").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(scanned.size >= 2)
        val forbidden = listOf("Bitmap" + "Factory", "Image" + "Decoder", "takePersistable" + "UriPermission")
        for (file in scanned) {
            val text = file.readText()
            for (word in forbidden) assertFalse("${file.name} uses $word", text.contains(word))
        }
    }

    /** True if this runtime's ARGB_8888 memory layout is R, G, B, A (always on Android devices). */
    private fun hostStoresRgba(): Boolean {
        val probe = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        probe.setPixel(0, 0, 0xFFFF0000.toInt())
        val b = java.nio.ByteBuffer.allocate(4)
        probe.copyPixelsToBuffer(b)
        return b.get(0) == 0xFF.toByte()
    }

    private fun sharedRoot() = File(app.cacheDir, "shared")

    @Test
    fun prepareShareCopiesTheExactBytesUnderTheSanitisedNameAndLeavesOtherSharedCopies() = runBlocking {
        CoreTestSupport.clearFileProviderCache()
        // A job result's shared copy that an app may still be reading.
        val jobShare = File(sharedRoot(), "job-id/result.pdf").apply { parentFile!!.mkdirs(); writeText("job") }
        val bytes = "%PDF-1.7 reader share \u0000ÿ".toByteArray(Charsets.ISO_8859_1)
        val uri = CoreTestSupport.contentUri(app, "a/b:report", bytes)
        val doc = (reader.open(uri) as ReaderOpenResult.Opened).document
        val progress = CopyOnWriteArrayList<Pair<Long, Long>>()
        val request = reader.prepareShare(doc) { done, total -> progress += done to total }
        assertEquals(PdfJobs.shareFileName(doc.name), request.name)
        assertTrue(request.name.endsWith(".pdf") && !request.name.contains('/'))
        assertEquals("content", request.uri.scheme)
        assertEquals(PdfJobs.shareAuthority(app), request.uri.authority)
        val copy = sharedRoot().walkTopDown().single { it.isFile && it.name == request.name }
        assertTrue(bytes.contentEquals(copy.readBytes()))
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
        assertEquals("job", jobShare.readText())
        // Sharing again reuses the copy.
        assertEquals(request, reader.prepareShare(doc))
        assertEquals(1, sharedRoot().walkTopDown().count { it.isFile && it.name == request.name })
        reader.close(doc)
        drain()
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun aClosedDocumentCannotBeSharedButAnOpenSourceStillReadsAndTheFolderIsDeleted() = runBlocking {
        val doc = (reader.open(pdfUri()) as ReaderOpenResult.Opened).document
        val source = reader.openSource(doc)
        reader.close(doc)
        drain()
        assertTrue(readerDirs().isEmpty())
        // Opened before the close: still reads the whole private copy.
        val buf = java.nio.ByteBuffer.allocate(64)
        source.use { it.read(buf, 0) }
        assertEquals("%PDF-1.7 test", String(buf.array(), 0, buf.position()))
        try {
            reader.prepareShare(doc)
            fail("closed document shared")
        } catch (e: IOException) {
            // Expected.
        }
        try {
            reader.openSource(doc).close()
            fail("closed document opened")
        } catch (e: IOException) {
            // Expected.
        }
    }

    private fun swapRedBlue(argb: Int): Int =
        (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)

    private companion object {
        const val ENGINE_THREAD = "test-engine"
    }

    /** RDR-6: a PDF larger than the engine's input limit fails as OutOfMemory, before or during the copy. */
    @Test
    fun fileOverTheInputLimitIsOutOfMemoryAndLeavesNothing() {
        val jobs = PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main)
        val small = PdfReader(app, jobs, maxInputBytes = 8)
        // The test provider reports no size: the copy stops once past the limit.
        assertEquals(ReaderOpenResult.Failed(PdfError.OutOfMemory), runBlocking { small.open(pdfUri()) })
        assertTrue(readerDirs().isEmpty())
        assertTrue(fake.readerCalls.isEmpty())

        // A provider that reports the size: refused before its stream is opened.
        Robolectric.setupContentProvider(SizedProvider::class.java, SizedProvider.AUTHORITY)
        SizedProvider.opened.set(0)
        val sized = Uri.parse("content://${SizedProvider.AUTHORITY}/big.pdf")
        assertEquals(ReaderOpenResult.Failed(PdfError.OutOfMemory), runBlocking { small.open(sized) })
        assertEquals(0, SizedProvider.opened.get())
        assertTrue(readerDirs().isEmpty())

        // Exactly at the limit is fine ("%PDF-1.7 test" is 13 bytes).
        val atLimit = runBlocking { PdfReader(app, jobs, maxInputBytes = 13).open(pdfUri()) }
        assertTrue(atLimit is ReaderOpenResult.Opened)
    }

    @Test
    fun hostilePageSizesAreClampedOrCorrupt() {
        fake.readerPages = listOf(PageDims(Float.MAX_VALUE, 0.001f), PageDims(595f, 842f))
        assertEquals(listOf(PageDims(PdfReader.MAX_PAGE_PT, PdfReader.MIN_PAGE_PT), PageDims(595f, 842f)), openOk().pages)
        fake.readerPages = listOf(PageDims(595f, Float.NaN))
        assertEquals(ReaderOpenResult.Failed(PdfError.Corrupt), runBlocking { reader.open(pdfUri()) })
    }

    private fun canned(): PageText {
        val boxes = FloatArray(8 * 4) { i -> if (i % 4 < 2) 10f + i else 20f + i }
        return PageTextFormat.build("Hi there", boxes, intArrayOf(0), intArrayOf(0), false)
    }

    @Test
    fun textReturnsTheCannedPageTextAndDeletesTheFile() {
        val doc = openOk()
        fake.readerTexts[1] = canned()
        val r = runBlocking { reader.text(doc, 1) }
        assertTrue(r.toString(), r is ReaderText.Ok)
        assertEquals("Hi there", (r as ReaderText.Ok).text.text)
        assertEquals("there", r.text.string(r.text.wordRange(4)))
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
        val empty = runBlocking { reader.text(doc, 0) }
        assertEquals(0, (empty as ReaderText.Ok).text.length)
        val past = runBlocking { reader.text(doc, 7) }
        assertTrue(past is ReaderText.Err && past.error is PdfError.Io)
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
    }

    @Test
    fun textReplyThatDisagreesWithItsFileIsIoAndDeleted() {
        val doc = openOk()
        fake.readerTexts[0] = canned()
        val lies = listOf<(PdfPageText.Ok) -> PdfPageText>({ it.copy(units = it.units + 1) }, { it.copy(truncated = true) })
        for (lie in lies) {
            (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
                override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText =
                    lie(fake.pageText(pdf, page, out, job) as PdfPageText.Ok)
            }
            val r = runBlocking { reader.text(doc, 0) }
            assertTrue(r.toString(), r is ReaderText.Err && r.error is PdfError.Io)
            assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
        }
        // A garbage file is Io too.
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText {
                out.writeText("not a text file at all, really")
                return PdfPageText.Ok(0, false)
            }
        }
        val g = runBlocking { reader.text(doc, 0) }
        assertTrue(g.toString(), g is ReaderText.Err && g.error is PdfError.Io)
        assertEquals(listOf(doc.file), doc.dir.listFiles()!!.toList())
    }

    @Test
    fun closingDuringARenderAndATextCallNeverRacesTheFolderDeletion() {
        // close() deletes the folder on the engine thread while the render's and the text call's
        // cleanup run on the IO dispatcher; two concurrent deleteRecursively walks of one folder
        // trip FileTreeWalk's assert (enabled with -ea), on whichever thread loses.
        val errors = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        try {
            fake.readerTexts[0] = canned()
            repeat(100) { round ->
                val doc = openOk()
                val inRender = CountDownLatch(1)
                val go = CountDownLatch(1)
                fake.beforeRender = {
                    inRender.countDown()
                    go.await(5, TimeUnit.SECONDS)
                }
                runBlocking {
                    val tile = async(Dispatchers.IO) { reader.render(doc, RenderRequest(0, 0.1f, 0, 0, 10, 10)) }
                    assertTrue(inRender.await(5, TimeUnit.SECONDS))
                    val text = async(Dispatchers.IO) { reader.text(doc, 0) }
                    reader.close(doc)
                    go.countDown()
                    tile.await()
                    text.await()
                }
                fake.beforeRender = null
                val deadline = System.nanoTime() + 5_000_000_000L
                while (doc.dir.exists() && System.nanoTime() < deadline) Thread.sleep(2)
                assertFalse("round $round: folder left behind", doc.dir.exists())
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun textAfterCloseIsCancelledAndLeavesNothing() {
        val doc = openOk()
        reader.close(doc)
        assertEquals(ReaderText.Err(PdfError.Cancelled), runBlocking { reader.text(doc, 0) })
        Thread.sleep(200)
        assertTrue(!doc.dir.exists())
        assertTrue(fake.textCalls.isEmpty())
    }

    /** Reports a 100-byte `big.pdf` and counts attempts to open it (which fail). */
    class SizedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("big.pdf", 100L)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened.incrementAndGet()
            throw FileNotFoundException(uri.toString())
        }
        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "app.venkat.offkit.sizedtest"
            val opened = java.util.concurrent.atomic.AtomicInteger()
        }
    }
}
