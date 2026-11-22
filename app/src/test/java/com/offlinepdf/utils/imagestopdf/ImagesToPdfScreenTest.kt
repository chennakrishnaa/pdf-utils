package com.offlinepdf.utils.imagestopdf

import android.app.Application
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.feature.imagestopdf.ImagesToPdfScreen
import com.offlinepdf.utils.feature.imagestopdf.ImagesToPdfViewModel
import com.offlinepdf.utils.feature.imagestopdf.TAG_IMAGESTOPDF_ADD
import com.offlinepdf.utils.feature.imagestopdf.imagesToPdfSizeTag
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.TAG_FILE_LIST_EMPTY
import com.offlinepdf.utils.ui.components.TAG_JOB_CANCEL
import com.offlinepdf.utils.ui.components.TAG_JOB_ERROR
import com.offlinepdf.utils.ui.components.TAG_JOB_PROGRESS
import com.offlinepdf.utils.ui.components.TAG_JOB_RESET
import com.offlinepdf.utils.ui.components.TAG_JOB_RUN
import com.offlinepdf.utils.ui.components.TAG_JOB_SAVE
import com.offlinepdf.utils.ui.components.TAG_JOB_SUMMARY
import com.offlinepdf.utils.ui.components.fileRowDownTag
import com.offlinepdf.utils.ui.components.fileRowRemoveTag
import com.offlinepdf.utils.ui.components.fileRowTag
import com.offlinepdf.utils.ui.components.fileRowUpTag
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.pdfErrorMessage
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Robolectric + FakePdfEngine: the "Images to PDF" screen and its ViewModel (no MuPDF). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImagesToPdfScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: ImagesToPdfViewModel

    /** Contents of the files the engine received, captured on the engine thread (inputs are deleted after the job). */
    private val received = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        fake.beforeStep = { _, step ->
            if (step == 1) {
                received.clear()
                fake.calls.last().images!!.forEach { received += it.readBytes().copyOfRange(3, it.length().toInt()).decodeToString() }
            }
        }
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { ImagesToPdfScreen(onBack = {}) } } }
        rule.waitForIdle()
        // Same instance the screen created (through its explicit initializer, bound to this test's Application).
        vm = ViewModelProvider(rule.activity)[ImagesToPdfViewModel::class.java]
        assertSame(app, vm.getApplication<Application>())
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
    }

    /** A content Uri whose bytes sniff as JPEG (so they are passed through unchanged) followed by [marker]. */
    private fun image(name: String, marker: String = name): Uri =
        CoreTestSupport.contentUri(app, name, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + marker.toByteArray())

    private fun addImages(vararg names: String) {
        val before = vm.images.value.size
        rule.runOnUiThread { vm.add(names.map { image(it) }) }
        CoreTestSupport.idleUntil(message = "images added") { vm.images.value.size == before + names.size }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun runAndAwait(): FakePdfEngine.Call {
        if (vm.jobState.value != JobUiState.Idle) click(TAG_JOB_RESET)
        val n = fake.calls.size
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "job finished") {
            fake.calls.size == n + 1 && vm.jobState.value !is JobUiState.Running
        }
        rule.waitForIdle()
        return fake.calls.last()
    }

    private fun assertRowTitle(index: Int, name: String) {
        rule.onNode(hasTestTag(fileRowTag(index)) and hasAnyDescendant(hasText(name))).performScrollTo().assertExists()
    }

    @Test
    fun runEnabledOnlyWithImagesAndRowsInOrder() {
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
            .assertTextEquals(app.getString(R.string.imagestopdf_run))

        addImages("a.jpg", "b.jpg")
        addImages("c.jpg") // a second pick appends
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
        assertRowTitle(0, "a.jpg")
        assertRowTitle(1, "b.jpg")
        assertRowTitle(2, "c.jpg")

        click(fileRowRemoveTag(0))
        click(fileRowRemoveTag(0))
        click(fileRowRemoveTag(0))
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun reorderAndRemoveChangeWhatTheEngineReceives() {
        addImages("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        click(fileRowDownTag(0)) // b a c d
        click(fileRowUpTag(3)) // b a d c
        click(fileRowRemoveTag(1)) // b d c
        assertRowTitle(0, "b.jpg")
        assertRowTitle(1, "d.jpg")
        assertRowTitle(2, "c.jpg")

        val call = runAndAwait()
        assertEquals(FakePdfEngine.Op.IMAGES_TO_PDF, call.op)
        assertEquals(listOf("b.jpg", "d.jpg", "c.jpg"), received.toList())
    }

    @Test
    fun sameImageTwiceIsAllowed() {
        val uri = image("same.jpg")
        rule.runOnUiThread { vm.add(listOf(uri, uri)) }
        CoreTestSupport.idleUntil { vm.images.value.size == 2 }
        rule.waitForIdle()
        assertRowTitle(0, "same.jpg")
        assertRowTitle(1, "same.jpg")
        runAndAwait()
        assertEquals(listOf("same.jpg", "same.jpg"), received.toList())
    }

    @Test
    fun selectedPageSizeReachesEngine() {
        addImages("a.jpg")
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.FIT_IMAGE)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).assertIsNotSelected()
        assertEquals(PageSize.FIT_IMAGE, runAndAwait().pageSize)

        click(imagesToPdfSizeTag(PageSize.A4))
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).assertIsSelected()
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.FIT_IMAGE)).assertIsNotSelected()
        assertEquals(PageSize.A4, runAndAwait().pageSize)

        click(imagesToPdfSizeTag(PageSize.LETTER))
        assertEquals(PageSize.LETTER, runAndAwait().pageSize)
    }

    @Test
    fun progressThenSummaryThenSave() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        val content = "%PDF-1.7\n% three pages\n".toByteArray()
        fake.outputContent = content
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Ok(pages = 3, bytes = 1_258_291))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capture = fake.beforeStep!!
        fake.beforeStep = { op, step ->
            capture(op, step)
            if (step == 2) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "held at step 2") {
            reached.count == 0L && vm.jobState.value == JobUiState.Running(1, 3)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertExists()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertExists()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).assertIsNotEnabled()
        rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ready") { vm.jobState.value is JobUiState.ReadyToSave }
        rule.waitForIdle()
        val summary = "3 pages · ${formatBytes(1_258_291)}"
        assertEquals("3 pages · 1.2 MB", summary)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()
        assertEquals("images.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/images.pdf")
        shadowOf(app.contentResolver).registerOutputStream(dest, sink)
        rule.runOnUiThread { vm.save(dest) }
        CoreTestSupport.idleUntil(message = "saved") { vm.jobState.value is JobUiState.Saved }
        rule.waitForIdle()
        assertArrayEquals(content, sink.toByteArray())
        rule.onNodeWithText(app.getString(R.string.job_saved_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(summary)
    }

    @Test
    fun singlePageSummaryUsesSingular() {
        addImages("a.jpg")
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Ok(pages = 1, bytes = 500))
        runAndAwait()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals("1 page · 500 B")
    }

    @Test
    fun unsupportedImageShowsError() {
        addImages("a.jpg")
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Err(PdfError.UnsupportedImage))
        runAndAwait()
        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo()
            .assertTextEquals(app.getString(pdfErrorMessage(PdfError.UnsupportedImage)))
    }

    @Test
    fun cancelReturnsToIdle() {
        addImages("a.jpg", "b.jpg")
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 1) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "running") { reached.count == 0L }
        rule.waitForIdle()
        click(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "idle") { vm.jobState.value == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertIsEnabled()
        // The list is kept for another attempt.
        rule.onNode(hasText("a.jpg")).assertExists()
    }
}
