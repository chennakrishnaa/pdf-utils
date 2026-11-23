package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.util.BitSet

/**
 * Stack-depth guard for MuPDF's recursive object walks (security audit SA-11).
 *
 * Saving with `garbage` makes MuPDF mark every reachable object recursively
 * (`source/pdf/pdf-write.c` `markobj`: one native frame per nested dictionary/array, following
 * indirect references), and grafting a page for Merge copies objects recursively too
 * (`pdf-graft.c` `pdf_graft_mapped_object`). A chain of indirect objects
 * (`4 0 obj <</Next 5 0 R>>`, `5 0 obj <</Next 6 0 R>>`, …) therefore costs one native frame per
 * link and overflows the thread stack: SIGSEGV, the whole process dies, nothing is catchable.
 * Measured on the host JVM (MuPDF 1.28.5, x86_64): 12,000 links save fine on a 1 MB thread
 * stack, 15,000 crash it (see [MAX_DEPTH]).
 *
 * [check] walks the object graph ITERATIVELY (explicit stack, visited set, the same order as
 * `markobj`: depth first from the trailer, an object is entered once) and measures the deepest
 * nesting of dictionaries/arrays. Documents nested deeper than [MAX_DEPTH], or with more than
 * [MAX_OBJECTS] objects, are rejected before any recursive native call. This holds whatever the
 * calling thread's stack; on top of it the engine runs every operation on a thread with a large
 * explicit stack ([EngineStack]).
 */
internal object ObjectGraph {
    /**
     * Deepest accepted nesting (dictionaries/arrays, through indirect references). Measured with a
     * plain `garbage=deduplicate` save of a `/Next` chain: on a 1 MB thread stack 12,000 links
     * save, 15,000 crash (≈ 70-85 B per level); on a 128 MB stack 200,000 links save (19 s). The
     * bound is 4× below the 1 MB crash point, so the guard alone protects even a default-size
     * thread (in case a platform ignores [EngineStack]'s stack size), and ~65× below what the
     * 128 MB stack was shown to hold. Ordinary PDFs nest a few dozen levels; the deepest
     * legitimate shape is a linked list such as sibling bookmarks (`/Next`), so a document with
     * more than ~3,000 top-level bookmarks is refused as damaged.
     */
    const val MAX_DEPTH = 3_000

    /** Most objects (xref entries) an input PDF may have; larger documents are "too large". */
    const val MAX_OBJECTS = 500_000

    /**
     * @throws PdfInputException [PdfError.OutOfMemory] when [doc] has more than [MAX_OBJECTS]
     *   objects, [PdfError.Corrupt] when its reachable objects nest deeper than [maxDepth].
     */
    fun check(doc: PDFDocument, maxDepth: Int = MAX_DEPTH) {
        val count = doc.countObjects()
        if (count > MAX_OBJECTS) throw PdfInputException(PdfError.OutOfMemory)
        if (depth(doc, count, maxDepth) > maxDepth) throw PdfInputException(PdfError.Corrupt)
    }

    /** A container being walked: [obj] (owned, destroyed when popped), its keys if a dictionary, the next index. */
    private class Frame(val obj: PDFObject, val keys: List<String>?, var next: Int = 0) {
        val size: Int = keys?.size ?: obj.size()
    }

    /**
     * Deepest container nesting reachable from the trailer, stopping early once it exceeds [limit]
     * (then returns limit + 1).
     */
    fun depth(doc: PDFDocument, count: Int, limit: Int): Int {
        val visited = BitSet(count)
        val stack = ArrayList<Frame>()
        var deepest = 0
        try {
            enter(doc.trailer, count, visited)?.let { stack.add(it) }
            deepest = stack.size
            while (stack.isNotEmpty()) {
                val top = stack[stack.size - 1]
                if (top.next >= top.size) {
                    stack.removeAt(stack.size - 1)
                    top.obj.destroy()
                    continue
                }
                val i = top.next++
                val child = if (top.keys != null) top.obj.get(top.keys[i]) else top.obj.get(i)
                val frame = enter(child, count, visited) ?: continue
                stack.add(frame)
                if (stack.size > deepest) deepest = stack.size
                if (deepest > limit) return limit + 1
            }
            return deepest
        } finally {
            for (f in stack) f.obj.destroy()
        }
    }

    /**
     * Follows [value] (owned: destroyed here unless returned inside a frame) through indirect
     * references, marking each object number once; returns a frame if it ends at a container not
     * yet entered.
     */
    private fun enter(value: PDFObject, count: Int, visited: BitSet): Frame? {
        var v = value
        while (v.isIndirect) {
            val num = v.asIndirect()
            if (num <= 0 || num >= count || visited.get(num)) {
                destroy(v)
                return null
            }
            visited.set(num)
            val r = v.resolve()
            destroy(v)
            v = r
            if (v.isIndirect && v.asIndirect() == num) { destroy(v); return null }
        }
        return when {
            v.isDictionary -> {
                val keys = ArrayList<String>()
                for (k in v) {
                    if (k.isName) keys.add(k.asName())
                    destroy(k)
                }
                Frame(v, keys)
            }
            v.isArray -> Frame(v, null)
            else -> { destroy(v); null }
        }
    }

    private fun destroy(o: PDFObject) {
        if (o !== PDFObject.Null) o.destroy()
    }
}
