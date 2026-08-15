package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.editor.impl.DocumentImpl
import com.jetbrains.rider.plugins.robustyaml.editor.ownerLine
import com.jetbrains.rider.plugins.robustyaml.editor.sequenceIndent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a dash typed under a key ends up. SS14 writes items on the level of their key, but a file
 * written with the platform indent must not be split in two, so an existing list wins over the rule.
 */
class RobustSequenceIndentTest {
    @Test
    fun `existing items decide the indent`() {
        val document = document(
            "      vertices:",
            "      ",
            "        - -0.20,0.10",
            "        - -0.10,0.20",
        )

        assertEquals(8, sequenceIndent(document, owner = 0, typed = 1))
    }

    @Test
    fun `an empty list falls back to the level of the key`() {
        val document = document(
            "      vertices:",
            "      ",
            "      density: 20",
        )

        assertEquals(6, sequenceIndent(document, owner = 0, typed = 1))
    }

    @Test
    fun `items written on the level of the key keep that level`() {
        val document = document(
            "  components:",
            "  ",
            "  - type: Sprite",
        )

        assertEquals(2, sequenceIndent(document, owner = 0, typed = 1))
    }

    /** The line being typed is not an item yet, and counting it would freeze the current indent. */
    @Test
    fun `the typed line is skipped`() {
        val document = document(
            "      vertices:",
            "          -",
            "        - -0.20,0.10",
        )

        assertEquals(8, sequenceIndent(document, owner = 0, typed = 1))
    }

    @Test
    fun `a mapping under the key is not a sequence`() {
        val document = document(
            "    fixtures:",
            "    ",
            "      fix1:",
        )

        assertEquals(4, sequenceIndent(document, owner = 0, typed = 1))
    }

    /** An item of an outer list sits to the left of the key and belongs to somebody else. */
    @Test
    fun `an item indented less than the key is not ours`() {
        val document = document(
            "      vertices:",
            "      ",
            "  - type: Sprite",
        )

        assertEquals(6, sequenceIndent(document, owner = 0, typed = 1))
    }

    @Test
    fun `the owner is the closest line ending with a colon`() {
        val document = document(
            "  layers:",
            "",
            "    ",
        )

        assertEquals(0, ownerLine(document, 2))
    }

    @Test
    fun `a line that is not a key owns nothing`() {
        val document = document(
            "  - state: red",
            "    ",
        )

        assertNull(ownerLine(document, 1))
    }

    private fun document(vararg lines: String) = DocumentImpl(lines.joinToString("\n"))
}
