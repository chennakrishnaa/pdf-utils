package app.venkat.offkit.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.ui.about.TAG_ABOUT_SUPPORT
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.home.HomeScreen
import app.venkat.offkit.ui.home.TAG_ACTION_ABOUT
import app.venkat.offkit.ui.home.TAG_RECENT_SECTION
import app.venkat.offkit.ui.support.HousePromo
import app.venkat.offkit.ui.support.SupportScreen
import app.venkat.offkit.ui.support.SupportStore
import app.venkat.offkit.ui.support.TAG_SUPPORT_CARD
import app.venkat.offkit.ui.support.TAG_SUPPORT_CARD_HIDE
import app.venkat.offkit.ui.support.TAG_SUPPORT_DONATE
import app.venkat.offkit.ui.support.TAG_SUPPORT_EMAIL
import app.venkat.offkit.ui.support.TAG_SUPPORT_INTRO
import app.venkat.offkit.ui.support.TAG_SUPPORT_ISSUES
import app.venkat.offkit.ui.support.TAG_SUPPORT_RATE
import app.venkat.offkit.ui.support.TAG_SUPPORT_SHARE
import app.venkat.offkit.ui.support.TAG_SUPPORT_SOURCE
import app.venkat.offkit.ui.support.playMarketUri
import app.venkat.offkit.ui.support.playWebUrl
import app.venkat.offkit.ui.support.promoCardTag
import app.venkat.offkit.ui.support.promoHideTag
import app.venkat.offkit.ui.support.supportPromoTag
import app.venkat.offkit.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupportTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    /** Runs before the activity starts: fake engine, no recents, no hidden cards. */
    @get:Rule(order = 0)
    val fresh = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            (app as PdfUtilApp).engine = FakePdfEngine()
            app.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences(SupportStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            RecentFilesStore.resetForTesting()
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        RecentFilesStore.resetForTesting()
    }

    private val samplePromos = listOf(
        HousePromo("first", R.string.tool_merge_title, R.string.tool_merge_desc, "https://example.org/first"),
        HousePromo("second", R.string.tool_compress_title, R.string.tool_compress_desc, "https://example.org/second"),
    )

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textExists(text: String) =
        rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = CoreTestSupport.idleUntil(message = tag) { exists(tag) }

    /** Unclipped top of [tag] in the root (boundsInRoot is clipped to the scroll viewport). */
    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun bottom(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { content() } } }
        rule.waitForIdle()
    }

    private fun drainStarted() {
        val shadow = shadowOf(rule.activity)
        while (shadow.nextStartedActivity != null) Unit
    }

    /** Clicks [tag] (scrolled into view) and returns the activity it started. */
    private fun clickAndCapture(tag: String): Intent {
        drainStarted()
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        var started: Intent? = null
        CoreTestSupport.idleUntil(message = "intent from $tag") {
            started = shadowOf(rule.activity).nextStartedActivity
            started != null
        }
        return started!!
    }

    private fun openSupportFromHome() {
        waitFor(TAG_SUPPORT_CARD)
        rule.onNodeWithTag(TAG_SUPPORT_CARD).performScrollTo().performClick()
        waitFor(TAG_SUPPORT_INTRO)
    }

    private fun versionName(): String {
        val a = rule.activity
        return a.packageManager.getPackageInfo(a.packageName, 0).versionName!!
    }

    // 1. Home card, hide, persistence, About entry.

    @Test
    fun homeCardSitsBelowRecentsAndOpensSupport() {
        waitFor(TAG_SUPPORT_CARD)
        waitFor("recent_empty")
        assertTrue(top(TAG_SUPPORT_CARD) >= bottom(TAG_RECENT_SECTION))
        assertTrue(top(TAG_SUPPORT_CARD) >= bottom("tool_add_images"))
        rule.onNodeWithTag(TAG_SUPPORT_CARD).performScrollTo().assertIsDisplayed()
        assertTrue(textExists(rule.activity.getString(R.string.support_card_text)))
        rule.onNodeWithTag(TAG_SUPPORT_CARD).performClick()
        waitFor(TAG_SUPPORT_INTRO)
        rule.onNodeWithTag(TAG_SUPPORT_INTRO).assertIsDisplayed()
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        waitFor(TAG_SUPPORT_CARD)
    }

    @Test
    fun hidingCardPersistsAndAboutStillOpensSupport() {
        waitFor(TAG_SUPPORT_CARD)
        rule.onNodeWithTag(TAG_SUPPORT_CARD_HIDE).performScrollTo().performClick()
        CoreTestSupport.idleUntil(message = "card gone") { !exists(TAG_SUPPORT_CARD) }
        assertTrue(SupportStore(rule.activity).cardHidden)

        rule.activityRule.scenario.recreate()
        waitFor("tool_images_to_pdf")
        waitFor("recent_empty")
        assertFalse(exists(TAG_SUPPORT_CARD))

        rule.onNodeWithTag(TAG_ACTION_ABOUT).performClick()
        waitFor(TAG_ABOUT_SUPPORT)
        rule.onNodeWithTag(TAG_ABOUT_SUPPORT).performScrollTo().performClick()
        waitFor(TAG_SUPPORT_INTRO)
    }

    // 2. Action intents.

    @Test
    fun rateOpensMarketLink() {
        openSupportFromHome()
        val intent = clickAndCapture(TAG_SUPPORT_RATE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(playMarketUri(rule.activity.packageName)), intent.data)
        assertEquals("market://details?id=app.venkat.offkit", intent.data.toString())
    }

    @Test
    fun rateFallsBackToWebWhenMarketUnhandled() {
        openSupportFromHome()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val web = playWebUrl(rule.activity.packageName)
        // Only the https page has a handler; market:// then throws ActivityNotFoundException.
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(web)).addCategory(Intent.CATEGORY_BROWSABLE)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "org.example.browser"
                name = "org.example.browser.Main"
                applicationInfo = app.applicationInfo
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(webIntent, info)
        shadowOf(app).checkActivities(true)
        val intent = clickAndCapture(TAG_SUPPORT_RATE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(web), intent.data)
    }

    @Test
    fun shareStartsChooserWithPlayLink() {
        openSupportFromHome()
        val intent = clickAndCapture(TAG_SUPPORT_SHARE)
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val send = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.contains("https://play.google.com/store/apps/details?id=app.venkat.offkit"))
    }

    @Test
    fun issuesAndSourceOpenGitHub() {
        openSupportFromHome()
        val issues = clickAndCapture(TAG_SUPPORT_ISSUES)
        assertEquals(Intent.ACTION_VIEW, issues.action)
        assertEquals("https://github.com/chennakrishnaa/pdf-utils/issues", issues.data.toString())
        val source = clickAndCapture(TAG_SUPPORT_SOURCE)
        assertEquals(Intent.ACTION_VIEW, source.action)
        assertEquals(rule.activity.getString(R.string.source_code_url), source.data.toString())
    }

    @Test
    fun emailUsesMailtoWithVersionSubject() {
        openSupportFromHome()
        val intent = clickAndCapture(TAG_SUPPORT_EMAIL)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:chennakrishnaa@yahoo.com", intent.data.toString())
        assertEquals("Offkit ${versionName()}", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    // 3. Donate row.

    @Test
    fun donateAbsentAsShipped() {
        assertTrue(rule.activity.getString(R.string.support_donate_url).isBlank())
        openSupportFromHome()
        waitFor(TAG_SUPPORT_EMAIL)
        assertFalse(exists(TAG_SUPPORT_DONATE))
        setContent { SupportScreen(onBack = {}, donateUrl = " ") }
        waitFor(TAG_SUPPORT_EMAIL)
        assertFalse(exists(TAG_SUPPORT_DONATE))
    }

    @Test
    fun donatePresentWithUrlOpensIt() {
        setContent { SupportScreen(onBack = {}, donateUrl = "https://example.org/donate") }
        waitFor(TAG_SUPPORT_DONATE)
        val intent = clickAndCapture(TAG_SUPPORT_DONATE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/donate", intent.data.toString())
    }

    // 4. House promo slot.

    @Test
    fun noPromoAnywhereWithShippedEmptyList() {
        val a = rule.activity
        val header = a.getString(R.string.support_more_header)
        val promoted = a.getString(R.string.support_promoted)
        waitFor(TAG_SUPPORT_CARD)
        assertFalse(textExists(promoted))
        assertFalse(exists(promoCardTag("first")))
        openSupportFromHome()
        assertFalse(textExists(header))
        assertFalse(textExists(promoted))
    }

    @Test
    fun samplePromosShowOneOnHomeAndAllOnSupport() {
        val a = rule.activity
        setContent { HomeScreen(onOpenTool = {}, onOpenAbout = {}, promos = samplePromos) }
        waitFor(promoCardTag("first"))
        waitFor(TAG_SUPPORT_CARD)
        assertFalse(exists(promoCardTag("second")))
        assertTrue(top(promoCardTag("first")) < top(TAG_SUPPORT_CARD))
        assertTrue(top(promoCardTag("first")) >= bottom(TAG_RECENT_SECTION))
        assertEquals(
            1,
            rule.onAllNodesWithText(a.getString(R.string.support_promoted), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        val drained = shadowOf(a)
        while (drained.nextStartedActivity != null) Unit
        val opened = clickAndCapture(promoCardTag("first"))
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.org/first", opened.data.toString())

        // Hiding by id shows the next one, and persists.
        rule.onNodeWithTag(promoHideTag("first")).performScrollTo().performClick()
        waitFor(promoCardTag("second"))
        assertFalse(exists(promoCardTag("first")))
        assertEquals(setOf("first"), SupportStore(a).hiddenPromos)
        assertFalse(SupportStore(a).cardHidden)
        setContent { HomeScreen(onOpenTool = {}, onOpenAbout = {}, promos = samplePromos) }
        waitFor(promoCardTag("second"))
        assertFalse(exists(promoCardTag("first")))

        setContent { SupportScreen(onBack = {}, promos = samplePromos) }
        waitFor(supportPromoTag("first"))
        waitFor(supportPromoTag("second"))
        assertTrue(textExists(a.getString(R.string.support_more_header)))
        assertEquals(
            2,
            rule.onAllNodesWithText(a.getString(R.string.support_promoted), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    // 5. Small screen.

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun toolsStayAboveTheFoldOnSmallScreen() {
        setContent { HomeScreen(onOpenTool = {}, onOpenAbout = {}, promos = samplePromos) }
        waitFor("recent_empty")
        waitFor(promoCardTag("first"))
        waitFor(TAG_SUPPORT_CARD)
        val rootHeight = rule.onRoot().fetchSemanticsNode().size.height
        listOf("tool_images_to_pdf", "tool_compress", "tool_merge", "tool_add_images").forEach { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            assertTrue("$tag fully visible", bottom(tag) <= rootHeight)
        }
    }
}
