package app.venkat.offkit.engine

import app.venkat.offkit.engine.ops.MergeOp
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SR3-4: Merge charges one reference budget across all inputs, and bounds its save's dedup pass. */
class MergeBudgetTest {
    private val engine = MuPdfEngine()

    @Test
    fun budgetIsSharedAcrossInputs() {
        // 300,000 page-reachable references per input: 6 copies fit the 2 M budget, 7 do not.
        val pdf = File(dir, "dense.pdf").also { it.writeBytes(DeepObjectGraphTest.densePdf(arrays = 300, refs = 1_000)) }
        val o = File(dir, "m.pdf")
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.merge(List(7) { pdf }, o))
        assertFalse(o.exists())
        assertTrue(engine.merge(List(2) { pdf }, o) is PdfResult.Ok)
    }

    @Test
    fun budgetNewWalk_keepsTheCount_andRestartsTheClock() {
        val b = ObjectGraph.Budget(maxRefs = 10, maxMillis = 200)
        Thread.sleep(300)
        b.newWalk()
        b.spend(3)
        assertEquals(3, b.spent)
    }

    @Test
    fun dedupSave_onlyForSmallMergedDocuments() {
        assertEquals(PdfInputs.SAVE_OPTIONS, MergeOp.saveOptions(MergeOp.MAX_DEDUP_OBJECTS))
        val big = MergeOp.saveOptions(MergeOp.MAX_DEDUP_OBJECTS + 1)
        assertFalse(big.contains("deduplicate"))
        assertTrue(big.contains("garbage=compact"))
    }

    companion object {
        private lateinit var dir: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("merge-budget").toFile()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
