package app.venkat.offkit

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Open with Offkit" on a device: a stand-in file manager in the test APK (its own uid, a
 * non-exported provider) fires `ACTION_VIEW` with a read grant at `MainActivity`. The reader must
 * show page 1 of that document, which proves the activity's read grant survives the SB-13 intent
 * rewrite. Without the grant the reader's error screen shows. A second VIEW reaches the same
 * (singleTask) instance and replaces the document. Back from that reader (system Back or the top
 * bar's arrow) returns to the file manager, with Offkit stopped in the background, not finished.
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
        // A file manager that stayed (another uid: no key injection into it) is finished with a
        // global Back, so it cannot take the next test's launch.
        if (find(root(), ViewTrampolineActivity.LABEL) != null) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (find(root(), ViewTrampolineActivity.LABEL) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        }
        scratch.deleteRecursively()
    }

    /** Live `MainActivity` instances; main thread only. */
    private fun mainActivitiesOnMain(): List<Activity> {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
            .flatMap { registry.getActivitiesInStage(it) }
            .filterIsInstance<MainActivity>()
    }

    private fun mainActivities(): List<Activity> {
        var result: List<Activity> = emptyList()
        instrumentation.runOnMainSync { result = mainActivitiesOnMain() }
        return result
    }

    private fun finishMainActivities() {
        instrumentation.runOnMainSync { mainActivitiesOnMain().forEach { it.finish() } }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (mainActivities().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    private fun viewFromFileManager(name: String, pages: Int, grant: Boolean = true, stay: Boolean = false) {
        val pdf = Fixtures.textPdf(scratch, name, List(pages) { "Page ${it + 1} of $name" })
        val intent = Intent().setClassName(instrumentation.context.packageName, ViewTrampolineActivity::class.java.name)
            .putExtra(ViewTrampolineActivity.EXTRA_NAME, name)
            .putExtra(ViewTrampolineActivity.EXTRA_BYTES, pdf.readBytes())
            .putExtra(ViewTrampolineActivity.EXTRA_GRANT, grant)
            .putExtra(ViewTrampolineActivity.EXTRA_STAY, stay)
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

    private fun stageOf(activity: Activity): Stage? {
        var stage: Stage? = null
        instrumentation.runOnMainSync { stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) }
        return stage
    }

    private fun readerDirs(): List<File> =
        File(target.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith("reader-") }

    /**
     * After leaving the reader: the file manager is in front again, Offkit's activity is stopped
     * (in the background, not destroyed) and the reader's copy is gone. [tearDown]
     * finishes the file manager.
     */
    private fun assertBackInTheFileManager(main: Activity) {
        assertNotNull("the file manager is in front", waitFor(ViewTrampolineActivity.LABEL))
        assertEquals(instrumentation.context.packageName, root()?.packageName?.toString())
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (stageOf(main) != Stage.STOPPED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals("Offkit stopped, not destroyed", Stage.STOPPED, stageOf(main))
        assertTrue("not finishing", !main.isFinishing)
        while (readerDirs().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals("reader copy deleted", emptyList<File>(), readerDirs())
    }

    @Test
    fun systemBackFromTheReaderReturnsToTheFileManager() {
        viewFromFileManager("back.pdf", pages = 2, stay = true)
        assertNotNull("page 1", waitFor(pageOne(2)))
        val main = mainActivities().single()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertBackInTheFileManager(main)
    }

    @Test
    fun topBarBackFromTheReaderReturnsToTheFileManager() {
        viewFromFileManager("arrow.pdf", pages = 2, stay = true)
        assertNotNull("page 1", waitFor(pageOne(2)))
        val main = mainActivities().single()
        val arrow = waitFor(target.getString(R.string.action_back))
        assertNotNull("Back arrow", arrow)
        var clickable: AccessibilityNodeInfo? = arrow
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue("Back arrow tapped", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        assertBackInTheFileManager(main)
    }
}
