package app.venkat.offkit.ui.about

/**
 * One displayed block of a licence text: [text] is shown as is, centred when [centred].
 */
internal data class LicenceBlock(val text: String, val centred: Boolean = false)

/** A line made only of `=`, `-`, `*` or `_` (a heading underline or separator). */
private val RULE_LINE = Regex("""^\s*[=\-*_]{3,}\s*$""")

/** The start of a list item: `-`, `*`, `•`, `(a)`, `(iv)`, `a)`, `1.`, `1)`, `10.`. */
private val LIST_ITEM = Regex("""^\s*([-*•]|\([0-9A-Za-z]{1,4}\)|[0-9]{1,3}[.)]|[A-Za-z][.)])\s+\S""")

/** Leading spaces from which a line in a short block counts as centred. */
private const val CENTRED_INDENT = 8

/** Average line length (trimmed) under which a block's line breaks are taken as deliberate. */
private const val SHORT_LINE_AVERAGE = 40

/**
 * Reflows a plain-text licence hard-wrapped at ~75 columns for a narrow screen, for display only.
 *
 * The text is split into blocks at blank lines. Prose lines inside a block are joined into one
 * paragraph (line break -> space, indentation collapsed); a list item (after a line that does not
 * end in a letter, so not mid-sentence) starts a new line and its wrapped continuation lines are
 * joined onto it. Blocks are kept line by line when they contain a
 * rule line (`====`, `----`, so also a heading with its underline) or when their lines are short
 * on purpose (average under [SHORT_LINE_AVERAGE] characters: titles, name/version tables). A
 * short block whose lines are all indented by [CENTRED_INDENT] or more spaces is trimmed and
 * marked [LicenceBlock.centred]. No non-whitespace character is added, removed or reordered.
 */
internal fun reflowLicenceText(text: String): List<LicenceBlock> =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { block -> block.lines().map { it.trimEnd() }.filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }
        .map(::reflowBlock)

private fun reflowBlock(lines: List<String>): LicenceBlock {
    val average = lines.sumOf { it.trim().length } / lines.size
    val shortOnPurpose = average < SHORT_LINE_AVERAGE
    if (shortOnPurpose && lines.all { it.length - it.trimStart().length >= CENTRED_INDENT }) {
        return LicenceBlock(lines.joinToString("\n") { it.trim() }, centred = true)
    }
    if (lines.any { RULE_LINE.matches(it) } || shortOnPurpose) {
        return LicenceBlock(lines.joinToString("\n"))
    }
    val out = StringBuilder()
    lines.forEachIndexed { index, line ->
        when {
            index == 0 -> out.append(line.trim())
            // "section\n7.  This ..." is a wrapped sentence, not an item: items follow a finished line.
            LIST_ITEM.containsMatchIn(line) && !lines[index - 1].last().isLetter() -> out.append('\n').append(line.trim())
            else -> out.append(' ').append(line.trim())
        }
    }
    return LicenceBlock(out.toString())
}
