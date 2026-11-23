package app.venkat.paperpixel.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.venkat.paperpixel.FakePdfEngine
import app.venkat.paperpixel.MainActivity
import app.venkat.paperpixel.PdfUtilApp
import app.venkat.paperpixel.R
import app.venkat.paperpixel.core.CoreTestSupport
import app.venkat.paperpixel.core.RecentFile
import app.venkat.paperpixel.core.RecentFilesStore
import app.venkat.paperpixel.ui.components.formatBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeRecentsTest {
    /** Runs before the activity starts: a fresh store with empty preferences, and the fake engine. */
    @get:Rule(order = 0)
    val freshStore = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            (app as PdfUtilApp).engine = FakePdfEngine()
            app.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
            RecentFilesStore.resetForTesting()
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecentFilesStore.resetForTesting()
    }

    private val store: RecentFilesStore get() = RecentFilesStore.get(rule.activity)

    private fun fileUri(name: String, exists: Boolean = true): Uri {
        val f = File(rule.activity.filesDir, name)
        if (exists) f.writeBytes("%PDF-1.4".toByteArray()) else f.delete()
        return Uri.fromFile(f)
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = CoreTestSupport.idleUntil(message = tag) { exists(tag) }

    private fun waitGone(tag: String) = CoreTestSupport.idleUntil(message = "$tag gone") { !exists(tag) }

    private fun addTwo(): Pair<RecentFile, RecentFile> {
        val older = RecentFile(fileUri("older.pdf"), "older.pdf", 2048, System.currentTimeMillis() - 5 * 60_000, "merge")
        val newer = RecentFile(fileUri("newer.pdf"), "newer.pdf", 3L * 1024 * 1024, System.currentTimeMillis(), "compress")
        store.add(older)
        store.add(newer)
        return older to newer
    }

    @Test
    fun emptyStoreShowsHint() {
        waitFor("recent_empty")
        rule.onNodeWithTag("recent_section").performScrollTo()
        rule.onNodeWithTag("recent_empty").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("recent_clear").assertDoesNotExist()
        rule.onNodeWithTag("recent_row_0").assertDoesNotExist()
    }

    @Test
    fun rowsRenderNewestFirstWithNameToolAndSize() {
        waitFor("recent_empty")
        addTwo()
        waitFor("recent_row_1")
        val a = rule.activity
        rule.onNodeWithTag("recent_row_0").performScrollTo()
            .assertTextContains("newer.pdf")
            .assertTextContains(a.getString(R.string.tool_compress_title), substring = true)
            .assertTextContains(formatBytes(3L * 1024 * 1024), substring = true)
            .assertTextContains(a.getString(R.string.recent_just_now), substring = true)
        rule.onNodeWithTag("recent_row_1").performScrollTo()
            .assertTextContains("older.pdf")
            .assertTextContains(a.getString(R.string.tool_merge_title), substring = true)
            .assertTextContains("2.0 KB", substring = true)
            .assertTextContains(a.getString(R.string.recent_minutes_ago, 5), substring = true)
        rule.onNodeWithTag("recent_empty").assertDoesNotExist()
        // The remove action says it only removes the entry from the list.
        rule.onNode(hasTestTag("recent_row_0_remove") and hasContentDescription(a.getString(R.string.recent_remove)))
            .assertExists()
    }

    @Test
    fun unknownToolIdShowsNoLabel() {
        waitFor("recent_empty")
        store.add(RecentFile(fileUri("x.pdf"), "x.pdf", 10, System.currentTimeMillis(), ""))
        waitFor("recent_row_0")
        rule.onNodeWithTag("recent_row_0").performScrollTo()
            .assertTextContains("10 B · ${rule.activity.getString(R.string.recent_just_now)}", substring = true)
    }

    @Test
    fun removeDeletesOneEntry() {
        waitFor("recent_empty")
        val (older, _) = addTwo()
        waitFor("recent_row_1")
        rule.onNodeWithTag("recent_row_0_remove").performScrollTo().performClick()
        CoreTestSupport.idleUntil { store.recents.value.size == 1 }
        waitGone("recent_row_1")
        assertEquals(listOf(older.uri), store.recents.value.map { it.uri })
        rule.onNodeWithTag("recent_row_0").assertTextContains("older.pdf")
    }

    @Test
    fun clearAsksForConfirmationThenEmptiesStore() {
        waitFor("recent_empty")
        addTwo()
        waitFor("recent_row_1")
        rule.onNodeWithTag("recent_clear").performScrollTo().performClick()
        rule.onAllNodesWithText(rule.activity.getString(R.string.recent_clear_text)).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        rule.onNodeWithTag("recent_clear_cancel").performClick()
        rule.waitForIdle()
        assertEquals(2, store.recents.value.size)

        rule.onNodeWithTag("recent_clear").performScrollTo().performClick()
        rule.onNodeWithTag("recent_clear_confirm").performClick()
        CoreTestSupport.idleUntil { store.recents.value.isEmpty() }
        waitFor("recent_empty")
        rule.onNodeWithTag("recent_row_0").assertDoesNotExist()
    }

    @Test
    fun tappingAvailableEntryStartsViewIntent() {
        waitFor("recent_empty")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val newer = RecentFile(
            CoreTestSupport.contentUri(app, "newer.pdf", "%PDF-1.4".toByteArray()),
            "newer.pdf", 8, System.currentTimeMillis(), "merge",
        )
        store.add(newer)
        waitFor("recent_row_0")
        val shadow = shadowOf(rule.activity)
        while (shadow.nextStartedActivity != null) Unit // drain
        rule.onNodeWithTag("recent_row_0").performScrollTo().performClick()
        var started: Intent? = null
        CoreTestSupport.idleUntil(message = "view intent") {
            started = shadow.nextStartedActivity
            started != null
        }
        val intent = started!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(newer.uri, intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, store.recents.value.size)
    }

    @Test
    fun unavailableEntryShowsMessageAndIsRemoved() {
        waitFor("recent_empty")
        val gone = RecentFile(fileUri("gone.pdf", exists = false), "gone.pdf", 5, System.currentTimeMillis(), "merge")
        store.add(gone)
        waitFor("recent_row_0")
        val shadow = shadowOf(rule.activity)
        while (shadow.nextStartedActivity != null) Unit
        rule.onNodeWithTag("recent_row_0").performScrollTo().performClick()
        val message = rule.activity.getString(R.string.recent_unavailable)
        CoreTestSupport.idleUntil(message = "snackbar") {
            rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        CoreTestSupport.idleUntil { store.recents.value.isEmpty() }
        waitFor("recent_empty")
        assertNull(shadow.nextStartedActivity)
    }

    @Test
    fun shareStartsChooser() {
        waitFor("recent_empty")
        val (_, newer) = addTwo()
        waitFor("recent_row_0")
        val shadow = shadowOf(rule.activity)
        while (shadow.nextStartedActivity != null) Unit
        rule.onNodeWithTag("recent_row_0_share").performScrollTo().performClick()
        var started: Intent? = null
        CoreTestSupport.idleUntil(message = "share intent") {
            started = shadow.nextStartedActivity
            started != null
        }
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        val send = started!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(newer.uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }
}
