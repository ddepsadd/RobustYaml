package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.completion.atSequenceItemValue
import org.jetbrains.yaml.YAMLParserDefinition

/**
 * Where a value is expected and where a key is. The text alone cannot say it — a key is recognised
 * by the colon left of the caret, and an item of a block sequence never has one — so an empty `- `
 * under a list of enum members was taken for a key and completion offered nothing at all.
 */
class RobustCompletionPositionTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
    override fun getTestDataPath(): String = "src/rider/test/data"

    override fun skipSpaces(): Boolean = false

    override fun setUp() {
        super.setUp()
        application.registerService(ReadActionCache::class.java, NoReadActionCache())
    }

    private class NoReadActionCache : ReadActionCache {
        override val processingContext: ProcessingContext? get() = null

        override fun <T> allowInWriteAction(action: () -> T): T = action()

        override fun allowInWriteAction(action: Runnable) = action.run()

        override fun disable() = Unit
    }

    fun testItemOfASequenceIsAValue() {
        assertTrue(
            isValue(
                """
                - type: entity
                  id: Foo
                  components:
                  - type: Eye
                    visMask:
                    - Norm<caret>al
                """,
            ),
        )
    }

    /**
     * What the sandbox showed: nothing typed yet. Completion inserts its own identifier at the
     * caret, so the item is a scalar either way and the walk has to give the same answer as above.
     */
    fun testEmptyItemIsAValue() {
        assertTrue(
            isValue(
                """
                - type: entity
                  id: Foo
                  components:
                  - type: Eye
                    visMask:
                    - IntellijIdeaRul<caret>ezzz
                """,
            ),
        )
    }

    /** An item that is a mapping is where keys go, and `- type: Sprite` is exactly that. */
    fun testItemThatIsAMappingIsAKey() {
        assertFalse(
            isValue(
                """
                - type: entity
                  id: Foo
                  components:
                  - type: Sprite
                    sta<caret>te: red
                """,
            ),
        )
    }

    fun testKeyOfANestedMappingIsAKey() {
        assertFalse(
            isValue(
                """
                - type: entity
                  id: Foo
                  components:
                  - type: Fixtures
                    fixtures:
                      fix1:
                        den<caret>sity: 20
                """,
            ),
        )
    }

    private fun isValue(text: String): Boolean {
        val marked = text.trimIndent()
        val caret = marked.indexOf(CARET)
        assertTrue("no <caret> in the text", caret >= 0)

        val file = createPsiFile("test", marked.replace(CARET, ""))
        val element: PsiElement? = file.findElementAt(caret)
        assertNotNull("no element at caret", element)
        return atSequenceItemValue(element!!)
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
