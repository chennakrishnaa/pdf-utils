package app.venkat.offkit.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderPrintFile
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/** P1: [PdfReader.preparePrint] with the fake engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderPreparePrintTest {
    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var engine: ExecutorCoroutineDispatcher
    private lateinit var reader: PdfReader
    private lateinit var dir: File
    private lateinit var source: File
    private val bytes = "%PDF-1.7\nencrypted bytes\n".toByteArray()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine().apply { readerPages = List(2) { PageDims(595f, 842f) } }
        (app as PdfUtilApp).engine = fake
        engine = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        reader = PdfReader(app, PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined))
        dir = File(app.cacheDir, "jobs/reader-print-test").apply { mkdirs() }
        source = File(dir, "document.pdf").apply { writeBytes(bytes) }
    }

    @After
    fun tearDown() = engine.close()

    private fun doc(password: String?, encrypted: Boolean) =
        ReaderDocument("p.pdf", List(2) { PageDims(595f, 842f) }, dir, source, password, encrypted)

    private fun printCopies() = dir.listFiles().orEmpty().filter { it.name.startsWith("print-") }

    @Test
    fun anUnencryptedDocumentPrintsItsPrivateCopyWithoutAnEngineCall() = runBlocking {
        assertEquals(ReaderPrintFile.Ok(source), reader.preparePrint(doc(null, false)))
        assertTrue(fake.decryptCalls.isEmpty())
    }

    @Test
    fun aProtectedDocumentGetsOneDecryptedCopyReusedThenDeletedOnClose() = runBlocking {
        fake.readerPassword = "secret"
        val d = doc("secret", true)
        val first = reader.preparePrint(d) as ReaderPrintFile.Ok
        assertEquals(dir, first.file.parentFile)
        assertTrue(first.file.name.matches(Regex("print-[0-9a-f-]{36}\\.pdf")))
        assertArrayEquals(FakePdfEngine.DECRYPTED_PREFIX + bytes, first.file.readBytes())
        assertEquals(listOf("secret"), fake.readerPasswords)
        assertEquals(first, reader.preparePrint(d))
        assertEquals(1, fake.decryptCalls.size)
        // The original private copy is untouched (Share sends it).
        assertArrayEquals(bytes, source.readBytes())
        reader.close(d)
        withContext(engine) {}
        assertFalse(first.file.exists())
        assertFalse(dir.exists())
        assertEquals(ReaderPrintFile.Err(PdfError.Cancelled), reader.preparePrint(d))
    }

    @Test
    fun anOwnerPasswordOnlyDocumentIsDecryptedWithoutAPassword() = runBlocking {
        for (pw in listOf(null, "")) {
            val r = reader.preparePrint(doc(pw, true))
            assertTrue(r is ReaderPrintFile.Ok)
        }
        assertEquals(listOf<String?>(null, null), fake.readerPasswords)
    }

    @Test
    fun failuresAndCloseDuringTheCopyLeaveNoFile() = runBlocking {
        fake.decryptError = PdfError.OutOfMemory
        assertEquals(ReaderPrintFile.Err(PdfError.OutOfMemory), reader.preparePrint(doc(null, true)))
        assertTrue(printCopies().isEmpty())
        fake.decryptError = null
        fake.readerPages = List(5) { PageDims(595f, 842f) } // a reply that disagrees with the document
        assertTrue((reader.preparePrint(doc(null, true)) as ReaderPrintFile.Err).error is PdfError.Io)
        assertTrue(printCopies().isEmpty())
        fake.readerPages = List(2) { PageDims(595f, 842f) }
        val d = doc(null, true)
        fake.beforeDecrypt = { reader.close(d) }
        assertEquals(ReaderPrintFile.Err(PdfError.Cancelled), reader.preparePrint(d))
        withContext(engine) {}
        assertFalse(dir.exists())
    }
}
