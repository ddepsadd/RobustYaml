package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mentions of a localization key outside prototypes. A rename rewrites exactly what this finds, so a
 * mention read out of a comment would edit prose and a mention missed would leave a dead key behind.
 */
class RobustLocaleUsageIndexTest {
    @Test
    fun `a string literal is a usage`() {
        val usages = literals("""Loc.GetString("comp-thief-target");""")

        assertEquals(listOf("comp-thief-target"), usages.map { it.id })
    }

    @Test
    fun `the range covers the text inside the quotes`() {
        val text = """Loc.GetString("comp-thief-target");"""
        val usage = literals(text).single()

        assertEquals("comp-thief-target", text.substring(usage.start, usage.end))
    }

    @Test
    fun `several literals of one line are all found`() {
        val usages = literals("""Pick("hello-there", "general-kenobi");""")

        assertEquals(listOf("hello-there", "general-kenobi"), usages.map { it.id })
    }

    /** A key written down in prose is not a usage, and rewriting one would be editing English. */
    @Test
    fun `a literal inside a line comment is not a usage`() {
        val usages = literals("""// see "comp-thief-target" for the wording""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a literal inside a block comment is not a usage`() {
        val usages = literals("""/* uses "comp-thief-target" */ var x = 1;""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a literal inside a doc comment is not a usage`() {
        val usages = literals("""/// <summary>"comp-thief-target"</summary>""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a verbatim literal is a usage`() {
        val usages = literals("""var key = @"comp-thief-target";""")

        assertEquals(listOf("comp-thief-target"), usages.map { it.id })
    }

    /**
     * Without a state of its own the three quotes read as an empty literal followed by an opening
     * one, and everything after them is scanned as string — 57 files of ss14-wega start that way.
     */
    @Test
    fun `a raw string does not swallow the rest of the file`() {
        val usages = literals(
            """
            var json = ""${'"'}{ "a": 1 }""${'"'};
            Loc.GetString("comp-thief-target");
            """.trimIndent(),
        )

        assertEquals(listOf("comp-thief-target"), usages.map { it.id })
    }

    /** A key assembled at runtime has no whole form to rename, and its head is not the key. */
    @Test
    fun `an interpolated literal is not a usage`() {
        val usages = literals("""Loc.GetString($"accent-{name}-replacement");""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a literal without a dash is not indexed`() {
        val usages = literals("""Loc.GetString("captain");""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `an escaped quote does not end the literal`() {
        val usages = literals("""var s = "say \"comp-thief-target\" now"; Loc.GetString("real-key");""")

        assertEquals(listOf("real-key"), usages.map { it.id })
    }

    /** A char literal holding a quote used to flip the scanner into a string for the whole file. */
    @Test
    fun `a quote in a char literal does not open a string`() {
        val usages = literals("""var c = '"'; Loc.GetString("comp-thief-target");""")

        assertEquals(listOf("comp-thief-target"), usages.map { it.id })
    }

    @Test
    fun `a message names another message`() {
        val usages = placeables("""greeting = Hello, { name-of-the-day }!""")

        assertEquals(listOf("name-of-the-day"), usages.map { it.id })
    }

    @Test
    fun `a term reference is a usage of the message without its dash`() {
        val usages = placeables("""greeting = Welcome to { -station-name }.""")

        assertEquals(listOf("station-name"), usages.map { it.id })
    }

    /** `{ msg.attr }` names the message `msg`; the attribute belongs to it and is not a key. */
    @Test
    fun `an attribute reference names its message`() {
        val usages = placeables("""greeting = { comp-thief-target.desc }""")

        assertEquals(listOf("comp-thief-target"), usages.map { it.id })
    }

    /**
     * `ent-<prototype id>` carries the id as it is written in YAML, so a reference to one holds
     * upper case: demanding lower case lost 1936 of them on ss14-wega.
     */
    @Test
    fun `a reference to an entity message keeps its case`() {
        val usages = placeables("""spawn = Spawns { ent-MarkerBase }.""")

        assertEquals(listOf("ent-MarkerBase"), usages.map { it.id })
    }

    /** A selector is not a reference, and neither is a call — both are followed by other syntax. */
    @Test
    fun `a selector is not a reference`() {
        val usages = placeables(
            """
            count = { ${'$'}amount ->
                [one] one
               *[other] many
            }
            """.trimIndent(),
        )

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a function call is not a reference`() {
        val usages = placeables("""greeting = { CAPITALIZE(${'$'}name) }""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a variable is not a message`() {
        val usages = placeables("""greeting = Hello, { ${'$'}user-name }!""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `a placeable inside a comment is not a usage`() {
        val usages = placeables("""# was { name-of-the-day } before""")

        assertTrue(usages.isEmpty())
    }

    @Test
    fun `the range of a placeable covers the id alone`() {
        val text = """greeting = Hello, { name-of-the-day }!"""
        val usage = placeables(text).single()

        assertEquals("name-of-the-day", text.substring(usage.start, usage.end))
    }

    @Test
    fun `usages are dispatched by extension`() {
        assertFalse(RobustLocaleUsageIndex.usages("""x = "a-b";""", "cs").isEmpty())
        assertFalse(RobustLocaleUsageIndex.usages("""x = { a-b }""", "ftl").isEmpty())
        assertTrue(RobustLocaleUsageIndex.usages("""x = "a-b";""", "yml").isEmpty())
    }

    private fun literals(text: String) = RobustLocaleUsageIndex.literals(text)

    private fun placeables(text: String) = RobustLocaleUsageIndex.placeables(text)
}
