package com.jetbrains.rider.plugins.robustyaml

import com.jetbrains.rider.plugins.robustyaml.editor.RobustSequenceDashMacro
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a skeleton has to write its own `- `. The case that made this a function rather than a
 * literal in the template is the second one: Enter under `components:` leaves the dash behind, and
 * writing another gave `- - type:`.
 */
class RobustSequenceDashTest {
    @Test
    fun `an empty line owes a dash`() {
        assertTrue(RobustSequenceDashMacro.dashNeeded(""))
        assertTrue(RobustSequenceDashMacro.dashNeeded("    "))
        assertTrue(RobustSequenceDashMacro.dashNeeded("\t"))
    }

    @Test
    fun `a line opened by the enter handler does not`() {
        assertFalse(RobustSequenceDashMacro.dashNeeded("  - "))
        assertFalse(RobustSequenceDashMacro.dashNeeded("-"))
        assertFalse(RobustSequenceDashMacro.dashNeeded("      -   "))
    }

    @Test
    fun `text of its own is not a dash`() {
        assertTrue(RobustSequenceDashMacro.dashNeeded("  id: "))
        assertTrue(RobustSequenceDashMacro.dashNeeded("  - type: Sprite "))
    }
}
