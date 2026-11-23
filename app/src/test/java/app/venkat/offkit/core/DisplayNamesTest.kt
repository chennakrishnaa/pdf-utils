package app.venkat.offkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: [sanitizeDisplayName] is pure Kotlin. */
class DisplayNamesTest {
    private val fb = "file"

    @Test
    fun table() {
        val cases = listOf(
            // input to expected
            "report.pdf" to "report.pdf",
            "x\u202Efdp.exe" to "xfdp.exe", // RLO removed: no visual spoof
            "invoice\u2067fdp\u2069.exe" to "invoicefdp.exe", // isolates
            "a\u200Eb\u200Fc\u061Cd" to "abcd", // bidi marks
            "\uFEFFbom.pdf" to "bom.pdf",
            "zero\u200Bwidth\u2060.pdf" to "zerowidth.pdf",
            "../../etc/passwd" to ".._.._etc_passwd",
            "C:\\Windows\\x.pdf" to "C__Windows_x.pdf",
            "nul\u0000byte.pdf" to "nulbyte.pdf",
            "line\nbreak\r\ttab.pdf" to "linebreaktab.pdf",
            "c1\u0085\u009Bctl.pdf" to "c1ctl.pdf",
            "  many   spaces \u00A0 here.pdf  " to "many spaces here.pdf",
            "\u0000\n\t\u202E\u200B\uFEFF" to fb, // only control characters
            "   " to fb,
            "" to fb,
            "Urlaub \uD83C\uDFD6\uFE0F.jpg" to "Urlaub \uD83C\uDFD6.jpg", // emoji kept, variation selector dropped (SR-6)
            // SR-6: invisible/default-ignorable characters and Zalgo.
            "\u3164" to fb, // Hangul filler alone: looks empty
            "\u115F\u1160\uFFA0 \u3164.pdf" to ".pdf",
            "soft\u00ADhy\u034Fphen.pdf" to "softhyphen.pdf",
            "a\u2061\u2062\u2063\u2064b\u206A\u206Fc\uFFF9\uFFFA\uFFFBd.pdf" to "abcd.pdf",
            "tag\uDB40\uDC41\uDB40\uDC7Fs.pdf" to "tags.pdf", // U+E0041, U+E007F
            "vs\uFE00\uDB40\uDD00.pdf" to "vs.pdf", // U+FE00, U+E0100
            "mongol\u180E.pdf" to "mongol.pdf",
            "pua\uE000\uDB80\uDC00.pdf" to "pua.pdf", // U+E000, U+F0000
            "unassigned\u0378.pdf" to "unassigned.pdf",
            "Z\u0301\u0302\u0303\u0304\u0305\u0306a.pdf" to "Z\u0301\u0302\u0303a.pdf", // max 3 marks
            "\u0301\u0302 \u20DD" to fb, // only marks and spaces
            "e\u0301te\u0301.pdf" to "e\u0301te\u0301.pdf", // ordinary accents kept
            "\u6587\u4EF6 \u0645\u0644\u0641 \u0444\u0430\u0439\u043B.pdf" to "\u6587\u4EF6 \u0645\u0644\u0641 \u0444\u0430\u0439\u043B.pdf",
            "\u0939\u093F\u0928\u094D\u0926\u0940.pdf" to "\u0939\u093F\u0928\u094D\u0926\u0940.pdf", // Devanagari (virama kept)
        )
        cases.forEach { (input, expected) ->
            assertEquals("input ${input.map { "%04X".format(it.code) }}", expected, sanitizeDisplayName(input, fb))
        }
        assertEquals(fb, sanitizeDisplayName(null, fb))
    }

    @Test
    fun longNamesAreCappedKeepingTheExtension() {
        val huge = "a".repeat(10_000) + ".pdf"
        val out = sanitizeDisplayName(huge, fb)
        assertEquals(MAX_DISPLAY_NAME_LENGTH, out.length)
        assertTrue(out.endsWith("a.pdf"))

        // No usable extension: plain cut.
        val noExt = sanitizeDisplayName("b".repeat(10_000), fb)
        assertEquals("b".repeat(MAX_DISPLAY_NAME_LENGTH), noExt)

        // A 10 000-char "extension" is not kept separately.
        val longExt = sanitizeDisplayName("x." + "e".repeat(10_000), fb)
        assertEquals(MAX_DISPLAY_NAME_LENGTH, longExt.length)

        // Exactly at the cap: unchanged.
        val exact = "c".repeat(MAX_DISPLAY_NAME_LENGTH - 4) + ".pdf"
        assertEquals(exact, sanitizeDisplayName(exact, fb))
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        val emoji = "\uD83D\uDCC4" // one code point, two chars
        val out = sanitizeDisplayName(emoji.repeat(500) + ".pdf", fb)
        assertEquals(MAX_DISPLAY_NAME_LENGTH, out.codePointCount(0, out.length))
        assertTrue(out.endsWith(".pdf"))
        assertFalse(out.any { Character.isSurrogate(it) && !hasPartner(out, out.indexOf(it)) })
        // Lone surrogate halves in the input are dropped.
        assertEquals("ab", sanitizeDisplayName("a\uD800b", fb))
    }

    private fun hasPartner(s: String, i: Int): Boolean =
        if (Character.isHighSurrogate(s[i])) i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
        else i > 0 && Character.isHighSurrogate(s[i - 1])
}
