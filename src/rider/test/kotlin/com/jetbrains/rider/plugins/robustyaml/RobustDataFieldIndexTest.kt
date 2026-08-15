package com.jetbrains.rider.plugins.robustyaml

import com.jetbrains.rider.plugins.robustyaml.index.RobustDataFieldIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which YAML keys name a prototype, read off C# without a parser. The rule behind them is global by
 * key name while the truth is per owning type, so a name declared as anything else anywhere has to
 * fall out — a rename reads these keys and writes into the content through them.
 */
class RobustDataFieldIndexTest {
    private fun prototypeFields(text: String): Map<String, String> =
        RobustDataFieldIndex.index(text)
            .filterKeys { it.startsWith(RobustDataFieldIndex.PROTOTYPE_FIELD_KEY) }
            .mapKeys { it.key.removePrefix(RobustDataFieldIndex.PROTOTYPE_FIELD_KEY) }

    private fun plainFields(text: String): Set<String> =
        RobustDataFieldIndex.index(text).keys
            .filter { it.startsWith(RobustDataFieldIndex.PLAIN_FIELD_KEY) }
            .mapTo(mutableSetOf()) { it.removePrefix(RobustDataFieldIndex.PLAIN_FIELD_KEY) }

    @Test
    fun `a ProtoId field names the prototype of its argument`() {
        val fields = prototypeFields(
            """
            public sealed partial class LatheRecipePrototype
            {
                [DataField("result")] public ProtoId<EntityPrototype> Result;
            }
            """.trimIndent(),
        )

        assertEquals(mapOf("result" to "EntityPrototype"), fields)
    }

    /** In YAML a list of ids and a single id are written the same way, so the collection is transparent. */
    @Test
    fun `a collection of ProtoId names the same prototype`() {
        val fields = prototypeFields(
            """
            public sealed partial class RandomSpawnerComponent
            {
                [DataField] public List<EntProtoId> Prototypes = new();
            }
            """.trimIndent(),
        )

        assertEquals(mapOf("prototypes" to "EntityPrototype"), fields)
    }

    /**
     * The serializer overrides the type: the field is a plain string and the prototype is named
     * nowhere else. Its argument is the last one — the dictionary serializers carry the value type
     * in front of it.
     */
    @Test
    fun `a serializer names the prototype the field type does not`() {
        val fields = prototypeFields(
            """
            public sealed partial class ConstructionComponent
            {
                [DataField("graph", required: true,
                    customTypeSerializer: typeof(PrototypeIdSerializer<ConstructionGraphPrototype>))]
                public string Graph = default!;

                [DataField(customTypeSerializer:
                    typeof(PrototypeIdDictionarySerializer<int, StackPrototype>))]
                public Dictionary<string, int> Stacks = new();
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf("graph" to "ConstructionGraphPrototype", "stacks" to "StackPrototype"),
            fields,
        )
    }

    @Test
    fun `a field of any other type is written down as plain`() {
        val text =
            """
            public sealed partial class SpriteComponent
            {
                [DataField("sprite")] public string? Sprite;
                [DataField] public bool Visible = true;
            }
            """.trimIndent()

        assertEquals(emptyMap<String, String>(), prototypeFields(text))
        assertEquals(setOf("sprite", "visible"), plainFields(text))
    }

    /**
     * The name is what both sides are keyed by, and this is the whole reason the plain half exists:
     * `name` is a `ProtoId` in one class of the checkout and a string in a hundred others, and 249
     * values of ss14-wega written under it are ids of something else entirely.
     */
    @Test
    fun `a name declared both ways is written down both ways`() {
        val text =
            """
            public sealed partial class AntagPrototype
            {
                [DataField] public string Name = string.Empty;
            }

            public sealed partial class StartingGearPrototype
            {
                [DataField] public ProtoId<EntityPrototype> Name;
            }
            """.trimIndent()

        assertEquals(mapOf("name" to "EntityPrototype"), prototypeFields(text))
        assertEquals(setOf("name"), plainFields(text))
    }
}
