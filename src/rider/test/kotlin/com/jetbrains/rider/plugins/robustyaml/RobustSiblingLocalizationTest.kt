package com.jetbrains.rider.plugins.robustyaml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that reads a dead message id off its neighbours. It exists because the type says nothing:
 * `wordReplacements` is `Dictionary<string, string>`, and only the system that reads the field hands
 * both sides to `Loc.GetString`. Everything here is about what may be counted as a neighbour — get
 * that wrong and the warning lands on values of an entirely different kind.
 */
class RobustSiblingLocalizationTest {
    @Test
    fun `the odd one out among declared neighbours is dead`() {
        val entries = listOf("accent-dwarf-words-1", "accent-dwarf-words-2", "accent-dwarf-words-535")

        assertEquals(listOf(2), dead(entries, declared = setOf("accent-dwarf-words-1", "accent-dwarf-words-2")))
    }

    /** One neighbour is a coincidence; the warning needs a crowd to stand against. */
    @Test
    fun `a single declared neighbour is not enough`() {
        val entries = listOf("reagent-name-egg", "raw-egg")

        assertEquals(emptyList<Int>(), dead(entries, declared = setOf("reagent-name-egg")))
    }

    @Test
    fun `a mapping where most ids are unknown says nothing`() {
        val entries = listOf("some-name", "other-name", "third-name", "known-one", "known-two")

        assertEquals(emptyList<Int>(), dead(entries, declared = setOf("known-one", "known-two")))
    }

    /**
     * `flavor: raw-egg` names a prototype, and the flavours are spelled in the same kebab case as
     * messages — four findings sat here until the exclusion was added.
     */
    @Test
    fun `a value naming a prototype is not a message`() {
        val entries = listOf("reagent-name-egg", "reagent-desc-egg", "raw-egg")

        assertEquals(
            emptyList<Int>(),
            dead(entries, declared = setOf("reagent-name-egg", "reagent-desc-egg"), prototypes = setOf("raw-egg")),
        )
    }

    /**
     * `metamorphicFillBaseName: fill-` is a prefix of a sprite state. A dash alone is not the shape
     * of a key, and treating it as one produced 150 findings across the reagent files.
     */
    @Test
    fun `a dangling dash is not the shape of a key`() {
        val entries = listOf("reagent-name-lemon", "reagent-desc-lemon", "fill-")

        assertEquals(
            emptyList<Int>(),
            dead(entries, declared = setOf("reagent-name-lemon", "reagent-desc-lemon")),
        )
    }

    /** A tag or an alias is not a value at all, whatever it is spelled like. */
    @Test
    fun `tags and aliases are not candidates`() {
        val entries = listOf("job-name-cburn", "job-name-rd", "!type:Some-Thing", "*Some-Anchor")

        assertEquals(
            emptyList<Int>(),
            dead(entries, declared = setOf("job-name-cburn", "job-name-rd")),
        )
    }

    /** The id is cut at the first dot: what follows is a Fluent attribute, not another message. */
    @Test
    fun `an attribute belongs to its message`() {
        val entries = listOf("ent-Crowbar", "ent-Wrench", "ent-Missing.desc")

        assertEquals(listOf(2), dead(entries, declared = setOf("ent-Crowbar", "ent-Wrench")))
    }

    private fun dead(
        entries: List<String>,
        declared: Set<String> = emptySet(),
        prototypes: Set<String> = emptySet(),
    ) = RobustValidation.deadSiblings(entries, { it in declared }, { it in prototypes })
}
