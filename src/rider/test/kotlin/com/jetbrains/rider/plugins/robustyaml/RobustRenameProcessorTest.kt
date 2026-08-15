package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.ReadActionCache
import com.intellij.testFramework.ParsingTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.navigation.RobustRenameProcessor
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * What Shift+F6 is allowed to rename. The declaration of an id is named by its value, so the stock
 * rename would rewrite the key `id` instead; and a key that merely mentions an id is not the thing
 * being renamed at all.
 */
class RobustRenameProcessorTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
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

    fun testDeclarationOfAnIdCanBeRenamed() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
            """,
        )

        assertTrue(processor.canProcessElement(key(file, "id")))
    }

    fun testNestedIdCannotBeRenamed() {
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
            .first { it.keyText == "id" && it.valueText == "blinking" }
        assertFalse(processor.canProcessElement(nested))
    }

    fun testKindOfAPrototypeCannotBeRenamed() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
            """,
        )

        assertFalse(processor.canProcessElement(key(file, "type")))
    }

    /** Standing on the declaration, there is nothing to substitute — it is already the target. */
    fun testDeclarationIsItsOwnTarget() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
            """,
        )

        val declaration = key(file, "id")
        assertSame(declaration, processor.substituteElementToRename(declaration, null))
    }

    private fun parse(text: String): PsiFile = createPsiFile("test", text.trimIndent())

    private fun key(file: PsiFile, name: String): YAMLKeyValue =
        PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).first { it.keyText == name }

    private val processor = RobustRenameProcessor()
}
