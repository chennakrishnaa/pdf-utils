package com.offlinepdf.utils.compress

import android.app.Application
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.feature.compress.CompressScreen
import com.offlinepdf.utils.feature.compress.CompressViewModel
import com.offlinepdf.utils.feature.compress.TAG_COMPRESS_CHOOSE
import com.offlinepdf.utils.feature.compress.TAG_COMPRESS_NOTE
import com.offlinepdf.utils.feature.compress.TAG_COMPRESS_ROOT
import com.offlinepdf.utils.feature.compress.compressLevelTag
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.TAG_JOB_CANCEL
import com.offlinepdf.utils.ui.components.TAG_JOB_ERROR
import com.offlinepdf.utils.ui.components.TAG_JOB_PROGRESS
import com.offlinepdf.utils.ui.components.TAG_JOB_RUN
import com.offlinepdf.utils.ui.components.TAG_JOB_RESET
import com.offlinepdf.utils.ui.components.TAG_JOB_SAVE
import com.offlinepdf.utils.ui.components.TAG_JOB_SUMMARY
import com.offlinepdf.utils.ui.components.fileRowTag
import com.offlinepdf.utils.ui.nav.AppNavHost
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric + [FakePdfEngine]. Robolectric content Uris report no size, so the original size
 * comes from the ViewModel's fallback: the length of the input bytes copied for the job, which
 * the test controls through the content registered for the Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompressScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: CompressViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { CompressScreen(onBack = {}) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[CompressViewModel::class.java]
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
    }

    private fun pdf(name: String, size: Int = 10_000): Uri =
        CoreTestSupport.contentUri(app, name, ByteArray(size) { (it % 251).toByte() })

    private fun choose(uri: Uri) {
        rule.runOnUiThread { vm.choose(uri) }
        CoreTestSupport.idleUntil(message = "chosen $uri") { vm.file.value?.uri == uri }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun state() = vm.jobState.value

    private fun runToReady() {
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
    }

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_compress")
        rule.onNodeWithTag(TAG_COMPRESS_ROOT).assertExists()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runDisabledUntilChosen_chooseAgainReplaces_defaultMedium() {
        rule.onNodeWithTag(TAG_COMPRESS_NOTE).performScrollTo().assertTextEquals(app.getString(R.string.compress_note))
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW)).assertIsNotSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertIsNotSelected()
        assertEquals(CompressionLevel.MEDIUM, vm.level.value)

        choose(pdf("first.pdf"))
        rule.onNodeWithText("first.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()

        choose(pdf("second.pdf"))
        rule.onNodeWithText("second.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("first.pdf").assertDoesNotExist()
        rule.onNodeWithTag(fileRowTag(1)).assertDoesNotExist()
        assertEquals("second-compressed.pdf", vm.suggestedFileName())
    }

    @Test
    fun eachSelectedLevelReachesEngine() {
        choose(pdf("a.pdf"))
        runToReady() // default: Medium
        click(TAG_JOB_RESET) // "Discard" brings back the run button
        click(compressLevelTag(CompressionLevel.LOW))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW)).assertIsSelected()
        runToReady()
        click(TAG_JOB_RESET)
        click(compressLevelTag(CompressionLevel.HIGH))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).assertIsNotSelected()
        runToReady()

        val calls = fake.callsFor(FakePdfEngine.Op.COMPRESS)
        assertEquals(
            listOf(CompressionLevel.MEDIUM, CompressionLevel.LOW, CompressionLevel.HIGH),
            calls.map { it.level },
        )
    }

    @Test
    fun progressSummarySaveAndSaved() {
        choose(pdf("report.pdf", size = 10_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 3, bytes = 1_000))
        fake.outputContent = "%PDF-1.7\ncompressed output\n".toByteArray()
        val received = CountDownLatch(1)
        var inputSize = -1L
        val release = CountDownLatch(1)
        fake.beforeStep = { op, step ->
            if (op == FakePdfEngine.Op.COMPRESS && step == 1) {
                inputSize = fake.callsFor(op).last().pdf!!.length()
                received.countDown()
            }
            if (step == 2) release.await(10, TimeUnit.SECONDS)
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).performScrollTo().assertIsNotEnabled()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
        assertEquals(10_000L, inputSize)
        val summary = "9.8 KB → 1000 B (90% smaller)"
        assertEquals(summary, app.getString(R.string.compress_summary, "9.8 KB", "1000 B", 90))
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()
        assertEquals("report-compressed.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/report-compressed.pdf")
        shadowOf(app.contentResolver).registerOutputStream(dest, sink)
        rule.runOnUiThread { vm.save(dest) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        rule.onNodeWithText(app.getString(R.string.job_saved_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
    }

    @Test
    fun notSmallerShowsAlreadySmallMessage_andCanStillSave() {
        choose(pdf("tiny.pdf", size = 5_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 1, bytes = 5_000))
        runToReady()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo()
            .assertTextEquals(app.getString(R.string.compress_summary_no_gain))
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun unknownOriginalSizeShowsJustNewSize() {
        // An empty input copy gives no usable original size.
        choose(pdf("empty.pdf", size = 0))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 1, bytes = 2_048))
        runToReady()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo()
            .assertTextEquals(app.getString(R.string.compress_summary_size_only, "2.0 KB"))
    }

    @Test
    fun encryptedAndCorruptShowMessages() {
        choose(pdf("a.pdf"))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Err(PdfError.Encrypted))
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo().assertTextEquals(app.getString(R.string.error_encrypted))

        rule.runOnUiThread { vm.reset() }
        rule.waitForIdle()
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Err(PdfError.Corrupt))
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo().assertTextEquals(app.getString(R.string.error_corrupt))
        assertEquals(2, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun cancelReturnsToIdle() {
        choose(pdf("a.pdf"))
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        click(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        assertEquals("a.pdf", vm.file.value?.name)
        assertTrue(CoreTestSupport.jobsEmpty(app))
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun suggestedFileNameLogic() {
        assertEquals("report-compressed.pdf", CompressViewModel.compressedFileName("report.pdf"))
        assertEquals("scan-compressed.pdf", CompressViewModel.compressedFileName("scan"))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(""))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName("   "))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(null))
        assertEquals("Report-compressed.pdf", CompressViewModel.compressedFileName("Report.PDF"))
        assertEquals("a_b-compressed.pdf", CompressViewModel.compressedFileName("a/b.pdf"))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(".pdf"))
        // Without a chosen file the ViewModel suggests the fallback.
        assertEquals("compressed.pdf", vm.suggestedFileName())
    }
}
