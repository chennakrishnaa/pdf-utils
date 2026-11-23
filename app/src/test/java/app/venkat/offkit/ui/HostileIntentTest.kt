package app.venkat.offkit.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.setOnboardingSeen
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_ROOT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SB-13: `MainActivity` is exported (launcher). An explicit intent from another app carrying a
 * nav-deep-link-like data Uri, random extras or a `content:` stream must land on home (or the
 * introduction on a first run) and process nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostileIntentTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        (app as PdfUtilApp).engine = FakePdfEngine()
        RecentFilesStore.resetForTesting()
    }

    @After
    fun tearDown() {
        setOnboardingSeen(true)
        RecentFilesStore.resetForTesting()
    }

    private fun hostile(route: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/$route"))
            .setComponent(ComponentName(app, MainActivity::class.java))
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://evil.provider/doc/1"))
            .putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(1, 2, 3))
            .putExtra("random", "x".repeat(1000))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test
    fun deepLinkLikeIntentLandsOnHome() {
        setOnboardingSeen(true)
        for (route in listOf("support", "compress", "about", "merge")) {
            // ActivityScenario cannot be used: it matches the activity by its (now replaced) intent.
            val controller = Robolectric.buildActivity(MainActivity::class.java, hostile(route)).setup()
            rule.waitForIdle()
            rule.onNodeWithTag("home_footer").performScrollTo()
            rule.onNodeWithTag("action_back").assertDoesNotExist()
            val a = controller.get()
            assertNull(a.intent.data)
            assertNull(a.intent.extras)
            assertNull(a.intent.clipData)
            assertEquals(0, a.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            controller.pause().stop().destroy()
        }
        assertTrue(RecentFilesStore.get(app).recents.value.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun hostileIntentOnFirstRunShowsIntroduction() {
        setOnboardingSeen(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java, hostile("support")).setup()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ONBOARDING_ROOT).assertExists()
        controller.pause().stop().destroy()
    }
}
