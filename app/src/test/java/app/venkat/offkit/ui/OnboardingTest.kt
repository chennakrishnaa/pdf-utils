package app.venkat.offkit.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.TAG_ABOUT_INTRO
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.onboarding.ONBOARDING_PAGE_COUNT
import app.venkat.offkit.ui.onboarding.OnboardingStore
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_DONE
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_INDICATOR
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_NEXT
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_ROOT
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_SKIP
import app.venkat.offkit.ui.onboarding.onboardingPageTag
import app.venkat.offkit.ui.onboarding.onboardingReducedMotion
import app.venkat.offkit.ui.onboarding.onboardingTitleTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The first-run introduction: when it shows, its navigation, the stored flag and replay from About. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingTest {
    /** A fresh install: the flag is cleared before the activity starts. */
    @get:Rule(order = Int.MIN_VALUE)
    val firstRun = OnboardingSeenRule(seen = false)

    @get:Rule(order = 0)
    val fakeEngine = object : ExternalResource() {
        override fun before() {
            (ApplicationProvider.getApplicationContext<Application>() as PdfUtilApp).engine = FakePdfEngine()
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val seen: Boolean
        get() = OnboardingStore(ApplicationProvider.getApplicationContext()).seen

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun assertHome() {
        rule.onNodeWithTag("tool_images_to_pdf").performScrollTo().assertIsDisplayed()
        assertFalse("introduction still shown", exists(TAG_ONBOARDING_ROOT))
    }

    private fun assertOnPage(number: Int) {
        rule.onNodeWithTag(onboardingPageTag(number)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_ONBOARDING_INDICATOR)
            .assertContentDescriptionEquals(rule.activity.getString(R.string.onboarding_page_of, number, ONBOARDING_PAGE_COUNT))
    }

    private fun next(times: Int = 1) = repeat(times) {
        rule.onNodeWithTag(TAG_ONBOARDING_NEXT).performClick()
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun freshInstallShowsIntroductionAndGetStartedOpensHomeForGood() {
        rule.onNodeWithTag(TAG_ONBOARDING_ROOT).assertIsDisplayed()
        assertFalse("home shown on first run", exists("tool_images_to_pdf"))
        assertOnPage(1)
        rule.onNodeWithTag(onboardingTitleTag(1)).assertTextEquals(rule.activity.getString(R.string.onboarding_welcome_title))
        rule.onNodeWithTag(onboardingTitleTag(1)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithTag(TAG_ONBOARDING_SKIP).assertIsDisplayed()
        assertFalse(exists(TAG_ONBOARDING_DONE))

        next(3)
        assertOnPage(4)
        assertFalse("Skip shown on the last page", exists(TAG_ONBOARDING_SKIP))
        assertFalse(exists(TAG_ONBOARDING_NEXT))
        assertFalse("flag set before finishing", seen)

        rule.onNodeWithTag(TAG_ONBOARDING_DONE).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertHome()
        assertTrue(seen)

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertHome()
    }

    @Test
    fun swipingChangesPage() {
        rule.onNodeWithTag(onboardingPageTag(1)).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertOnPage(2)
    }

    /** Page 2 shows the four tools as a grid; every tool name is on screen (scrolling the sheet if needed). */
    @Test
    fun toolGridShowsAllFourToolNames() {
        next()
        assertOnPage(2)
        for (name in listOf(
            R.string.tool_images_to_pdf_title,
            R.string.tool_compress_title,
            R.string.tool_merge_title,
            R.string.tool_add_images_title,
        )) {
            rule.onNodeWithText(rule.activity.getString(name)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun skipFromFirstPageOpensHomeAndSetsFlag() {
        rule.onNodeWithTag(TAG_ONBOARDING_SKIP).performClick()
        rule.waitForIdle()
        assertHome()
        assertTrue(seen)
    }

    /** Back on a later page goes to the previous page; Back on page 1 dismisses the introduction like Skip (home, flag set). */
    @Test
    fun backGoesToPreviousPageThenHomeFromFirstPage() {
        next(2)
        assertOnPage(3)
        pressBack()
        assertOnPage(2)
        assertFalse(seen)
        pressBack()
        assertOnPage(1)
        assertFalse(seen)
        pressBack()
        assertHome()
        assertTrue(seen)
    }

    @Test
    fun recreateKeepsPageAndDoesNotSetFlag() {
        next(2)
        assertOnPage(3)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ONBOARDING_ROOT).assertIsDisplayed()
        assertOnPage(3)
        assertFalse(seen)
    }

    @Test
    fun aboutShowIntroductionOpensFlowAndReturnsToAbout() {
        rule.onNodeWithTag(TAG_ONBOARDING_SKIP).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("action_about").performClick()
        rule.waitForIdle()

        // Finish with Get started: back on About.
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performScrollTo().performClick()
        rule.waitForIdle()
        assertOnPage(1)
        next(3)
        rule.onNodeWithTag(TAG_ONBOARDING_DONE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performScrollTo().assertIsDisplayed()
        assertFalse(exists(TAG_ONBOARDING_ROOT))

        // Skip: back on About.
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ONBOARDING_SKIP).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performScrollTo().assertIsDisplayed()

        // System Back on page 1: back on About (not home), then Back again leaves About.
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performClick()
        rule.waitForIdle()
        pressBack()
        rule.onNodeWithTag(TAG_ABOUT_INTRO).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        assertHome()
    }
}

/** Animations turned off in system settings: static illustrations, plain paging, same behaviour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingReducedMotionTest {
    @get:Rule(order = Int.MIN_VALUE)
    val firstRun = OnboardingSeenRule(seen = false)

    @get:Rule(order = 0)
    val noAnimations = object : ExternalResource() {
        private val resolver get() = ApplicationProvider.getApplicationContext<Application>().contentResolver

        override fun before() {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        }

        override fun after() {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun assertOnPage(number: Int) {
        rule.onNodeWithTag(onboardingPageTag(number)).assertIsDisplayed()
        rule.onNodeWithTag(onboardingTitleTag(number)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ONBOARDING_INDICATOR)
            .assertContentDescriptionEquals(rule.activity.getString(R.string.onboarding_page_of, number, ONBOARDING_PAGE_COUNT))
    }

    private fun next() {
        rule.onNodeWithTag(TAG_ONBOARDING_NEXT).performClick()
        rule.waitForIdle()
    }

    @Test
    fun rendersAndPagesWithAnimationsOff() {
        assertTrue("reduced motion detected", onboardingReducedMotion(rule.activity))
        assertOnPage(1)
        rule.onNodeWithTag(onboardingPageTag(1)).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertOnPage(2)
        next()
        assertOnPage(3)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertOnPage(2)
        next()
        next()
        assertOnPage(4)
        assertFalse(rule.onAllNodesWithTag(TAG_ONBOARDING_SKIP).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag(TAG_ONBOARDING_DONE).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_images_to_pdf").performScrollTo().assertIsDisplayed()
        assertTrue(OnboardingStore(ApplicationProvider.getApplicationContext()).seen)
    }
}

/** Large text on a small phone: every page's title and the primary button stay reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp")
class OnboardingLargeFontTest {
    @get:Rule(order = Int.MIN_VALUE)
    val firstRun = OnboardingSeenRule(seen = false)

    @get:Rule(order = 0)
    val largeFont = object : ExternalResource() {
        override fun before() = org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun titlesAndButtonsReachableAtFontScale150() {
        val config = rule.activity.resources.configuration
        assertTrue("fontScale ${config.fontScale}", config.fontScale == 1.5f)
        assertTrue("screen ${config.screenWidthDp}x${config.screenHeightDp}", config.screenWidthDp == 360)
        for (page in 1..ONBOARDING_PAGE_COUNT) {
            rule.onNodeWithTag(onboardingTitleTag(page)).performScrollTo().assertIsDisplayed()
            val button = if (page < ONBOARDING_PAGE_COUNT) TAG_ONBOARDING_NEXT else TAG_ONBOARDING_DONE
            rule.onNodeWithTag(button).assertIsDisplayed()
            rule.onNodeWithTag(TAG_ONBOARDING_INDICATOR).assertIsDisplayed()
            if (page == 2) {
                for (name in listOf(R.string.tool_images_to_pdf_title, R.string.tool_add_images_title)) {
                    rule.onNodeWithText(rule.activity.getString(name)).performScrollTo().assertIsDisplayed()
                }
            }
            if (page < ONBOARDING_PAGE_COUNT) {
                rule.onNodeWithTag(TAG_ONBOARDING_SKIP).assertIsDisplayed()
                rule.onNodeWithTag(button).performClick()
                rule.waitForIdle()
            }
        }
        rule.onNodeWithTag(TAG_ONBOARDING_DONE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_images_to_pdf").performScrollTo().assertIsDisplayed()
    }
}
