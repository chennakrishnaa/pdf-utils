package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Raw-file pre-scan for object-stream bombs (security re-check SR3-1), run before MuPDF opens an
 * input.
 *
 * Why: resolving ANY object that lives in an object stream makes MuPDF (`pdf-xref.c`
 * `pdf_load_obj_stm`) parse every object of that stream in one native call, before the
 * [ObjectGraph] budget can count anything. A 175 KB file whose object stream inflates to 120 MB of
 * `1 0 R` costs ~900 MB; if the catalog lives in that stream, `Document.openDocument` /
 * `countPages` pays it. Parsed size grows with decoded size, so the decoded size is bounded here.
 *
 * What: one forward pass over the file (fixed 64 KiB buffer, positional reads; no whole-file array),
 * tokenising it roughly as MuPDF's lexer does (names with `#xx` escapes decoded, literal strings with
 * nesting and escapes, comments, hex strings; strings/comments longer than 64 KiB are cut there).
 * Every stream whose preceding dictionary (tokens since the last `obj`, `endobj`, `stream` or
 * `endstream` keyword) contains the name `/ObjStm` OR `/N` is a candidate: MuPDF loads any type-2
 * xref target as an object stream reading only `/N` and `/First` (no `/Type` check), so the `/N`
 * test catches a dictionary without `/Type`. ICC profile streams also carry `/N`; they are small and
 * pass (`/N` anywhere, also in a nested dictionary, makes a candidate: a superset). The filter is
 * read as MuPDF reads it (`pdf_open_filter`: `Filter|F`, `/F` used only when `/Filter` is absent;
 * K7-2), from the top level of the stream dictionary only (M2-2: `<<`/`>>` and `[`/`]` nesting is
 * tracked, so a nested `/DecodeParms<</Predictor 12>>` is not misread). Because MuPDF's dictionary
 * parser can swallow a `<<` or `>>` after a key (`/K >>`, `/K 1 <<`), a candidate is refused when a
 * `/Filter` or `/F` name appears nested or in an array, appears twice at the top level, when a `>>`
 * closes nothing, when the nesting is unbalanced at `stream`, or when its dictionary held a string or
 * comment cut at 64 KiB. `/F` as a file specification (a dictionary or string: MuPDF 1.28.5
 * has no external-file streams, no `/FFilter`, and would read the data raw) is refused like any
 * other non-name filter. Per candidate:
 * - no filter: its raw extent (to the next `endstream`) counts as decoded bytes;
 * - `/Filter` exactly `/FlateDecode` or `/Fl` (a name or a one-element array): inflated with a
 *   bounded [Inflater] into a scratch buffer, stopping at the bound. A stream that is not valid
 *   zlib (e.g. encrypted) counts what it yielded and is otherwise ignored, so encrypted files still
 *   reach the Encrypted check; `/DecodeParms` predictors only shrink the output, so they are ignored;
 * - any other filter, a chain, or an indirect / non-name `/Filter`: the file is refused. MuPDF would
 *   decode them (LZW, RunLength, ASCII chains), and their expansion cannot be bounded here without
 *   reimplementing them; legitimate writers use Flate alone for object streams.
 * Refused (`OutOfMemory`, "too large") when one candidate decodes to more than [maxStream] bytes
 * (16 MiB) or all candidates together to more than [maxTotal] bytes (64 MiB). Compressed bytes
 * consumed by the inflater are charged against the same bounds, so overlapping candidates (a stream
 * keyword inside another stream's data; scanning resumes right after each `stream` keyword, the
 * data is tokenised too) keep the total work linear in the file size plus the bounds.
 *
 * Encrypted files (reader only; the tools refuse every encrypted PDF): the data of an encrypted
 * object stream is ciphertext here, so pass 1 cannot measure it (not valid zlib: ignored above;
 * an attacker who knows the key could even make the ciphertext inflate harmlessly). So pass 1 also
 * records EVERY Flate candidate in [Result.flate], with the object number and generation of the
 * `N G obj` header in front of it, and the bytes charged for unfiltered candidates in
 * [Result.rawTotal] (ciphertext is as long as the plaintext, plus at most 32 bytes for AES).
 * [checkDecrypted] (pass 2) runs after MuPDF authenticated the password and BEFORE anything
 * resolves an object that may live in an object stream (the page count): it reads each recorded
 * object's stream through MuPDF (`readRawStream`: crypt filter applied, no other filter) and
 * enforces the same limits on the plaintext: the same filter policy (none, or Flate alone;
 * anything else, including a stream-level `/Crypt` filter, refused), [maxStream] per stream and
 * [maxTotal] for all object streams together, decoded and consumed bytes, starting from
 * [Result.rawTotal]. Refused also: a Flate candidate with no readable header, more than
 * [MAX_RECORDED] Flate candidates, a recorded object that MuPDF does not resolve to a stream, or a
 * raw stream over [maxStream].
 *
 * This is a MITIGATION, not a guarantee. MuPDF reaches objects through xref offsets, not by scanning,
 * and xref repair can find streams anywhere; an object stream can evade the scan if, for example,
 * its dictionary hides a reset keyword inside a string or comment longer than 64 KiB, or the scan's
 * tokenisation of preceding binary data diverges from MuPDF's view at that offset. The `:engine`
 * process (killed on cancel, its death reported as an error) remains the backstop.
 */
internal object ObjStmScan {
    /** Largest decoded size of one object stream: 16 MiB (real ones are tens to hundreds of KB). */
    const val MAX_STREAM_BYTES = 16L shl 20

    /** Largest decoded size of all object streams of one file together: 64 MiB. */
    const val MAX_TOTAL_BYTES = 64L shl 20

    /**
     * @throws PdfInputException `OutOfMemory` when [file] has an object stream over the bounds or one
     *   whose filter cannot be bounded.
     * @throws java.io.IOException when [file] cannot be read.
     */
    fun check(file: File, maxStream: Long = MAX_STREAM_BYTES, maxTotal: Long = MAX_TOTAL_BYTES): Result =
        RandomAccessFile(file, "r").use { raf -> Scanner(raf.channel, maxStream, maxTotal).run() }

    /** Most Flate candidates recorded; an encrypted file with more is refused by [checkDecrypted]. */
    const val MAX_RECORDED = 10_000

    /** An object reference `num gen R`; [num] is -1 when the scan saw no `num gen obj` header. */
    data class Ref(val num: Int, val gen: Int)

    /**
     * Outcome of a passed pass 1: every Flate candidate ([flate]; [overflow] when more than
     * [MAX_RECORDED]) and the bytes charged for unfiltered candidates ([rawTotal]).
     */
    class Result(val flate: List<Ref>, val overflow: Boolean, val rawTotal: Long)

    /**
     * Pass 2 for an encrypted [pdf] that MuPDF opened and authenticated; see the class comment.
     * Must run before the page count or any other object that may live in an object stream is
     * resolved. Resolves only the recorded object numbers.
     *
     * K7-3: resolving a candidate can make MuPDF repair the file (an `N G obj` header that does not
     * match the xref), and a repair loads every object stream before it is measured. The cost of
     * that one repair is already paid, but the document is refused when it was not repaired before
     * pass 2 and is after any step of it, so nothing further is done with it.
     *
     * @throws PdfInputException `OutOfMemory` when a limit is exceeded or a stream cannot be measured.
     */
    fun checkDecrypted(pdf: PDFDocument, pass1: Result, maxStream: Long = MAX_STREAM_BYTES, maxTotal: Long = MAX_TOTAL_BYTES) {
        if (pass1.overflow) tooLargeInput()
        val repairedBefore = pdf.wasRepaired()
        fun checkRepair() { if (!repairedBefore && pdf.wasRepaired()) tooLargeInput() }
        var totalOut = pass1.rawTotal
        var totalIn = 0L
        val seen = HashSet<Ref>()
        for (ref in pass1.flate) {
            if (ref.num <= 0 || ref.gen < 0) tooLargeInput()
            if (!seen.add(ref)) continue
            val obj = pdf.newIndirect(ref.num, ref.gen)
            try {
                val isStream = obj.isStream
                checkRepair()
                if (!isStream) tooLargeInput()
                val flate = when (streamFilter(obj)) {
                    null -> false
                    "FlateDecode", "Fl" -> true
                    else -> tooLargeInput()
                }
                val raw = obj.readRawStream()
                if (raw.size > maxStream) tooLargeInput()
                if (!flate) {
                    totalOut += raw.size
                } else {
                    val inf = Inflater()
                    try {
                        inf.setInput(raw)
                        val scratch = ByteArray(CHUNK)
                        var out = 0L
                        while (!inf.finished()) {
                            val got = try {
                                inf.inflate(scratch)
                            } catch (e: DataFormatException) {
                                break // MuPDF stops there too
                            }
                            out += got
                            if (out > maxStream || totalOut + out > maxTotal) tooLargeInput()
                            if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        }
                        totalOut += out
                        totalIn += inf.bytesRead
                    } finally {
                        inf.end()
                    }
                }
                if (totalOut > maxTotal || totalIn > maxTotal) tooLargeInput()
            } finally {
                obj.destroy()
            }
        }
        checkRepair()
    }

    private fun tooLargeInput(): Nothing = throw PdfInputException(PdfError.OutOfMemory)

    /**
     * The single filter name of [stream] (`Filter|F`, as `pdf_open_filter`; null: none); "?" for a
     * chain, an indirect or non-name filter (including an `/F` file specification, K7-2).
     */
    private fun streamFilter(stream: PDFObject): String? {
        val f = stream.get("Filter").let {
            if (!it.isNull) it else {
                if (it !== PDFObject.Null) it.destroy()
                stream.get("F")
            }
        }
        try {
            if (f.isIndirect) return "?"
            return when {
                f.isNull -> null
                f.isName -> f.asName()
                f.isArray -> when (f.size()) {
                    0 -> null
                    1 -> {
                        val e = f.get(0)
                        try {
                            if (e.isName && !e.isIndirect) e.asName() else "?"
                        } finally {
                            if (e !== PDFObject.Null) e.destroy()
                        }
                    }
                    else -> "?"
                }
                else -> "?"
            }
        } finally {
            if (f !== PDFObject.Null) f.destroy()
        }
    }

    private const val CHUNK = 64 * 1024
    private const val MAX_SPAN = 64 * 1024
    private const val MAX_KEPT = 64

    private class Scanner(private val ch: FileChannel, private val maxStream: Long, private val maxTotal: Long) {
        private val size = ch.size()
        private val buf = ByteBuffer.allocate(CHUNK)
        private var bufStart = 0L
        private var bufLen = 0

        private var candidate = false

        /** The value of one filter key of the current dictionary: names read, and whether anything else was. */
        private class FilterSlot {
            val names = ArrayList<String>()
            var unknown = false
            var seen = false
            fun clear() { names.clear(); unknown = false; seen = false }
        }

        private val filterSlot = FilterSlot() // /Filter
        private val fSlot = FilterSlot() // /F, MuPDF's abbreviation (used when /Filter is absent)
        private var cur = filterSlot // the slot being read while filterState != 0
        private var filterState = 0 // 0 none, 1 after /Filter or /F, 2 inside its array

        /*
         * M2-2: nesting since the last reset keyword. Only a `/Filter` or `/F` at the top level of the
         * stream dictionary (dictDepth <= 1, outside any array) names the stream's filter; one in a
         * nested dictionary (`/DecodeParms<</F …>>`, `/X<</Filter[]>>`) or an array does not, as in
         * MuPDF's `pdf_parse_dict`. Strings and comments are skipped by the tokeniser, so `(<<)` or
         * `%>>` do not count. Depth 0 (no `<<` seen) keeps the old reading: any such name counts.
         */
        private var dictDepth = 0
        private var arrayDepth = 0
        private var filterArrayDepth = 0 // arrayDepth outside the filter's own array

        /** A string or comment was cut at [MAX_SPAN] since the last reset (its tail was tokenised). */
        private var cut = false

        /**
         * M2-2: the dictionary has a shape where the scanner's nesting may differ from MuPDF's.
         * `pdf_parse_dict` takes ONE token as a (null) value after a key in `/K >>`, `/K 1 2 >>`
         * and `/K 1 <<`, so a `<<` or `>>` the scanner counts may be swallowed. Set on a `>>` at
         * depth 0, a `/Filter` or `/F` name nested or in an array, and a second top-level
         * `/Filter` or `/F`; a candidate with it set is refused.
         */
        private var odd = false
        private var rawStart = -1L
        private var totalOut = 0L
        private var totalIn = 0L
        private val word = StringBuilder()
        private val flate = ArrayList<Ref>()
        private var overflow = false
        private var rawTotal = 0L
        private var numA = -1L // the last two unsigned integer tokens (numA the earlier), else -1
        private var numB = -1L
        private var objRef: Ref? = null // `num gen obj` header of the object being scanned

        /** Byte at [p] (0..255), or -1 at end of file. */
        private fun at(p: Long): Int {
            if (p >= size) return -1
            if (p < bufStart || p >= bufStart + bufLen) {
                buf.clear()
                val r = ch.read(buf, p)
                if (r <= 0) return -1
                bufStart = p
                bufLen = r
            }
            return buf.get((p - bufStart).toInt()).toInt() and 0xFF
        }

        fun run(): Result {
            var p = 0L
            while (true) {
                val c = at(p)
                if (c < 0) break
                p = when {
                    isWhite(c) -> p + 1
                    c == '%'.code -> skipUntil(p + 1) { it == '\n'.code || it == '\r'.code }
                    c == '('.code -> skipString(p + 1).also { otherValue() }
                    c == '<'.code -> if (at(p + 1) == '<'.code) (p + 2).also { otherValue(); dictDepth++ } else skipUntil(p + 1) { it == '>'.code }.also { otherValue() }
                    c == '>'.code && at(p + 1) == '>'.code -> (p + 2).also { otherValue(); if (dictDepth > 0) dictDepth-- else odd = true }
                    c == '['.code -> {
                        if (filterState == 1) { filterState = 2; filterArrayDepth = arrayDepth } else otherValue()
                        arrayDepth++
                        p + 1
                    }
                    c == ']'.code -> {
                        if (arrayDepth > 0) arrayDepth--
                        if (filterState == 2 && arrayDepth <= filterArrayDepth) filterState = 0
                        p + 1
                    }
                    c == '/'.code -> readName(p + 1)
                    isDelim(c) -> p + 1
                    else -> readWord(p)
                }
            }
            finishRaw(size)
            return Result(flate, overflow, rawTotal)
        }

        private fun otherValue() {
            if (filterState != 0) {
                cur.unknown = true
                if (filterState == 1) filterState = 0
            }
        }

        private inline fun skipUntil(from: Long, stop: (Int) -> Boolean): Long {
            var p = from
            val end = from + MAX_SPAN
            while (p < end) {
                val c = at(p)
                if (c < 0) return p
                p++
                if (stop(c)) return p
            }
            cut = true
            return p
        }

        private fun skipString(from: Long): Long {
            var p = from
            var depth = 1
            val end = from + MAX_SPAN
            while (p < end) {
                val c = at(p++)
                when (c) {
                    -1 -> return p - 1
                    '\\'.code -> p++
                    '('.code -> depth++
                    ')'.code -> if (--depth == 0) return p
                }
            }
            cut = true
            return p
        }

        private fun readName(from: Long): Long {
            word.setLength(0)
            var p = from
            while (true) {
                val c = at(p)
                if (c < 0 || isWhite(c) || isDelim(c)) break
                p++
                var ch = c
                if (c == '#'.code) {
                    val h = hex(at(p))
                    val l = hex(at(p + 1))
                    if (h >= 0 && l >= 0) {
                        ch = h * 16 + l
                        p += 2
                    }
                }
                if (word.length < MAX_KEPT) word.append(ch.toChar())
            }
            onName(word.toString())
            numA = -1L
            numB = -1L
            return p
        }

        private fun readWord(from: Long): Long {
            word.setLength(0)
            var p = from
            while (true) {
                val c = at(p)
                if (c < 0 || isWhite(c) || isDelim(c)) break
                p++
                if (word.length < MAX_KEPT) word.append(c.toChar())
            }
            val w = word.toString()
            when (w) {
                "stream" -> { onStream(p); reset() }
                "endstream" -> { finishRaw(from); reset() }
                "obj" -> {
                    reset()
                    objRef = if (numA in 1..Int.MAX_VALUE.toLong() && numB in 0..65535L) Ref(numA.toInt(), numB.toInt()) else null
                }
                "endobj" -> { reset(); objRef = null }
                else -> otherValue()
            }
            val n = if (w.isNotEmpty() && w.length <= 10 && w.all { it in '0'..'9' }) w.toLong() else -1L
            numA = if (n >= 0) numB else -1L
            numB = n
            return p
        }

        private fun onName(n: String) {
            // A name read as a filter value still makes a candidate (a superset: `/X /F /N 1`).
            if (n == "ObjStm" || n == "N") candidate = true
            when (filterState) {
                1 -> { cur.names.add(n); filterState = 0; return }
                2 -> { cur.names.add(n); return }
            }
            if (dictDepth > 1 || arrayDepth > 0) {
                // M2-2: not a key of the stream dictionary for the scanner, but MuPDF may see it as one.
                if (n == "Filter" || n == "F") odd = true
                return
            }
            when (n) {
                "Filter" -> startFilter(filterSlot)
                "F" -> startFilter(fSlot)
            }
        }

        private fun startFilter(slot: FilterSlot) {
            if (slot.seen) odd = true // M2-2: a second one (MuPDF keeps the last; one may be nested for it)
            slot.clear()
            slot.seen = true
            cur = slot
            filterState = 1
        }

        private fun reset() {
            candidate = false
            filterSlot.clear()
            fSlot.clear()
            filterState = 0
            dictDepth = 0
            arrayDepth = 0
            cut = false
            odd = false
        }

        /** `stream` keyword ending at [after]: checks the stream if its dictionary made it a candidate. */
        private fun onStream(after: Long) {
            finishRaw(after)
            if (!candidate) return
            // M2-2: the nesting is not where MuPDF's would be (unbalanced, or a cut string or comment
            // whose tail was tokenised could fake a `<<`): which keys are top-level is not known.
            if (dictDepth != 0 || arrayDepth != 0 || cut || odd) tooLarge()
            var d = after
            if (at(d) == '\r'.code) d++
            if (at(d) == '\n'.code) d++
            val slot = if (filterSlot.seen) filterSlot else fSlot
            val filters = slot.names
            when {
                slot.unknown || filters.size > 1 -> tooLarge()
                filters.isEmpty() -> rawStart = d
                filters[0] == "FlateDecode" || filters[0] == "Fl" -> { record(); inflate(d) }
                else -> tooLarge()
            }
        }

        private fun finishRaw(end: Long) {
            if (rawStart < 0) return
            val n = maxOf(0L, end - rawStart)
            rawStart = -1
            if (n > maxStream || totalOut + n > maxTotal) tooLarge()
            totalOut += n
            rawTotal += n
        }

        private fun inflate(from: Long) {
            val inf = Inflater()
            try {
                val scratch = ByteArray(CHUNK)
                val input = ByteBuffer.allocate(CHUNK)
                var pos = from
                var out = 0L
                while (true) {
                    if (inf.needsInput()) {
                        if (pos >= size) break
                        input.clear()
                        val r = ch.read(input, pos)
                        if (r <= 0) break
                        pos += r
                        inf.setInput(input.array(), 0, r)
                    }
                    val got = try {
                        inf.inflate(scratch)
                    } catch (e: DataFormatException) {
                        break
                    }
                    out += got
                    val read = inf.bytesRead
                    if (out > maxStream || read > maxStream) tooLarge()
                    if (totalOut + out > maxTotal || totalIn + read > maxTotal) tooLarge()
                    if (inf.finished() || (got == 0 && inf.needsDictionary())) break
                }
                totalOut += out
                totalIn += inf.bytesRead
            } finally {
                inf.end()
            }
        }

        /** Records the current Flate candidate for [checkDecrypted]. */
        private fun record() {
            if (flate.size >= MAX_RECORDED) overflow = true else flate.add(objRef ?: Ref(-1, -1))
        }

        private fun tooLarge(): Nothing = throw PdfInputException(PdfError.OutOfMemory)

        private fun isWhite(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        private fun hex(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
