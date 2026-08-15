package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.documentation.CategoryDeclaration
import com.jetbrains.rider.plugins.robustyaml.documentation.categoryAt
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Reading a category out of its declaration. What ends up in the popup is decided here, and the
 * cases below are the shapes the content actually has: every category has a name, four of the twelve
 * have a description, two a suffix, and the flags are usually left at their defaults.
 */
class RobustCategoryDocumentationTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
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

    fun testEveryKeyOfADeclarationIsRead() {
        val category = read(
            """
            - type: entityCategory
              id: HideSpawnMenu
              name: entity-category-name-hide
              description: entity-category-desc-hide
              hideSpawnMenu: true
              inheritable: false
            """,
        )

        assertEquals("entity-category-name-hide", category.name)
        assertEquals("entity-category-desc-hide", category.description)
        assertNull(category.suffix)
        assertEquals(true, category.hideSpawnMenu)
        assertEquals(false, category.inheritable)
    }

    /** Eight of the twelve categories carry a name and nothing else. */
    fun testAbsentKeysStayNull() {
        val category = read(
            """
            - type: entityCategory
              id: Mapping
              name: entity-category-name-mapping
            """,
        )

        assertEquals("entity-category-name-mapping", category.name)
        assertNull(category.description)
        assertNull(category.suffix)
        assertNull(category.hideSpawnMenu)
        assertNull(category.inheritable)
    }

    /** `BooleanSerializer` is `bool.Parse`, so the case of the value does not matter to the engine. */
    fun testBooleansAreReadTheWayTheEngineReadsThem() {
        val category = read(
            """
            - type: entityCategory
              id: Debug
              hideSpawnMenu: True
              inheritable: FALSE
            """,
        )

        assertEquals(true, category.hideSpawnMenu)
        assertEquals(false, category.inheritable)
    }

    fun testAValueThatIsNotABooleanIsNotOne() {
        val category = read(
            """
            - type: entityCategory
              id: Debug
              hideSpawnMenu: yes
            """,
        )

        assertNull(category.hideSpawnMenu)
    }

    /** The keys of the neighbour must not leak in: a file declares categories one after another. */
    fun testOnlyTheKeysOfItsOwnDeclarationAreRead() {
        val file = parse(
            """
            - type: entityCategory
              id: Spawner
              name: entity-category-name-spawner
              description: entity-category-desc-spawner

            - type: entityCategory
              id: Mapping
              name: entity-category-name-mapping
            """,
        )

        val second = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" && it.valueText == "Mapping" }
        val category = categoryAt(second, "categories.yml")

        assertNotNull(category)
        assertEquals("entity-category-name-mapping", category!!.name)
        assertNull("the neighbour's description leaked in", category.description)
    }

    private fun read(text: String): CategoryDeclaration {
        val file = parse(text)
        val declaration = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" }
        val category = categoryAt(declaration, "entityCategory.yml")
        assertNotNull("no declaration read", category)
        return category!!
    }

    private fun parse(text: String): PsiFile = createPsiFile("test", text.trimIndent())
}
