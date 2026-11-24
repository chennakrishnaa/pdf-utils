package app.venkat.offkit.ui.about

/**
 * One displayed block of a licence text: [text] is shown as is, centred when [centred].
 */
internal data class LicenceBlock(val text: String, val centred: Boolean = false)

/** A line made only of `=`, `-`, `*` or `_` (a heading underline or separator). */
private val RULE_LINE = Regex("""^\s*[=\-*_]{3,}\s*$""")

/** The start of a list item: `-`, `*`, `•`, `(a)`, `(iv)`, `a)`, `1.`, `1)`, `10.`. */
private val LIST_ITEM = Regex("""^\s*([-*•]|\([0-9A-Za-z]{1,4}\)|[0-9]{1,3}[.)]|[A-Za-z][.)])\s+\S""")

/**
 * A copyright line: `Copyright`, `(c)`, `(C)` or `©`, then a year or a `<year>` placeholder
 * (`Copyright (C) 2007 ...`, `Copyright © 2011 ...`, `Copyright 2016 by ...`). The year keeps a
 * list item `(c) You must ...` and a wrapped "... corresponding / Copyright Holder." out.
 */
private val COPYRIGHT_LINE = Regex("""^\s*(Copyright|\([cC]\)|©)\s+((\([cC]\)|©)\s*)?[0-9<]""")

/** A numbered section heading: `2.1. The Initial Developer Grant.`, `3.4. Intellectual ...`. */
private val NUMBERED_HEADING = Regex("""^\s*[0-9]+(\.[0-9]+)*\.\s+\S""")

/** A source-comment line: starts with `*` (also the closing star-slash), slash-star, or `%`. */
private val COMMENT_LINE = Regex("""^\s*(/\*|\*|%)""")

/** Leading spaces from which a line in a short block counts as centred. */
private const val CENTRED_INDENT = 8

/** Longest line length (trimmed) under which a block's line breaks are taken as deliberate. */
private const val SHORT_LINE_MAX = 40

/** A line under this fraction of the block's longest line can be a heading (see [isHeading]). */
private const val HEADING_FRACTION = 0.6

/**
 * Reflows a plain-text licence hard-wrapped at ~75 columns for a narrow screen, for display only.
 *
 * The text is split into blocks at blank lines. A block is kept line by line when it contains a
 * rule line (`====`, `----`, so also a heading with its underline), when it is a source comment
 * of two or more lines (every line starts with `*`, slash-star or `%`: the prefixes stay, so no stray
 * `*` lands inside a sentence), or when its lines are short on purpose (longest line under
 * [SHORT_LINE_MAX] characters: titles, name/version tables). A short block whose lines are all
 * indented by [CENTRED_INDENT] or more spaces is trimmed and marked [LicenceBlock.centred].
 *
 * Otherwise the lines are joined into paragraphs (line break -> one space, indentation
 * collapsed), except that a line starts a new display line when:
 * - it is a list item and the line before does not end in a letter (so not mid-sentence);
 *   the item's wrapped continuation lines are joined onto it;
 * - it is a copyright line ([COPYRIGHT_LINE]), or the first line after a run of them (a copyright
 *   line ending in `,` takes the next line as its continuation);
 * - the line before is a heading ([isHeading]): `PREAMBLE`, `2.1. The Initial Developer Grant.`.
 *
 * No non-whitespace character is added, removed or reordered.
 */
internal fun reflowLicenceText(text: String): List<LicenceBlock> =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { block -> block.lines().map { it.trimEnd() }.filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }
        .map(::reflowBlock)

/**
 * True if [line] stands alone as a heading: short (under [SHORT_LINE_MAX]) and all upper case,
 * or under [HEADING_FRACTION] of the block's [longest] line and either a numbered heading or
 * ending a sentence (not in a letter, digit, `,`, `;`, `-`, `(` or `/`). Hard-wrapped prose fills
 * its lines, so a line that much shorter which ends a sentence was broken on purpose.
 */
private fun isHeading(line: String, longest: Int): Boolean {
    val t = line.trim()
    if (t.length < SHORT_LINE_MAX && t.any { it.isLetter() } && t == t.uppercase()) return true
    if (t.length >= longest * HEADING_FRACTION) return false
    val last = t.last()
    return NUMBERED_HEADING.containsMatchIn(t) || !(last.isLetterOrDigit() || last in ",;-(/")
}

private fun reflowBlock(lines: List<String>): LicenceBlock {
    val longest = lines.maxOf { it.trim().length }
    val shortOnPurpose = longest < SHORT_LINE_MAX
    if (shortOnPurpose && lines.all { it.length - it.trimStart().length >= CENTRED_INDENT }) {
        return LicenceBlock(lines.joinToString("\n") { it.trim() }, centred = true)
    }
    val comment = lines.size > 1 && lines.all { COMMENT_LINE.containsMatchIn(it) }
    if (shortOnPurpose || comment || lines.any { RULE_LINE.matches(it) }) {
        return LicenceBlock(lines.joinToString("\n"))
    }
    // copyright[i]: line i is a copyright line or the continuation of one that ends in ','.
    val copyright = BooleanArray(lines.size)
    lines.forEachIndexed { i, line ->
        copyright[i] = COPYRIGHT_LINE.containsMatchIn(line) || (i > 0 && copyright[i - 1] && lines[i - 1].endsWith(','))
    }
    val out = StringBuilder()
    lines.forEachIndexed { index, line ->
        if (index == 0) {
            out.append(line.trim())
            return@forEachIndexed
        }
        val previous = lines[index - 1]
        val newLine =
            // "section\n7.  This ..." is a wrapped sentence, not an item: items follow a finished line.
            (LIST_ITEM.containsMatchIn(line) && !previous.last().isLetter()) ||
                COPYRIGHT_LINE.containsMatchIn(line) ||
                (copyright[index - 1] && !copyright[index]) ||
                isHeading(previous, longest)
        out.append(if (newLine) '\n' else ' ').append(line.trim())
    }
    return LicenceBlock(out.toString())
}
