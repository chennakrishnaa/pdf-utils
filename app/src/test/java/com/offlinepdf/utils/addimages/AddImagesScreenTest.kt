package com.offlinepdf.utils.addimages

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.offlinepdf.utils.FakePdfEngine
import com.offlinepdf.utils.MainActivity
import com.offlinepdf.utils.PdfUtilApp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.core.CoreTestSupport
import com.offlinepdf.utils.core.RecentFilesStore
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.feature.addimages.AddImagesScreen
import com.offlinepdf.utils.feature.addimages.AddImagesViewModel
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_ADD_IMAGES
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_CHOOSE_PDF
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_NOTE
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_PDF_NAME
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_ROOT
import com.offlinepdf.utils.feature.addimages.TAG_ADDIMAGES_SAVE_ERROR
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.TAG_ACTION_BACK
import com.offlinepdf.utils.ui.components.TAG_JOB_CANCEL
import com.offlinepdf.utils.ui.components.TAG_JOB_ERROR
import com.offlinepdf.utils.ui.components.TAG_JOB_PROGRESS
import com.offlinepdf.utils.ui.components.TAG_JOB_RESET
import com.offlinepdf.utils.ui.components.TAG_JOB_RUN
import com.offlinepdf.utils.ui.components.TAG_JOB_SAVE
import com.offlinepdf.utils.ui.components.TAG_JOB_SAVING
import com.offlinepdf.utils.ui.components.TAG_JOB_SUMMARY
import com.offlinepdf.utils.ui.components.TAG_LEAVE_CONFIRM
import com.offlinepdf.utils.ui.components.TAG_LEAVE_STAY
import com.offlinepdf.utils.ui.components.fileRowDownTag
import com.offlinepdf.utils.ui.components.fileRowRemoveTag
import com.offlinepdf.utils.ui.components.fileRowTag
import com.offlinepdf.utils.ui.components.fileRowUpTag
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.nav.AppNavHost
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
@Config(sdk = [36])
class AddImagesScreenTest {
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
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { AddImagesScreen(onBack = { backCalls++ }) } } }
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

    private fun back() {
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
    }

    private fun runToReady() {
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
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
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, sink.size())
        assertEquals(app.getString(R.string.core_result_unavailable), vm.saveError.value)
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

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun state() = vm.jobState.value

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_add_images")
        rule.onNodeWithTag(TAG_ADDIMAGES_ROOT).assertExists()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runEnabledOnlyWithPdfAndImagesAndChoosingAgainReplaces() {
        rule.onNodeWithTag(TAG_ADDIMAGES_NOTE).performScrollTo().assertTextEquals(app.getString(R.string.addimages_note))
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).performScrollTo().assertTextEquals(app.getString(R.string.addimages_no_pdf))
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()

        // Only a PDF (a file:// Uri, so that its size is known).
        val firstFile = File(app.filesDir, "first.pdf").apply { writeText("%PDF-1.7 first") }
        choosePdf(Uri.fromFile(firstFile), "first.pdf")
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).performScrollTo()
            .assertTextEquals("first.pdf", formatBytes("%PDF-1.7 first".length.toLong()))
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()

        // Choosing again replaces it.
        choosePdf(pdf("second.pdf", "second, longer"), "second.pdf")
        rule.onNodeWithTag(TAG_ADDIMAGES_PDF_NAME).performScrollTo().assertTextEquals("second.pdf")
        rule.onNodeWithText("first.pdf").assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()

        // Both.
        addImages(image("a.jpg"))
        rule.onNodeWithTag(fileRowTag(0)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
        assertEquals("second-with-images.pdf", vm.suggestedFileName())

        recordInputs()
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES).size)
        assertEquals(listOf("%PDF-1.7 second, longer"), receivedPdf.toList())
    }

    @Test
    fun runDisabledWithOnlyImages() {
        addImages(image("a.jpg"), image("b.jpg"))
        rule.onNodeWithTag(fileRowTag(1)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
        assertEquals("with-images.pdf", vm.suggestedFileName())
    }

    @Test
    fun engineGetsPdfAndImagesInListOrderAfterMoveAndRemove() {
        choosePdf(pdf("doc.pdf", "DOC"), "doc.pdf")
        addImages(image("a.jpg", "A"), image("b.jpg", "B"), image("c.jpg", "C"), image("d.jpg", "D"))
        recordInputs()

        click(fileRowDownTag(0)) // B A C D
        click(fileRowUpTag(3)) // B A D C
        click(fileRowRemoveTag(1)) // B D C
        assertEquals(listOf("b.jpg", "d.jpg", "c.jpg"), vm.images.value.map { it.file.name })

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }

        val calls = fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES)
        assertEquals(1, calls.size)
        assertEquals(3, calls[0].images!!.size)
        assertEquals(listOf("%PDF-1.7 DOC"), receivedPdf.toList())
        assertEquals(listOf(listOf("B", "D", "C")), receivedImages.toList())
    }

    @Test
    fun progressSummarySaveAndSaved() {
        choosePdf(pdf("report.pdf"), "report.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        fake.setResult(FakePdfEngine.Op.APPEND_IMAGES, PdfResult.Ok(pages = 5, bytes = 1_258_291L))
        fake.outputContent = "%PDF-1.7\nwith images\n".toByteArray()
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertIsDisplayed()
        // Inputs are locked while the job runs.
        rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsNotEnabled()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
        val summary = app.resources.getQuantityString(R.plurals.addimages_summary, 5, 5, formatBytes(1_258_291L))
        assertEquals("5 pages · 1.2 MB", summary)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()
        assertEquals("report-with-images.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/out.pdf")
        shadowOf(app.contentResolver).registerOutputStream(dest, sink)
        rule.runOnUiThread { vm.save(dest) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        rule.onNodeWithText(app.getString(R.string.job_saved_title)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun singlePageSummaryIsSingular() {
        assertEquals("1 page · 1.0 KB", app.resources.getQuantityString(R.plurals.addimages_summary, 1, 1, formatBytes(1024)))
    }

    @Test
    fun encryptedAndUnsupportedImageErrorsShowMessages() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"))
        for ((err, msg) in listOf(PdfError.Encrypted to R.string.error_encrypted, PdfError.UnsupportedImage to R.string.error_unsupported_image)) {
            fake.setResult(FakePdfEngine.Op.APPEND_IMAGES, PdfResult.Err(err))
            click(TAG_JOB_RUN)
            CoreTestSupport.idleUntil(message = "Failed $err") { state() is JobUiState.Failed }
            rule.waitForIdle()
            rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo().assertTextEquals(app.getString(msg))
            rule.runOnUiThread { vm.reset() }
            CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle }
            rule.waitForIdle()
        }
        assertEquals(2, fake.callsFor(FakePdfEngine.Op.APPEND_IMAGES).size)
    }

    @Test
    fun cancelReturnsToIdle() {
        choosePdf(pdf("doc.pdf"), "doc.pdf")
        addImages(image("a.jpg"), image("b.jpg"))
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        click(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(fileRowUpTag(1)).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsEnabled()
        assertEquals(2, vm.images.value.size)
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
        click(fileRowUpTag(1))
        assertResultDiscarded()

        runToReady()
        click(fileRowRemoveTag(0))
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
            assertTrue(state() is JobUiState.Saving)
            rule.onNodeWithTag(TAG_JOB_SAVING).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
            rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
            rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsNotEnabled()
            // The ViewModel ignores changes too.
            rule.runOnUiThread {
                vm.moveDown(0)
                vm.remove(1)
                vm.addImages(listOf(image("c.jpg")))
                vm.choosePdf(listOf(other))
            }
            rule.waitForIdle()
            assertEquals(listOf("a.jpg", "b.jpg"), vm.images.value.map { it.file.name })
            assertEquals("doc.pdf", vm.pdf.value?.name)
            assertTrue(state() is JobUiState.Saving)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ADDIMAGES_CHOOSE_PDF).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_ADDIMAGES_ADD_IMAGES).performScrollTo().assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun backWithResultAsksBeforeLeaving() {
        back()
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()

        ready()
        back()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        assertTrue(state() is JobUiState.ReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(2, backCalls)
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        ready()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("add_images", recents.single().tool)
        assertEquals(AddImagesViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveErrorStaysUntilNextAction() {
        ready()
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        rule.waitForIdle()
        val failed = app.getString(R.string.core_save_failed)
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        assertTrue(state() is JobUiState.ReadyToSave)

        // Discarding (the next action) clears it.
        click(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).assertDoesNotExist()

        // "Result no longer available" arrives in Idle and stays until the inputs change.
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).performScrollTo()
            .assertTextEquals(app.getString(R.string.core_result_unavailable))
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).assertExists()
        click(fileRowRemoveTag(1))
        rule.onNodeWithTag(TAG_ADDIMAGES_SAVE_ERROR).assertDoesNotExist()
    }
}
