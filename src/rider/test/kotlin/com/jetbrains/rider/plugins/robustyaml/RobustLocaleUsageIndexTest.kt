package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * A hole of an interpolated string is code, and 43 keys of the content sit in one. Without a
     * state for it the quote opening the nested literal reads as the quote closing the outer string,
     * the key becomes code, and the rename that was supposed to follow it walks past.
     */
    @Test
    fun `a literal inside an interpolation hole is a usage`() {
        val usages = literals("""var s = ${'$'}"\n{Loc.GetString("ban-list-unbanned-by", x)}";""")

        assertEquals(listOf("ban-list-unbanned-by"), usages.map { it.id })
    }

    @Test
    fun `a literal after an interpolation hole is still read`() {
        val usages = literals("""${'$'}"{count} of {Loc.GetString("admin-logs-reset")} left";""")

        assertEquals(listOf("admin-logs-reset"), usages.map { it.id })
    }

    @Test
    fun `a hole holding braces of its own is closed at the right one`() {
        val usages = literals("""${'$'}"{list.Select(x => new { A = Loc.GetString("a-b") })}";""")

        assertEquals(listOf("a-b"), usages.map { it.id })
    }

    /** `{{` is how a brace is written literally, and it opens nothing. */
    @Test
    fun `escaped braces do not open a hole`() {
        val usages = literals("""${'$'}"{{not-a-hole}}"; Loc.GetString("real-key");""")

        assertEquals(listOf("real-key"), usages.map { it.id })
    }

    @Test
    fun `a verbatim interpolated string is scanned both ways round`() {
        assertEquals(
            listOf("a-b"),
            literals("""${'$'}@"line {Loc.GetString("a-b")}"""").map { it.id },
        )
        assertEquals(
            listOf("c-d"),
            literals("""@${'$'}"line {Loc.GetString("c-d")}"""").map { it.id },
        )
    }

    /** C# 11 raw strings interpolate too, and three keys of the content live only inside one. */
    @Test
    fun `a literal inside a raw interpolated string is a usage`() {
        val usages = literals(
            "return ${'$'}\"\"\"\n{Loc.GetString(\"ban-banned-1\")}\n{expires}\n\"\"\";",
        )

        assertEquals(listOf("ban-banned-1"), usages.map { it.id })
    }

    @Test
    fun `a plain raw string still does not swallow what follows`() {
        val usages = literals("var x = \"\"\"a-b\"\"\"; Loc.GetString(\"real-key\");")

        assertEquals(listOf("real-key"), usages.map { it.id })
    }

    @Test
    fun `a namespaced binding is a usage`() {
        assertEquals(
            listOf("shuttle-console-docks-label"),
            bindings("""<controls:Label Text="{controls:Loc 'shuttle-console-docks-label'}"/>""").map { it.id },
        )
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
        assertFalse(RobustLocaleUsageIndex.usages("""<B Text="{Loc 'a-b'}"/>""", "xaml").isEmpty())
        assertFalse(RobustLocaleUsageIndex.usages("""<FTLTextpart Key="a-b"/>""", "xml").isEmpty())
        assertTrue(RobustLocaleUsageIndex.usages("""x = "a-b";""", "yml").isEmpty())
    }

    /** Both forms occur — 1326 values quoted in the content, 378 bare. */
    @Test
    fun `a binding is read quoted and bare`() {
        assertEquals(listOf("ui-options-title"), bindings("""<B Text="{Loc 'ui-options-title'}"/>""").map { it.id })
        assertEquals(listOf("admin-logs-reset"), bindings("""<B Text="{Loc admin-logs-reset}"/>""").map { it.id })
    }

    @Test
    fun `the range of a binding covers the id alone`() {
        val text = """<Button Text="{Loc 'admin-logs-reset'}" />"""
        val usage = bindings(text).single()

        assertEquals("admin-logs-reset", text.substring(usage.start, usage.end))
    }

    @Test
    fun `a binding that is not Loc is not a usage`() {
        assertTrue(bindings("""<B Text="{Binding some-path}"/>""").isEmpty())
    }

    @Test
    fun `a commented out binding is not a usage`() {
        assertTrue(bindings("""<!-- <B Text="{Loc 'ui-options-title'}"/> -->""").isEmpty())
    }

    @Test
    fun `a guidebook attribute is a usage`() {
        val text = """<FTLTextpart Key="guidebook-antags-thief"/>"""
        val usage = attributes(text).single()

        assertEquals("guidebook-antags-thief", text.substring(usage.start, usage.end))
    }

    /** Any string of an XML file would drag in the encoding of its own declaration. */
    @Test
    fun `another attribute is not a usage`() {
        assertTrue(attributes("""<?xml version="1.0" encoding="utf-8"?>""").isEmpty())
    }

    @Test
    fun `the caret inside a literal names the key`() {
        val text = """Loc.GetString("ban-list-unbanned-by");"""

        assertEquals("ban-list-unbanned-by", idAt(text, text.indexOf("unbanned")))
    }

    /** The caret sits between characters, so both ends of the key still point at it. */
    @Test
    fun `the caret at either end of a literal names the key`() {
        val text = """Loc.GetString("ban-list-unbanned-by");"""
        val start = text.indexOf("ban-list")

        assertEquals("ban-list-unbanned-by", idAt(text, start))
        assertEquals("ban-list-unbanned-by", idAt(text, start + "ban-list-unbanned-by".length))
    }

    @Test
    fun `the caret on the quotes is outside the key`() {
        val text = """Loc.GetString("ban-list-unbanned-by");"""

        assertNull(idAt(text, text.indexOf('(')))
        assertNull(idAt(text, text.length - 1))
    }

    /** The whole point of the scanner: prose that spells a key is not a place to rename. */
    @Test
    fun `the caret in a commented out literal names nothing`() {
        val text = """// see "comp-thief-target" for the wording"""

        assertNull(idAt(text, text.indexOf("thief")))
    }

    /** The line Shift+F6 was reported dead on: the call sits in the hole of an interpolated string. */
    @Test
    fun `the caret inside a literal in an interpolation hole names the key`() {
        val text = """: ${'$'}"\n{Loc.GetString("ban-list-unbanned-by", ("unbanner", unban.Admin))}";"""

        assertEquals("ban-list-unbanned-by", idAt(text, text.indexOf("unbanned-by")))
    }

    @Test
    fun `the caret inside a xaml binding names the key`() {
        val text = """<Button Text="{Loc 'admin-logs-reset'}" />"""

        assertEquals("admin-logs-reset", idAt(text, text.indexOf("logs"), "xaml"))
    }

    private fun idAt(text: String, offset: Int, extension: String = "cs") =
        RobustLocaleUsageIndex.usageAt(text, extension, offset)?.id

    private fun literals(text: String) = RobustLocaleUsageIndex.literals(text)

    private fun placeables(text: String) = RobustLocaleUsageIndex.placeables(text)

    private fun bindings(text: String) = RobustLocaleUsageIndex.bindings(text)

    private fun attributes(text: String) = RobustLocaleUsageIndex.attributes(text)
}
