package com.jetbrains.rider.plugins.robustyaml.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import org.jetbrains.yaml.psi.YAMLScalar

class RobustLocalizationDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()

        val id = RobustLocalization.messageId(scalar.textValue.trim())
        if (!RobustLocalization.looksLikeMessageId(id)) return emptyList()

        val project = file.project
        if (!RobustLocalization.hasMessage(project, id)) return emptyList()
        return listOf(MessageDocumentationTarget(project, id))
    }
}

internal class MessageDocumentationTarget(
    private val project: Project,
    private val id: String,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(id).locationText(LOCATION).presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            val translations = smartReadAction(project) { translations() }
            if (translations.isEmpty()) return@asyncDocumentation null

            val html = buildString {
                append(DocumentationMarkup.DEFINITION_START)
                HtmlSyntaxInfoUtil.appendStyledSpan(
                    this,
                    DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                    StringUtil.escapeXmlEntities(id),
                    1.0f,
                )
                append(DocumentationMarkup.DEFINITION_END)

                append(DocumentationMarkup.SECTIONS_START)
                for ((culture, text) in translations) {
                    append(DocumentationMarkup.SECTION_HEADER_START)
                    append(StringUtil.escapeXmlEntities(culture))
                    append(DocumentationMarkup.SECTION_SEPARATOR)
                    HtmlSyntaxInfoUtil.appendStyledSpan(
                        this,
                        DefaultLanguageHighlighterColors.STRING,
                        StringUtil.escapeXmlEntities(shorten(text)),
                        1.0f,
                    )
                    append(DocumentationMarkup.SECTION_END)
                }
                append(DocumentationMarkup.SECTIONS_END)
            }
            DocumentationResult.documentation(DocumentationContent.content(html))
        }

    private fun translations(): List<Pair<String, String>> =
        RobustLocalization.translations(project, id)

    private fun shorten(text: String): String {
        val single = text.replace('\n', ' ')
        return if (single.length <= MAX_LENGTH) single else single.take(MAX_LENGTH) + "…"
    }

    private companion object {
        const val LOCATION = "localization"
        const val MAX_LENGTH = 400
    }
}
