package app.venkat.offkit

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.openwith.ViewTrampolineActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Open with Offkit" on a device: a stand-in file manager in the test APK (its own uid, a
 * non-exported provider) fires `ACTION_VIEW` with a read grant at `MainActivity`. The reader must
 * show page 1 of that document, which proves the activity's read grant survives the SB-13 intent
 * rewrite. Without the grant the reader's error screen shows. A second VIEW reaches the same
 * (singleTask) instance and replaces the document.
 */
@RunWith(AndroidJUnit4::class)
class OpenWithOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var scratch: File

    @Before
    fun setUp() {
        setOnboardingSeen(true)
        finishMainActivities()
        PdfJobs.get(target)
        scratch = File(target.cacheDir, "openwith-ondevice-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // singleTask: a MainActivity left behind would take the next test's launch as onNewIntent.
        finishMainActivities()
        scratch.deleteRecursively()
    }

    private fun mainActivities(): List<Activity> {
        var result: List<Activity> = emptyList()
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            result = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
                .flatMap { registry.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>()
        }
        return result
    }

    private fun finishMainActivities() {
        instrumentation.runOnMainSync { mainActivities().forEach { it.finish() } }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (mainActivities().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    private fun viewFromFileManager(name: String, pages: Int, grant: Boolean = true) {
        val pdf = Fixtures.textPdf(scratch, name, List(pages) { "Page ${it + 1} of $name" })
        val intent = Intent().setClassName(instrumentation.context.packageName, ViewTrampolineActivity::class.java.name)
            .putExtra(ViewTrampolineActivity.EXTRA_NAME, name)
            .putExtra(ViewTrampolineActivity.EXTRA_BYTES, pdf.readBytes())
            .putExtra(ViewTrampolineActivity.EXTRA_GRANT, grant)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.context.startActivity(intent)
    }

    private fun root(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun waitFor(label: String, timeoutMs: Long = 20_000): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            find(root(), label)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun pageOne(count: Int) = target.getString(R.string.reader_page_cd, 1, count)

    @Test
    fun viewWithReadGrantShowsPageOne() {
        viewFromFileManager("from-files.pdf", pages = 2)
        assertNotNull("reader title", waitFor("from-files.pdf"))
        assertNotNull("page 1", waitFor(pageOne(2)))
        assertEquals(1, mainActivities().size)
    }

    @Test
    fun viewWithoutGrantShowsTheReadersErrorScreen() {
        viewFromFileManager("no-grant.pdf", pages = 1, grant = false)
        assertNotNull("error screen", waitFor(target.getString(R.string.action_choose_another)))
        assertNull(find(root(), pageOne(1)))
    }

    @Test
    fun secondViewReachesTheSameInstanceAndReplacesTheDocument() {
        viewFromFileManager("first.pdf", pages = 2)
        assertNotNull("page 1 of first", waitFor(pageOne(2)))
        val first = mainActivities().single()
        viewFromFileManager("second.pdf", pages = 3)
        assertNotNull("second title", waitFor("second.pdf"))
        assertNotNull("page 1 of second", waitFor(pageOne(3)))
        // singleTask: no second copy of the app; the same activity got onNewIntent.
        assertEquals(listOf(first), mainActivities())
        val readerDirs = File(target.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith("reader-") }
        assertEquals(1, readerDirs.size)
    }
}
