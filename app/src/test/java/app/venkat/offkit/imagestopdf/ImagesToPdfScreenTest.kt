package app.venkat.offkit.imagestopdf

import android.app.Application
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
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
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PDF_DOCUMENT_OVERHEAD_BYTES
import app.venkat.offkit.engine.PDF_PAGE_OVERHEAD_BYTES
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfScreen
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfViewModel
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_ADD
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_QUALITY_NOTE
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_SAVE_ERROR
import app.venkat.offkit.feature.imagestopdf.imagesToPdfQualityTag
import app.venkat.offkit.feature.imagestopdf.imagesToPdfSizeTag
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.TAG_FILE_LIST_EMPTY
import app.venkat.offkit.ui.components.TAG_JOB_CANCEL
import app.venkat.offkit.ui.components.TAG_JOB_ERROR
import app.venkat.offkit.ui.components.TAG_JOB_PROGRESS
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.TAG_JOB_SAVE
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_JOB_SAVING
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.fileRowDownTag
import app.venkat.offkit.ui.components.fileRowRemoveTag
import app.venkat.offkit.ui.components.fileRowTag
import app.venkat.offkit.ui.components.fileRowUpTag
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.pdfErrorMessage
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Robolectric + FakePdfEngine: the "Images to PDF" screen and its ViewModel (no MuPDF). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImagesToPdfScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: ImagesToPdfViewModel

    /** Contents of the files the engine received, captured on the engine thread (inputs are deleted after the job). */
    private val received = CopyOnWriteArrayList<String>()

    /** Times the screen called onBack. */
    private val backs = AtomicInteger()

    /** Released in tearDown so a held estimate never blocks the process-wide engine thread. */
    private val releaseAll = CountDownLatch(1)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        RecentFilesStore.resetForTesting()
        fake.beforeStep = { _, step ->
            if (step == 1) {
                received.clear()
                fake.calls.last().images!!.forEach { received += it.readBytes().copyOfRange(3, it.length().toInt()).decodeToString() }
            }
        }
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { ImagesToPdfScreen(onBack = { backs.incrementAndGet() }) } } }
        rule.waitForIdle()
        // Same instance the screen created (through its explicit initializer, bound to this test's Application).
        vm = ViewModelProvider(rule.activity)[ImagesToPdfViewModel::class.java]
        assertSame(app, vm.getApplication<Application>())
    }

    @After
    fun tearDown() {
        releaseAll.countDown()
        fake.beforeStep = null
        fake.estimate = null
        RecentFilesStore.resetForTesting()
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
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).performScrollTo().assertIsDisplayed()
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
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).performScrollTo().assertIsDisplayed()
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
        rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.LOW)).assertIsNotEnabled()
        rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
        rule.runOnUiThread {
            vm.setQuality(CompressionLevel.HIGH)
            vm.setPageSize(PageSize.A4)
            vm.remove(0)
        }
        assertEquals(null, vm.quality.value)
        assertEquals(PageSize.FIT_IMAGE, vm.pageSize.value)
        assertEquals(3, vm.images.value.size)

        release.countDown()
        CoreTestSupport.idleUntil(message = "ready") { vm.jobState.value is JobUiState.ReadyToSave }
        rule.waitForIdle()
        val summary = "3 pages · ${formatBytes(1_258_291)}"
        assertEquals("3 pages · 1.2 MB", summary)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()
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
        CoreTestSupport.idleUntil(message = "recorded") { RecentFilesStore.get(app).recents.value.isNotEmpty() }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("images_to_pdf", recents.single().tool)

        // Changing an option after saving clears the Saved panel.
        click(imagesToPdfQualityTag(CompressionLevel.LOW))
        assertEquals(JobUiState.Idle, vm.jobState.value)
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
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

    // ---- Image quality and size estimates ----

    private val allQualities: List<CompressionLevel?> = listOf(null) + CompressionLevel.entries

    private val perImage = mapOf(
        null to 1_000_000L,
        CompressionLevel.LOW to 400_000L,
        CompressionLevel.MEDIUM to 200_000L,
        CompressionLevel.HIGH to 50_000L,
    )

    private fun fixedEstimate(): PdfEstimate.Ok = PdfEstimate.Ok(
        original = perImage.getValue(null),
        byLevel = CompressionLevel.entries.associateWith { perImage.getValue(it) },
    )

    private fun expectedTotal(level: CompressionLevel?, images: Int): String =
        "≈ " + formatBytes(PDF_DOCUMENT_OVERHEAD_BYTES + images * (perImage.getValue(level) + PDF_PAGE_OVERHEAD_BYTES))

    private fun awaitEstimates() {
        CoreTestSupport.idleUntil(message = "estimates done") { vm.estimates.value.pendingCount == 0 }
        rule.waitForIdle()
    }

    private fun assertCardTrailing(level: CompressionLevel?, text: String) {
        rule.onNodeWithTag(imagesToPdfQualityTag(level)).performScrollTo().assert(hasText(text))
    }

    private fun assertNoTrailing(level: CompressionLevel?) {
        val tag = imagesToPdfQualityTag(level)
        rule.onNodeWithTag(tag).performScrollTo().assertExists()
        listOf("≈", app.getString(R.string.imagestopdf_estimating), app.getString(R.string.imagestopdf_estimate_unknown)).forEach {
            rule.onNode(hasTestTag(tag) and hasText(it, substring = true)).assertDoesNotExist()
        }
    }

    @Test
    fun qualityDefaultsToOriginalAndSelectedLevelReachesEngine() {
        addImages("a.jpg")
        rule.onNodeWithTag(imagesToPdfQualityTag(null)).performScrollTo().assertIsSelected()
            .assert(hasText(app.getString(R.string.imagestopdf_quality_original)))
            .assert(hasText(app.getString(R.string.imagestopdf_quality_original_detail)))
        rule.onNodeWithTag(TAG_IMAGESTOPDF_QUALITY_NOTE).performScrollTo()
            .assertTextEquals(app.getString(R.string.imagestopdf_quality_note))
        assertEquals(null, runAndAwait().compression)

        CompressionLevel.entries.forEach { level ->
            click(imagesToPdfQualityTag(level))
            rule.onNodeWithTag(imagesToPdfQualityTag(level)).assertIsSelected()
            rule.onNodeWithTag(imagesToPdfQualityTag(null)).assertIsNotSelected()
            assertEquals(level, runAndAwait().compression)
        }
        rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.LOW))
            .assert(hasText("Up to ${CompressionLevel.LOW.maxLongEdgePx} px, very good quality"))
    }

    @Test
    fun noImagesShowsNoEstimatesAndRunDisabled() {
        allQualities.forEach(::assertNoTrailing)
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
        assertTrue(fake.estimateCalls.isEmpty())
    }

    @Test
    fun estimatesShowTotalsAndFollowTheList() {
        fake.estimate = { fixedEstimate() }
        addImages("a.jpg", "b.jpg", "c.jpg")
        awaitEstimates()
        assertEquals(3, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 3)) }
        assertEquals("≈ 2.9 MB", expectedTotal(null, 3))

        addImages("d.jpg")
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 4)) }

        click(fileRowDownTag(0))
        click(fileRowUpTag(3))
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 4)) }

        click(fileRowRemoveTag(1))
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 3)) }

        click(fileRowRemoveTag(0))
        click(fileRowRemoveTag(0))
        click(fileRowRemoveTag(0))
        allQualities.forEach(::assertNoTrailing)
    }

    @Test
    fun heldEstimatesShowEstimatingThenTotals() {
        fake.estimate = {
            releaseAll.await(5, TimeUnit.SECONDS)
            fixedEstimate()
        }
        try {
            addImages("a.jpg", "b.jpg")
            CoreTestSupport.idleUntil(message = "estimate started") { fake.estimateCalls.isNotEmpty() }
            rule.waitForIdle()
            assertEquals(2, vm.estimates.value.pendingCount)
            allQualities.forEach { assertCardTrailing(it, app.getString(R.string.imagestopdf_estimating)) }
        } finally {
            releaseAll.countDown()
        }
        awaitEstimates()
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 2)) }
    }

    @Test
    fun removingAnImageWhileItIsEstimatedDropsItsEstimate() {
        val first = CountDownLatch(1)
        fake.estimate = { _ ->
            if (fake.estimateCalls.size == 1) {
                first.countDown()
                releaseAll.await(5, TimeUnit.SECONDS)
            }
            fixedEstimate()
        }
        try {
            addImages("a.jpg", "b.jpg")
            CoreTestSupport.idleUntil(message = "first estimate held") { first.count == 0L }
            click(fileRowRemoveTag(0))
            assertEquals(1, vm.estimates.value.pendingCount)
        } finally {
            releaseAll.countDown()
        }
        awaitEstimates()
        // a.jpg (cancelled once in progress) and b.jpg; a.jpg is not estimated again.
        assertEquals(2, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 1)) }
        assertRowTitle(0, "b.jpg")
    }

    @Test
    fun failingEstimateShowsDash() {
        fake.estimate = { PdfEstimate.Err(PdfError.UnsupportedImage) }
        addImages("a.jpg", "b.jpg")
        CoreTestSupport.idleUntil(message = "failed") { vm.estimates.value.anyFailed && vm.estimates.value.pendingCount == 0 }
        rule.waitForIdle()
        allQualities.forEach { assertCardTrailing(it, app.getString(R.string.imagestopdf_estimate_unknown)) }
    }

    @Test
    fun estimatesPausedDuringJobAndResumedAfterwards() {
        addImages("a.jpg")
        awaitEstimates()
        val gate = CountDownLatch(1)
        fake.beforeStep = { _, step -> if (step == 1) gate.await(5, TimeUnit.SECONDS) }
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "running") { vm.isRunning }
        // Added while running: ignored (inputs are locked).
        rule.runOnUiThread { vm.add(listOf(image("x.jpg"))) }
        gate.countDown()
        CoreTestSupport.idleUntil(message = "done") { vm.jobState.value is JobUiState.ReadyToSave }
        rule.waitForIdle()
        assertEquals(1, vm.images.value.size)
        assertEquals(1, fake.estimateCalls.size)

        // A pick while ReadyToSave discards the result and is estimated.
        addImages("b.jpg")
        assertEquals(JobUiState.Idle, vm.jobState.value)
        awaitEstimates()
        assertEquals(2, fake.estimateCalls.size)
    }

    @Test
    fun pendingEstimatesArePausedForTheJobAndResumeAfterIt() {
        fake.estimate = {
            if (fake.estimateCalls.size == 1) releaseAll.await(5, TimeUnit.SECONDS)
            fixedEstimate()
        }
        val gate = CountDownLatch(1)
        try {
            addImages("a.jpg", "b.jpg")
            CoreTestSupport.idleUntil(message = "first estimate held") { fake.estimateCalls.size == 1 }
            fake.beforeStep = { _, step -> if (step == 1) gate.await(5, TimeUnit.SECONDS) }
            click(TAG_JOB_RUN)
            assertTrue(vm.jobState.value is JobUiState.Running)
            // The in-flight estimate finishes; its (cancelled) result is dropped and nothing else is estimated.
            releaseAll.countDown()
            CoreTestSupport.idleUntil(message = "job on engine") { fake.calls.size == 1 }
            rule.waitForIdle()
            assertEquals(1, fake.estimateCalls.size)
            assertEquals(2, vm.estimates.value.pendingCount)
            assertCardTrailing(null, app.getString(R.string.imagestopdf_estimating))
        } finally {
            releaseAll.countDown()
            gate.countDown()
        }
        CoreTestSupport.idleUntil(message = "ready") { vm.jobState.value is JobUiState.ReadyToSave }
        awaitEstimates()
        assertEquals(3, fake.estimateCalls.size)
        allQualities.forEach { assertCardTrailing(it, expectedTotal(it, 2)) }
    }

    // ---- Invalidation, locking, leaving ----

    private fun readyResult() {
        runAndAwait()
        assertTrue(vm.jobState.value is JobUiState.ReadyToSave)
        assertTrue(CoreTestSupport.jobFiles(app).isNotEmpty())
    }

    private fun assertResultDiscarded() {
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, vm.jobState.value)
        awaitEstimates()
        CoreTestSupport.idleUntil(message = "cache empty") { CoreTestSupport.jobsEmpty(app) }
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
    }

    @Test
    fun changingAnInputAfterAResultDiscardsIt() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        awaitEstimates()

        readyResult()
        click(imagesToPdfQualityTag(CompressionLevel.MEDIUM))
        assertResultDiscarded()

        readyResult()
        click(imagesToPdfSizeTag(PageSize.LETTER))
        assertResultDiscarded()

        readyResult()
        click(fileRowDownTag(0))
        assertResultDiscarded()

        readyResult()
        click(fileRowRemoveTag(2))
        assertResultDiscarded()

        readyResult()
        addImages("d.jpg")
        assertResultDiscarded()

        // A failed result is cleared too.
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Err(PdfError.UnsupportedImage))
        runAndAwait()
        assertTrue(vm.jobState.value is JobUiState.Failed)
        click(imagesToPdfQualityTag(CompressionLevel.HIGH))
        assertResultDiscarded()

        // Selecting the option that is already selected changes nothing.
        fake.results.clear()
        readyResult()
        click(imagesToPdfQualityTag(CompressionLevel.HIGH))
        assertTrue(vm.jobState.value is JobUiState.ReadyToSave)
    }

    @Test
    fun inputsLockedWhileSavingAndSaveErrorKept() {
        addImages("a.jpg", "b.jpg")
        awaitEstimates()
        readyResult()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/slow.pdf")
        shadowOf(app.contentResolver).registerOutputStream(
            dest,
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    writing.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw IOException("disk full")
                }
            },
        )
        try {
            rule.runOnUiThread { vm.save(dest) }
            CoreTestSupport.idleUntil(message = "saving") { writing.count == 0L && vm.jobState.value is JobUiState.Saving }
            rule.waitForIdle()
            rule.onNodeWithTag(TAG_JOB_SAVING).performScrollTo().assertExists()
            rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.HIGH)).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
            rule.runOnUiThread {
                vm.setQuality(CompressionLevel.HIGH)
                vm.setPageSize(PageSize.A4)
                vm.moveDown(0)
                vm.remove(0)
                vm.add(listOf(image("x.jpg")))
            }
            rule.waitForIdle()
            assertEquals(null, vm.quality.value)
            assertEquals(PageSize.FIT_IMAGE, vm.pageSize.value)
            assertEquals(2, vm.images.value.size)
            assertTrue(vm.jobState.value is JobUiState.Saving)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "save failed") { vm.saveError.value != null }
        rule.waitForIdle()
        // The error stays visible in ReadyToSave (not wiped by a state change).
        assertTrue(vm.jobState.value is JobUiState.ReadyToSave)
        rule.onNodeWithTag(TAG_IMAGESTOPDF_SAVE_ERROR).performScrollTo()
            .assertTextEquals(app.getString(R.string.core_save_failed))
        // Changing an input clears it together with the stale result.
        click(imagesToPdfQualityTag(CompressionLevel.LOW))
        rule.onNodeWithTag(TAG_IMAGESTOPDF_SAVE_ERROR).assertDoesNotExist()
        assertEquals(JobUiState.Idle, vm.jobState.value)
    }

    @Test
    fun backWithResultAsksBeforeLeaving() {
        addImages("a.jpg")
        readyResult()
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertExists().performClick()
        rule.waitForIdle()
        assertEquals(0, backs.get())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertExists().performClick()
        rule.waitForIdle()
        assertEquals(1, backs.get())
    }

    @Test
    fun backWithNothingPendingLeavesDirectly() {
        addImages("a.jpg")
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertDoesNotExist()
        assertEquals(1, backs.get())
    }
}
