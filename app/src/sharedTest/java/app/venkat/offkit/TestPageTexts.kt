package app.venkat.offkit

import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PageTextFormat

/** Canned page texts for selection tests. */
object TestPageTexts {
    const val X0 = 250f
    const val Y0 = 400f
    const val CHAR_W = 10f
    const val CHAR_H = 12f
    const val ROW = 30f

    /**
     * [lines], one per row, each character [CHAR_W] x [CHAR_H] pt, the first at ([X0], [Y0]) and
     * each row [ROW] pt below the previous one; all in one block.
     */
    fun grid(vararg lines: String, truncated: Boolean = false): PageText {
        val text = lines.joinToString("")
        val boxes = FloatArray(text.length * 4)
        val starts = IntArray(lines.size)
        var i = 0
        for ((row, line) in lines.withIndex()) {
            starts[row] = i
            for (col in line.indices) {
                boxes[4 * i] = X0 + col * CHAR_W
                boxes[4 * i + 1] = Y0 + row * ROW
                boxes[4 * i + 2] = X0 + (col + 1) * CHAR_W
                boxes[4 * i + 3] = Y0 + row * ROW + CHAR_H
                i++
            }
        }
        return PageTextFormat.build(text, boxes, starts, IntArray(lines.size), truncated)
    }

    /** Page point at the middle of character [col] of row [row] of a [grid]. */
    fun centre(row: Int, col: Int): Pair<Float, Float> = (X0 + (col + 0.5f) * CHAR_W) to (Y0 + row * ROW + CHAR_H / 2f)
}
