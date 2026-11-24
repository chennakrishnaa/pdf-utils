package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.util.BitSet

/**
 * Bounded, iterative pre-checks of an input PDF's object graph (security audits SA-11, SR2-2/3/4),
 * run before the MuPDF calls whose cost grows with the attacker's graph.
 *
 * What MuPDF 1.28.5 actually does (read in the sources and measured in capped child JVMs):
 * - **Saving** (`pdf-write.c`): without `garbage` no step follows indirect references
 *   (`markobj`, the recursive one, runs only for `do_garbage >= 1`), so Compress and Add images
 *   save with [PdfInputs.EDIT_SAVE_OPTIONS] and have no depth limit. But a full save loads EVERY
 *   object (`preloadobjstms`, `unpack_objstm_objs`) and keeps it parsed in memory, at roughly 55 B
 *   per reference: a 2 MB file of identical 1,000-reference arrays in object streams costs ~1.1 GB.
 *   [checkObjects] therefore counts every object's entries under a [Budget] first.
 * - **Grafting** (`pdf-graft.c` `pdf_graft_mapped_object`, Merge): recursive per indirect link and
 *   per container, starting from the page's `Contents Resources MediaBox CropBox BleedBox TrimBox
 *   ArtBox Rotate UserUnit` (inheritable lookup; the page's own `/Parent` is not copied, but every
 *   key inside what it reaches is followed). Each level opens an `fz_try`, and the context's
 *   256-slot exception stack runs out after ~125 links: the graft then silently truncates
 *   (`pdf_resolve_indirect` swallows the error), so its native depth is capped by MuPDF itself
 *   (no crash at any chain length, even on a 64 KB thread), but the page loses content.
 *   It also copies everything reachable: 20,000 × 1,000 references hung off a page cost 1.3 GB.
 *   [checkPages] walks exactly what the graft will copy, under a [Budget], and refuses nesting
 *   beyond [MAX_GRAFT_NESTING] (well below the truncation point) as Corrupt.
 * - `garbage` on Merge's NEW document recurses over the grafted copy only, whose depth the graft
 *   caps as above, so Merge keeps [PdfInputs.SAVE_OPTIONS].
 *
 * Neither walk recurses (explicit stacks), and neither makes MuPDF load more than the following
 * operation loads anyway. Engine operations also run on [EngineStack]'s large thread as defence in
 * depth.
 */
internal object ObjectGraph {
    /**
     * Most objects (xref entries) an input PDF may have; larger documents are "too large" (SR2-2:
     * a 490,000-object file cost 3.4 s and 340 MB before the walk budget stopped it). For scale, a
     * 500-page text PDF with one link per page has about 1,500 objects.
     */
    const val MAX_OBJECTS = 200_000

    /**
     * Most references/entries a walk may visit before the input is "too large". Measured (SR3, host
     * MuPDF 1.28.5, `bipseq-objstm.pdf`): a walk that reaches this bound peaks at ~285 MB RSS, because
     * resolving one object parses its whole object stream before anything is counted; [ObjStmScan]
     * now bounds that per-stream parse before the file is opened.
     */
    const val MAX_REFERENCES = 2_000_000

    /** Wall-clock budget of one walk. */
    const val MAX_WALK_MILLIS = 3_000L

    /**
     * Deepest nesting (indirect links + containers along a path from a page key) that Merge
     * accepts. Each level costs the graft one or two of MuPDF's 256 `fz_try` slots; a chain of
     * ~125 links is where the graft starts to truncate silently. 200 levels ≈ 98 links: refused as
     * Corrupt before that. Real pages nest a dozen levels (resources → forms → resources → fonts …).
     */
    const val MAX_GRAFT_NESTING = 200

    /** Graft's copy list (`pdf-graft.c` `pdf_graft_mapped_page`), in its order. */
    private val GRAFT_KEYS = listOf("Contents", "Resources", "MediaBox", "CropBox", "BleedBox", "TrimBox", "ArtBox", "Rotate", "UserUnit")

    /** Reference and time budget of one walk; [spend] throws `PdfInputException(OutOfMemory)` once exceeded. */
    class Budget(private val maxRefs: Int = MAX_REFERENCES, private val maxMillis: Long = MAX_WALK_MILLIS) {
        private var deadline = System.nanoTime() + maxMillis * 1_000_000

        /**
         * Starts a new walk on this budget: the reference count carries on (one count for all inputs
         * of a Merge, SR3-4), the time allowance restarts, so later inputs are not charged for the
         * opening and pre-scan of earlier ones.
         */
        fun newWalk() {
            deadline = System.nanoTime() + maxMillis * 1_000_000
        }
        var spent = 0
            private set

        fun spend() {
            spent++
            if (spent > maxRefs) throw PdfInputException(PdfError.OutOfMemory)
            if (spent and 0x3FF == 0 && System.nanoTime() > deadline) throw PdfInputException(PdfError.OutOfMemory)
        }

        /** Charges [n] (>= 0) units at once; overflow-safe, and checks the deadline on every call. */
        fun spend(n: Int) {
            require(n >= 0)
            if (n.toLong() + spent > maxRefs) throw PdfInputException(PdfError.OutOfMemory)
            spent += n
            if (System.nanoTime() > deadline) throw PdfInputException(PdfError.OutOfMemory)
        }
    }

    /**
     * Most annotations (`/Annots` entries) one page may have (SR3-3). MuPDF builds every annotation
     * of a page on `loadPage` (output validation, Merge's link copy; ~460 B each), and Merge's
     * `createLink` is quadratic in the links already on the page. Real pages have tens.
     */
    const val MAX_ANNOTS_PER_PAGE = 10_000

    /**
     * SR3-3: for every page, resolves only its `/Annots` array (the length; no annotation is loaded)
     * and charges that length to [budget] per page, so P pages sharing one array of M entries cost
     * P × M. A page with more than [maxPerPage] entries is "too large". Run it before any `loadPage`.
     * @throws PdfInputException `OutOfMemory`.
     */
    fun checkAnnots(doc: PDFDocument, budget: Budget = Budget(), maxPerPage: Int = MAX_ANNOTS_PER_PAGE) {
        for (p in 0 until doc.countPages()) {
            budget.spend()
            val page = doc.findPage(p)
            try {
                budget.spend(annotCount(page, maxPerPage))
            } finally {
                destroy(page)
            }
        }
    }

    /** Length of [page]'s `/Annots` array (0 if absent or not an array); `OutOfMemory` if over [max]. */
    private fun annotCount(page: PDFObject, max: Int): Int {
        val raw = page.get("Annots")
        val arr = if (raw.isIndirect) raw.resolve().also { destroy(raw) } else raw
        try {
            val n = if (arr.isArray) arr.size() else 0
            if (n > max) throw PdfInputException(PdfError.OutOfMemory)
            return n
        } finally {
            destroy(arr)
        }
    }

    /**
     * Compress / Add images: counts every object (1 until `countObjects()`) and every entry of its
     * direct structure, NOT following indirect references (the save writes each object once).
     * @throws PdfInputException `OutOfMemory` when [budget] runs out.
     */
    fun checkObjects(doc: PDFDocument, budget: Budget = Budget()) {
        val count = doc.countObjects()
        if (count > MAX_OBJECTS) throw PdfInputException(PdfError.OutOfMemory)
        val stack = ArrayList<Frame>()
        try {
            for (num in 1 until count) {
                budget.spend()
                val ref = doc.newIndirect(num, 0)
                val obj = try { ref.resolve() } finally { ref.destroy() }
                frameOf(obj, 0)?.let { stack.add(it) }
                while (stack.isNotEmpty()) {
                    val top = stack[stack.size - 1]
                    if (top.next >= top.size) {
                        stack.removeAt(stack.size - 1).obj.destroy()
                        continue
                    }
                    budget.spend()
                    val child = top.child(top.next++)
                    if (child.isIndirect) destroy(child) else frameOf(child, 0)?.let { stack.add(it) }
                }
            }
        } finally {
            for (f in stack) f.obj.destroy()
        }
    }

    /**
     * Merge: walks, for every page, what `pdf_graft_mapped_page` will copy (the [GRAFT_KEYS]
     * values, looked up with inheritance, and everything reachable from them; one visited set per
     * document, as Merge uses one graft map per input). Each page's `/Annots` length is charged as in
     * [checkAnnots] (Merge loads the page's links), and a page over [maxAnnots] is refused (SR3-3).
     * @throws PdfInputException `OutOfMemory` when [budget] runs out or a page has too many
     *   annotations, `Corrupt` when a path nests deeper than [maxNesting].
     */
    fun checkPages(
        doc: PDFDocument,
        budget: Budget = Budget(),
        maxNesting: Int = MAX_GRAFT_NESTING,
        maxAnnots: Int = MAX_ANNOTS_PER_PAGE,
    ) {
        val count = doc.countObjects()
        if (count > MAX_OBJECTS) throw PdfInputException(PdfError.OutOfMemory)
        val visited = BitSet(count)
        val stack = ArrayList<Frame>()
        try {
            for (p in 0 until doc.countPages()) {
                val page = doc.findPage(p)
                try {
                    budget.spend(annotCount(page, maxAnnots))
                    for (key in GRAFT_KEYS) {
                        budget.spend()
                        val v = page.getInheritable(key)
                        enter(v, 1, count, visited, budget, maxNesting)?.let { stack.add(it) }
                        while (stack.isNotEmpty()) {
                            val top = stack[stack.size - 1]
                            if (top.next >= top.size) {
                                stack.removeAt(stack.size - 1).obj.destroy()
                                continue
                            }
                            budget.spend()
                            enter(top.child(top.next++), top.level + 1, count, visited, budget, maxNesting)?.let { stack.add(it) }
                        }
                    }
                } finally {
                    destroy(page)
                }
            }
        } finally {
            for (f in stack) f.obj.destroy()
        }
    }

    /** A container being walked: [obj] (owned), its keys if a dictionary, the next index, its nesting [level]. */
    private class Frame(val obj: PDFObject, val keys: List<String>?, val level: Int, var next: Int = 0) {
        val size: Int = keys?.size ?: obj.size()
        fun child(i: Int): PDFObject = if (keys != null) obj.get(keys[i]) else obj.get(i)
    }

    /**
     * Follows [value] (owned) through indirect references as the graft does (each hop is a level,
     * each object number entered once); returns a frame if it ends at a container not yet entered.
     */
    private fun enter(value: PDFObject, level: Int, count: Int, visited: BitSet, budget: Budget, maxNesting: Int): Frame? {
        var v = value
        var lvl = level
        while (v.isIndirect) {
            val num = v.asIndirect()
            if (num <= 0 || num >= count || visited.get(num)) {
                destroy(v)
                return null
            }
            visited.set(num)
            budget.spend()
            val r = v.resolve()
            destroy(v)
            v = r
            lvl++
        }
        if (lvl > maxNesting && (v.isDictionary || v.isArray)) {
            destroy(v)
            throw PdfInputException(PdfError.Corrupt)
        }
        return frameOf(v, lvl)
    }

    /** A frame for [v] (owned) if it is a dictionary or an array, else destroys it and returns null. */
    private fun frameOf(v: PDFObject, level: Int): Frame? = when {
        v.isDictionary -> {
            val keys = ArrayList<String>()
            for (k in v) {
                if (k.isName) keys.add(k.asName())
                destroy(k)
            }
            Frame(v, keys, level)
        }
        v.isArray -> Frame(v, null, level)
        else -> {
            destroy(v)
            null
        }
    }

    private fun destroy(o: PDFObject) {
        if (o !== PDFObject.Null) o.destroy()
    }
}
