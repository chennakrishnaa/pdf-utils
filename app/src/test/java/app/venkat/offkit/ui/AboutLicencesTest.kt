package app.venkat.offkit.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.ui.about.ASSET_LICENSE
import app.venkat.offkit.ui.about.ASSET_NOTICES
import app.venkat.offkit.ui.about.ASSET_PRIVACY
import app.venkat.offkit.ui.about.TAG_ABOUT_NO_DATA
import app.venkat.offkit.ui.about.TAG_ABOUT_PRIVACY
import app.venkat.offkit.ui.about.TAG_ABOUT_PRIVACY_URL
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextEquals
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import app.venkat.offkit.ui.about.TAG_ABOUT_AGPL_TEXT
import app.venkat.offkit.ui.about.TAG_ABOUT_NOTICES
import app.venkat.offkit.ui.about.TAG_LICENCE_TEXT
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AboutLicencesTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun useFakeEngine() {
        (ApplicationProvider.getApplicationContext<Application>() as PdfUtilApp).engine = FakePdfEngine()
    }

    private fun openAboutRow(tag: String) {
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        CoreTestSupport.idleUntil(message = "licence text loaded") {
            rule.onAllNodesWithTag(TAG_LICENCE_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToText(text: String) {
        rule.onNodeWithTag(TAG_LICENCE_TEXT).performScrollToNode(hasText(text, substring = true))
        rule.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun noticesScreenListsMuPdfLibrariesAndApacheLicence() {
        openAboutRow(TAG_ABOUT_NOTICES)
        scrollToText("MuPDF")
        scrollToText("FreeType")
        scrollToText("HarfBuzz")
        scrollToText("Independent JPEG Group")
        scrollToText("Apache License")
        scrollToText("Material Symbols")
        // Back closes the text and returns to About, not Home.
        rule.onNodeWithTag("action_back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_NOTICES).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun agplTextIsReadableOffline() {
        openAboutRow(TAG_ABOUT_AGPL_TEXT)
        scrollToText("GNU AFFERO GENERAL PUBLIC LICENSE")
        scrollToText("END OF TERMS AND CONDITIONS")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_AGPL_TEXT).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun bundledAssetsAreReadable() {
        val assets = rule.activity.assets
        listOf(ASSET_LICENSE, ASSET_NOTICES, ASSET_PRIVACY).forEach { name ->
            assets.open(name).use { check(it.readBytes().isNotEmpty()) { name } }
        }
    }

    @Test
    fun privacyPolicyIsReadableOffline() {
        openAboutRow(TAG_ABOUT_PRIVACY)
        // Titled as the policy (not "Open-source licences").
        rule.onAllNodesWithText("Privacy policy").onFirst().assertIsDisplayed()
        scrollToText("OFFKIT PRIVACY POLICY")
        scrollToText("collects no personal data")
        scrollToText("requests no Android permissions and makes no network connections")
        scrollToText("chennakrishnaa@yahoo.com")
        rule.onNodeWithTag("action_back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_PRIVACY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutSaysNoDataCollected() {
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_NO_DATA).performScrollTo().assertTextEquals("No data collected. Works offline.")
    }

    @Test
    fun privacyPolicyLinkOpensWithActionView() {
        val url = "https://github.com/chennakrishnaa/pdf-utils/blob/main/PRIVACY.md"
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_PRIVACY_URL).performScrollTo().assertTextEquals(url).performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse(url), started.data)
        assertEquals(true, started.hasCategory(Intent.CATEGORY_BROWSABLE))
        rule.onNodeWithTag(TAG_ABOUT_PRIVACY_URL).assertIsDisplayed()
    }
}
