package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate files for Find Usages. Precision comes from resolving the reference afterwards, so the
 * index only has to be generous enough not to lose a mention and cheap enough to hold: it keys on
 * the two shapes a reference takes in the content — a prototype id and a localization key.
 */
class RobustYamlValueIndexTest {
    @Test
    fun `values of keys are indexed`() {
        val keys = values(
            """
            - type: entity
              parent: BaseItem
              id: Crowbar
              name: crowbar-name
            """,
        )

        assertTrue(keys.containsAll(listOf("BaseItem", "Crowbar", "crowbar-name")))
    }

    @Test
    fun `sequence items are indexed`() {
        val keys = values(
            """
              parent:
              - BaseItem
              - BaseTool
            """,
        )

        assertTrue(keys.containsAll(listOf("BaseItem", "BaseTool")))
    }

    @Test
    fun `inline sequences are split`() {
        val keys = values("""  categories: [ HideSpawnMenu, Cargo ]""")

        assertTrue(keys.containsAll(listOf("HideSpawnMenu", "Cargo")))
    }

    @Test
    fun `quotes are stripped`() {
        assertTrue(values("""  parent: "BaseItem"""").contains("BaseItem"))
    }

    @Test
    fun `values that cannot be references are left out`() {
        val keys = values(
            """
            - type: entity
              sprite: Objects/Tools/crowbar.rsi
              amount: 12
              enabled: true
              color: '#4a90d9'
            """,
        )

        assertFalse(keys.contains("true"))
        assertFalse(keys.contains("12"))
        assertFalse(keys.any { it.contains('/') })
    }

    /** A comment is not a reference: the annotator never sees one, so neither should the index. */
    @Test
    fun `commented lines are ignored`() {
        val keys = values(
            """
            - type: entity
              parent: BaseItem # was BaseTool
            """,
        )

        assertTrue(keys.contains("BaseItem"))
        assertFalse(keys.contains("BaseTool"))
    }

    @Test
    fun `a byte order mark does not hide the first line`() {
        assertTrue(values("﻿  parent: BaseItem").contains("BaseItem"))
    }

    @Test
    fun `keys themselves are not values`() {
        val keys = values(
            """
            - type: entity
              components:
              - type: Sprite
            """,
        )

        assertEquals(setOf("Sprite"), keys - "entity")
    }

    private fun values(text: String): Set<String> =
        RobustYamlValueIndex.values(text.trimIndent()).keys
}
