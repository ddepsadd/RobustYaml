package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Robust rich text in a popup. Only the tags the client registers may turn into HTML: square brackets
 * in a description are as often prose as markup, and `[redacted]` is registered nowhere.
 */
class RobustMarkupTest {
    private fun html(text: String) = RobustMarkup.toHtml(text)

    @Test
    fun `a colour becomes a span`() {
        assertEquals(
            "<span style='color:#9b59b6'>Mansus Codex</span>",
            html("[color=#9b59b6]Mansus Codex[/color]"),
        )
    }

    @Test
    fun `a colour by name is kept, css knowing the same names`() {
        assertEquals("<span style='color:red'>кровь</span>", html("[color=red]кровь[/color]"))
    }

    @Test
    fun `bold and italic nest`() {
        assertEquals("<b>очень <i>важно</i></b>", html("[bold]очень [italic]важно[/italic][/bold]"))
    }

    /** A tag nobody registers is prose, and there are three such words in the content. */
    @Test
    fun `an unknown tag stays text`() {
        assertEquals("данные [redacted] навсегда", html("данные [redacted] навсегда"))
    }

    @Test
    fun `a closing tag without an opening one stays text`() {
        assertEquals("хвост [/color]", html("хвост [/color]"))
    }

    /** The renderer closes what the text left open, or the colour would run on through the popup. */
    @Test
    fun `an unclosed tag is closed at the end`() {
        assertEquals("<span style='color:red'>красное</span>", html("[color=red]красное"))
    }

    @Test
    fun `a colour with something other than a colour in it stays text`() {
        assertEquals("[color=&lt;script&gt;]x", html("[color=<script>]x"))
    }

    @Test
    fun `text around the tags is escaped`() {
        assertEquals("a &lt;b&gt; &amp; c", html("a <b> & c"))
    }

    @Test
    fun `an unterminated bracket stays text`() {
        assertEquals("цена [1", html("цена [1"))
    }

    @Test
    fun `a bullet becomes one`() {
        assertEquals("• пункт", html("[bullet]пункт"))
    }
}
