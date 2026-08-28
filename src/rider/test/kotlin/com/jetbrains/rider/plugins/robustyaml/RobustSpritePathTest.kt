package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustSprites
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Which sprite a `state:` is judged against — the part of the rule that is about the shape of the
 * tree rather than about the content, and so belongs here rather than in `MeasureStates`.
 *
 * The cases without a chain of parents are the ones a parsing test can hold: everything else needs
 * the index. That is enough for what goes wrong structurally — a state read against the wrong
 * mapping, a rule of the engine forgotten.
 */
class RobustSpritePathTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
    override fun getTestDataPath(): String = "src/rider/test/data"

    override fun skipSpaces(): Boolean = false

    override fun setUp() {
        super.setUp()
        application.registerService(ReadActionCache::class.java, NoReadActionCache())
    }

    fun testTheLayerAnswersForItself() {
        val path = pathAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                sprite: Objects/outer.rsi
                layers:
                - sprite: Objects/inner.rsi
                  state: icon
            """,
        )

        assertEquals("Objects/inner.rsi", path)
    }

    fun testALayerFallsBackToTheComponent() {
        val path = pathAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                sprite: Objects/outer.rsi
                layers:
                - state: icon
            """,
        )

        assertEquals("Objects/outer.rsi", path)
    }

    /** `SpriteComponent` reads its own state only `if (layerDatums.Count == 0)`. */
    fun testAStateBesideLayersIsNotRead() {
        val path = pathAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                sprite: Objects/outer.rsi
                state: icon
                layers:
                - state: base
            """,
        )

        assertNull(path)
    }

    fun testWithoutLayersTheComponentStateIsRead() {
        val path = pathAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                sprite: Objects/outer.rsi
                state: icon
            """,
        )

        assertEquals("Objects/outer.rsi", path)
    }

    /** An abstract prototype is never instantiated; its sprite may come from a descendant. */
    fun testAnAbstractPrototypeIsLeftAlone() {
        val path = pathAt(
            """
            - type: entity
              id: Foo
              abstract: true
              components:
              - type: Sprite
                sprite: Objects/outer.rsi
                state: icon
            """,
        )

        assertNull(path)
    }

    /** The first `state:` of the text — in every case here that is the one under test. */
    private fun pathAt(text: String): String? {
        val file: PsiFile = createPsiFile("test", text.trimIndent())
        val state = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "state" }
        return RobustSprites.pathFor(state)
    }

    private class NoReadActionCache : ReadActionCache {
        override val processingContext: ProcessingContext? get() = null

        override fun <T> allowInWriteAction(action: () -> T): T = action()

        override fun allowInWriteAction(action: Runnable) = action.run()

        override fun disable() = Unit
    }
}
