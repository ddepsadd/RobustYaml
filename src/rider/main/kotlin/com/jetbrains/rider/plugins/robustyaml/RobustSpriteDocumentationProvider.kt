package com.jetbrains.rider.plugins.robustyaml

import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLScalar

class RobustSpriteDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()
        val image = RobustSpritePreview.findImage(scalar) ?: return emptyList()
        return listOf(SpritePreviewTarget(image))
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
            append("<div style='padding: 4px'><img width='")
            append(image.width)
            append("' height='")
            append(image.height)
            append("' src='")
            append(StringUtil.escapeXmlEntities(key))
            append("'></div>")
            append("<div style='padding: 4px'><code>")
            append(StringUtil.escapeXmlEntities(RobustSpritePreview.describe(png)))
            append("</code></div>")
        }
        return DocumentationResult.documentation(
            DocumentationContent.content(html, mapOf(key to image)),
        )
    }
}
