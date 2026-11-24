package app.venkat.offkit.addimages

import app.venkat.offkit.core.isReadyToSave
import app.venkat.offkit.core.isSaved
import app.venkat.offkit.core.isSavingNow
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.core.UriIoTest
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.feature.addimages.AddImagesScreen
import app.venkat.offkit.feature.addimages.AddImagesViewModel
import app.venkat.offkit.feature.addimages.TAG_ADDIMAGES_ADD_IMAGES
import app.venkat.offkit.feature.addimages.TAG_ADDIMAGES_CHOOSE_PDF
import app.venkat.offkit.feature.addimages.TAG_ADDIMAGES_NOTE
import app.venkat.offkit.feature.addimages.TAG_ADDIMAGES_PDF_NAME
import app.venkat.offkit.feature.addimages.TAG_ADDIMAGES_ROOT
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_ACTION_CLOSE
import app.venkat.offkit.ui.components.TAG_JOB_CANCEL
import app.venkat.offkit.ui.components.TAG_JOB_ERROR
import app.venkat.offkit.ui.components.TAG_JOB_PROGRESS
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.TAG_JOB_SAVE
import app.venkat.offkit.ui.components.TAG_JOB_SAVING
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_EARLIER
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_LATER
import app.venkat.offkit.ui.components.TAG_THUMB_REMOVE
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.pdfErrorBody
import app.venkat.offkit.ui.components.thumbTag
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
// The design frame (412 x 915 dp). On the default, shorter Robolectric screen the tile row under the
// empty PDF slot stays partly behind the bottom bar after performScrollTo(), so taps miss the tiles.
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class AddImagesScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: AddImagesViewModel
    private var backCalls = 0

    /** Per call: contents of the PDF and of the images the engine received (read on the engine thread). */
    private val receivedPdf = CopyOnWriteArrayList<String>()
    private val receivedImages = CopyOnWriteArrayList<List<String>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        resetRecents()
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { AddImagesScreen(onBack = { backCalls++ }) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[AddImagesViewModel::class.java]
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
        resetRecents()
    }

    private fun resetRecents() {
        RecentFilesStore.resetForTesting()
        app.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** An output stream whose first write blocks until [release] is counted down. */
    private class BlockingSink(val release: CountDownLatch) : OutputStream() {
        val entered = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            bytes.write(b, off, len)
        }
    }

    private object FailingSink : OutputStream() {
        override fun write(b: Int) = throw IOException("disk full")
        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
    }

    private fun dest(sink: OutputStream): Uri {
        val uri = Uri.parse("content://dest.test/${System.nanoTime()}/out.pdf")
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)
        return uri
    }

    private fun runToReady() {
        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
    }

    private fun ready() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        runToReady()
    }

    /** Asserts the result was discarded: Idle, run button back, nothing in the job cache, save refused. */
    private fun assertResultDiscarded() {
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, sink.size())
    }

    private fun pdf(name: String, content: String = name): Uri =
        CoreTestSupport.contentUri(app, name, "%PDF-1.7 $content".toByteArray())

    /** A JPEG-signature file (passes the normaliser unchanged) carrying [marker]. */
    private fun image(name: String, marker: String = name): Uri =
        CoreTestSupport.contentUri(app, name, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + marker.toByteArray())

    private fun imageMarker(f: File): String = f.readBytes().let { it.copyOfRange(3, it.size).decodeToString() }

    private fun choosePdf(uri: Uri, expectedName: String) {
        rule.runOnUiThread { vm.choosePdf(listOf(uri)) }
        CoreTestSupport.idleUntil(message = "pdf $expectedName") { vm.pdf.value?.name == expectedName }
        rule.waitForIdle()
    }

    private fun addImages(vararg uris: Uri) {
        val expected = vm.images.value.size + uris.size
        rule.runOnUiThread { vm.addImages(uris.toList()) }
        CoreTestSupport.idleUntil(message = "$expected images") { vm.images.value.size == expected }
        rule.waitForIdle()
    }

    private fun recordInputs(block: ((step: Int) -> Unit)? = null) {
        fake.beforeStep = { op, step ->
            if (op == FakePdfEngine.Op.APPEND_IMAGES && step == 1) {
                val call = fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES).last()
                receivedPdf += call.pdf!!.readText()
                receivedImages += call.images!!.map(::imageMarker)
            }
            block?.invoke(step)
        }
    }

    /** Taps a node outside the scrolling content (bottom bar, top bar, job screens). */
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    /** Clicks a node in the scrolling content (slot, card button, header button, tiles, toolbar). */
    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Taps tile [index] (selects it, or deselects it when already selected). */
    private fun select(index: Int) = click(thumbTag(index))

    private fun names() = vm.images.value.map { it.file.name }

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun addLabel(n: Int) = app.resources.getQuantityString(R.plurals.add_n_pages, n, n)

    private fun pagesText(pages: Int) = app.resources.getQuantityString(R.plurals.pages_count, pages, pages)

    private fun runToFailure(error: PdfError) {
        fake.setResult(FakePdfEngine.Op.APPEND_IMAGES, PdfResult.Err(error))
        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed $error") { state() is JobUiState.Failed }
        rule.waitForIdle()
    }

    private fun state() = vm.jobState.value

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_add_images")
        rule.onNodeWithTag(TAG_ADDIMAGES_ROOT).assertExists()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noPdfShowsSlotAndRunAsksForPdf() {
        rule.onNodeWithText(str(R.string.screen_add_pages)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.section_pdf)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(str(R.string.choose_pdf)).assertExists()
        rule.onNodeWithText(str(R.string.choose_pdf_hint)).assertExists()
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.addimages_section_count, 0)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsEnabled()
            .assertTextEquals(str(R.string.add))
        rule.onNodeWithText(str(R.string.add_pages_hint)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ADDIMAGES_NOTE).performScrollTo().assertTextEquals(str(R.string.add_pages_note))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.choose_pdf_first))

        // Images alone are not enough.
        addImages(image("a.jpg"), image("b.jpg"))
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.choose_pdf_first))
        assertEquals("with-images.pdf", vm.suggestedFileName())
    }

    @Test
    fun pdfWithoutImagesAsksForImagesAndChoosingAgainReplaces() {
        // From a provider that reports its size (only content Uris are accepted).
        Robolectric.setupContentProvider(UriIoTest.NamedProvider::class.java, "named.test")
        choosePdf(Uri.parse("content://named.test/doc/42"), "Holiday photo.jpg")
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Holiday photo.jpg").assertTextEquals("Holiday photo.jpg", formatBytes(12345L))
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertTextEquals(str(R.string.change))
        rule.onNodeWithText(str(R.string.choose_pdf_hint)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.add_images_first))

        // Choosing again replaces it.
        choosePdf(pdf("second.pdf", "second, longer"), "second.pdf")
        rule.onNodeWithText("second.pdf").assertExists()
        rule.onNodeWithText("Holiday photo.jpg").assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.add_images_first))

        // Both: the button counts the pages to add, and the engine gets the new PDF.
        addImages(image("a.jpg"))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled().assertTextEquals(addLabel(1))
        assertEquals("Add 1 page", addLabel(1))
        rule.onNodeWithText(str(R.string.add_pages_hint)).assertIsDisplayed()
        assertEquals("second-with-images.pdf", vm.suggestedFileName())

        recordInputs()
        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES).size)
        assertEquals(listOf("%PDF-1.7 second, longer"), receivedPdf.toList())
    }

    @Test
    fun runLabelCountsImages() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"), image("c.jpg"))
        rule.onNodeWithText(str(R.string.addimages_section_count, 3)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled().assertTextEquals(addLabel(3))
        assertEquals("Add 3 pages", addLabel(3))
        rule.onNodeWithTag(thumbTag(2)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(3)).assertDoesNotExist()
    }

    @Test
    fun selectingATileShowsItsToolbarAndTappingAgainDeselects() {
        addImages(image("a.jpg"), image("b.jpg"), image("c.jpg"))
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertIsNotSelected()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()

        select(1)
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        rule.onNodeWithTag(thumbTag(0)).assertIsNotSelected()
        rule.onNodeWithText(str(R.string.cd_image_n, 2)).assertExists()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsEnabled()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertIsEnabled()

        select(1)
        rule.onNodeWithTag(thumbTag(1)).assertIsNotSelected()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
    }

    @Test
    fun firstTileCannotMoveEarlierAndLastCannotMoveLater() {
        addImages(image("a.jpg"), image("b.jpg"), image("c.jpg"))
        select(0)
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsEnabled()
        select(2)
        rule.onNodeWithTag(thumbTag(2)).assertIsSelected()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsNotEnabled()
    }

    @Test
    fun selectionFollowsMovedImageAndClearsOnRemove() {
        addImages(image("a.jpg"), image("b.jpg"), image("c.jpg"))
        select(0)
        click(TAG_THUMB_MOVE_LATER)
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names())
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        rule.onNodeWithTag(thumbTag(0)).assertIsNotSelected()

        click(TAG_THUMB_MOVE_LATER)
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), names())
        rule.onNodeWithTag(thumbTag(2)).assertIsSelected()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsNotEnabled()

        click(TAG_THUMB_MOVE_EARLIER)
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names())
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()

        click(TAG_THUMB_REMOVE)
        assertEquals(listOf("b.jpg", "c.jpg"), names())
        rule.onNodeWithTag(thumbTag(0)).assertIsNotSelected()
        rule.onNodeWithTag(thumbTag(1)).assertIsNotSelected()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.addimages_section_count, 2)).assertIsDisplayed()
    }

    @Test
    fun engineGetsPdfAndImagesInShownOrderAfterMoveAndRemove() {
        choosePdf(pdf("doc.pdf", "DOC"), "doc.pdf")
        addImages(image("a.jpg", "A"), image("b.jpg", "B"), image("c.jpg", "C"), image("d.jpg", "D"))
        recordInputs()

        select(0)
        click(TAG_THUMB_MOVE_LATER) // B A C D
        select(3)
        click(TAG_THUMB_MOVE_EARLIER) // B A D C
        select(1)
        click(TAG_THUMB_REMOVE) // B D C
        assertEquals(listOf("b.jpg", "d.jpg", "c.jpg"), names())
        rule.onNodeWithTag(TAG_JOB_RUN).assertTextEquals(addLabel(3))

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }

        val calls = fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES)
        assertEquals(1, calls.size)
        assertEquals(3, calls[0].images!!.size)
        assertEquals(listOf("%PDF-1.7 DOC"), receivedPdf.toList())
        assertEquals(listOf(listOf("B", "D", "C")), receivedImages.toList())
    }

    @Test
    fun progressResultSaveAndSaved() {
        choosePdf(pdf("report.pdf"), "report.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        fake.setResult(FakePdfEngine.Op.APPEND_IMAGES, PdfResult.Ok(pages = 5, bytes = 1_258_291L))
        fake.outputContent = "%PDF-1.7\nwith images\n".toByteArray()
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.processing_progress, 1, 3, "report.pdf")).assertIsDisplayed()
        // The options (and so the inputs) are not on screen while the job runs.
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).assertDoesNotExist()
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pagesText(5))
        rule.onNodeWithText(formatBytes(1_258_291L)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()
        assertEquals("report-with-images.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        val savedName = (state() as JobUiState.Done).let { it.savedName ?: it.suggestedName }
        rule.onNodeWithText(str(R.string.result_saved_as, savedName)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pagesText(5))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
    }

    @Test
    fun encryptedShowsLockedStrings() {
        choosePdf(pdf("locked.pdf"), "locked.pdf")
        addImages(image("a.jpg"))
        runToFailure(PdfError.Encrypted)
        rule.onNodeWithText(str(R.string.error_locked_title)).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(R.string.error_locked_body))
        rule.onNodeWithText("locked.pdf").assertExists()
    }

    @Test
    fun engineErrorShowsErrorScreenAndItsButtonReturnsToOptions() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        runToFailure(PdfError.UnsupportedImage)

        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo()
            .assertTextEquals(str(pdfErrorBody(PdfError.UnsupportedImage)))
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_choose_another))
        tap(TAG_JOB_RESET)

        assertEquals(JobUiState.Idle, state())
        assertEquals(0, backCalls)
        rule.onNodeWithTag(TAG_JOB_ERROR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("a.jpg", "b.jpg"), names())

        // Back on the error screen also returns to the options.
        runToFailure(PdfError.Corrupt)
        tap(TAG_ACTION_BACK)
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, backCalls)
        assertEquals(2, fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES).size)
    }

    @Test
    fun cancelReturnsToOptionsWithInputsKept() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        tap(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed().assertIsEnabled().assertTextEquals(addLabel(2))
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithText("doc.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsEnabled()
        select(1)
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).performScrollTo().assertIsEnabled()
        assertEquals(listOf("a.jpg", "b.jpg"), names())
        assertEquals(0, backCalls)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun suggestedFileNameLogic() {
        assertEquals("report-with-images.pdf", AddImagesViewModel.suggestedNameFor("report.pdf"))
        assertEquals("Scan-with-images.pdf", AddImagesViewModel.suggestedNameFor("Scan.PDF"))
        assertEquals("notes-with-images.pdf", AddImagesViewModel.suggestedNameFor("notes"))
        assertEquals("with-images.pdf", AddImagesViewModel.suggestedNameFor(""))
        assertEquals("with-images.pdf", AddImagesViewModel.suggestedNameFor("   "))
        assertEquals("with-images.pdf", AddImagesViewModel.suggestedNameFor(".pdf"))
        assertEquals("with-images.pdf", AddImagesViewModel.suggestedNameFor(null))
    }

    @Test
    fun changingInputsDiscardsReadyResult() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"), image("c.jpg"))
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))
        // The result screen hides the inputs; the ViewModel still discards on any change.
        rule.runOnUiThread { vm.moveUp(1) }
        rule.waitForIdle()
        assertResultDiscarded()

        runToReady()
        rule.runOnUiThread { vm.remove(0) }
        rule.waitForIdle()
        assertResultDiscarded()

        runToReady()
        addImages(image("d.jpg"))
        assertResultDiscarded()

        runToReady()
        choosePdf(pdf("other.pdf"), "other.pdf")
        assertResultDiscarded()
        assertEquals(3, vm.images.value.size)
    }

    @Test
    fun inputsLockedWhileSaving() {
        ready()
        val other = pdf("other.pdf")
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            rule.runOnUiThread { vm.save(dest(sink)) }
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            rule.waitForIdle()
            assertTrue(state().isSavingNow)
            rule.onNodeWithTag(TAG_JOB_SAVING).assertIsDisplayed().assertIsNotEnabled()
            rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).assertDoesNotExist()
            rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).assertDoesNotExist()
            rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
            // Leaving is ignored while saving.
            tap(TAG_ACTION_CLOSE)
            rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
            assertEquals(0, backCalls)
            // The ViewModel ignores changes too.
            rule.runOnUiThread {
                vm.moveDown(0)
                vm.remove(1)
                vm.addImages(listOf(image("c.jpg")))
                vm.choosePdf(listOf(other))
            }
            rule.waitForIdle()
            assertEquals(listOf("a.jpg", "b.jpg"), names())
            assertEquals("doc.pdf", vm.pdf.value?.name)
            assertTrue(state().isSavingNow)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun backFromOptionsLeavesWithoutDialog() {
        tap(TAG_ACTION_BACK)
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
    }

    @Test
    fun discardOnUnsavedResultAsksThenLeaves() {
        ready()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_discard))
        tap(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed()
        tap(TAG_LEAVE_STAY)
        assertEquals(0, backCalls)
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()

        // Close and system back ask too.
        tap(TAG_ACTION_CLOSE)
        tap(TAG_LEAVE_STAY)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed()
        tap(TAG_LEAVE_CONFIRM)
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun doneAfterSaveLeavesWithoutDialog() {
        ready()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
        tap(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        ready()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("add_images", recents.single().tool)
        assertEquals(AddImagesViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveShowsSnackbarAndKeepsResult() {
        ready()
        val failed = str(R.string.error_save_message, str(R.string.error_save_title), str(R.string.error_save_body))
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(rule, message = "save error shown") {
            rule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithText(failed).assertExists()
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
        // The screen reported it as shown, so it is not shown again.
        assertNull(vm.saveError.value)
    }
}
