package app.venkat.offkit

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.venkat.offkit.ui.onboarding.OnboardingStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First launch on a real device: the introduction shows, Next reaches the last page and
 * Get started opens home and stores the flag. Drives the UI through the platform accessibility
 * tree (no extra test libraries), finding nodes by their visible text.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Before
    fun freshInstall() = setOnboardingSeen(false)

    /** Leave the device as other tests and the smoke script expect it: introduction seen. */
    @After
    fun markSeen() = setOnboardingSeen(true)

    @Test
    fun firstLaunchShowsIntroductionAndGetStartedReachesHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull(waitFor(target.getString(R.string.onboarding_welcome_title)))
            assertFalse(
                "home shown under the introduction",
                exists(target.getString(R.string.tool_compress_desc)),
            )
            repeat(3) { step ->
                click(target.getString(R.string.onboarding_next))
                assertNotNull(waitFor(target.getString(R.string.onboarding_page_of, step + 2, 4)))
            }
            click(target.getString(R.string.onboarding_done))
            assertNotNull(waitFor(target.getString(R.string.tool_compress_desc)))
            assertFalse(exists(target.getString(R.string.onboarding_done)))
            assertTrue(OnboardingStore(target).seen)
        }
    }

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

    /** First node whose text or content description equals [label], depth first. */
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun exists(label: String) = find(root(), label) != null

    private fun waitFor(label: String, timeoutMs: Long = 10_000): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            find(root(), label)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun click(label: String) {
        var node = waitFor(label) ?: throw AssertionError("'$label' not found")
        while (!node.isClickable) node = node.parent ?: throw AssertionError("'$label' is not clickable")
        assertTrue("click on '$label'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
