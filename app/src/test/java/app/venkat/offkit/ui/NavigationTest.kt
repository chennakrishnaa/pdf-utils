package app.venkat.offkit.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val cards = listOf("tool_images_to_pdf", "tool_compress", "tool_merge", "tool_add_images")

    @Before
    fun useFakeEngine() {
        // Opening a tool screen must never load MuPDF in Robolectric.
        (ApplicationProvider.getApplicationContext<Application>() as PdfUtilApp).engine = FakePdfEngine()
    }

    /** A tool screen is showing: it has a back action and the home footer is gone. */
    private fun assertToolScreenShowing() {
        rule.onNodeWithTag("action_back").assertIsDisplayed()
        rule.onNodeWithTag("home_footer").assertDoesNotExist()
    }

    @Test
    fun homeShowsAllToolCardsAndFooter() {
        cards.forEach { rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithTag("action_about").assertIsDisplayed()
        rule.onNodeWithTag("home_footer").performScrollTo()
            .assertTextEquals(rule.activity.getString(R.string.home_footer))
    }

    @Test
    fun eachCardOpensAToolScreenAndBackReturnsHome() {
        cards.forEach { card ->
            rule.onNodeWithTag(card).performScrollTo().performClick()
            rule.waitForIdle()
            assertToolScreenShowing()
            rule.onNodeWithTag("action_back").performClick()
            rule.waitForIdle()
            cards.forEach { rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        }
    }

    @Test
    fun systemBackReturnsHome() {
        rule.onNodeWithTag("tool_merge").performScrollTo().performClick()
        rule.waitForIdle()
        assertToolScreenShowing()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        cards.forEach { rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun aboutShowsLicenceCreditAndSourceUrl() {
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()
        val a = rule.activity
        rule.onNodeWithTag("about_license").performScrollTo()
            .assertTextEquals(a.getString(R.string.about_license))
        rule.onNodeWithTag("about_license_notice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("about_mupdf").performScrollTo()
            .assertTextEquals(a.getString(R.string.about_mupdf))
        rule.onNodeWithTag("about_source_url").performScrollTo()
            .assertTextEquals("https://github.com/chennakrishnaa/pdf-utils")
        rule.onNodeWithTag("about_version").performScrollTo()
            .assertTextEquals(a.getString(R.string.about_version, a.packageManager.getPackageInfo(a.packageName, 0).versionName))
        rule.onNodeWithTag("action_back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_images_to_pdf").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingSourceUrlOpensItWithActionView() {
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about_source_url").performScrollTo().performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://github.com/chennakrishnaa/pdf-utils"), started.data)
        // Still on About: nothing crashed or navigated.
        rule.onNodeWithTag("about_source_url").assertIsDisplayed()
    }
}
