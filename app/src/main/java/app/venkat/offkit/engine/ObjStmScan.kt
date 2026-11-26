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
 * What: one forward byte search for EVERY `obj` keyword in the file (also spelled with `#xx`
 * escapes, `o#62j`, which MuPDF's lexer decodes, W5; fixed 64 KiB buffers, positional reads; no
 * whole-file array), wherever it is, also inside strings and stream data (Q1).
 * Each one starts a fresh parse of that object alone: nothing before its `obj` (another object, the
 * binary data of an earlier stream) can change its state. The parse ends at once unless the first
 * token is `<<` (MuPDF gives only the object right after `obj` a stream, and only a dictionary has
 * `/N`); otherwise it tokenises roughly as MuPDF's lexer does (names with `#xx` escapes decoded,
 * literal strings with nesting and escapes, comments, hex strings, all skipped to their real end) up
 * to the first `stream`, `endstream`, `obj` or `endobj` keyword. Stream data is never tokenised. As
 * MuPDF reaches an object through an xref offset at its `N G obj` header (or, on repair, by lexing
 * `obj` tokens), every object MuPDF can load is parsed here; parses started at `obj` lookalikes in
 * binary data only cost work (all parses together are bounded by twice the file size plus 64 MiB,
 * past which the file is refused). A stream whose dictionary (the tokens from its `obj` to its
 * `stream` keyword) contains the name `/ObjStm` OR `/N` is a candidate: MuPDF loads any type-2
 * xref target as an object stream reading only `/N` and `/First` (no `/Type` check), so the `/N`
 * test catches a dictionary without `/Type`. ICC profile streams also carry `/N`; they are small and
 * pass (`/N` anywhere, also in a nested dictionary, makes a candidate: a superset). The filter is
 * read as MuPDF reads it (`pdf_open_filter`: `Filter|F`, `/F` used only when `/Filter` is absent;
 * K7-2), from the top level of the stream dictionary only (M2-2: `<<`/`>>` and `[`/`]` nesting is
 * tracked, so a nested `/DecodeParms<</Predictor 12>>` is not misread). Because MuPDF's dictionary
 * parser can swallow a `<<` or `>>` after a key (`/K >>`, `/K 1 <<`), a candidate is refused when a
 * `/Filter` or `/F` name appears nested or in an array, appears twice at the top level, when a `>>`
 * closes nothing, when the nesting is unbalanced at `stream`, or when its dictionary held a string or
 * comment longer than 64 KiB. `/F` as a file specification (a dictionary or string: MuPDF 1.28.5
 * has no external-file streams, no `/FFilter`, and would read the data raw) is refused like any
 * other non-name filter. Per candidate:
 * - no filter: its raw extent (to the next `endstream` bytes) counts as decoded bytes;
 * - `/Filter` exactly `/FlateDecode` or `/Fl` (a name or a one-element array): inflated with a
 *   bounded [Inflater] into a scratch buffer, stopping at the bound. A stream that is not valid
 *   zlib (e.g. encrypted) counts what it yielded and is otherwise ignored, so encrypted files still
 *   reach the Encrypted check; `/DecodeParms` predictors only shrink the output, so they are ignored;
 * - W1: one ASCII filter (`/ASCII85Decode` or `/A85`, `/ASCIIHexDecode` or `/AHx`), alone or in
 *   front of that single Flate (`[/A85 /Fl]`, legacy Distiller / Ghostscript ASCII output): decoded
 *   by [AsciiDecoder] (byte for byte MuPDF's `next_a85d` / `next_ahxd`), streamed into the bounded
 *   inflater or counted directly. The ASCII data is read from the stream start to its end-of-data
 *   marker (`~>`, `>`), a byte MuPDF would refuse (MuPDF then treats the stream as ended), or the
 *   end of the file: never to `/Length` or `endstream` (`endstream` is valid ASCII85 data). A85's
 *   `z` makes 4 bytes of 1, so the ASCII output is measured, never the raw length: the ASCII-only
 *   output counts as decoded bytes, the ASCII output fed to the inflater as its consumed bytes. Every
 *   raw byte the ASCII decoder consumes (white space too) is charged to the scan's work bound (twice
 *   the file size plus 64 MiB, below), so overlapping candidates cannot make it quadratic;
 * - any other filter (LZW, RunLength, Crypt, image filters, ...), more than one ASCII filter, an
 *   ASCII filter after Flate, more than one Flate (`[/Fl /Fl]` multiplies a bomb), or an indirect /
 *   non-name `/Filter`: the file is refused. Their expansion cannot be bounded here without
 *   reimplementing them; legitimate writers use Flate (rarely behind ASCII) for object streams.
 * Refused (`OutOfMemory`, "too large") when one candidate decodes to more than [maxStream] bytes
 * (16 MiB) or all candidates together to more than [maxTotal] bytes (64 MiB). Compressed bytes
 * consumed by the inflater are charged against the same bounds, so overlapping candidates (an
 * object inside another stream's data) keep the total work linear in the file size plus the bounds.
 *
 * Encrypted files (reader only; the tools refuse every encrypted PDF): the data of an encrypted
 * object stream is ciphertext here, so pass 1 cannot measure it (not valid zlib: ignored above;
 * an attacker who knows the key could even make the ciphertext inflate harmlessly). So pass 1 also
 * records EVERY filtered candidate (Flate, ASCII, or ASCII then Flate) in [Result.flate], with the
 * object number and generation of the `N G obj` header in front of it, and the bytes charged for
 * unfiltered candidates in [Result.rawTotal] (ciphertext is as long as the plaintext, plus at most
 * 32 bytes for AES). [checkDecrypted] (pass 2) runs after MuPDF authenticated the password and
 * BEFORE anything resolves an object that may live in an object stream (the page count): it reads
 * each recorded object's stream through MuPDF (`readRawStream`: crypt filter applied, no other
 * filter) and enforces the same limits on the plaintext: the same filter policy (none, Flate,
 * ASCII, or ASCII then Flate; anything else, including a stream-level `/Crypt` filter, refused),
 * [maxStream] per stream and [maxTotal] for all object streams together, decoded and consumed bytes
 * (ASCII input included), starting from [Result.rawTotal]. Refused also: a filtered candidate with
 * no readable header, more than [MAX_RECORDED] filtered candidates, a recorded object that MuPDF
 * does not resolve to a stream, or a raw stream over [maxStream].
 *
 * This is a MITIGATION, not a guarantee. The remaining mismatch with MuPDF's own object discovery:
 * the scan's lexer is an approximation of MuPDF's (shapes known to differ are refused, M2-2); an
 * unfiltered candidate is measured to the next `endstream` bytes, while MuPDF reads `/Length`; and
 * the scan only measures what it finds as `obj` headers (MuPDF needs one too). The `:engine`
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

    /** Most filtered candidates recorded; an encrypted file with more is refused by [checkDecrypted]. */
    const val MAX_RECORDED = 10_000

    /** An object reference `num gen R`; [num] is -1 when the scan saw no `num gen obj` header. */
    data class Ref(val num: Int, val gen: Int)

    /**
     * Outcome of a passed pass 1: every filtered (Flate and/or ASCII) candidate ([flate]; [overflow]
     * when more than [MAX_RECORDED]) and the bytes charged for unfiltered candidates ([rawTotal]).
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
                val chain = streamFilters(obj)?.let { chainOf(it) } ?: tooLargeInput()
                val raw = obj.readRawStream()
                if (raw.size > maxStream) tooLargeInput()
                if (chain.ascii == null && !chain.flate) {
                    totalOut += raw.size
                } else {
                    var pos = 0
                    val plain = Source { buf ->
                        val n = minOf(buf.size, raw.size - pos)
                        System.arraycopy(raw, pos, buf, 0, n)
                        pos += n
                        n
                    }
                    val src = if (chain.ascii == null) plain else AsciiSource(plain, AsciiDecoder(chain.ascii)) { n ->
                        totalIn += n
                        if (totalIn > maxTotal) tooLargeInput()
                    }
                    val m = measure(src, chain.flate) { out, read ->
                        if (out > maxStream || read > maxStream) tooLargeInput()
                        if (totalOut + out > maxTotal || totalIn + read > maxTotal) tooLargeInput()
                    }
                    totalOut += m[0]
                    totalIn += m[1]
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
     * The filter names of [stream] (`Filter|F`, as `pdf_open_filter`; empty: none); null for an
     * indirect or non-name filter or element (including an `/F` file specification, K7-2) or an
     * array longer than any accepted chain.
     */
    private fun streamFilters(stream: PDFObject): List<String>? {
        val f = stream.get("Filter").let {
            if (!it.isNull) it else {
                if (it !== PDFObject.Null) it.destroy()
                stream.get("F")
            }
        }
        try {
            if (f.isIndirect) return null
            return when {
                f.isNull -> emptyList()
                f.isName -> listOf(f.asName())
                f.isArray -> {
                    val n = f.size()
                    if (n > 2) return null
                    (0 until n).map { i ->
                        val e = f.get(i)
                        try {
                            if (e.isName && !e.isIndirect) e.asName() else return null
                        } finally {
                            if (e !== PDFObject.Null) e.destroy()
                        }
                    }
                }
                else -> null
            }
        } finally {
            if (f !== PDFObject.Null) f.destroy()
        }
    }

    /**
     * An accepted object-stream filter chain: an optional ASCII filter ([ascii]: true ASCII85, false
     * ASCIIHex, null none) then an optional single Flate.
     */
    private class Chain(val ascii: Boolean?, val flate: Boolean)

    /** W1: `[]`, `[Fl]`, `[A]`, `[A Fl]` (A: one ASCII filter, either spelling); anything else is null (refused). */
    private fun chainOf(names: List<String>): Chain? {
        var i = 0
        var ascii: Boolean? = null
        when (names.getOrNull(i)) {
            "ASCII85Decode", "A85" -> { ascii = true; i++ }
            "ASCIIHexDecode", "AHx" -> { ascii = false; i++ }
        }
        var flate = false
        if (names.getOrNull(i).let { it == "FlateDecode" || it == "Fl" }) { flate = true; i++ }
        return if (i == names.size) Chain(ascii, flate) else null
    }

    /** A pull source of stream bytes: fills [read]'s buffer from index 0 and returns the count, 0 or less at its end. */
    private fun interface Source {
        fun read(buf: ByteArray): Int
    }

    /** [raw] decoded by [dec]; every raw byte the decoder consumes is passed to [charge] first. */
    private class AsciiSource(private val raw: Source, private val dec: AsciiDecoder, private val charge: (Int) -> Unit) : Source {
        private val rbuf = ByteArray(CHUNK)
        private var rpos = 0
        private var rlen = 0

        override fun read(buf: ByteArray): Int {
            var n = 0
            while (!dec.ended && n <= buf.size - 4) {
                if (rpos == rlen) {
                    rpos = 0
                    rlen = maxOf(0, raw.read(rbuf))
                    if (rlen == 0) break
                }
                val used = dec.decode(rbuf, rpos, rlen, buf, n)
                charge(used)
                rpos += used
                n += dec.produced
            }
            return n
        }
    }

    /**
     * Decoded size of the stream [src] yields, inflated with a bounded [Inflater] when [flate];
     * [check] gets the bytes decoded so far and the inflater input consumed so far after every step
     * (it throws to refuse). Returns `[decoded, inflater input]`. Data that is not valid zlib ends
     * the stream (what was decoded counts), as MuPDF stops there too.
     */
    private inline fun measure(src: Source, flate: Boolean, check: (out: Long, read: Long) -> Unit): LongArray {
        val buf = ByteArray(CHUNK)
        if (!flate) {
            var out = 0L
            while (true) {
                val r = src.read(buf)
                if (r <= 0) break
                out += r
                check(out, 0L)
            }
            return longArrayOf(out, 0L)
        }
        val inf = Inflater()
        try {
            val scratch = ByteArray(CHUNK)
            var out = 0L
            var dry = false
            while (true) {
                if (!dry && inf.needsInput()) {
                    val r = src.read(buf)
                    // W4: at the end of the source the inflater's pending output is still drained.
                    if (r <= 0) dry = true else inf.setInput(buf, 0, r)
                }
                val got = try {
                    inf.inflate(scratch)
                } catch (e: DataFormatException) {
                    break
                }
                out += got
                check(out, inf.bytesRead)
                if (inf.finished() || (got == 0 && (dry || inf.needsDictionary()))) break
            }
            return longArrayOf(out, inf.bytesRead)
        } finally {
            inf.end()
        }
    }

    /**
     * W1: streaming `ASCII85Decode` ([a85]) or `ASCIIHexDecode`, byte for byte as MuPDF 1.28.5
     * (`filter-basic.c` `next_a85d` / `next_ahxd`), because its output feeds the inflater:
     * - white space as MuPDF's `iswhite` (NUL, TAB, LF, FF, CR, space, also BS and DEL) is skipped;
     * - A85: `!`..`u` in groups of 5 (32-bit wrapping arithmetic, so an overflowing group still
     *   gives 4 bytes), `z` between groups gives 4 zero bytes; `~` ends the data whatever follows
     *   it (MuPDF only warns when it is not `>`) after the partial final group (1 char: nothing,
     *   2 to 4 chars: 1 to 3 bytes, padded with `u` as `*85^k + 0xff..`);
     * - AHx: hex digits in pairs; `>` ends the data, an odd final digit giving one byte (`d0`);
     * - any other byte (and `z` inside a group) ends the data: MuPDF throws "bad data", and its
     *   stream reader treats the throw as the end of the stream for good;
     * - the end of input without a marker ends quietly; a partial group or odd digit there gives
     *   nothing, as in MuPDF.
     * MuPDF may lose up to one 256-byte output buffer more than this at bad data; never less.
     */
    internal class AsciiDecoder(private val a85: Boolean) {
        /** The end of data was reached (marker or bad byte); nothing more is decoded. */
        var ended = false
            private set

        /** Bytes written by the last [decode]. */
        var produced = 0
            private set

        private var word = 0
        private var count = 0

        /**
         * Decodes [inp] from [off] (exclusive [end]) into [out] from [outOff], while [out] has room
         * for 4 more bytes and the data has not ended. Returns the input bytes consumed.
         */
        fun decode(inp: ByteArray, off: Int, end: Int, out: ByteArray, outOff: Int): Int {
            var i = off
            var o = outOff
            while (i < end && !ended && o <= out.size - 4) {
                val c = inp[i++].toInt() and 0xFF
                if (a85) {
                    when {
                        c in '!'.code..'u'.code -> {
                            word = word * 85 + (c - '!'.code)
                            if (count == 4) {
                                out[o++] = (word ushr 24).toByte()
                                out[o++] = (word ushr 16).toByte()
                                out[o++] = (word ushr 8).toByte()
                                out[o++] = word.toByte()
                                word = 0
                                count = 0
                            } else {
                                count++
                            }
                        }
                        c == 'z'.code && count == 0 -> repeat(4) { out[o++] = 0 }
                        c == '~'.code -> {
                            when (count) {
                                2 -> {
                                    word = word * (85 * 85 * 85) + 0xffffff
                                    out[o++] = (word ushr 24).toByte()
                                }
                                3 -> {
                                    word = word * (85 * 85) + 0xffff
                                    out[o++] = (word ushr 24).toByte()
                                    out[o++] = (word ushr 16).toByte()
                                }
                                4 -> {
                                    word = word * 85 + 0xff
                                    out[o++] = (word ushr 24).toByte()
                                    out[o++] = (word ushr 16).toByte()
                                    out[o++] = (word ushr 8).toByte()
                                }
                            }
                            ended = true
                        }
                        !asciiWhite(c) -> ended = true
                    }
                } else {
                    val h = hexDigit(c)
                    when {
                        h >= 0 -> if (count == 0) {
                            word = h
                            count = 1
                        } else {
                            out[o++] = ((word shl 4) or h).toByte()
                            count = 0
                        }
                        c == '>'.code -> {
                            if (count == 1) out[o++] = (word shl 4).toByte()
                            ended = true
                        }
                        !asciiWhite(c) -> ended = true
                    }
                }
            }
            produced = o - outOff
            return i - off
        }

        private fun asciiWhite(c: Int) = c == 0 || c == 8 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 || c == 0x7F

        private fun hexDigit(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }

    private const val CHUNK = 64 * 1024
    private const val MAX_SPAN = 64 * 1024
    private const val MAX_KEPT = 64

    private class Scanner(private val ch: FileChannel, private val maxStream: Long, private val maxTotal: Long) {
        private val size = ch.size()

        /** A positional read window over the file (fixed buffer, no whole-file array). */
        private inner class Window {
            private val buf = ByteBuffer.allocate(CHUNK)
            private var start = 0L
            private var len = 0

            /** Byte at [p] (0..255), or -1 outside the file. */
            fun at(p: Long): Int {
                if (p < 0 || p >= size) return -1
                if (p < start || p >= start + len) {
                    buf.clear()
                    val r = ch.read(buf, p)
                    if (r <= 0) return -1
                    start = p
                    len = r
                }
                return buf.get((p - start).toInt()).toInt() and 0xFF
            }
        }

        private val search = Window() // finds `obj` keywords
        private val tok = Window() // tokenises one object
        private fun at(p: Long) = tok.at(p)
        private val back = ByteBuffer.allocate(BACK)

        /**
         * Q1: bytes all per-object parses together may still tokenise. Real files spend about the
         * size of their dictionaries; only parses started at many overlapping `obj` tokens (inside
         * strings or stream data) can exceed it, and such a file is refused.
         */
        private var budget = 2 * size + MAX_TOTAL_BYTES
        private var done = false // the current object's parse reached a keyword that ends it

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
         * M2-2: nesting since the object's `obj` keyword. Only a `/Filter` or `/F` at the top level of
         * the stream dictionary (dictDepth <= 1, outside any array) names the stream's filter; one in
         * a nested dictionary (`/DecodeParms<</F …>>`, `/X<</Filter[]>>`) or an array does not, as in
         * MuPDF's `pdf_parse_dict`. Strings and comments are skipped by the tokeniser, so `(<<)` or
         * `%>>` do not count.
         */
        private var dictDepth = 0
        private var arrayDepth = 0
        private var filterArrayDepth = 0 // arrayDepth outside the filter's own array

        /** A string or comment of this object was longer than [MAX_SPAN]. */
        private var cut = false

        /**
         * M2-2: the dictionary has a shape where the scanner's nesting may differ from MuPDF's.
         * `pdf_parse_dict` takes ONE token as a (null) value after a key in `/K >>`, `/K 1 2 >>`
         * and `/K 1 <<`, so a `<<` or `>>` the scanner counts may be swallowed. Set on a `>>` at
         * depth 0, a `/Filter` or `/F` name nested or in an array, and a second top-level
         * `/Filter` or `/F`; a candidate with it set is refused.
         */
        private var odd = false
        private var totalOut = 0L
        private var totalIn = 0L
        private val word = StringBuilder()
        private val flate = ArrayList<Ref>()
        private var overflow = false
        private var rawTotal = 0L
        private var objRef: Ref? = null // `num gen obj` header of the object being scanned

        fun run(): Result {
            var p = 0L
            while (true) {
                val o = nextObj(p)
                if (o < 0) break
                parseObject(o)
                p = objEnd
            }
            return Result(flate, overflow, rawTotal)
        }

        /** End of the `obj` keyword [nextObj] last returned. */
        private var objEnd = 0L

        /**
         * Q1: start of the next `obj` keyword at or after [from], wherever it is (also inside a
         * string or stream data), as MuPDF's lexer could read it after a generation number: followed
         * by white space, a delimiter or the end, preceded by white space, a delimiter or a number
         * character. W5: any of its letters may be written `#xx` (`o#62j`, `#6f#62#6a`): MuPDF's
         * `lex_name` decodes keywords too, so it loads such a header from an xref offset and on
         * repair alike. Sets [objEnd]; -1 when there is none.
         */
        private fun nextObj(from: Long): Long {
            var p = from
            while (true) {
                val c = search.at(p)
                if (c < 0) return -1
                if (c == 'o'.code || c == '#'.code) {
                    val e = objAt(p)
                    if (e >= 0) {
                        val after = search.at(e)
                        val before = search.at(p - 1)
                        if ((after < 0 || isWhite(after) || isDelim(after)) &&
                            (before < 0 || isWhite(before) || isDelim(before) || before in '0'.code..'9'.code || before == '.'.code || before == '+'.code || before == '-'.code)
                        ) { objEnd = e; return p }
                    }
                }
                p++
            }
        }

        /** W5: end of an `obj` at [p], each letter literal or `#xx` (hex in either case), else -1. Reads at most 9 bytes. */
        private fun objAt(p: Long): Long {
            var q = p
            for (k in OBJ) {
                val c = search.at(q)
                q += if (c == k.code) {
                    1
                } else if (c == '#'.code) {
                    val h = hex(search.at(q + 1))
                    val l = hex(search.at(q + 2))
                    if (h < 0 || l < 0 || h * 16 + l != k.code) return -1
                    3
                } else {
                    return -1
                }
            }
            return q
        }

        /**
         * Q1: one object, from its `obj` keyword at [o] to the first `stream`, `endstream`, `obj` or
         * `endobj` keyword, with all state fresh: nothing before [o] (another object, stream data) can
         * reach it. MuPDF (`pdf_parse_ind_obj`) gives an object a stream only when `stream` follows
         * the object right after `obj`, and only a dictionary has `/N`, so a parse that does not start
         * with `<<` ends at once.
         */
        private fun parseObject(o: Long) {
            reset()
            objRef = refBefore(o)
            var p = objEnd
            while (true) {
                val c = at(p)
                p = when {
                    c >= 0 && isWhite(c) -> p + 1
                    c == '%'.code -> skipUntil(p + 1) { it == '\n'.code || it == '\r'.code }
                    else -> break
                }
            }
            if (at(p) == '<'.code && at(p + 1) == '<'.code) {
                while (!done) {
                    val c = at(p)
                    if (c < 0) break
                    if (p - o > budget) tooLarge()
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
            }
            budget -= p - o
            if (budget < 0) tooLarge()
        }

        /** The `num gen` in front of the `obj` keyword at [o] (white space only between), or null. */
        private fun refBefore(o: Long): Ref? {
            val from = maxOf(0L, o - BACK)
            back.clear()
            val n = maxOf(0, ch.read(back, from))
            val b = back.array()
            var i = minOf(n.toLong(), o - from).toInt() - 1
            fun c(k: Int) = if (k >= 0) b[k].toInt() and 0xFF else -1
            fun digits(): Long {
                var v = 0L
                var count = 0
                while (c(i) in '0'.code..'9'.code && count < 10) { v += (c(i) - '0'.code) * pow10(count); count++; i-- }
                return if (count == 0 || c(i) in '0'.code..'9'.code) -1 else v
            }
            while (isWhite(c(i))) i--
            val gen = digits()
            if (gen !in 0..65535L || !isWhite(c(i))) return null
            while (isWhite(c(i))) i--
            val num = digits()
            if (num !in 1..Int.MAX_VALUE.toLong()) return null
            val before = c(i)
            if (i < 0 && from > 0) return null
            if (before >= 0 && !isWhite(before) && !isDelim(before)) return null
            return Ref(num.toInt(), gen.toInt())
        }

        private fun pow10(k: Int): Long { var r = 1L; repeat(k) { r *= 10 }; return r }

        private fun otherValue() {
            if (filterState != 0) {
                cur.unknown = true
                if (filterState == 1) filterState = 0
            }
        }

        /** Skips to just after the first byte matching [stopAt] (or the end); marks [cut] past [MAX_SPAN]. */
        private inline fun skipUntil(from: Long, stopAt: (Int) -> Boolean): Long {
            var p = from
            while (true) {
                val c = at(p)
                if (c < 0) break
                p++
                if (stopAt(c)) break
            }
            if (p - from > MAX_SPAN) cut = true
            return p
        }

        /** Skips a literal string (nesting, escapes) to its end, as MuPDF does; marks [cut] past [MAX_SPAN]. */
        private fun skipString(from: Long): Long {
            var p = from
            var depth = 1
            while (true) {
                val c = at(p++)
                when (c) {
                    -1 -> { p--; break }
                    '\\'.code -> p++
                    '('.code -> depth++
                    ')'.code -> if (--depth == 0) break
                }
            }
            if (p - from > MAX_SPAN) cut = true
            return p
        }

        private fun readName(from: Long): Long {
            val p = lexName(from)
            onName(word.toString())
            return p
        }

        /**
         * A name's or keyword's characters from [from] into [word], `#xx` decoded as MuPDF's
         * `lex_name` does for both (W4: so `s#74ream` is the `stream` keyword, as for MuPDF).
         */
        private fun lexName(from: Long): Long {
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
            return p
        }

        private fun readWord(from: Long): Long {
            val p = lexName(from)
            when (word.toString()) {
                "stream" -> { onStream(p); done = true }
                "endstream", "obj", "endobj" -> done = true // an `obj` here starts its own parse
                else -> otherValue()
            }
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
            done = false
            objRef = null
        }

        /** `stream` keyword ending at [after]: checks the stream if its dictionary made it a candidate. */
        private fun onStream(after: Long) {
            if (!candidate) return
            // M2-2: the nesting is not where MuPDF's would be (unbalanced, swallowed by MuPDF, or a
            // string or comment over 64 KiB): which keys are top-level is not known.
            if (dictDepth != 0 || arrayDepth != 0 || cut || odd) tooLarge()
            val d = dataStart(after)
            val slot = if (filterSlot.seen) filterSlot else fSlot
            if (slot.unknown) tooLarge()
            val chain = chainOf(slot.names) ?: tooLarge()
            if (chain.ascii == null && !chain.flate) countRaw(d) else { record(); decode(d, chain) }
        }

        /**
         * W4: where MuPDF 1.28.5 starts the data of a stream whose `stream` keyword ends at [after]
         * (`pdf-parse.c` `pdf_parse_ind_obj`): spaces (0x20 only) are skipped, then ONE more byte is
         * consumed whatever it is (`>`, `<`, `(`, `%`, TAB, LF, the first data byte...), and when that
         * byte is CR, also an LF right after it. A repair (`pdf_repair_obj`) computes its own offset
         * without skipping spaces, but `pdf_cache_object` re-parses the object through
         * `pdf_parse_ind_obj` before any stream read, so this rule holds there too.
         */
        private fun dataStart(after: Long): Long {
            var d = after
            while (at(d) == ' '.code) d++
            val c = at(d)
            if (c < 0) return d
            d++
            if (c == '\r'.code && at(d) == '\n'.code) d++
            return d
        }

        /** An unfiltered candidate's data from [d] to the next `endstream` (or the end) counts as decoded bytes. */
        private fun countRaw(d: Long) {
            val limit = minOf(size, d + maxStream + 1)
            var p = d
            while (p < limit && !endstreamAt(p)) p++
            val n = p - d
            if (n > maxStream || totalOut + n > maxTotal) tooLarge()
            totalOut += n
            rawTotal += n
        }

        private fun endstreamAt(p: Long): Boolean {
            for (k in ENDSTREAM.indices) if (at(p + k) != ENDSTREAM[k].code) return false
            return true
        }

        /**
         * A filtered candidate's data from [from] (to its end of data, never `/Length`), through
         * [chain]: the decoded bytes and the inflater's input count against the bounds; the raw
         * bytes the ASCII decoder consumes against the work [budget].
         */
        private fun decode(from: Long, chain: Chain) {
            var pos = from
            val file = Source { buf ->
                if (pos >= size) return@Source -1
                val r = ch.read(ByteBuffer.wrap(buf), pos)
                if (r > 0) pos += r
                r
            }
            val src = if (chain.ascii == null) file else AsciiSource(file, AsciiDecoder(chain.ascii)) { n ->
                budget -= n
                if (budget < 0) tooLarge()
            }
            val m = measure(src, chain.flate) { out, read ->
                if (out > maxStream || read > maxStream) tooLarge()
                if (totalOut + out > maxTotal || totalIn + read > maxTotal) tooLarge()
            }
            totalOut += m[0]
            totalIn += m[1]
        }

        /** Records the current filtered candidate for [checkDecrypted]. */
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

    private const val BACK = 48
    private const val ENDSTREAM = "endstream"
    private const val OBJ = "obj"
}
