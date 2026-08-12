package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading id declarations out of text. The index has no PSI to ask, so an alias has to be resolved
 * the way the engine resolves it: against the anchors of the whole document, not against the text
 * above the alias.
 */
class RobustPrototypeIdIndexTest {
    @Test
    fun `id is read with its kind`() {
        val ids = ids(
            """
            - type: entity
              id: Crowbar
            """,
        )

        assertEquals(setOf("Crowbar"), ids.keys)
        assertEquals("entity", kindOf(ids, "Crowbar"))
    }

    /**
     * `id: *BackgammonBoard` declares the id its anchor carries — thirteen prototypes in the content
     * are written this way, and the id itself appeared nowhere in the index before.
     */
    @Test
    fun `an alias declares the id its anchor carries`() {
        val ids = ids(
            """
            - type: entity
              id: BackgammonBoard
              components:
              - type: TabletopGame
                setup:
                  !type:TabletopBackgammonSetup
                  boardPrototype: &BackgammonBoard BackgammonBoardTabletop

            - type: entity
              id: *BackgammonBoard
            """,
        )

        assertEquals(setOf("BackgammonBoard", "BackgammonBoardTabletop"), ids.keys)
        assertEquals("entity", kindOf(ids, "BackgammonBoardTabletop"))
    }

    /** The alias is where the prototype is declared, so that is where a jump has to land. */
    @Test
    fun `the offset of an aliased id points at the alias`() {
        val text =
            """
            - type: entity
              id: Board
              components:
              - type: TabletopGame
                boardPrototype: &Board BoardTabletop

            - type: entity
              id: *Board
            """.trimIndent()

        val offset = offsetOf(RobustPrototypeIdIndex.prototypeIds(text), "BoardTabletop")
        assertEquals('*', text[offset])
        assertTrue("offset is not on the declaring line", text.startsWith("  id: *Board", offset - 6))
    }

    /**
     * Robust parses the document before resolving: an unknown anchor becomes a placeholder that a
     * second pass fills in, so an alias standing above its anchor is legal.
     */
    @Test
    fun `an alias is resolved when its anchor comes later`() {
        val ids = ids(
            """
            - type: entity
              id: *Board

            - type: entity
              id: Board
              components:
              - type: TabletopGame
                boardPrototype: &Board BoardTabletop
            """,
        )

        assertTrue("forward reference lost", "BoardTabletop" in ids)
    }

    /** An unresolvable alias is a load-time exception for the engine, and no id at all for us. */
    @Test
    fun `an alias without an anchor declares nothing`() {
        val ids = ids(
            """
            - type: entity
              id: *Missing
            """,
        )

        assertTrue("garbage id in the index: ${ids.keys}", ids.isEmpty())
    }

    /**
     * An anchor may mark a mapping rather than a value — `icon: &IconOpenClose` with the mapping on
     * the lines below — and 419 of the 758 aliases in the content point at one.
     */
    @Test
    fun `an anchor on a mapping carries no id`() {
        val ids = ids(
            """
            - type: entity
              id: Remote
              components:
              - type: Sprite
                icon: &IconOpenClose
                  sprite: Objects/Devices/remote.rsi
                  state: open

            - type: entity
              id: *IconOpenClose
            """,
        )

        assertEquals(setOf("Remote"), ids.keys)
    }

    /** `offset: &icon-offset -0.09375, 0.0625` marks a vector; half of it is not an id. */
    @Test
    fun `an anchor on a multi-token value carries no id`() {
        val ids = ids(
            """
            - type: entity
              id: Card
              components:
              - type: Sprite
                offset: &icon-offset -0.09375, 0.0625

            - type: entity
              id: *icon-offset
            """,
        )

        assertEquals(setOf("Card"), ids.keys)
    }

    /** `R&D computer board` is prose: an ampersand inside a value marks nothing. */
    @Test
    fun `an ampersand inside a value is not an anchor`() {
        val ids = ids(
            """
            - type: entity
              id: Board
              name: R&D computer board

            - type: entity
              id: *D
            """,
        )

        assertEquals(setOf("Board"), ids.keys)
    }

    private fun ids(text: String): Map<String, String> =
        RobustPrototypeIdIndex.prototypeIds(text.trimIndent())

    private fun kindOf(ids: Map<String, String>, id: String): String? =
        ids[id]?.let { RobustPrototypeIdIndex.parseEntries(it).firstOrNull()?.first }

    private fun offsetOf(ids: Map<String, String>, id: String): Int =
        ids[id]?.let { RobustPrototypeIdIndex.parseEntries(it).first().second }
            ?: throw AssertionError("no id '$id' in ${ids.keys}").also { assertNull(it) }
}
