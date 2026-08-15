package com.jetbrains.rider.plugins.robustyaml

import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a Fluent entry reads. Both halves matter to the entity hover: the description of an entity is
 * an attribute of its message 13909 times against 7489 written as `description:` in YAML, and 11593
 * of the texts are a bare `{ ent-X }` pointing at another entry.
 */
class RobustLocaleEntryTest {
    private fun entry(text: String, offset: Int = 0) = RobustLocalization.entryAt(text, offset)

    @Test
    fun `value and attributes are told apart`() {
        val parsed = entry(
            """
            ent-Crowbar = лом
                .desc = Тяжёлый лом.
                .suffix = синий
            """.trimIndent(),
        )!!

        assertEquals("лом", parsed.value)
        assertEquals("Тяжёлый лом.", parsed.attributes["desc"])
        assertEquals("синий", parsed.attributes["suffix"])
    }

    /** An entry with attributes alone has no value — that is how a description is overridden alone. */
    @Test
    fun `an entry without a value has none`() {
        val parsed = entry("ent-Crowbar =\n    .desc = Тяжёлый лом.")!!

        assertNull(parsed.value)
        assertEquals("Тяжёлый лом.", parsed.attributes["desc"])
    }

    @Test
    fun `a multiline value keeps its lines and stops at the first attribute`() {
        val parsed = entry("ent-Crowbar = лом,\n    он же монтировка\n    .desc = Тяжёлый.")!!

        assertEquals("лом,\nон же монтировка", parsed.value)
        assertEquals("Тяжёлый.", parsed.attributes["desc"])
    }

    @Test
    fun `a multiline attribute keeps its lines`() {
        val parsed = entry("ent-Crowbar = лом\n    .desc = Тяжёлый\n      и холодный.")!!

        assertEquals("Тяжёлый\nи холодный.", parsed.attributes["desc"])
    }

    /** A line at column zero is the next entry, not a continuation of this one. */
    @Test
    fun `the next declaration ends the entry`() {
        val parsed = entry("ent-Crowbar = лом\nent-CrowbarRed = красный лом")!!

        assertEquals("лом", parsed.value)
        assertEquals(emptyMap<String, String>(), parsed.attributes)
    }

    @Test
    fun `the whole body is still available as one string`() {
        val text = "ent-Crowbar = лом\n    .desc = Тяжёлый лом."

        assertEquals("лом\n.desc = Тяжёлый лом.", RobustLocalization.messageAt(text, 0))
    }

    private val bundle = mapOf(
        "ent-BaseCrowbar" to RobustLocalization.Entry("лом", mapOf("desc" to "Тяжёлый лом.")),
        "ent-Loop" to RobustLocalization.Entry("{ ent-Loop }", emptyMap()),
    )

    private fun resolved(text: String) = RobustLocalization.resolved(text) { bundle[it] }

    @Test
    fun `a reference is replaced by what it names`() {
        assertEquals("лом", resolved("{ ent-BaseCrowbar }"))
    }

    @Test
    fun `a reference to an attribute takes the attribute`() {
        assertEquals("Тяжёлый лом.", resolved("{ ent-BaseCrowbar.desc }"))
    }

    /** `{ "" }` is how Fluent writes an empty override, and 545 texts in the content are exactly that. */
    @Test
    fun `a string literal becomes its content`() {
        assertEquals("", resolved("""{ "" }"""))
        assertEquals("нечто", resolved("""{ "нечто" }"""))
    }

    @Test
    fun `a reference inside a sentence is replaced in place`() {
        assertEquals("Это лом, честно", resolved("Это { ent-BaseCrowbar }, честно"))
    }

    /** Arguments are supplied by the engine at runtime, so they are left standing as written. */
    @Test
    fun `a variable and a function call are left alone`() {
        assertEquals("Привет, { \$user }", resolved("Привет, { \$user }"))
        assertEquals("{ NUMBER(\$x) }", resolved("{ NUMBER(\$x) }"))
    }

    @Test
    fun `an unknown message is left alone`() {
        assertEquals("{ ent-Nothing }", resolved("{ ent-Nothing }"))
    }

    @Test
    fun `a message pointing at itself terminates`() {
        assertEquals("{ ent-Loop }", resolved("{ ent-Loop }"))
    }
}
