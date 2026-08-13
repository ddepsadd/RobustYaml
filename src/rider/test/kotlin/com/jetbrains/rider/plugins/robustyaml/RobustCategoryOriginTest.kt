package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiFile
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The walk from a value of `categories:` up to the query the backend is asked. Every step of it is
 * a place the hover can go silent without saying anything, which is exactly what happened: the popup
 * either appears or it does not, and nothing in between is visible.
 */
class RobustCategoryOriginTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
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

    /** The commonest form by far: 1091 of the 1140 values are written inline. */
    fun testInlineCategoryReachesItsKey() {
        val (scalar, file) = valueAt(
            """
            - type: entity
              id: BaseStructure
              categories: [ <caret>HideSpawnMenu ]
            """,
        )

        assertEquals("HideSpawnMenu", scalar.textValue)

        val keyValue = RobustYamlContext.owningKey(scalar)
        assertNotNull("no owning key for an inline sequence item", keyValue)
        assertEquals("categories", keyValue!!.keyText)

        val declaration = RobustYamlContext.declarationAround(keyValue)
        assertNotNull("no declaration around the key", declaration)
        assertEquals("entity", declaration!!.name)
        assertFalse(declaration.isComponent)

        val origin = RobustYamlContext.originTo(declaration, keyValue)
        assertNotNull("no origin for the key", origin)
        assertEquals(emptyList<String>(), origin!!.path)
        assertNull(origin.root)
        assertNotNull(file)
    }

    /** The block form is rarer for entities — 49 values — but has to reach the same query. */
    fun testBlockCategoryReachesItsKey() {
        val (scalar, _) = valueAt(
            """
            - type: entity
              id: BaseStructure
              categories:
              - <caret>HideSpawnMenu
            """,
        )

        val keyValue = RobustYamlContext.owningKey(scalar)
        assertNotNull("no owning key for a block sequence item", keyValue)
        assertEquals("categories", keyValue!!.keyText)

        val declaration = RobustYamlContext.declarationAround(keyValue)!!
        assertEquals(emptyList<String>(), RobustYamlContext.originTo(declaration, keyValue)!!.path)
    }

    private fun valueAt(text: String): Pair<YAMLScalar, PsiFile> {
        val marked = text.trimIndent()
        val caret = marked.indexOf(CARET)
        assertTrue("no <caret> in the text", caret >= 0)

        val file = createPsiFile("test", marked.replace(CARET, ""))
        val element = file.findElementAt(caret)
        assertNotNull("no element at caret", element)

        val scalar = com.intellij.psi.util.PsiTreeUtil
            .getParentOfType(element, YAMLScalar::class.java, false)
        assertNotNull("caret is not inside a scalar", scalar)
        return scalar!! to file
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
