package app.venkat.offkit.imagestopdf

import app.venkat.offkit.core.isReadyToSave
import app.venkat.offkit.core.isSaved
import app.venkat.offkit.core.isSavingNow
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PDF_DOCUMENT_OVERHEAD_BYTES
import app.venkat.offkit.engine.PDF_PAGE_OVERHEAD_BYTES
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfScreen
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfViewModel
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_ADD
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_QUALITY_NOTE
import app.venkat.offkit.feature.imagestopdf.TAG_IMAGESTOPDF_ROOT
import app.venkat.offkit.feature.imagestopdf.imagesToPdfQualityTag
import app.venkat.offkit.feature.imagestopdf.imagesToPdfSizeTag
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
import app.venkat.offkit.ui.components.TAG_JOB_SHARE
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.TAG_THUMB_ADD
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_EARLIER
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_LATER
import app.venkat.offkit.ui.components.TAG_THUMB_REMOVE
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.pdfErrorBody
import app.venkat.offkit.ui.components.pdfErrorTitle
import app.venkat.offkit.ui.components.thumbTag
import app.venkat.offkit.ui.assertOpensImageDocumentPicker
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // A share in an earlier test cached its own data dir; without this the share copy fails.
        CoreTestSupport.clearFileProviderCache()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        resetRecents()
        fake.beforeStep = { _, step -> if (step == 1) captureInputs() }
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
        resetRecents()
    }

    private fun resetRecents() {
        RecentFilesStore.resetForTesting()
        app.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Records the markers of the images the engine received for the current job, in order. */
    private fun captureInputs() {
        received.clear()
        fake.calls.last().images!!.forEach { received += it.readBytes().copyOfRange(3, it.length().toInt()).decodeToString() }
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

    private fun names(): List<String> = vm.images.value.map { it.name }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun pages(n: Int): String = app.resources.getQuantityString(R.plurals.pages_count, n, n)

    private fun state() = vm.jobState.value

    /** Clicks [tag], scrolling to it first when it is in the scrolling content (not the fixed bottom bar). */
    private fun click(tag: String) {
        val node = rule.onNodeWithTag(tag)
        if (node.hasScrollableAncestor()) node.performScrollTo()
        node.performClick()
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.hasScrollableAncestor(): Boolean {
        var parent = fetchSemanticsNode().parent
        while (parent != null) {
            if (SemanticsActions.ScrollBy in parent.config) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * Clicks tile [index]. performScrollTo only scrolls the nearest scroller (the horizontal row), so
     * the screen's column is first scrolled to the images header, just above the row.
     */
    private fun clickTile(index: Int) {
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo()
        rule.onNodeWithTag(thumbTag(index)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Selects tile [index] (if not selected yet) and clicks the toolbar button [tag]. */
    private fun onTile(index: Int, tag: String) {
        if (!rule.onNodeWithTag(thumbTag(index)).fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false }) {
            clickTile(index)
        }
        click(tag)
    }

    /** Back to the options with the inputs kept (what the error screen's button does). */
    private fun resetToOptions() {
        rule.runOnUiThread { vm.reset() }
        rule.waitForIdle()
    }

    private fun runAndAwait(): FakePdfEngine.Call {
        if (state() != JobUiState.Idle) resetToOptions()
        val n = fake.calls.size
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "job finished") {
            fake.calls.size == n + 1 && state() !is JobUiState.Running
        }
        rule.waitForIdle()
        return fake.calls.last()
    }

    private fun runToFailure(error: PdfError) {
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Err(error))
        runAndAwait()
        assertTrue(state() is JobUiState.Failed)
    }

    /** A save destination whose provider reports "images.pdf" as its display name. */
    private fun dest(sink: OutputStream): Uri =
        CoreTestSupport.saveDestination(app, sink, displayName = "images.pdf")

    private fun saveTo(sink: OutputStream) {
        rule.runOnUiThread { vm.save(dest(sink)) }
        CoreTestSupport.idleUntil(message = "saved") { state().isSaved }
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    // ---- Images: empty, add, select, move, remove ----

    @Test
    fun addTileAfterImagesOpensTheSamePickerAndIsNotCountedAsAnImage() {
        // Empty: the Add tile alone is the empty state.
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo()
        rule.onNodeWithTag(TAG_THUMB_ADD).assertIsDisplayed().assertTextEquals(str(R.string.add))
        addImages("a.jpg", "b.jpg", "c.jpg")
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 3)).assertExists()
        rule.onNodeWithTag(thumbTag(3)).assertDoesNotExist()
        rule.onNodeWithText(pages(3)).assertExists()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo()
        assertTrue(rule.onNodeWithTag(TAG_THUMB_ADD).getBoundsInRoot().left >= rule.onNodeWithTag(thumbTag(2)).getBoundsInRoot().right)

        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_IMAGESTOPDF_ADD)
        val header = shadowOf(rule.activity).nextStartedActivity
        assertOpensImageDocumentPicker(header, "the header Add")
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).performScrollTo()
        rule.onNodeWithTag(TAG_THUMB_ADD).performScrollTo().performClick()
        rule.waitForIdle()
        val tile = shadowOf(rule.activity).nextStartedActivity
        assertOpensImageDocumentPicker(tile, "the Add tile")

        // Move/remove still treat c.jpg as the last image.
        onTile(2, TAG_THUMB_MOVE_EARLIER)
        assertEquals(listOf("a.jpg", "c.jpg", "b.jpg"), names())
        onTile(1, TAG_THUMB_REMOVE)
        assertEquals(listOf("a.jpg", "b.jpg"), names())
        rule.onNodeWithTag(thumbTag(2)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_THUMB_ADD).assertExists()
    }

    @Test
    fun noImages_buttonDisabledWithAddImagesFirst() {
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ROOT).assertExists()
        rule.onNodeWithText(str(R.string.screen_images)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 0)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertIsDisplayed().assertIsEnabled().assertTextEquals(str(R.string.add))
        rule.onNodeWithText(str(R.string.images_hint)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.add_images_first))
        rule.onNodeWithText(pages(0)).assertExists()
        rule.onNodeWithText("≈", substring = true).assertDoesNotExist()
        allQualities.forEach(::assertNoTrailing)
        assertTrue(fake.estimateCalls.isEmpty())

        // Clicking the disabled button does nothing.
        rule.onNodeWithTag(TAG_JOB_RUN).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun addOpensTheImagePicker() {
        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_IMAGESTOPDF_ADD)
        assertNotNull(shadowOf(rule.activity).nextStartedActivity)
    }

    @Test
    fun addShowsTilesCountAndEnablesTheButton() {
        addImages("a.jpg", "b.jpg")
        addImages("c.jpg") // a second pick appends
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.images_hint)).assertIsDisplayed()
        (0..2).forEach { rule.onNodeWithTag(thumbTag(it)).performScrollTo().assertIsDisplayed().assertIsNotSelected() }
        rule.onNodeWithTag(thumbTag(3)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled().assertTextEquals(str(R.string.action_create_pdf))
        rule.onNodeWithText(pages(3)).assertExists()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names())

        // Removing every image returns to the empty state.
        repeat(3) { onTile(0, TAG_THUMB_REMOVE) }
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 0)).assertExists()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.add_images_first))
    }

    @Test
    fun tapSelectsAndTapAgainDeselects() {
        addImages("a.jpg", "b.jpg")
        clickTile(1)
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        rule.onNodeWithTag(thumbTag(0)).assertIsNotSelected()
        rule.onNodeWithText(str(R.string.cd_image_n, 2)).assertExists()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertExists()

        clickTile(0)
        rule.onNodeWithTag(thumbTag(0)).assertIsSelected()
        rule.onNodeWithTag(thumbTag(1)).assertIsNotSelected()

        clickTile(0)
        rule.onNodeWithTag(thumbTag(0)).assertIsNotSelected()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertDoesNotExist()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
    }

    @Test
    fun moveEarlierAndLater_selectionFollows_endsDisabled() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        clickTile(0)
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsEnabled()

        click(TAG_THUMB_MOVE_LATER)
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names())
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        click(TAG_THUMB_MOVE_LATER)
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), names())
        rule.onNodeWithTag(thumbTag(2)).assertIsSelected()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertIsEnabled()

        click(TAG_THUMB_MOVE_EARLIER)
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), names())
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        rule.onNodeWithTag(thumbTag(2)).assertIsNotSelected()
        rule.onNodeWithText(str(R.string.cd_image_n, 2)).assertExists()
    }

    @Test
    fun removeClearsSelection_andAddingClearsIt() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        onTile(1, TAG_THUMB_REMOVE)
        assertEquals(listOf("a.jpg", "c.jpg"), names())
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
        (0..1).forEach { rule.onNodeWithTag(thumbTag(it)).assertIsNotSelected() }
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 2)).assertExists()

        clickTile(1)
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected()
        addImages("d.jpg")
        (0..2).forEach { rule.onNodeWithTag(thumbTag(it)).assertIsNotSelected() }
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
    }

    @Test
    fun reorderAndRemoveChangeWhatTheEngineReceives() {
        addImages("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        onTile(0, TAG_THUMB_MOVE_LATER) // b a c d
        onTile(3, TAG_THUMB_MOVE_EARLIER) // b a d c
        onTile(1, TAG_THUMB_REMOVE) // b d c
        assertEquals(listOf("b.jpg", "d.jpg", "c.jpg"), names())

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
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertExists()
        assertEquals(listOf("same.jpg", "same.jpg"), names())
        runAndAwait()
        assertEquals(listOf("same.jpg", "same.jpg"), received.toList())
    }

    // ---- Options ----

    @Test
    fun selectedPageSizeReachesEngine() {
        addImages("a.jpg")
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.FIT_IMAGE)).performScrollTo().assertIsSelected()
            .assertTextEquals(str(R.string.page_size_fit))
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).assertIsNotSelected().assertTextEquals(str(R.string.page_size_a4))
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.LETTER)).assertIsNotSelected().assertTextEquals(str(R.string.page_size_letter))
        assertEquals(PageSize.FIT_IMAGE, runAndAwait().pageSize)

        resetToOptions()
        click(imagesToPdfSizeTag(PageSize.A4))
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.A4)).assertIsSelected()
        rule.onNodeWithTag(imagesToPdfSizeTag(PageSize.FIT_IMAGE)).assertIsNotSelected()
        assertEquals(PageSize.A4, runAndAwait().pageSize)

        resetToOptions()
        click(imagesToPdfSizeTag(PageSize.LETTER))
        assertEquals(PageSize.LETTER, runAndAwait().pageSize)
    }

    @Test
    fun qualityDefaultsToOriginalAndSelectedLevelReachesEngine() {
        addImages("a.jpg")
        rule.onNodeWithTag(imagesToPdfQualityTag(null)).performScrollTo().assertIsSelected()
            .assert(hasText(str(R.string.quality_original)))
            .assert(hasText(str(R.string.quality_original_desc)))
        rule.onNodeWithTag(TAG_IMAGESTOPDF_QUALITY_NOTE).performScrollTo().assertTextEquals(str(R.string.estimate_note))
        assertEquals(null, runAndAwait().compression)

        CompressionLevel.entries.forEach { level ->
            resetToOptions()
            click(imagesToPdfQualityTag(level))
            rule.onNodeWithTag(imagesToPdfQualityTag(level)).assertIsSelected()
            rule.onNodeWithTag(imagesToPdfQualityTag(null)).assertIsNotSelected()
            assertEquals(level, runAndAwait().compression)
        }
        resetToOptions()
        // The pack descriptions name the engine's real limits.
        rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.LOW)).performScrollTo()
            .assert(hasText("Up to ${CompressionLevel.LOW.maxLongEdgePx} px, very good quality"))
        rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.MEDIUM))
            .assert(hasText("Up to ${CompressionLevel.MEDIUM.maxLongEdgePx} px, good quality"))
        rule.onNodeWithTag(imagesToPdfQualityTag(CompressionLevel.HIGH))
            .assert(hasText("Up to ${CompressionLevel.HIGH.maxLongEdgePx} px, smallest file"))
    }

    // ---- Size estimates ----

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

    private fun assertRowTrailing(level: CompressionLevel?, text: String) {
        rule.onNodeWithTag(imagesToPdfQualityTag(level)).performScrollTo().assert(hasText(text))
    }

    private fun assertNoTrailing(level: CompressionLevel?) {
        val tag = imagesToPdfQualityTag(level)
        rule.onNodeWithTag(tag).performScrollTo().assertExists()
        rule.onNode(hasTestTag(tag) and hasText("≈", substring = true)).assertDoesNotExist()
    }

    /** The bottom bar shows "N pages" with [estimate] under it (or nothing under it when null). */
    private fun assertBottomSummary(images: Int, estimate: String?) {
        if (estimate != null) {
            rule.onNode(hasText(pages(images)) and hasText(estimate)).assertExists()
        } else {
            rule.onNode(hasText(pages(images)) and hasText("≈", substring = true)).assertDoesNotExist()
            rule.onNodeWithText(pages(images)).assertExists()
        }
    }

    @Test
    fun estimatesShowPerRowAndInTheBottomBar_andFollowTheList() {
        fake.estimate = { fixedEstimate() }
        addImages("a.jpg", "b.jpg", "c.jpg")
        awaitEstimates()
        assertEquals(3, fake.estimateCalls.size)
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 3)) }
        assertEquals("≈ 2.9 MB", expectedTotal(null, 3))
        assertBottomSummary(3, expectedTotal(null, 3))

        // The bottom bar follows the selected quality.
        click(imagesToPdfQualityTag(CompressionLevel.HIGH))
        assertBottomSummary(3, expectedTotal(CompressionLevel.HIGH, 3))

        addImages("d.jpg")
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 4)) }
        assertBottomSummary(4, expectedTotal(CompressionLevel.HIGH, 4))

        onTile(0, TAG_THUMB_MOVE_LATER)
        onTile(3, TAG_THUMB_MOVE_EARLIER)
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 4)) }

        onTile(1, TAG_THUMB_REMOVE)
        awaitEstimates()
        assertEquals(4, fake.estimateCalls.size)
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 3)) }

        repeat(3) { onTile(0, TAG_THUMB_REMOVE) }
        allQualities.forEach(::assertNoTrailing)
        assertBottomSummary(0, null)
    }

    @Test
    fun heldEstimatesShowNothingThenTotals() {
        fake.estimate = {
            releaseAll.await(5, TimeUnit.SECONDS)
            fixedEstimate()
        }
        try {
            addImages("a.jpg", "b.jpg")
            CoreTestSupport.idleUntil(message = "estimate started") { fake.estimateCalls.isNotEmpty() }
            rule.waitForIdle()
            assertEquals(2, vm.estimates.value.pendingCount)
            allQualities.forEach(::assertNoTrailing)
            assertBottomSummary(2, null)
        } finally {
            releaseAll.countDown()
        }
        awaitEstimates()
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 2)) }
        assertBottomSummary(2, expectedTotal(null, 2))
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
            rule.waitForIdle()
            onTile(0, TAG_THUMB_REMOVE)
            assertEquals(1, vm.estimates.value.pendingCount)
        } finally {
            releaseAll.countDown()
        }
        awaitEstimates()
        // a.jpg (cancelled once in progress) and b.jpg; a.jpg is not estimated again.
        assertEquals(2, fake.estimateCalls.size)
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 1)) }
        assertEquals(listOf("b.jpg"), names())
    }

    @Test
    fun failingEstimateShowsNoSize() {
        fake.estimate = { PdfEstimate.Err(PdfError.UnsupportedImage) }
        addImages("a.jpg", "b.jpg")
        CoreTestSupport.idleUntil(message = "failed") { vm.estimates.value.anyFailed && vm.estimates.value.pendingCount == 0 }
        rule.waitForIdle()
        allQualities.forEach(::assertNoTrailing)
        assertBottomSummary(2, null)
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
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
        CoreTestSupport.idleUntil(message = "done") { state().isReadyToSave }
        rule.waitForIdle()
        assertEquals(1, vm.images.value.size)
        assertEquals(1, fake.estimateCalls.size)

        // A pick while ReadyToSave discards the result and is estimated.
        addImages("b.jpg")
        assertEquals(JobUiState.Idle, state())
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
            assertTrue(state() is JobUiState.Running)
            // The in-flight estimate finishes; its (cancelled) result is dropped and nothing else is estimated.
            releaseAll.countDown()
            CoreTestSupport.idleUntil(message = "job on engine") { fake.calls.size == 1 }
            rule.waitForIdle()
            assertEquals(1, fake.estimateCalls.size)
            assertEquals(2, vm.estimates.value.pendingCount)
        } finally {
            releaseAll.countDown()
            gate.countDown()
        }
        CoreTestSupport.idleUntil(message = "ready") { state().isReadyToSave }
        awaitEstimates()
        assertEquals(3, fake.estimateCalls.size)
        resetToOptions()
        allQualities.forEach { assertRowTrailing(it, expectedTotal(it, 2)) }
    }

    // ---- Run, processing, result ----

    @Test
    fun runProcessingResultSaveAndSaved() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        val content = "%PDF-1.7\n% three pages\n".toByteArray()
        fake.outputContent = content
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Ok(pages = 3, bytes = 1_258_291))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 1) captureInputs()
            if (step == 2) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "held at step 2") {
            reached.count == 0L && state() == JobUiState.Running(1, 3)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertIsDisplayed()
        // The options are gone while processing; the ViewModel ignores input changes.
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertDoesNotExist()
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        rule.runOnUiThread {
            vm.setQuality(CompressionLevel.HIGH)
            vm.setPageSize(PageSize.A4)
            vm.remove(0)
        }
        assertEquals(null, vm.quality.value)
        assertEquals(PageSize.FIT_IMAGE, vm.pageSize.value)
        assertEquals(3, vm.images.value.size)

        release.countDown()
        CoreTestSupport.idleUntil(message = "ready") { state().isReadyToSave }
        rule.waitForIdle()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), received.toList())
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pages(3))
        rule.onNodeWithText(formatBytes(1_258_291)).assertExists()
        assertEquals("1.2 MB", formatBytes(1_258_291))
        rule.onNodeWithText("images.pdf").assertExists()
        rule.onNodeWithText(str(R.string.result_not_saved)).assertExists()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_as)).assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_discard))
        assertEquals("images.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        saveTo(sink)
        assertArrayEquals(content, sink.toByteArray())
        assertEquals("images.pdf", (state() as JobUiState.Done).savedName)
        rule.onNodeWithText(str(R.string.result_saved_as, "images.pdf")).assertExists()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_copy))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pages(3))
        CoreTestSupport.idleUntil(message = "recorded") { RecentFilesStore.get(app).recents.value.isNotEmpty() }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("images_to_pdf", recents.single().tool)
    }

    @Test
    fun singlePageResultUsesSingular() {
        addImages("a.jpg")
        fake.setResult(FakePdfEngine.Op.IMAGES_TO_PDF, PdfResult.Ok(pages = 1, bytes = 500))
        runAndAwait()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals("1 page")
        rule.onNodeWithText("500 B").assertExists()
    }

    private fun assertErrorScreenReturnsToOptions(error: PdfError) {
        runToFailure(error)
        rule.onNodeWithText(str(pdfErrorTitle(error))).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(pdfErrorBody(error)))
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.job_try_again)).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ROOT).assertExists()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        assertEquals(listOf("a.jpg", "b.jpg"), names())
        assertEquals(0, backs.get())
    }

    @Test
    fun engineErrorsShowErrorScreen_buttonReturnsToOptionsWithImages() {
        addImages("a.jpg", "b.jpg")
        assertErrorScreenReturnsToOptions(PdfError.UnsupportedImage)
        assertErrorScreenReturnsToOptions(PdfError.ImageTooLarge)
        assertErrorScreenReturnsToOptions(PdfError.Corrupt)

        // Back on the error screen also returns to the options.
        runToFailure(PdfError.UnsupportedImage)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, backs.get())
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertExists()
    }

    @Test
    fun cancelReturnsToOptionsWithImagesKept() {
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
        CoreTestSupport.idleUntil(message = "idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertIsEnabled()
        // The list is kept for another attempt.
        rule.onNodeWithTag(thumbTag(1)).performScrollTo().assertExists()
        rule.onNodeWithText(str(R.string.imagestopdf_section_count, 2)).assertExists()
        assertEquals(listOf("a.jpg", "b.jpg"), names())
        assertEquals(0, backs.get())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    // ---- Invalidation, locking, leaving ----

    private fun readyResult() {
        runAndAwait()
        assertTrue(state().isReadyToSave)
        assertTrue(CoreTestSupport.jobFiles(app).isNotEmpty())
    }

    private fun assertResultDiscarded() {
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        awaitEstimates()
        CoreTestSupport.idleUntil(message = "cache empty") { CoreTestSupport.jobsEmpty(app) }
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
    }

    @Test
    fun resultScreenOffersNoInputs_viewModelStillDropsStaleResult() {
        addImages("a.jpg", "b.jpg", "c.jpg")
        awaitEstimates()

        readyResult()
        // The options cannot be changed from the result screen.
        rule.onNodeWithTag(TAG_IMAGESTOPDF_ADD).assertDoesNotExist()
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        allQualities.forEach { rule.onNodeWithTag(imagesToPdfQualityTag(it)).assertDoesNotExist() }
        PageSize.entries.forEach { rule.onNodeWithTag(imagesToPdfSizeTag(it)).assertDoesNotExist() }

        // ViewModel contract (e.g. a pick delivered late): any input change drops the result.
        rule.runOnUiThread { vm.setQuality(CompressionLevel.MEDIUM) }
        assertResultDiscarded()

        readyResult()
        rule.runOnUiThread { vm.setPageSize(PageSize.LETTER) }
        assertResultDiscarded()

        readyResult()
        rule.runOnUiThread { vm.moveDown(0) }
        assertResultDiscarded()

        readyResult()
        rule.runOnUiThread { vm.remove(2) }
        assertResultDiscarded()

        readyResult()
        addImages("d.jpg")
        assertResultDiscarded()

        // A failed result is cleared too.
        runToFailure(PdfError.UnsupportedImage)
        rule.runOnUiThread { vm.setQuality(CompressionLevel.HIGH) }
        assertResultDiscarded()

        // Selecting the option that is already selected changes nothing.
        fake.results.clear()
        readyResult()
        rule.runOnUiThread { vm.setQuality(CompressionLevel.HIGH) }
        rule.waitForIdle()
        assertTrue(state().isReadyToSave)
    }

    @Test
    fun inputsLockedWhileSaving_failedSaveShowsSnackbarAndKeepsResult() {
        addImages("a.jpg", "b.jpg")
        awaitEstimates()
        readyResult()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = dest(
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
            rule.runOnUiThread { vm.save(target) }
            CoreTestSupport.idleUntil(message = "saving") { writing.count == 0L && state().isSavingNow }
            rule.waitForIdle()
            rule.onNodeWithTag(TAG_JOB_SAVING).assertExists().assertIsNotEnabled()
            rule.onNodeWithTag(TAG_JOB_SHARE).assertIsNotEnabled()
            rule.onNodeWithTag(TAG_JOB_RESET).assertIsNotEnabled()
            // Leaving is ignored while saving.
            systemBack()
            rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertDoesNotExist()
            assertEquals(0, backs.get())
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
            assertEquals(listOf("a.jpg", "b.jpg"), names())
            assertTrue(state().isSavingNow)
        } finally {
            release.countDown()
        }
        val failed = str(R.string.error_save_message, str(R.string.error_save_title), str(R.string.error_save_body))
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

    @Test
    fun shareButtonRequestsShare() {
        addImages("a.jpg")
        readyResult()
        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_JOB_SHARE)
        CoreTestSupport.idleUntil(rule, message = "shared") { (state() as? JobUiState.Done)?.shared == true }
        rule.waitForIdle()
        assertNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertNull(vm.shareRequest.value)
        rule.onNodeWithText(str(R.string.result_shared_only)).assertExists()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
    }

    @Test
    fun discardOnUnsavedResultAsks_keepStays_confirmLeaves() {
        addImages("a.jpg")
        readyResult()
        click(TAG_JOB_RESET)
        rule.onNodeWithText(str(R.string.discard_title)).assertExists()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(0, backs.get())
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
        assertTrue(state().isReadyToSave)

        // Close and system back ask as well; confirming leaves and drops the result.
        rule.onNodeWithTag(TAG_ACTION_CLOSE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_STAY).performClick()
        rule.waitForIdle()
        systemBack()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, backs.get())
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun doneAfterSaveLeavesWithoutDialog() {
        addImages("a.jpg")
        readyResult()
        saveTo(ByteArrayOutputStream())
        click(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertDoesNotExist()
        assertEquals(1, backs.get())
        assertEquals(JobUiState.Idle, state())
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
