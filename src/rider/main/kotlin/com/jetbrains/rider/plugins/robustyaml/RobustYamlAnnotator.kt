package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class RobustYamlAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is YAMLKeyValue -> annotateTypeKey(element, holder)
            is YAMLScalar -> annotateResourcePath(element, holder)
        }
    }

    private fun annotateTypeKey(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        val value = keyValue.value ?: return

        when {
            RobustYamlContext.isPrototypeKindKey(keyValue) ->
                paint(holder, value, RobustYamlColors.PROTOTYPE_KIND)
            RobustYamlContext.isComponentTypeKey(keyValue) ->
                paint(holder, value, RobustYamlColors.COMPONENT_NAME)
        }
    }

    private fun annotateResourcePath(scalar: YAMLScalar, holder: AnnotationHolder) {
        val text = scalar.textValue
        if (!text.contains('/') || text.any { it.isWhitespace() }) return
        paint(holder, scalar, RobustYamlColors.RESOURCE_PATH)
    }

    private fun paint(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
            .create()
    }
}
