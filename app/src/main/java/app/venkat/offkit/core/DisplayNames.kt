package app.venkat.offkit.core

/** Longest display name kept, in Unicode code points, extension included. */
const val MAX_DISPLAY_NAME_LENGTH = 120

/** Longest extension (after the last dot, dot excluded) that is kept separately when truncating. */
private const val MAX_EXTENSION_LENGTH = 10

/** Inputs longer than this many chars are cut to their start plus [SCAN_TAIL] chars of their end. */
private const val SCAN_LIMIT = MAX_DISPLAY_NAME_LENGTH * 8
private const val SCAN_TAIL = 32

/**
 * Makes an untrusted display name (from a documents provider, or stored earlier) safe to show
 * and to offer as a save-dialog suggestion. Display names are only ever shown or suggested,
 * never used to build a path.
 *
 * - Removes, by category (SR-6), every control (Cc: NUL, newlines, tabs), format (Cf: bidi
 *   controls and isolates, zero-width characters, BOM, soft hyphen, tag characters,
 *   U+2061–2064, U+206A–206F, U+FFF9–FFFB), unpaired surrogate (Cs), private-use (Co) and
 *   unassigned (Cn) code point, plus the default-ignorable ones in other categories (CGJ
 *   U+034F, Hangul fillers U+115F/1160/3164/FFA0, variation selectors, ...). Removing U+200D
 *   (zero-width joiner) and U+FE0F splits compound emoji into their parts; simple emoji and
 *   non-Latin text are kept.
 * - Keeps at most 3 consecutive combining marks (Mn/Me) per base character.
 * - Replaces the path separators `/` and `\` and the `:` with `_`.
 * - Collapses runs of whitespace to one space and trims.
 * - Caps the result at [MAX_DISPLAY_NAME_LENGTH] code points, keeping a short extension.
 *
 * Returns [fallback] when nothing visible is left (null, blank, only removed characters, or
 * only whitespace and combining marks).
 */
fun sanitizeDisplayName(raw: String?, fallback: String): String {
    if (raw.isNullOrEmpty()) return fallback
    // Bound the work for absurd inputs before scanning: a few times the cap is plenty.
    // The end is kept too, so an extension survives (split surrogate halves are dropped below).
    val input = if (raw.length > SCAN_LIMIT) raw.substring(0, SCAN_LIMIT) + raw.substring(raw.length - SCAN_TAIL) else raw
    val sb = StringBuilder(input.length)
    var lastWasSpace = false
    var marks = 0
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        i += Character.charCount(cp)
        if (isRemoved(cp)) continue
        if (isCombiningMark(cp)) {
            if (++marks > MAX_COMBINING_MARKS) continue
        } else {
            marks = 0
        }
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            if (!lastWasSpace) sb.append(' ')
            lastWasSpace = true
            continue
        }
        lastWasSpace = false
        when (cp) {
            '/'.code, '\\'.code, ':'.code -> sb.append('_')
            else -> sb.appendCodePoint(cp)
        }
    }
    val cleaned = capLength(sb.toString().trim())
    return if (!hasVisible(cleaned)) fallback else cleaned
}

/** Unicode general categories removed outright: Cc, Cf, Cs (unpaired), Co, Cn. */
private val REMOVED_TYPES = intArrayOf(
    Character.CONTROL.toInt(), // C0, DEL, C1 (NUL, CR, LF, TAB included)
    Character.FORMAT.toInt(), // bidi controls/isolates, ZW characters, BOM, soft hyphen, tags, U+2061-2064, U+206A-206F, U+FFF9-FFFB
    Character.SURROGATE.toInt(), // unpaired surrogate halves
    Character.PRIVATE_USE.toInt(),
    Character.UNASSIGNED.toInt(),
)

private fun isRemoved(cp: Int): Boolean =
    Character.getType(cp) in REMOVED_TYPES || isDefaultIgnorable(cp)

/**
 * Default-ignorable code points that are not in a removed category (most are marks or letters
 * that render as nothing): CGJ, Hangul fillers, Khmer inherent vowels, Mongolian selectors,
 * variation selectors, musical-symbol format characters, the tag and variation-selector planes.
 */
private fun isDefaultIgnorable(cp: Int): Boolean =
    cp == 0x00AD || cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 ||
        cp == 0x17B4 || cp == 0x17B5 || cp in 0x180B..0x180F || cp in 0xFE00..0xFE0F ||
        cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF ||
        cp in 0x200B..0x200F || cp in 0x2060..0x206F || cp == 0xFEFF || cp in 0xFFF0..0xFFF8

/** Combining (Mn/Me) marks kept after one base character; further ones are dropped (no "Zalgo"). */
private const val MAX_COMBINING_MARKS = 3

private fun isCombiningMark(cp: Int): Boolean {
    val t = Character.getType(cp)
    return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
}

/** True when [s] has at least one code point that draws something: not whitespace, not a mark. */
private fun hasVisible(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !isCombiningMark(cp)) return true
    }
    return false
}

private fun capLength(name: String): String {
    val count = name.codePointCount(0, name.length)
    if (count <= MAX_DISPLAY_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot) else ""
    val extCount = ext.codePointCount(0, ext.length)
    val keepExt = extCount in 2..(MAX_EXTENSION_LENGTH + 1)
    val tail = if (keepExt) ext else ""
    val baseSource = if (keepExt) name.substring(0, dot) else name
    val budget = MAX_DISPLAY_NAME_LENGTH - (if (keepExt) extCount else 0)
    val end = baseSource.offsetByCodePoints(0, minOf(budget, baseSource.codePointCount(0, baseSource.length)))
    return baseSource.substring(0, end).trimEnd() + tail
}
