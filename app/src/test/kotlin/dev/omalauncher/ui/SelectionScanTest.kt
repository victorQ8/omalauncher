package dev.omalauncher.ui

import dev.omalauncher.BuildConfig
import dev.omalauncher.theme.GuestThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionScanTest {
    private val width = 100
    private val height = 80
    private val background = 0xFF1A1B26.toInt()
    private val accent = 0xFF7AA2F7.toInt()

    /** foot's selection color in the terminal app: the accent, three steps of blue off. */
    private val selection = 0xFF7AA2F4.toInt()

    private fun screen() = IntArray(width * height) { background }

    private fun IntArray.fill(
        x: IntRange,
        y: IntRange,
        color: Int,
    ) {
        for (row in y) for (col in x) this[row * width + col] = color
    }

    @Test fun aWordIsOneRow() {
        val pixels = screen()
        pixels.fill(20..49, 10..29, selection)
        // Its letters, drawn in the background color, cut into the highlight.
        pixels.fill(24..26, 12..27, background)
        pixels.fill(20..20, 15..20, background)
        assertEquals(SelectionBounds(20, 30, 50, 30, 20), findSelection(pixels, width, selection, 0))
    }

    @Test fun rowsOfAKnownHeight() {
        val pixels = screen()
        pixels.fill(40..99, 0..19, selection)
        pixels.fill(0..99, 20..39, selection)
        pixels.fill(0..29, 40..59, selection)
        assertEquals(SelectionBounds(40, 20, 30, 60, 20), findSelection(pixels, width, selection, 20))
    }

    /** Before a word measured a cell, the first row ends where the highlighted span changes. */
    @Test fun rowsTellApartByTheirSpans() {
        val pixels = screen()
        pixels.fill(40..99, 0..19, selection)
        pixels.fill(0..59, 20..39, selection)
        assertEquals(SelectionBounds(40, 20, 60, 40, 20), findSelection(pixels, width, selection, 0))
    }

    /** Text in the accent itself, like a directory in ls, isn't the selection. */
    @Test fun accentTextIsNotSelected() {
        val pixels = screen()
        pixels.fill(0..99, 50..69, accent)
        assertNull(findSelection(pixels, width, selection, 0))
        pixels.fill(10..19, 10..29, selection)
        assertEquals(SelectionBounds(10, 30, 20, 30, 20), findSelection(pixels, width, selection, 0))
    }

    @Test fun nothingSelected() {
        assertNull(findSelection(screen(), width, selection, 20))
    }

    @Test fun selectionColorIsTheAccentOrJustOffIt() {
        val colors = mapOf("accent" to "#7aa2f7", "background" to "#1a1b26")
        val color = GuestThemeConfig.selectionColor(colors)
        if (BuildConfig.TERMINAL_ONLY) {
            assertEquals("#7aa2f4", color)
            assertEquals("#3a4b0b", GuestThemeConfig.selectionColor(mapOf("accent" to "#3a4b08")))
        } else {
            assertEquals("#7aa2f7", color)
        }
        assertNotEquals(null, GuestThemeConfig.selectionColor(mapOf("blue" to "#123456")))
    }
}
