package app.venkat.offkit.reader

import android.app.Application
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.ViewRequests
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderCopyHandoff
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.core.isReadyToSave
import app.venkat.offkit.core.isSaved
import app.venkat.offkit.feature.compress.CompressViewModel
import app.venkat.offkit.feature.compress.TAG_COMPRESS_FILE
import app.venkat.offkit.feature.compress.TAG_COMPRESS_ROOT
import app.venkat.offkit.feature.reader.TAG_READER_COMPRESS
import app.venkat.offkit.feature.reader.TAG_READER_MENU
import app.venkat.offkit.feature.reader.TAG_READER_TITLE
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_JOB_OPEN
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.nav.Routes
import app.venkat.offkit.ui.nav.ToolHandoffs
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** "Compress" in the reader's menu: the hand-off to the Compress tool and the back stack around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderCompressTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var nav: NavHostController
    private lateinit var requests: ViewRequests
    private var returnsToCaller = 0
    private val bytes = ByteArray(10_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CoreTestSupport.clearFileProviderCache()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        RecentFilesStore.resetForTesting()
        requests = ViewModelProvider(rule.activity)[ViewRequests::class.java]
        rule.runOnUiThread {
            rule.activity.setContent {
                OffkitTheme {
                    nav = rememberNavController()
                    val request by requests.request.collectAsState()
                    AppNavHost(
                        navController = nav,
                        viewRequest = request,
                        onViewRequestHandled = requests::handled,
                        onOpenDocument = { requests.post(it) },
                        onReturnToCaller = { returnsToCaller++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() = RecentFilesStore.resetForTesting()

    private fun pdf(name: String): Uri = CoreTestSupport.contentUri(app, name, bytes)

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntil(message: String, condition: () -> Boolean) = CoreTestSupport.idleUntil(rule, message = message, condition = condition)

    private fun title(): String? = rule.onAllNodesWithTag(TAG_READER_TITLE).fetchSemanticsNodes()
        .singleOrNull()?.config?.get(SemanticsProperties.Text)?.joinToString()

    private fun routes() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    private fun readerDirs(): List<File> =
        File(app.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith(PdfReader.READER_DIR_PREFIX) }

    private fun openReader(name: String, fromOtherApp: Boolean = false) {
        rule.runOnUiThread { requests.post(pdf(name), fromOtherApp = fromOtherApp) }
        waitUntil("reader shows $name") { title() == name }
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun compressFromMenu(): CompressViewModel {
        click(TAG_READER_MENU)
        click(TAG_READER_COMPRESS)
        waitUntil("compress on top") { nav.currentDestination?.route == Routes.COMPRESS && exists(TAG_COMPRESS_ROOT) }
        val vm = ViewModelProvider(nav.currentBackStackEntry!!)[CompressViewModel::class.java]
        waitUntil("file chosen") { vm.file.value != null }
        rule.waitForIdle()
        return vm
    }

    private fun systemBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    @Test
    fun compressOpensWithTheDocumentChosenAndCompressesTheReadersCopy() {
        openReader("report.pdf")
        val vm = compressFromMenu()
        val file = vm.file.value!!
        assertEquals("report.pdf", file.name)
        assertEquals(10_000L, file.sizeBytes)
        assertEquals(Uri.EMPTY, file.uri)
        rule.onNodeWithTag(TAG_COMPRESS_FILE).assertExists()
        assertEquals("report-compressed.pdf", vm.suggestedFileName())
        click(TAG_JOB_RUN)
        waitUntil("ready") { vm.jobState.value.isReadyToSave }
        val call = fake.callsFor(FakePdfEngine.Op.COMPRESS).single()
        assertTrue("the job's own copy", !call.pdf!!.parentFile!!.name.startsWith(PdfReader.READER_DIR_PREFIX))
        // The reader's copy is untouched and still there.
        assertEquals(1, readerDirs().size)
        assertTrue(bytes.contentEquals(File(readerDirs().single(), readerDirs().single().list()!!.first { it.endsWith(".pdf") }).readBytes()))
    }

    @Test
    fun backFromCompressShowsTheReaderWithItsDocument() {
        openReader("report.pdf")
        compressFromMenu()
        assertEquals(listOf(Routes.HOME, Routes.READER, Routes.COMPRESS), routes())
        click(TAG_ACTION_BACK)
        waitUntil("reader back") { nav.currentDestination?.route == Routes.READER && title() == "report.pdf" }
        assertEquals(1, readerDirs().size)
        // The hand-off was consumed: a later Compress from home starts empty.
        assertNull(ViewModelProvider(rule.activity)[ToolHandoffs::class.java].take())
    }

    @Test
    fun anOpenedResultPushesASecondReaderAndBackBackReturnsToTheFirstWithItsCopy() {
        openReader("report.pdf")
        val firstDir = readerDirs().single()
        val vm = compressFromMenu()
        click(TAG_JOB_RUN)
        waitUntil("ready") { vm.jobState.value.isReadyToSave }
        val sink = ByteArrayOutputStream()
        val dest = CoreTestSupport.saveDestination(app, sink, "report-compressed.pdf")
        rule.runOnUiThread { vm.save(dest) }
        waitUntil("saved") { vm.jobState.value.isSaved }
        // The test provider only takes writes: serve the saved bytes back for the second reader.
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(dest, java.io.ByteArrayInputStream(sink.toByteArray()))
        click(TAG_JOB_OPEN)
        try {
            waitUntil("second reader") { nav.currentDestination?.route == Routes.READER && title() == "report-compressed.pdf" }
        } catch (e: AssertionError) {
            throw AssertionError("routes=${routes()} title=${title()} dirs=${readerDirs()}", e)
        }
        assertEquals(listOf(Routes.HOME, Routes.READER, Routes.COMPRESS, Routes.READER), routes())
        assertEquals(2, readerDirs().size)
        click(TAG_ACTION_BACK)
        waitUntil("compress again") { nav.currentDestination?.route == Routes.COMPRESS }
        waitUntil("second copy deleted") { readerDirs().size == 1 }
        systemBack() // the saved result screen has Close, not Back; a saved result leaves at once
        waitUntil("first reader") { nav.currentDestination?.route == Routes.READER && title() == "report.pdf" }
        assertEquals(listOf(firstDir), readerDirs())
        assertTrue(firstDir.isDirectory)
    }

    @Test
    fun compressFromAReaderOpenedByAnotherAppReturnsToTheReaderThenToThatApp() {
        openReader("chat.pdf", fromOtherApp = true)
        compressFromMenu()
        systemBack()
        waitUntil("reader back") { nav.currentDestination?.route == Routes.READER && title() == "chat.pdf" }
        assertEquals(0, returnsToCaller)
        systemBack()
        assertEquals(1, returnsToCaller)
        waitUntil("reader popped") { nav.currentDestination?.route == Routes.HOME }
    }

    @Test
    fun backFromAReaderOfAnotherAppOverAnUnsavedResultPopsOnlyThatReader() {
        openReader("report.pdf")
        val vm = compressFromMenu()
        click(TAG_JOB_RUN)
        waitUntil("ready") { vm.jobState.value.isReadyToSave }
        openReader("chat.pdf", fromOtherApp = true)
        assertEquals(listOf(Routes.HOME, Routes.READER, Routes.COMPRESS, Routes.READER), routes())
        systemBack()
        // Through the exit transition, where the leaving reader is still composed and empty.
        try {
            waitUntil("compress on top") { nav.currentDestination?.route == Routes.COMPRESS }
        } catch (e: AssertionError) {
            throw AssertionError("routes=${routes()} returns=$returnsToCaller", e)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.READER, Routes.COMPRESS), routes())
        assertTrue("the unsaved result is kept", vm.jobState.value.isReadyToSave)
        assertEquals(1, returnsToCaller)
        assertEquals(1, readerDirs().size)
    }

    @Test
    fun theHandOffIsTakenOnce() {
        val holder = ToolHandoffs()
        val h = ReaderCopyHandoff(File("x"), "x.pdf")
        holder.post(h)
        assertEquals(h, holder.take())
        assertNull(holder.take())
    }
}
