package com.jetbrains.rider.plugins.robustyaml

import com.jetbrains.rider.plugins.robustyaml.index.CodeLink
import com.jetbrains.rider.plugins.robustyaml.index.CodeLinkKind
import com.jetbrains.rider.plugins.robustyaml.index.RobustCodeLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a string literal of C# names in the content. A rename rewrites exactly what this finds, so a
 * literal read out of a comment would edit prose and one claimed by the wrong rule would rewrite a
 * string that was never an id.
 */
class RobustCodeLinksTest {
    @Test
    fun `a literal declared EntProtoId is an id`() {
        val links = links("""private static readonly EntProtoId Mob = "MobHuman";""")

        assertEquals(listOf("MobHuman"), links.map { it.value })
        assertEquals(CodeLinkKind.PROTOTYPE_ID, links.single().kind)
    }

    /** `EntProtoId` says nothing about a class: it is an entity, and the kind is known without one. */
    @Test
    fun `EntProtoId carries no prototype class`() {
        val link = links("""private static readonly EntProtoId Mob = "MobHuman";""").single()

        assertNull(link.prototypeClass)
    }

    @Test
    fun `the type argument of ProtoId is the prototype class`() {
        val link = links("""private static readonly ProtoId<ShaderPrototype> S = "unshaded";""").single()

        assertEquals("ShaderPrototype", link.prototypeClass)
    }

    /** Ids of the engine are written lower case, and demanding PascalCase would drop them. */
    @Test
    fun `a lower case id is an id`() {
        val links = links("""private static readonly ProtoId<ShaderPrototype> S = "unshaded";""")

        assertEquals(listOf("unshaded"), links.map { it.value })
    }

    @Test
    fun `the range covers the text inside the quotes`() {
        val text = """private static readonly EntProtoId Mob = "MobHuman";"""
        val link = links(text).single()

        assertEquals("MobHuman", text.substring(link.start, link.end))
    }

    /** One declaration may carry several ids, and each of them is a reference of its own. */
    @Test
    fun `every literal of an array declaration is an id`() {
        val links = links("""new ProtoId<RoleTypePrototype>[] { "SoloAntagonist", "FreeAgent" }""")

        assertEquals(listOf("SoloAntagonist", "FreeAgent"), links.map { it.value })
    }

    /** A string is a string until a type says otherwise — that is the whole of the evidence. */
    @Test
    fun `a literal without a type beside it is not an id`() {
        assertTrue(links("""var name = "MobHuman";""").isEmpty())
    }

    /** The type has to stand before the literal: what follows one belongs to the next declaration. */
    @Test
    fun `a type after the literal does not claim it`() {
        assertTrue(links("""var name = "MobHuman"; // EntProtoId""").isEmpty())
    }

    @Test
    fun `a declaration of another type on the line above does not claim the literal`() {
        val text = """
            private static readonly EntProtoId Mob = "MobHuman";
            private static readonly string Note = "NotAnId";
        """.trimIndent()

        assertEquals(listOf("MobHuman"), links(text).map { it.value })
    }

    /** `= new() {` and the items under it — six literals of ss14-wega are written that way. */
    @Test
    fun `a literal under an open declaration is an id`() {
        val text = """
            private static readonly List<EntProtoId> Death = new() {
                "BloodCultConstruct", "BloodCultSoulStone"
            };
        """.trimIndent()

        assertEquals(
            listOf("BloodCultConstruct", "BloodCultSoulStone"),
            links(text).map { it.value },
        )
    }

    @Test
    fun `a literal inside a comment is not an id`() {
        assertTrue(links("""// EntProtoId Mob = "MobHuman";""").isEmpty())
    }

    @Test
    fun `an absolute path is a path`() {
        val links = links("""new SoundPathSpecifier("/Audio/Items/beep.ogg")""")

        assertEquals(CodeLinkKind.PATH, links.single().kind)
        assertEquals("/Audio/Items/beep.ogg", links.single().value)
    }

    /** A path needs no type beside it: the leading slash is what the engine itself reads. */
    @Test
    fun `a path is found without a declaring type`() {
        assertEquals(listOf("/Textures/Interface/Nano/button.png"), links(
            """var texture = "/Textures/Interface/Nano/button.png";""",
        ).map { it.value })
    }

    /** A directory of frames is a target as good as a file. */
    @Test
    fun `a path to an rsi directory is a path`() {
        val link = links("""var icons = "/Textures/Interface/Misc/job_icons.rsi";""").single()

        assertEquals(CodeLinkKind.PATH, link.kind)
    }

    @Test
    fun `a relative name is not a path`() {
        assertTrue(links("""var x = "Textures/foo.png";""").none { it.kind == CodeLinkKind.PATH })
    }

    /** A dashed literal is a localization key, and that one belongs to the locale index. */
    /** `sprite:` beside `state:` in YAML, and the argument before it in C#. */
    @Test
    fun `a literal after an rsi path is a state of it`() {
        val link = links(
            """new SpriteSpecifier.Rsi(new ResPath("/Textures/Misc/job_icons.rsi"), "Syndicate")""",
        ).last()

        assertEquals(CodeLinkKind.SPRITE_STATE, link.kind)
        assertEquals("Syndicate", link.value)
        assertEquals("/Textures/Misc/job_icons.rsi", link.spritePath)
    }

    /** The state stands on the next line as often as beside the path, so the gap is read by hand. */
    @Test
    fun `a state written on the next line still belongs to the path`() {
        val text = """
            new SpriteSpecifier.Rsi(new ResPath("/Textures/Misc/crosshair.rsi"),
                "gun_sight")
        """.trimIndent()

        assertEquals(CodeLinkKind.SPRITE_STATE, links(text).last().kind)
    }

    /** A state is a file name and may hold a dash, which no other rule here allows. */
    @Test
    fun `a dashed state is a state`() {
        val link = links("""new Rsi(new ResPath("/Textures/x.rsi"), "equipped-INNERCLOTHING")""").last()

        assertEquals("equipped-INNERCLOTHING", link.value)
    }

    /** Only the argument beside it: a literal further along the call names something else. */
    @Test
    fun `a literal separated by other code is not a state`() {
        val links = links("""Foo(new ResPath("/Textures/x.rsi"), Bar(), "Syndicate")""")

        assertTrue(links.none { it.kind == CodeLinkKind.SPRITE_STATE })
    }

    @Test
    fun `a dashed literal is neither`() {
        assertTrue(links("""EntProtoId Mob = "comp-thief-target";""").isEmpty())
    }

    @Test
    fun `the caret at either end of a value stands in it`() {
        val text = """private static readonly EntProtoId Mob = "MobHuman";"""
        val link = links(text).single()

        assertEquals(link, RobustCodeLinks.linkAt(text, link.start))
        assertEquals(link, RobustCodeLinks.linkAt(text, link.end))
        assertNull(RobustCodeLinks.linkAt(text, link.start - 2))
    }

    private fun links(text: String): List<CodeLink> = RobustCodeLinks.links(text)
}
