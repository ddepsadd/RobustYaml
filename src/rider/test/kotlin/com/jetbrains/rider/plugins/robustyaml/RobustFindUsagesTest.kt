package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * What Alt+F7 searches for, and what counts as a usage of it. Neither question can be answered by
 * the stock searcher: it looks for the name of the element, and the name of `id: Crowbar` is `id`.
 */
class RobustFindUsagesTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
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

    fun testIdOfAPrototypeIsSearchedByItsValue() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
              name: crowbar
            """,
        )

        assertEquals("Crowbar", searchedTarget(key(file, "id"))?.name)
    }

    /** A nested `id:` names an animation or a state, not a prototype: 3% of them in the content. */
    fun testNestedIdIsNotADeclaration() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
              components:
              - type: Appearance
                visuals:
                  id: blinking
            """,
        )

        val nested = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .filter { it.keyText == "id" }
        assertEquals(2, nested.size)
        assertNull(searchedTarget(nested.first { it.valueText == "blinking" }))
    }

    /** The caret often sits on a reference rather than on the declaration, and both must search
     * for the same id — the platform stops at the key-value and never reaches the reference. */
    fun testAReferenceIsSearchedByTheIdItPointsAt() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
              parent: BaseItem
            """,
        )

        assertEquals("BaseItem", searchedTarget(key(file, "parent"))?.name)
        assertNull(searchedTarget(key(file, "type")))
    }

    /**
     * An id may belong to several kinds at once — `Syndicate` is an antag, a department and more —
     * so a usage is recognised by what the declaration says, not by which one resolve picked.
     */
    fun testAReferenceMatchesEveryDeclarationOfTheSameId() {
        val file = parse(
            """
            - type: antag
              id: Syndicate
            - type: department
              id: Syndicate
            - type: entity
              id: Agent
              parent: Syndicate
            """,
        )

        val declarations = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .filter { it.keyText == "id" && it.valueText == "Syndicate" }
        assertEquals(2, declarations.size)

        val parent = key(file, "parent").value as YAMLScalar
        val reference = PrototypeIdReference(parent)
        for (declaration in declarations) {
            assertTrue("usage lost for one of the declarations", reference.isReferenceTo(declaration))
        }
    }

    fun testAReferenceDoesNotMatchAnotherId() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
            - type: entity
              id: Agent
              parent: Crowbar
            """,
        )

        val other = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" && it.valueText == "Agent" }
        val parent = key(file, "parent").value as YAMLScalar

        assertFalse(PrototypeIdReference(parent).isReferenceTo(other))
    }

    /**
     * Thirteen prototypes in the content name themselves through an alias. The engine reads the
     * anchored text and knows of no alias at all, so the search has to look for the same thing —
     * `valueText` answers with an empty string here and left those prototypes unsearchable.
     */
    fun testAnAliasedIdIsSearchedByTheTextItsAnchorCarries() {
        val file = parse(
            """
            - type: entity
              id: BackgammonBoard
              components:
              - type: TabletopGame
                boardPrototype: &BackgammonBoard BackgammonBoardTabletop

            - type: entity
              id: *BackgammonBoard
            """,
        )

        val declaration = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" && it.value !is YAMLScalar }
        assertEquals("BackgammonBoardTabletop", searchedTarget(declaration)?.name)
    }

    private fun parse(text: String): PsiFile = createPsiFile("test", text.trimIndent())

    private fun key(file: PsiFile, name: String): YAMLKeyValue =
        PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).first { it.keyText == name }
}
