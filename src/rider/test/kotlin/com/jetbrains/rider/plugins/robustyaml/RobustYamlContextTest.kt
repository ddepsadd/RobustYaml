package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.ReadActionCache
import com.intellij.util.ProcessingContext
import com.intellij.testFramework.ParsingTestCase
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The path a key lives at, and the class that path starts from. Every case here stands for a bug
 * that reached the sandbox: a tag read from the wrong node, a segment counted for a sequence item,
 * a tag applied to the very key that carries it.
 *
 * [ParsingTestCase] is used rather than a fixture because a light project boots the whole of Rider,
 * and Rider refuses to register its components without a solution. Everything under test here is a
 * pure walk over PSI, so a parser and a file are all it needs.
 */
class RobustYamlContextTest : ParsingTestCase("", "yml", YAMLParserDefinition()) {
    override fun getTestDataPath(): String = "src/rider/test/data"

    override fun skipSpaces(): Boolean = false

    override fun setUp() {
        super.setUp()
        // Reading the text of a scalar goes through this application service, and the mock
        // application of a parsing test registers nothing by itself.
        application.registerService(ReadActionCache::class.java, NoReadActionCache())
    }

    private class NoReadActionCache : ReadActionCache {
        override val processingContext: ProcessingContext? get() = null

        override fun <T> allowInWriteAction(action: () -> T): T = action()

        override fun allowInWriteAction(action: Runnable) = action.run()

        override fun disable() = Unit
    }

    fun testPathOfDirectComponentKey() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                sta<caret>te: red
            """,
        )

        assertNull(origin.root)
        assertEquals(emptyList<String>(), origin.path)
    }

    fun testPathDescendsThroughDictionaryAndNestedKeys() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Fixtures
                fixtures:
                  fix1:
                    den<caret>sity: 20
            """,
        )

        assertNull(origin.root)
        assertEquals(listOf("fixtures", "fix1"), origin.path)
    }

    /** The tag sits between the colon and a block mapping, so it is a child of the key-value. */
    fun testTagOnKeyBecomesRoot() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Fixtures
                fixtures:
                  fix1:
                    shape: !type:PolygonShape
                      vert<caret>ices:
                      - -0.2,0.1
            """,
        )

        assertEquals("PolygonShape", origin.root)
        assertEquals(emptyList<String>(), origin.path)
    }

    /** A tag on the carrier does not describe the carrier itself, only what lies below it. */
    fun testTagDoesNotApplyToItsOwnKey() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Fixtures
                fixtures:
                  fix1:
                    sha<caret>pe: !type:PolygonShape
                      vertices:
                      - -0.2,0.1
            """,
        )

        assertNull(origin.root)
        assertEquals(listOf("fixtures", "fix1"), origin.path)
    }

    fun testTagOnSequenceItemBecomesRoot() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Destructible
                thresholds:
                - behaviors:
                  - !type:PlaySoundBehavior
                    so<caret>und: /Audio/x.ogg
            """,
        )

        assertEquals("PlaySoundBehavior", origin.root)
        assertEquals(emptyList<String>(), origin.path)
    }

    /** A sequence item has no name, so it contributes no segment: the field is the key above it. */
    fun testSequenceItemAddsNoSegment() {
        val origin = originAt(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                layers:
                - sta<caret>te: red
            """,
        )

        assertNull(origin.root)
        assertEquals(listOf("layers"), origin.path)
    }

    fun testTaggedTypeIsFoundOnKeyAndOnSequenceItem() {
        val file = parse(
            """
            - type: entity
              id: Foo
              components:
              - type: Fixtures
                fixtures:
                  fix1:
                    shape: !type:PolygonShape
                      vertices: []
              - type: Destructible
                thresholds:
                - behaviors:
                  - !type:PlaySoundBehavior
                    sound: /Audio/x.ogg
            """,
        )

        val shape = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "shape" }
        assertEquals("PolygonShape", RobustYamlContext.taggedType(shape))

        val item = PsiTreeUtil.findChildrenOfType(file, YAMLSequenceItem::class.java)
            .first { RobustYamlContext.taggedType(it) != null }
        assertEquals("PlaySoundBehavior", RobustYamlContext.taggedType(item))
    }

    fun testTagOfAnotherKindIsNotATypeTag() {
        val file = parse(
            """
            - type: entity
              id: Foo
              components:
              - type: Sprite
                color: !!str red
            """,
        )

        val color = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "color" }
        assertNull(RobustYamlContext.taggedType(color))
    }

    /**
     * An anchor is written between the colon and the value, and Find Usages compares the text of
     * the scalar with what it is looking for: were the anchor part of that text, no search would
     * ever match `tooltip: &TextOpenClose door-remote-open-close-text`.
     */
    fun testAnchorIsNotPartOfTheValue() {
        val file = parse(
            """
            - type: entity
              id: Foo
              components:
              - type: DoorRemote
                tooltip: &TextOpenClose door-remote-open-close-text
            """,
        )

        val tooltip = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "tooltip" }
        assertEquals("door-remote-open-close-text", (tooltip.value as YAMLScalar).textValue)
    }

    /**
     * An alias is not a scalar — both descend from `YAMLValue`, but along different branches — so
     * `keyValue.value as? YAMLScalar` is null on it and `getValueText` answers with an empty string.
     * The engine sees neither: it resolves the alias while parsing and reads the anchored text.
     */
    fun testAnAliasStandsForTheValueItsAnchorMarks() {
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
        assertEquals("", declaration.valueText)
        assertEquals("BackgammonBoardTabletop", RobustYamlContext.resolvedText(declaration.value))
    }

    /** Robust resolves aliases after reading the document, so an anchor may stand below its alias. */
    fun testAnAliasResolvesWhenItsAnchorComesLater() {
        val file = parse(
            """
            - type: entity
              id: *Board

            - type: entity
              id: Board
              components:
              - type: TabletopGame
                boardPrototype: &Board BoardTabletop
            """,
        )

        val declaration = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" && it.value !is YAMLScalar }
        assertEquals("BoardTabletop", RobustYamlContext.resolvedText(declaration.value))
    }

    fun testAPlainValueIsItsOwnResolvedScalar() {
        val file = parse(
            """
            - type: entity
              id: Crowbar
            """,
        )

        val declaration = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .first { it.keyText == "id" }
        assertEquals("Crowbar", RobustYamlContext.resolvedText(declaration.value))
    }

    private fun parse(text: String): PsiFile = createPsiFile("test", text.trimIndent())

    private fun originAt(text: String): RobustYamlContext.Origin {
        val marked = text.trimIndent()
        val caret = marked.indexOf(CARET)
        assertTrue("no <caret> in the text", caret >= 0)

        val file = createPsiFile("test", marked.replace(CARET, ""))
        val element: PsiElement? = file.findElementAt(caret)
        assertNotNull("no element at caret", element)

        val declaration = RobustYamlContext.declarationAround(element!!)
        assertNotNull("no declaration around caret", declaration)

        val origin = RobustYamlContext.originAt(declaration!!, element)
        assertNotNull("no origin at caret", origin)
        return origin!!
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
