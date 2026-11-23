package app.venkat.paperpixel.ui

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
import app.venkat.paperpixel.core.CoreTestSupport
import app.venkat.paperpixel.FakePdfEngine
import app.venkat.paperpixel.MainActivity
import app.venkat.paperpixel.PdfUtilApp
import app.venkat.paperpixel.ui.about.ASSET_LICENSE
import app.venkat.paperpixel.ui.about.ASSET_NOTICES
import app.venkat.paperpixel.ui.about.TAG_ABOUT_AGPL_TEXT
import app.venkat.paperpixel.ui.about.TAG_ABOUT_NOTICES
import app.venkat.paperpixel.ui.about.TAG_LICENCE_TEXT
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
        listOf(ASSET_LICENSE, ASSET_NOTICES).forEach { name ->
            assets.open(name).use { check(it.readBytes().isNotEmpty()) { name } }
        }
    }
}
