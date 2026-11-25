package app.venkat.offkit.ui

import app.venkat.offkit.ui.about.LicenceBlock
import app.venkat.offkit.ui.about.reflowLicenceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Display reflow of the hard-wrapped licence texts (About > licences). */
class LicenceReflowTest {
    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.isFile }
            ?: error("asset $name not found")

    private fun String.noSpace() = filterNot { it.isWhitespace() }

    private fun assertNothingLost(name: String) {
        val original = asset(name).readText()
        val blocks = reflowLicenceText(original)
        assertEquals("$name changed beyond whitespace", original.noSpace(), blocks.joinToString("") { it.text }.noSpace())
    }

    @Test
    fun agplTextLosesNoCharacters() = assertNothingLost("LICENSE")

    @Test
    fun noticesLoseNoCharacters() = assertNothingLost("THIRD_PARTY_NOTICES.md")

    @Test
    fun agplProseIsJoinedAndTitleCentred() {
        val blocks = reflowLicenceText(asset("LICENSE").readText())
        assertEquals(
            LicenceBlock("GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3, 19 November 2007", centred = true),
            blocks.first(),
        )
        assertTrue(LicenceBlock("Preamble", centred = true) in blocks)
        val first = blocks.first { it.text.startsWith("The GNU Affero General Public License is a free") }
        assertTrue("prose not joined: ${first.text}", '\n' !in first.text)
        assertTrue(first.text.contains("copyleft license for software and other kinds of works"))
    }

    @Test
    fun proseLinesAreJoinedWithIndentCollapsed() {
        val text = "  The licenses for most software and other practical works are designed\n" +
            "to take away your freedom to share and change the works.  By contrast,\n" +
            "our General Public Licenses are intended to guarantee your freedom."
        assertEquals(
            listOf(
                LicenceBlock(
                    "The licenses for most software and other practical works are designed to take away your " +
                        "freedom to share and change the works.  By contrast, our General Public Licenses are " +
                        "intended to guarantee your freedom.",
                ),
            ),
            reflowLicenceText(text),
        )
    }

    @Test
    fun ruleLinesAndHeadingsKeepTheirLines() {
        val text = "THIRD-PARTY NOTICES\n===================\n\nHyphenation patterns\n--------------------\n\n" +
            "*****************************************************************\n"
        assertEquals(
            listOf(
                LicenceBlock("THIRD-PARTY NOTICES\n==================="),
                LicenceBlock("Hyphenation patterns\n--------------------"),
                LicenceBlock("*****************************************************************"),
            ),
            reflowLicenceText(text),
        )
    }

    @Test
    fun listItemsStartNewLinesAndJoinTheirContinuations() {
        val text = "    a) The work must carry prominent notices stating that you modified\n" +
            "    it, and giving a relevant date.\n" +
            "    b) The work must carry prominent notices stating that it is\n" +
            "    released under this License and any conditions added under section\n" +
            "    7.  This requirement modifies the requirement in section 4.\n" +
            "  - a dash item that is long enough to be wrapped by the original author\n" +
            "    onto a second line;\n" +
            "  (1) a numbered item in parentheses that is also wrapped by its author\n" +
            "      onto another line"
        assertEquals(
            listOf(
                LicenceBlock(
                    "a) The work must carry prominent notices stating that you modified it, and giving a relevant date.\n" +
                        "b) The work must carry prominent notices stating that it is released under this License and " +
                        "any conditions added under section 7.  This requirement modifies the requirement in section 4.\n" +
                        "- a dash item that is long enough to be wrapped by the original author onto a second line;\n" +
                        "(1) a numbered item in parentheses that is also wrapped by its author onto another line",
                ),
            ),
            reflowLicenceText(text),
        )
    }

    private fun notices() = reflowLicenceText(asset("THIRD_PARTY_NOTICES.md").readText())

    private fun List<LicenceBlock>.startingWith(prefix: String) = single { it.text.startsWith(prefix) }

    @Test
    fun headingsAloneOnALineStayOnTheirOwnLine() {
        val blocks = notices()
        // SIL Open Font License 1.1 (URW++ fonts, Manrope).
        assertTrue(blocks.none { it.text.startsWith("PREAMBLE The goals") })
        val preamble = blocks.filter { it.text.startsWith("PREAMBLE\nThe goals of the Open Font License (OFL) are to stimulate worldwide development") }
        assertEquals(1, preamble.size)
        preamble.forEach { assertEquals(it.text, 1, it.text.count { c -> c == '\n' }) }
        assertTrue(blocks.none { it.text.startsWith("DEFINITIONS \"Font Software\"") })
        blocks.startingWith("DEFINITIONS\n\"Font Software\" refers to the set of files released by the Copyright Holder(s)")
        // MPL 1.1: a numbered heading on its own line.
        val grant = blocks.startingWith("2.1. The Initial Developer Grant.")
        assertTrue(grant.text, grant.text.startsWith("2.1. The Initial Developer Grant.\nThe Initial Developer hereby grants You a world-wide"))
        assertTrue(blocks.startingWith("3.4. Intellectual Property Matters").text.startsWith("3.4. Intellectual Property Matters\n(a) Third Party Claims.\nIf Contributor"))
    }

    @Test
    fun copyrightLinesKeepTheirOwnLines() {
        val source = asset("THIRD_PARTY_NOTICES.md").readText()
        val harfBuzz = source.lines().filter { it.startsWith("Copyright © ") }
        assertEquals(17, harfBuzz.size)
        assertTrue(LicenceBlock(harfBuzz.joinToString("\n")) in notices())
        assertTrue(
            LicenceBlock(
                "Copyright 2016 by (URW)++ Design & Development.\n" +
                    "Copyright 2018 The Manrope Project Authors (https://github.com/googlefonts/manrope)",
            ) in notices(),
        )
        val agpl = reflowLicenceText(asset("LICENSE").readText())
        assertTrue(
            LicenceBlock(
                "Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\n" +
                    "Everyone is permitted to copy and distribute verbatim copies of this license document, " +
                    "but changing it is not allowed.",
            ) in agpl,
        )
        // GPL 2: a copyright line ending in ',' continues on the next line.
        assertTrue(
            notices().startingWith("Copyright (C) 1989, 1991").text
                .startsWith("Copyright (C) 1989, 1991 Free Software Foundation, Inc., <https://fsf.org/>\nEveryone is permitted"),
        )
    }

    @Test
    fun copyrightWordsInsideProseOrListItemsDoNotBreak() {
        val blocks = notices()
        // Apache 4(c): a list item labelled "(c)", not a copyright line.
        val apache = blocks.startingWith("(c) You must retain, in the Source form of any Derivative Works")
        assertTrue(apache.text, '\n' !in apache.text)
        // OFL 3): "Copyright Holder" wrapped to the start of a line.
        val ofl = blocks.first { it.text.startsWith("3) No Modified Version of the Font Software") }
        assertTrue(ofl.text, ofl.text.contains("by the corresponding Copyright Holder. This restriction") && '\n' !in ofl.text)
    }

    @Test
    fun commentBlockKeepsItsLines() {
        val source = asset("THIRD_PARTY_NOTICES.md").readText().lines()
        val start = source.indexOfFirst { it.startsWith(" * The copyright in this software is being made available") } - 1
        val end = source.indexOfFirst { it == " */" }
        val original = source.subList(start, end + 1).map { it.trimEnd() }
        val block = notices().startingWith("/*")
        assertEquals(original.joinToString("\n"), block.text)
        // No "*" lands inside a sentence: each display line has at most its own leading "*".
        block.text.lines().forEach { assertTrue(it, " * " !in it.trim().drop(1)) }
    }

    @Test
    fun shortParagraphsAreJoinedByLongestLine() {
        assertTrue(
            LicenceBlock("The Corresponding Source for a work in source code form is that same work.") in
                reflowLicenceText(asset("LICENSE").readText()),
        )
        assertTrue(LicenceBlock("Please replace <year> with the value from the FreeType version you actually use.") in notices())
        assertTrue(LicenceBlock("TERMINATION\nThis license becomes null and void if any of the above conditions are not met.") in notices())
    }

    @Test
    fun shortLinesKeepTheirBreaks() {
        val text = "MuPDF 1.28.5\nFreeType 2.14.3\nHarfBuzz 13.0.1"
        assertEquals(listOf(LicenceBlock(text)), reflowLicenceText(text))
    }
}
