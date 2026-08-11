package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * What the caret stands on for Find Usages. With no reference under it and nothing the platform
 * calls a named element, Alt+F7 refuses before any factory is consulted, which is what the id of a
 * prototype looked like: it is named by its value, not by its key.
 */
class RobustTargetElementEvaluatorTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
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

    fun testCaretInsideTheValueNamesTheDeclaration() {
        val named = namedElementAt(
            """
            - type: entity
              id: BaseMob<caret>Mutant
              parent: SimpleMobBase
            """,
        )

        assertTrue(named is YAMLKeyValue)
        assertEquals("BaseMobMutant", (named as YAMLKeyValue).valueText)
    }

    /** The key is not the name here, and treating it as one would search for the word `id`. */
    fun testCaretOnTheKeyNamesNothing() {
        assertNull(
            namedElementAt(
                """
                - type: entity
                  i<caret>d: BaseMobMutant
                """,
            ),
        )
    }

    /** A reference is searchable too — the search asks for the id it points at. */
    fun testValueOfAReferenceNamesTheKey() {
        val named = namedElementAt(
            """
            - type: entity
              id: BaseMobMutant
              parent: Simple<caret>MobBase
            """,
        )

        assertTrue(named is YAMLKeyValue)
        assertEquals("parent", (named as YAMLKeyValue).keyText)
    }

    fun testNestedIdNamesNothing() {
        assertNull(
            namedElementAt(
                """
                - type: entity
                  id: BaseMobMutant
                  components:
                  - type: Appearance
                    visuals:
                      id: blink<caret>ing
                """,
            ),
        )
    }

    private fun namedElementAt(text: String): PsiElement? {
        val marked = text.trimIndent()
        val caret = marked.indexOf(CARET)
        assertTrue("no <caret> in the text", caret >= 0)

        val file = createPsiFile("test", marked.replace(CARET, ""))
        val element = file.findElementAt(caret)
        assertNotNull("no element at caret", element)

        return RobustTargetElementEvaluator().getNamedElement(element!!)
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
