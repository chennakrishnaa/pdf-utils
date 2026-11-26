package app.venkat.offkit.ui

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.feature.reader.ReaderPageRendered
import app.venkat.offkit.feature.reader.TAG_READER_OPEN_ANOTHER
import app.venkat.offkit.feature.reader.TAG_READER_OPENING
import app.venkat.offkit.feature.reader.TAG_READER_TITLE
import app.venkat.offkit.feature.reader.readerPageTag
import app.venkat.offkit.setOnboardingSeen
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.home.TAG_HOME_PRIVACY
import app.venkat.offkit.ui.home.TAG_TOOL_READ
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_ROOT
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_SKIP
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Open with Offkit": `ACTION_VIEW` of a `content:` PDF opens the reader on it, with no picker.
 * Anything else that reaches the exported activity lands on home (or the reader's error screen
 * for a PDF Uri that cannot be read) and the navigation host never sees the intent's data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenWithIntentTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakePdfEngine
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        RecentFilesStore.resetForTesting()
        PdfJobs.get(app)
        setOnboardingSeen(true)
        TypeProvider.reset()
    }

    @After
    fun tearDown() {
        TypeProvider.release.countDown()
        PdfReader.get(app).typeTimeoutMillis = PdfReader.TYPE_TIMEOUT_MILLIS
        controller?.let { runCatching { it.pause().stop().destroy() } }
        setOnboardingSeen(true)
        RecentFilesStore.resetForTesting()
    }

    private fun pdf(name: String): Uri = CoreTestSupport.contentUri(app, name, "%PDF-1.7\n%fake\n".toByteArray())

    private fun view(uri: Uri?, type: String? = "application/pdf"): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .setComponent(ComponentName(app, MainActivity::class.java))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun start(intent: Intent, saved: Bundle? = null): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller = c
        if (saved == null) c.setup() else c.setup(saved)
        rule.waitForIdle()
        return c.get()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntil(message: String, condition: () -> Boolean) =
        CoreTestSupport.idleUntil(rule, message = message, condition = condition)

    private fun waitForTitle(name: String) = waitUntil("reader shows $name") {
        rule.onAllNodesWithTag(TAG_READER_TITLE).fetchSemanticsNodes()
            .any { it.config[SemanticsProperties.Text].joinToString() == name }
    }

    private fun waitUntilPage1() = waitUntil("page 1 rendered") {
        rule.onAllNodesWithTag(readerPageTag(0)).fetchSemanticsNodes()
            .any { it.config.getOrElseNullable(ReaderPageRendered) { null } == true }
    }

    private fun readerDirs(): List<File> =
        File(app.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith(PdfReader.READER_DIR_PREFIX) }

    private fun assertNoPicker(activity: MainActivity) =
        assertNull("a picker was launched", shadowOf(activity).nextStartedActivityForResult)

    private fun assertScrubbed(activity: MainActivity) {
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
        assertNull(activity.intent.data)
        assertNull(activity.intent.type)
        assertNull(activity.intent.extras)
        assertNull(activity.intent.clipData)
        assertEquals(0, activity.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun assertHome() {
        rule.onNodeWithTag(TAG_HOME_PRIVACY).performScrollTo()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertDoesNotExist()
        assertTrue("nothing was opened", fake.readerCalls.isEmpty())
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun coldViewOpensTheReaderOnTheDocumentAndBackGoesHome() {
        val activity = start(view(pdf("invoice.pdf")))
        waitForTitle("invoice.pdf")
        waitUntilPage1()
        assertNoPicker(activity)
        assertScrubbed(activity)
        assertEquals(1, readerDirs().size)
        // "Open another PDF" still asks the system picker.
        rule.onNodeWithTag(TAG_READER_OPEN_ANOTHER).performClick()
        rule.waitForIdle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo()
        waitUntil("reader copy deleted") { readerDirs().isEmpty() }
        assertTrue(RecentFilesStore.get(app).recents.value.isEmpty())
    }

    /** A Uri of [TypeProvider], whose type is whatever [TypeProvider.answer] says. */
    private fun typed(name: String): Uri {
        Robolectric.setupContentProvider(TypeProvider::class.java, TypeProvider.AUTHORITY)
        return Uri.parse("content://${TypeProvider.AUTHORITY}/${System.nanoTime()}/$name")
    }

    /** The reader's error screen, with nothing copied or given to the engine. */
    private fun assertErrorScreenAndNothingCopied() {
        waitUntil("error screen") { exists(TAG_ACTION_BACK) && !exists(TAG_HOME_PRIVACY) && !exists(TAG_READER_OPENING) && !exists(readerPageTag(0)) }
        assertTrue("nothing given to the engine", fake.readerCalls.isEmpty())
        assertTrue("nothing copied", readerDirs().isEmpty())
        assertEquals("nothing read from the provider", 0, TypeProvider.opened.get())
    }

    @Test
    fun noIntentTypeAndTheProviderSaysPdfOpensOffTheMainThread() {
        TypeProvider.answer = { "application/pdf" }
        val activity = start(view(typed("untyped.pdf"), type = null))
        waitForTitle("untyped.pdf")
        waitUntilPage1()
        assertNoPicker(activity)
        assertScrubbed(activity)
        assertTrue("type asked", TypeProvider.calls.get() >= 1)
        assertEquals("provider called on the main thread", 0, TypeProvider.mainThreadCalls.get())
    }

    @Test
    fun noIntentTypeAndTheProviderSaysOtherOrNoneOrThrowsCopiesNothing() {
        val answers = listOf<Pair<String, () -> String?>>(
            "other" to { "image/png" },
            "none" to { null },
            "throws" to { throw SecurityException("no") },
        )
        for ((name, answer) in answers) {
            TypeProvider.reset()
            TypeProvider.answer = answer
            start(view(typed("$name.pdf"), type = null))
            try {
                assertErrorScreenAndNothingCopied()
                assertTrue("type asked", TypeProvider.calls.get() >= 1)
                assertEquals("provider called on the main thread", 0, TypeProvider.mainThreadCalls.get())
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
            controller!!.pause().stop().destroy()
            controller = null
        }
    }

    @Test
    fun noIntentTypeAndAStalledProviderTimesOutWithoutBlockingTheMainThread() {
        PdfReader.get(app).typeTimeoutMillis = 300
        TypeProvider.answer = {
            TypeProvider.release.await(20, TimeUnit.SECONDS)
            "application/pdf"
        }
        start(view(typed("stalled.pdf"), type = null))
        // start() returned: onCreate did not wait for the provider.
        assertErrorScreenAndNothingCopied()
        assertEquals("provider called on the main thread", 0, TypeProvider.mainThreadCalls.get())
        // Its late "PDF" answer opens nothing.
        TypeProvider.release.countDown()
        Thread.sleep(200)
        rule.waitForIdle()
        assertTrue(fake.readerCalls.isEmpty())
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun noIntentTypeAndNoProviderShowsTheErrorScreen() {
        // No type on the intent and the provider reports none either: not taken as a PDF.
        start(view(pdf("untyped.pdf"), type = null))
        waitUntil("error screen") { exists(TAG_ACTION_BACK) && !exists(TAG_HOME_PRIVACY) && !exists(readerPageTag(0)) }
        assertTrue(fake.readerCalls.isEmpty())
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun firstRunShowsTheIntroductionThenTheDocument() {
        setOnboardingSeen(false)
        val activity = start(view(pdf("first.pdf")))
        rule.onNodeWithTag(TAG_ONBOARDING_ROOT).assertExists()
        assertTrue(fake.readerCalls.isEmpty())
        rule.onNodeWithTag(TAG_ONBOARDING_SKIP).performClick()
        rule.waitForIdle()
        waitForTitle("first.pdf")
        waitUntilPage1()
        assertNoPicker(activity)
    }

    @Test
    fun viewWhileOnAToolScreenPushesTheReaderAndBackReturnsToTheTool() {
        val activity = start(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app, MainActivity::class.java)))
        // A tool's finished, unsaved output: opening a document must not touch it.
        val jobOutput = File(app.cacheDir, "jobs/0000-job/output.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        rule.onNodeWithTag("tool_compress").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(exists(TAG_ACTION_BACK))
        controller!!.newIntent(view(pdf("from-files.pdf")))
        rule.waitForIdle()
        waitForTitle("from-files.pdf")
        waitUntilPage1()
        assertNoPicker(activity)
        assertScrubbed(activity)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        waitUntil("back on the tool") { !exists(TAG_READER_TITLE) && exists(TAG_ACTION_BACK) }
        waitUntil("reader copy deleted") { readerDirs().isEmpty() }
        assertTrue("job output kept", jobOutput.isFile)
    }

    @Test
    fun viewWhileTheReaderShowsADocumentReplacesIt() {
        start(view(pdf("a.pdf")))
        waitForTitle("a.pdf")
        waitUntilPage1()
        val first = readerDirs().single()
        controller!!.newIntent(view(pdf("b.pdf")))
        rule.waitForIdle()
        waitForTitle("b.pdf")
        waitUntilPage1()
        waitUntil("only B's copy is kept") { readerDirs().size == 1 && readerDirs().single() != first }
        // Back leaves the reader (one entry), not to A.
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo()
    }

    @Test
    fun recreationDoesNotOpenTheDocumentAgain() {
        start(view(pdf("kept.pdf")))
        waitForTitle("kept.pdf")
        waitUntilPage1()
        val opens = fake.readerCalls.count { it.request == null }
        controller = controller!!.recreate()
        rule.waitForIdle()
        waitForTitle("kept.pdf")
        assertEquals(opens, fake.readerCalls.count { it.request == null })
        assertEquals(1, readerDirs().size)
    }

    @Test
    fun afterProcessDeathTheRedeliveredViewIsNotReused() {
        val intent = view(pdf("gone.pdf"))
        start(intent)
        waitForTitle("gone.pdf")
        val saved = Bundle()
        controller!!.saveInstanceState(saved).pause().stop().destroy()
        controller = null
        waitUntil("copy deleted") { readerDirs().isEmpty() }
        val opens = fake.readerCalls.size
        // The saved state goes through a Parcel, as it does across a real process death.
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            parcel.readBundle(MainActivity::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
        // The system re-delivers the original VIEW intent with the saved state; the grant is gone.
        val activity = start(intent, restored)
        rule.waitForIdle()
        // The Uri is not reused: nothing is copied or opened again, no picker the user did not ask
        // for is launched, and the restored reader (it has no document) gives way to Home.
        assertNoPicker(activity)
        waitUntil("home") { exists(TAG_HOME_PRIVACY) }
        rule.onNodeWithTag(TAG_ACTION_BACK).assertDoesNotExist()
        assertTrue(!exists(readerPageTag(0)))
        assertEquals(opens, fake.readerCalls.size)
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun aViewRelaunchedFromRecentsIsNotOpenedAgain() {
        // Back finished the task's root (or the process died) and the user picks the task in
        // Recents: the original VIEW comes back, with no saved state, flagged as from history.
        val uri = pdf("old.pdf")
        for (type in listOf("application/pdf", null)) {
            val activity = start(view(uri, type).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
            assertScrubbed(activity)
            assertNoPicker(activity)
            assertHome()
            controller!!.pause().stop().destroy()
            controller = null
        }
    }

    @Test
    fun unreadableDocumentShowsTheReadersErrorScreen() {
        // A content: Uri with no data behind it (as one without a grant): the reader's Io error.
        val activity = start(view(Uri.parse("content://other.provider/doc/secret.pdf")))
        waitUntil("error screen") { exists(TAG_ACTION_BACK) && !exists(TAG_HOME_PRIVACY) && !exists(readerPageTag(0)) }
        assertNoPicker(activity)
        assertTrue(readerDirs().isEmpty())
        assertTrue(fake.readerCalls.isEmpty())
    }

    @Test
    fun notAPdfBehindAPdfTypeShowsTheReadersErrorScreen() {
        fake.readerError = PdfError.Corrupt
        start(view(pdf("not-really.pdf")))
        waitUntil("error screen") { exists(TAG_ACTION_BACK) && !exists(readerPageTag(0)) && fake.readerCalls.isNotEmpty() }
        waitUntil("copy deleted") { readerDirs().isEmpty() }
    }

    @Test
    fun otherIntentsLandOnHome() {
        val component = ComponentName(app, MainActivity::class.java)
        val readable = pdf("readable.pdf")
        val cases = listOf(
            "wrong type" to view(readable, "image/png"),
            "wildcard type" to view(readable, "*/*"),
            "file scheme" to view(Uri.fromFile(File(app.filesDir, "x.pdf"))),
            "no data" to Intent(Intent.ACTION_VIEW).setType("application/pdf").setComponent(component),
            "nav deep link" to view(Uri.parse("android-app://androidx.navigation/reader")),
            "send" to Intent(Intent.ACTION_SEND).setType("application/pdf").setComponent(component)
                .putExtra(Intent.EXTRA_STREAM, readable),
            "edit" to Intent(Intent.ACTION_EDIT).setDataAndType(readable, "application/pdf").setComponent(component),
            "deep-link extras" to Intent(Intent.ACTION_MAIN).setComponent(component)
                .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1, 2, 3))
                .putExtra(Intent.EXTRA_STREAM, readable),
        )
        for ((name, intent) in cases) {
            val activity = start(intent)
            assertScrubbed(activity)
            assertNoPicker(activity)
            try {
                assertHome()
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
            controller!!.pause().stop().destroy()
            controller = null
        }
    }

    @Test
    fun onNewIntentIgnoresANonPdfView() {
        val activity = start(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app, MainActivity::class.java)))
        controller!!.newIntent(view(pdf("pic.png"), "image/png"))
        controller!!.newIntent(view(Uri.parse("android-app://androidx.navigation/support")))
        rule.waitForIdle()
        assertScrubbed(activity)
        assertHome()
    }

    /** A provider whose [getType] answers with [answer] and records the thread it was called on. */
    class TypeProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? {
            calls.incrementAndGet()
            if (Looper.getMainLooper().isCurrentThread) mainThreadCalls.incrementAndGet()
            return answer()
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                .apply { addRow(arrayOf<Any>(uri.lastPathSegment!!, BYTES.size.toLong())) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened.incrementAndGet()
            val file = File.createTempFile("typed", ".pdf", context!!.cacheDir).apply { writeBytes(BYTES) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { file.delete() }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "app.venkat.offkit.typetest"
            private val BYTES = "%PDF-1.7\n%fake\n".toByteArray()

            @Volatile
            var answer: () -> String? = { null }
            val calls = AtomicInteger()
            val mainThreadCalls = AtomicInteger()
            val opened = AtomicInteger()

            @Volatile
            var release = CountDownLatch(1)

            fun reset() {
                answer = { null }
                calls.set(0)
                mainThreadCalls.set(0)
                opened.set(0)
                release.countDown()
                release = CountDownLatch(1)
            }
        }
    }
}
