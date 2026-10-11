package dev.omalauncher.ui

import dev.omalauncher.wayland.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraKeysTest {
    /** The seeded file is the layout the launcher always had. */
    @Test fun defaultFileIsTheLaunchersLayout() {
        val rows = DefaultExtraKeys
        assertEquals(listOf(8, 8), rows.map { it.size })
        assertEquals(Plain("esc", Keys.KEY_ESC), rows[0][0])
        assertEquals(Plain("/", Keys.KEY_SLASH), rows[0][1])
        assertEquals(Plain("copy", Keys.KEY_INSERT, held = Keys.KEY_LEFTCTRL), rows[0][7])
        assertEquals("ctrl", (rows[1][1] as Sticky).label)
        assertEquals(Plain("paste", Keys.KEY_INSERT, held = Keys.KEY_LEFTSHIFT), rows[1][7])
    }

    @Test fun namesAreCaseInsensitiveAndTheRestIsTyped() {
        val rows = parseExtraKeys("ESC | ~ F5 ls\n")!!
        assertEquals(listOf(Plain("esc", Keys.KEY_ESC), Typed("|"), Typed("~"), Plain("f5", Keys.KEY_F1 + 4), Typed("ls")), rows.single())
    }

    @Test fun skipsCommentsAndBlankLinesAndCapsRows() {
        val rows = parseExtraKeys("# keys\n\n  tab  \n" + "up\n".repeat(6))!!
        assertEquals(4, rows.size)
        assertEquals(Plain("tab", Keys.KEY_TAB), rows[0].single())
    }

    @Test fun anEmptyFileKeepsTheDefault() {
        assertNull(parseExtraKeys("# nothing here\n\n"))
    }
}
