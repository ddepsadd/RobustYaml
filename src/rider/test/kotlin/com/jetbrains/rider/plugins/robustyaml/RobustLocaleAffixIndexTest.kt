package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ends of keys that no file spells in full. Everything starting with one of them counts as used, and
 * 21819 messages of ss14-wega are reachable only that way — a head missed here is a warning on
 * working code.
 */
class RobustLocaleAffixIndexTest {
    private fun code(text: String) = RobustLocaleAffixIndex.affixes(text, "cs")

    private fun yaml(text: String) = RobustLocaleAffixIndex.affixes(text, "yml")

    @Test
    fun `an interpolated key gives its head`() {
        assertEquals(setOf("accent-"), code("""Loc.GetString($"accent-{name}-replacement");"""))
    }

    @Test
    fun `a concatenated key gives its head`() {
        assertEquals(setOf("cmd-"), code("""Loc.GetString("cmd-" + command);"""))
    }

    @Test
    fun `a whole key is not a prefix`() {
        assertTrue(code("""Loc.GetString("comp-thief-target");""").isEmpty())
    }

    /** A dataset names a run of messages by a prefix and a count, never one by one. */
    @Test
    fun `a localizedDataset prefix is read`() {
        val text = """
            - type: localizedDataset
              id: RandomFacts
              values:
                prefix: random-fact-
                count: 42
        """.trimIndent()

        assertEquals(setOf("random-fact-"), yaml(text))
    }

    @Test
    fun `a quoted dataset prefix is read`() {
        assertEquals(setOf("random-fact-"), yaml("""    prefix: "random-fact-"""" + "\n"))
    }

    @Test
    fun `a value that is not a prefix is ignored`() {
        assertTrue(yaml("  prefix: RandomFacts\n").isEmpty())
    }

    @Test
    fun `affixes are dispatched by extension`() {
        assertTrue(RobustLocaleAffixIndex.affixes("""x = "cmd-" + c;""", "ftl").isEmpty())
    }

    /**
     * `node.Description.Replace("-desc", "-tooltip")` derives one key from another, and nothing
     * anywhere holds the result whole — `heretic-know-ashgrasp-tooltip` was accused of being dead.
     */
    @Test
    fun `a tail literal is an affix when the file localizes`() {
        val text = """var key = d.Replace("-desc", "-tooltip"); Loc.GetString(key);"""

        assertEquals(setOf("-desc", "-tooltip"), code(text))
    }

    /** Without a call to localization a leading dash is a compiler flag, a shader name, anything. */
    @Test
    fun `a tail literal alone is not an affix`() {
        assertTrue(code("""var args = new[] { "-c", "-unshaded" };""").isEmpty())
    }
}
