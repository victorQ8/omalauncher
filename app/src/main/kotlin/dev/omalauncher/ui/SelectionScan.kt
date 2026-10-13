package dev.omalauncher.ui

import kotlin.math.abs

/** Where foot drew a selection, in the window view's pixels. */
internal data class SelectionBounds(
    /** The first selected cell's left edge, and the bottom of its row (exclusive). */
    val startX: Int,
    val startBottom: Int,
    /** The last selected cell's right edge, and the bottom of its row (both exclusive). */
    val endX: Int,
    val endBottom: Int,
    /** The first row's height: one cell's, unless the rows' spans couldn't tell them apart. */
    val firstRowHeight: Int,
)

/**
 * Finds the selection in a window's [pixels], [width] wide, as ARGB: the cells foot drew in
 * [color], the selection's background, which nothing else on screen is drawn in (see
 * GuestThemeConfig.selectionColor). foot never highlights empty cells, so the ends found are
 * the first and last selected cells with text. Rows are [cellHeight] tall when that's known
 * (more than 0); otherwise the first and last rows end where the highlighted span's edges move.
 * Null if nothing is highlighted.
 */
internal fun findSelection(
    pixels: IntArray,
    width: Int,
    color: Int,
    cellHeight: Int,
): SelectionBounds? {
    val height = pixels.size / width
    val left = IntArray(height) { -1 }
    val right = IntArray(height) { -1 }
    for (y in 0 until height) {
        val row = y * width
        var x = 0
        while (x < width && !matches(pixels[row + x], color)) x++
        if (x == width) continue
        left[y] = x
        x = width - 1
        while (!matches(pixels[row + x], color)) x--
        right[y] = x
    }
    val top = left.indexOfFirst { it >= 0 }
    if (top < 0) return null
    val bottom = left.indexOfLast { it >= 0 }

    /** From row [from], the furthest row in [step]'s direction that still spans the same columns. */
    fun sameSpan(
        from: Int,
        step: Int,
    ): Int {
        var y = from
        while (y + step in top..bottom && left[y + step] >= 0 &&
            abs(left[y + step] - left[from]) <= 1 && abs(right[y + step] - right[from]) <= 1
        ) {
            y += step
        }
        return y
    }
    val firstEnd = if (cellHeight > 0) minOf(top + cellHeight - 1, bottom) else sameSpan(top, 1)
    val lastStart = if (cellHeight > 0) maxOf(bottom - cellHeight + 1, top) else sameSpan(bottom, -1)
    val startX = (top..firstEnd).filter { left[it] >= 0 }.minOf { left[it] }
    val endX = (lastStart..bottom).filter { right[it] >= 0 }.maxOf { right[it] } + 1
    return SelectionBounds(startX, firstEnd + 1, endX, bottom + 1, firstEnd - top + 1)
}

/** Whether ARGB [pixel] is [color], give or take one in each channel for how the screen was read. */
private fun matches(
    pixel: Int,
    color: Int,
): Boolean =
    abs((pixel shr 16 and 0xff) - (color shr 16 and 0xff)) <= 1 &&
        abs((pixel shr 8 and 0xff) - (color shr 8 and 0xff)) <= 1 &&
        abs((pixel and 0xff) - (color and 0xff)) <= 1
