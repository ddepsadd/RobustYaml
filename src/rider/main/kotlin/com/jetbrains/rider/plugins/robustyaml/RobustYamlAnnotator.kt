package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

class RobustYamlAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is YAMLKeyValue -> annotateTypeKey(element, holder)
            is YAMLScalar -> annotateResourcePath(element, holder)
        }
    }

    private fun annotateTypeKey(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        if (keyValue.keyText != "type") return
        val value = keyValue.value ?: return
        val enclosing = PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true)

        when {
            enclosing == null -> paint(holder, value, RobustYamlColors.PROTOTYPE_KIND)
            enclosing.keyText == "components" && isInsideEntity(enclosing) ->
                paint(holder, value, RobustYamlColors.COMPONENT_NAME)
        }
    }

    private fun annotateResourcePath(scalar: YAMLScalar, holder: AnnotationHolder) {
        val text = scalar.textValue
        if (!text.contains('/') || text.any { it.isWhitespace() }) return
        paint(holder, scalar, RobustYamlColors.RESOURCE_PATH)
    }

    private fun isInsideEntity(componentsKeyValue: YAMLKeyValue): Boolean {
        val entityMapping =
            PsiTreeUtil.getParentOfType(componentsKeyValue, YAMLMapping::class.java, true) ?: return false
        return entityMapping.getKeyValueByKey("type")?.valueText == "entity"
    }

    private fun paint(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
            .create()
    }
}
