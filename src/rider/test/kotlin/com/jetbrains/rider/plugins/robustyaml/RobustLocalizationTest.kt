package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the caret stands on inside a `.ftl`. The file is one plain token to the frontend, so Shift+F6
 * has nothing to walk up to and the line has to be read by hand.
 */
class RobustLocalizationTest {
    @Test
    fun `the caret on the name of a declaration finds it`() {
        assertEquals("greeting-word", idAt("greeting-word = Hello", "greeting-w"))
    }

    @Test
    fun `the caret at the start of a declaration finds it`() {
        assertEquals("greeting-word", idAt("greeting-word = Hello", ""))
    }

    /** Right after the last character is still on the name — that is where typing leaves it. */
    @Test
    fun `the caret right after the name finds it`() {
        assertEquals("greeting-word", idAt("greeting-word = Hello", "greeting-word"))
    }

    @Test
    fun `the caret in the value finds nothing`() {
        assertNull(idAt("greeting-word = Hello", "greeting-word = He"))
    }

    @Test
    fun `the caret on a reference finds the message it names`() {
        assertEquals("station-name", idAt("greeting = Welcome to { station-name }.", "greeting = Welcome to { stat"))
    }

    @Test
    fun `the caret on a term reference drops the dash`() {
        assertEquals("station-name", idAt("greeting = Welcome to { -station-name }.", "greeting = Welcome to { -stat"))
    }

    /** An attribute belongs to the message above it and is not a message of its own. */
    @Test
    fun `the caret on an attribute finds nothing`() {
        assertNull(idAt("greeting = Hello\n    .desc = A word", "greeting = Hello\n    .de"))
    }

    @Test
    fun `the caret on a comment finds nothing`() {
        assertNull(idAt("# greeting-word is gone", "# greeting-w"))
    }

    @Test
    fun `the line is found in the middle of a file`() {
        val text = "first-word = One\nsecond-word = Two\nthird-word = Three"

        assertEquals("second-word", idAt(text, "first-word = One\nsecond-"))
    }

    /** 284 prototype files start with a byte order mark, and locale files are written the same way. */
    @Test
    fun `a byte order mark does not hide the first declaration`() {
        assertEquals("greeting-word", idAt("﻿greeting-word = Hello", "﻿greeting-w"))
    }

    /** The caret offset is written as the text standing before it, so the tests stay readable. */
    private fun idAt(text: String, before: String): String? =
        RobustLocalization.idAt(text, before.length)
}
