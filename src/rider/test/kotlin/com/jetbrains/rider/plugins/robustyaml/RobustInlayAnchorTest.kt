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
 * Where an inlay may hang on a value, stated as the property of YAML it rests on: the range of a
 * plain scalar stops at the value and owns neither the blanks nor the comment written after it.
 * That is why the anchor of a hint is simply `textRange.endOffset` and needs no walking.
 *
 * The test exists because a walk was written. Made to skip blanks so that typing a trailing space
 * would not shuffle the hint, it moved the anchor past a space that was never inside the range —
 * and on `station_beacon.yml:3`, where the trailing space is the author's, the hint came out a
 * column away from the id. The symptom of the cure looked exactly like the symptom of the disease.
 */
class RobustInlayAnchorTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
    override fun getTestDataPath(): String = "src/rider/test/data"

    override fun skipSpaces(): Boolean = false

    override fun setUp() {
        super.setUp()
        application.registerService(ReadActionCache::class.java, NoReadActionCache())
    }

    fun testTrailingBlanksAreNotPartOfTheScalar() {
        val scalar = scalarOf("- type: entity\n  id: BaseStationBeacon \n")

        assertEquals("BaseStationBeacon", scalar.textValue)
        assertEquals('n', scalar.containingFile.text[scalar.textRange.endOffset - 1])
        assertEquals(' ', scalar.containingFile.text[scalar.textRange.endOffset])
    }

    fun testACommentIsNotPartOfTheScalarEither() {
        val text = "- type: entity\n  id: BaseStationBeacon # note\n"
        val scalar = scalarOf(text)

        assertEquals("BaseStationBeacon", scalar.textValue)
        assertEquals(
            text.indexOf("BaseStationBeacon") + "BaseStationBeacon".length,
            scalar.textRange.endOffset,
        )
    }

    fun testTheRangeOfACleanValueEndsAtTheValue() {
        val text = "- type: entity\n  id: BaseStationBeacon\n"
        val scalar = scalarOf(text)

        assertEquals(
            text.indexOf("BaseStationBeacon") + "BaseStationBeacon".length,
            scalar.textRange.endOffset,
        )
    }

    private fun scalarOf(text: String): YAMLScalar {
        val file: PsiFile = createPsiFile("test", text)
        val key = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).first { it.keyText == "id" }
        return key.value as YAMLScalar
    }

    private class NoReadActionCache : ReadActionCache {
        override val processingContext: ProcessingContext? get() = null

        override fun <T> allowInWriteAction(action: () -> T): T = action()

        override fun allowInWriteAction(action: Runnable) = action.run()

        override fun disable() = Unit
    }
}
