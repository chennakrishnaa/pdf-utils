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

    @Test
    fun shortLinesKeepTheirBreaks() {
        val text = "MuPDF 1.28.5\nFreeType 2.14.3\nHarfBuzz 13.0.1"
        assertEquals(listOf(LicenceBlock(text)), reflowLicenceText(text))
    }
}
