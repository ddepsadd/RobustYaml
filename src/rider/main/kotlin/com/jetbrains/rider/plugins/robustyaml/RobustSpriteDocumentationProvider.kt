package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.containers.CollectionFactory
import com.intellij.util.ui.UIUtil
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.image.BufferedImage

class RobustSpriteDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()
        RobustSpritePreview.findRsi(scalar)?.let { return listOf(RsiContentsTarget(it)) }
        val image = RobustSpritePreview.findImage(scalar) ?: return emptyList()
        return listOf(SpritePreviewTarget(image))
    }
}

private class RsiContentsTarget(private val rsi: VirtualFile) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(rsi.name).presentation()

    override fun computeDocumentation(): DocumentationResult {
        val key = memoKey()
        documents[key]?.let { return it }

        build(cachedOnly = true)?.let {
            documents[key] = it
            return it
        }
        return DocumentationResult.asyncDocumentation {
            build(cachedOnly = false)?.also { documents[key] = it }
        }
    }

    private fun memoKey(): String =
        "${rsi.url}@${rsi.findChild(META)?.timeStamp ?: 0}@${ColorUtil.toHex(UIUtil.getLabelForeground())}"

    private fun build(cachedOnly: Boolean): DocumentationResult.Documentation? {
        run {
            val states = RobustSpritePreview.states(rsi)
            if (states.isEmpty()) return null
            val foreground = ColorUtil.toHex(UIUtil.getLabelForeground())

            val images = mutableMapOf<String, BufferedImage>()
            val entries = mutableListOf<Pair<String, String>>()
            for (state in states.take(MAX_STATES)) {
                val png = rsi.findChild("$state.png") ?: continue
                val key = RobustSpritePreview.imageKey(png) ?: continue
                val image =
                    if (cachedOnly) RobustSpritePreview.cachedThumbnail(png) ?: return null
                    else RobustSpritePreview.thumbnail(png) ?: continue
                images[key] = image
                entries += state to key
            }
            if (entries.isEmpty()) return null

            val html = buildString {
                append(DocumentationMarkup.DEFINITION_START)
                HtmlSyntaxInfoUtil.appendStyledSpan(
                    this,
                    RobustYamlColors.RESOURCE_PATH,
                    StringUtil.escapeXmlEntities(rsi.name),
                    1.0f,
                )
                append(DocumentationMarkup.GRAYED_START)
                RobustSpritePreview.frameOf(rsi)?.let {
                    append("  ")
                    append(it.first)
                    append("×")
                    append(it.second)
                }
                append(", ")
                append(states.size)
                append(" states")
                append(DocumentationMarkup.GRAYED_END)
                append(DocumentationMarkup.DEFINITION_END)

                val cell = RobustSpritePreview.thumbnailWidth() + CELL_PADDING
                append(DocumentationMarkup.CONTENT_START)
                append("<table align='center' width='")
                append(COLUMNS * cell)
                append("'>")
                for (row in entries.chunked(COLUMNS)) {
                    append("<tr>")
                    for ((_, key) in row) {
                        val image = images.getValue(key)
                        append("<td align='center' valign='bottom' width='")
                        append(cell)
                        append("' style='padding: 6px 6px 0 6px'><img width='")
                        append(image.width)
                        append("' height='")
                        append(image.height)
                        append("' src='")
                        append(StringUtil.escapeXmlEntities(key))
                        append("'></td>")
                    }
                    append("</tr><tr>")
                    for ((state, _) in row) {
                        append("<td align='center' valign='top' nowrap width='")
                        append(cell)
                        append("' style='padding: 2px 6px 6px 6px'>")
                        append("<span style='color: #")
                        append(foreground)
                        append("; font-size: 90%'>")
                        append(StringUtil.escapeXmlEntities(state))
                        append("</span></td>")
                    }
                    append("</tr>")
                }
                append("</table>")
                append(DocumentationMarkup.CONTENT_END)
                if (states.size > MAX_STATES) {
                    append(DocumentationMarkup.CONTENT_START)
                    append(DocumentationMarkup.GRAYED_START)
                    append("and ")
                    append(states.size - MAX_STATES)
                    append(" more")
                    append(DocumentationMarkup.GRAYED_END)
                    append(DocumentationMarkup.CONTENT_END)
                }
            }
            return DocumentationResult.documentation(DocumentationContent.content(html, images))
        }
    }

    private companion object {
        const val MAX_STATES = 128
        const val COLUMNS = 3
        const val CELL_PADDING = 12
        const val META = "meta.json"

        val documents =
            CollectionFactory.createConcurrentSoftValueMap<String, DocumentationResult.Documentation>()
    }
}

private class SpritePreviewTarget(private val png: VirtualFile) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(png.name).presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val key = RobustSpritePreview.imageKey(png) ?: return null
        val image = RobustSpritePreview.loadImage(png) ?: return null
        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START)
            HtmlSyntaxInfoUtil.appendStyledSpan(
                this,
                RobustYamlColors.RESOURCE_PATH,
                StringUtil.escapeXmlEntities(png.nameWithoutExtension),
                1.0f,
            )
            append(DocumentationMarkup.GRAYED_START)
            append("  ")
            append(StringUtil.escapeXmlEntities(RobustSpritePreview.describe(png)))
            append(DocumentationMarkup.GRAYED_END)
            append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START)
            append("<img width='")
            append(image.width)
            append("' height='")
            append(image.height)
            append("' src='")
            append(StringUtil.escapeXmlEntities(key))
            append("'>")
            append(DocumentationMarkup.CONTENT_END)
        }
        return DocumentationResult.documentation(
            DocumentationContent.content(html, mapOf(key to image)),
        )
    }
}
