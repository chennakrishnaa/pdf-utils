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
 * - Removes control characters (C0, DEL, C1, so NUL, newlines and tabs too), bidi controls and
 *   isolates (U+200E/200F, U+061C, U+202A–202E, U+2066–2069), zero-width characters
 *   (U+200B–200D, U+2060) and the BOM (U+FEFF). Removing U+200D (zero-width joiner) splits
 *   compound emoji into their parts; simple emoji and non-Latin text are kept.
 * - Replaces the path separators `/` and `\` and the `:` with `_`.
 * - Collapses runs of whitespace to one space and trims.
 * - Caps the result at [MAX_DISPLAY_NAME_LENGTH] code points, keeping a short extension.
 *
 * Returns [fallback] when nothing is left (null, blank or only removed characters).
 */
fun sanitizeDisplayName(raw: String?, fallback: String): String {
    if (raw.isNullOrEmpty()) return fallback
    // Bound the work for absurd inputs before scanning: a few times the cap is plenty.
    // The end is kept too, so an extension survives (split surrogate halves are dropped below).
    val input = if (raw.length > SCAN_LIMIT) raw.substring(0, SCAN_LIMIT) + raw.substring(raw.length - SCAN_TAIL) else raw
    val sb = StringBuilder(input.length)
    var lastWasSpace = false
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        i += Character.charCount(cp)
        if (isRemoved(cp)) continue
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
    return if (cleaned.isBlank()) fallback else cleaned
}

private fun isRemoved(cp: Int): Boolean =
    cp < 0x20 || cp in 0x7F..0x9F || // C0, DEL, C1 (NUL, CR, LF, TAB included)
        cp == 0x061C || cp == 0x200E || cp == 0x200F || // bidi marks
        cp in 0x202A..0x202E || cp in 0x2066..0x2069 || // bidi embeddings, overrides, isolates
        cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF || // zero-width characters, BOM
        Character.getType(cp) == Character.SURROGATE.toInt() // unpaired surrogate halves

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
