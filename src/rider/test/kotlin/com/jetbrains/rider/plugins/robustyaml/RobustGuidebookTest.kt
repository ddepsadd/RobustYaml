package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.LanguageASTFactory
import com.intellij.lang.xml.XMLLanguage
import com.intellij.lang.xml.XMLParserDefinition
import com.intellij.lang.xml.XmlASTFactory
import com.intellij.lang.xml.XmlSyntaxDefinitionExtension
import com.intellij.platform.syntax.psi.ElementTypeConverter
import com.intellij.platform.syntax.psi.ElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import com.intellij.platform.syntax.psi.LanguageSyntaxDefinitions
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.xmlElementTypeConverter
import com.intellij.testFramework.ParsingTestCase
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustGuidebook

/**
 * What counts as a prototype reference inside a guidebook document. The document is XML and has a
 * parser, so the question is about the shape of the tree — which node carries the value, and which
 * of its ancestors decides what the value means — and that is exactly what a measurement cannot see.
 */
class RobustGuidebookTest : ParsingTestCase("", "xml", XMLParserDefinition()) {
    override fun getTestDataPath(): String = "src/rider/test/data"

    override fun skipSpaces(): Boolean = false

    /**
     * A parser definition alone no longer gives XML a way to be read: the lexer sits behind
     * `LanguageSyntaxDefinitions`, and the tokens it produces are turned into `IElementType` by a
     * converter of their own. Without the first the mock environment stops at `No SyntaxDefinition
     * for language: XML`, without the second at `No IElementType found for elementType: XML_PROLOG`.
     */
    override fun setUp() {
        super.setUp()
        addExplicitExtension(
            LanguageSyntaxDefinitions.INSTANCE,
            XMLLanguage.INSTANCE,
            XmlSyntaxDefinitionExtension(),
        )
        addExplicitExtension(
            ElementTypeConverters.instance,
            XMLLanguage.INSTANCE,
            // The extension shipped for this is internal to its own file; the converter it hands
            // over is not, so the factory around it is written out here.
            XmlConverters,
        )
        // Without an AST factory the nodes of the tree are built by the parser definition, and that
        // one answers `Cannot create PSI for element type XML_DOCUMENT`.
        addExplicitExtension(LanguageASTFactory.INSTANCE, XMLLanguage.INSTANCE, XmlASTFactory())
    }

    private object XmlConverters : ElementTypeConverterFactory {
        override fun getElementTypeConverter(): ElementTypeConverter = xmlElementTypeConverter
    }

    fun testEntityOfAnEmbedNamesAnEntityPrototype() {
        val file = parse("""<Document><GuideEntityEmbed Entity="Crowbar" Caption="Лом"/></Document>""")

        assertEquals("entity", RobustGuidebook.kindOf(value(file, "Crowbar")!!))
    }

    fun testReagentAndDisciplineNameTheirOwnKinds() {
        val file = parse(
            """
            <Document>
              <GuideReagentEmbed Reagent="Romerol"/>
              <GuideTechDisciplineEmbed Discipline="Industrial"/>
            </Document>
            """,
        )

        assertEquals("reagent", RobustGuidebook.kindOf(value(file, "Romerol")!!))
        assertEquals("techDiscipline", RobustGuidebook.kindOf(value(file, "Industrial")!!))
    }

    /** A caption is text for the reader; only the attributes the embed resolves are references. */
    fun testACaptionNamesNothing() {
        val file = parse("""<Document><GuideEntityEmbed Entity="Crowbar" Caption="Лом"/></Document>""")

        assertNull(RobustGuidebook.kindOf(value(file, "Лом")!!))
    }

    /**
     * The tag decides. `Entity` is an ordinary word for an attribute, and without this check every
     * XML of the checkout would hand ids to the index.
     */
    fun testTheSameAttributeOnAnotherTagNamesNothing() {
        val file = parse("""<Document><Box Entity="Crowbar"/></Document>""")

        assertNull(RobustGuidebook.kindOf(value(file, "Crowbar")!!))
        assertEquals(emptySet<String>(), RobustGuidebook.ids(file.text))
    }

    fun testIdsOfADocumentAreTakenFromEmbedsOnly() {
        val text =
            """
            <Document>
              <GuideEntityEmbed Entity="Crowbar" Caption="Лом"/>
              <GuideEntityEmbed Entity="Wrench"/>
              <GuideReagentEmbed Reagent="Romerol"/>
              <Box Entity="NotAnEmbed"/>
            </Document>
            """

        assertEquals(setOf("Crowbar", "Wrench", "Romerol"), RobustGuidebook.ids(text))
    }

    /**
     * Three forms XML allows: either quote around a value, more than one naming attribute on a tag,
     * and a bare `>` inside the value of a neighbour. PSI reads all three, so the text rule behind
     * the index has to read them too — where it does not, Ctrl+click on the attribute goes on
     * working while the file drops out of the candidates of Find Usages and rename passes it by.
     */
    fun testTheTextRuleReadsEveryFormThePsiRuleReads() {
        val text =
            """
            <Document>
              <GuideEntityEmbed Entity='Crowbar'/>
              <GuideEntityEmbed Caption="a > b" Entity="Wrench"/>
              <GuideReagentEmbed Reagent="Romerol" Entity="Beaker"/>
            </Document>
            """.trimIndent()

        assertEquals(setOf("Crowbar", "Wrench", "Romerol", "Beaker"), RobustGuidebook.ids(text))

        val file = parse(text)
        assertEquals("entity", RobustGuidebook.kindOf(value(file, "Crowbar")!!))
        assertEquals("entity", RobustGuidebook.kindOf(value(file, "Wrench")!!))
        assertEquals("entity", RobustGuidebook.kindOf(value(file, "Beaker")!!))
        assertNull(RobustGuidebook.kindOf(value(file, "a > b")!!))
    }

    /** The offset is what the measurement reports and what a text edit would be written at. */
    fun testAReferenceStartsAtTheValueItself() {
        val text = """<GuideEntityEmbed Entity='Crowbar'/>"""

        val reference = RobustGuidebook.references(text).single()
        assertEquals("Crowbar", text.substring(reference.start, reference.start + reference.id.length))
    }

    private fun parse(text: String): PsiFile = createPsiFile("test", text.trimIndent())

    private fun value(file: PsiFile, text: String): XmlAttributeValue? =
        PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .firstOrNull { it.value == text }
}
