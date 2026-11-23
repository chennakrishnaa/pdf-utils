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
            "Urlaub \uD83C\uDFD6\uFE0F.jpg" to "Urlaub \uD83C\uDFD6\uFE0F.jpg", // emoji kept
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
