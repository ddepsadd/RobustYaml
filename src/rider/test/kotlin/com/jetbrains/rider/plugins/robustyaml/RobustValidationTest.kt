package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rules for reading a scalar, taken from the serializers of the engine. The measurement calls the
 * same function over the whole of ss14-wega; this covers the shapes the content happens not to have
 * and the collection unwrapping, which stayed silent for `Vector2[]` until it was found by hand.
 */
class RobustValidationTest {
    @Test
    fun `unknown type has no rules`() {
        assertNull(RobustValidation.accepts("SoundSpecifier", "whatever"))
        assertNull(RobustValidation.accepts("EntProtoId", "BaseItem"))
    }

    @Test
    fun `numbers follow invariant culture`() {
        assertAccepts("float", "1.5", "-0.3", "12")
        assertRejects("float", "1.5f", "NaN", "0x1p3", "fast")
        assertAccepts("int", "12", "-3")
        assertRejects("int", "1.5", "12.0")
    }

    @Test
    fun `booleans ignore case because bool Parse does`() {
        assertAccepts("bool", "true", "True", "FALSE")
        assertRejects("bool", "yes", "1")
    }

    @Test
    fun `time spans take seconds or a suffix`() {
        assertAccepts("TimeSpan", "3", "1.5", "30s", "2m", "1.5h")
        assertRejects("TimeSpan", "1h30m", "1,5", "0:30", "soon")
    }

    @Test
    fun `colors are a name or a hex of four lengths`() {
        assertAccepts("Color", "red", "Red", "#4a90d9", "#fff", "#ffff", "#4a90d9ff")
        assertRejects("Color", "4a90d9", "#4a90d", "notacolour")
    }

    @Test
    fun `vectors split on comma or x`() {
        assertAccepts("Vector2", "1.5,2", "1.5x2", "-0.3,0.5")
        assertRejects("Vector2", "1.5", "1,2,3")
        assertAccepts("Vector2i", "1,2", "1x2")
        assertRejects("Vector2i", "1.5,2")
    }

    @Test
    fun `angles are degrees unless suffixed with rad`() {
        assertAccepts("Angle", "90", "-45.5", "1.57rad")
        assertRejects("Angle", "90deg", "rad")
    }

    /** The type arrives as a presentable name, and a sequence carries the rules of its element. */
    @Test
    fun `collections are unwrapped to the element type`() {
        assertAccepts("Vector2[]", "1.5,2")
        assertRejects("Vector2[]", "ValidaciaHyita")
        assertAccepts("List<TimeSpan>", "30s")
        assertAccepts("bool?[]", "true")
        assertAccepts("HashSet<float>", "0.5")
    }

    /** A dictionary is a mapping in YAML, so its values are not a list of scalars. */
    @Test
    fun `types with more than one argument are left alone`() {
        assertNull(RobustValidation.accepts("Dictionary<string, float>", "0.5"))
        assertNull(RobustValidation.accepts("List<KeyValuePair<string, int>>", "1"))
    }

    @Test
    fun `nullable is the same type`() {
        assertAccepts("float?", "1.5")
        assertRejects("float?", "fast")
    }

    private fun assertAccepts(type: String, vararg values: String) {
        for (value in values) {
            assertEquals("$type should accept '$value'", true, RobustValidation.accepts(type, value))
        }
    }

    private fun assertRejects(type: String, vararg values: String) {
        for (value in values) {
            assertEquals("$type should reject '$value'", false, RobustValidation.accepts(type, value))
        }
    }
}
