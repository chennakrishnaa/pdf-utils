package com.offlinepdf.utils.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.offlinepdf.utils.MainActivity
import com.offlinepdf.utils.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val cards = listOf(
        "tool_images_to_pdf" to "stub_images_to_pdf",
        "tool_compress" to "stub_compress",
        "tool_merge" to "stub_merge",
        "tool_add_images" to "stub_add_images",
    )

    @Test
    fun homeShowsAllToolCardsAndFooter() {
        cards.forEach { (card, _) -> rule.onNodeWithTag(card).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithTag("action_about").assertIsDisplayed()
        rule.onNodeWithTag("home_footer").performScrollTo()
            .assertTextEquals(rule.activity.getString(R.string.home_footer))
    }

    @Test
    fun eachCardOpensItsStubAndBackReturnsHome() {
        cards.forEach { (card, stub) ->
            rule.onNodeWithTag(card).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(stub).assertIsDisplayed()
            rule.onNodeWithTag("action_back").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(stub).assertDoesNotExist()
            rule.onNodeWithTag(card).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun systemBackReturnsHome() {
        rule.onNodeWithTag("tool_merge").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("stub_merge").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("tool_merge").performScrollTo().assertIsDisplayed()
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
            .assertTextEquals("https://example.invalid/pdf-utils-source")
        rule.onNodeWithTag("about_version").performScrollTo()
            .assertTextEquals(a.getString(R.string.about_version, "0.1.0"))
        rule.onNodeWithTag("action_back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_images_to_pdf").performScrollTo().assertIsDisplayed()
    }
}
