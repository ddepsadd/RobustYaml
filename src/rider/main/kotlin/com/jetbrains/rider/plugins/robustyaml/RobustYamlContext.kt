package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

object RobustYamlContext {
    private val RESOURCE_PATH_KEYS = setOf("sprite", "rsiPath", "path", "sound", "texturePath")
    private val PROTOTYPE_ID_KEYS = setOf("parent", "proto", "prototype", "entity", "id")

    fun isResourcePathValue(scalar: YAMLScalar): Boolean {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        return keyValue.value === scalar && keyValue.keyText in RESOURCE_PATH_KEYS
    }

    data class DeclarationContext(val isComponent: Boolean, val name: String, val mapping: YAMLMapping)

    fun declarationAround(element: PsiElement): DeclarationContext? {
        var mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java, false)
        while (mapping != null) {
            val typeKey = mapping.getKeyValueByKey("type")
            val name = (typeKey?.value as? YAMLScalar)?.textValue
            if (typeKey != null && !name.isNullOrEmpty()) {
                return when {
                    isComponentTypeKey(typeKey) -> DeclarationContext(true, name, mapping)
                    isPrototypeKindKey(typeKey) -> DeclarationContext(false, name, mapping)
                    else -> null
                }
            }
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java, true)
        }
        return null
    }

    fun isPrototypeIdDeclaration(keyValue: YAMLKeyValue): Boolean =
        keyValue.keyText == "id" &&
            PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true) == null

    private val VALIDATED_ID_KEYS = setOf("parent", "proto", "prototype", "entity")

    fun isValidatedPrototypeIdReference(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        return keyValue.keyText in VALIDATED_ID_KEYS
    }

    fun isPrototypeId(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        return keyValue.keyText in PROTOTYPE_ID_KEYS
    }

    fun isPrototypeIdReference(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        if (keyValue.keyText !in PROTOTYPE_ID_KEYS) return false
        return !isPrototypeIdDeclaration(keyValue)
    }

    private fun owningKey(scalar: YAMLScalar): YAMLKeyValue? =
        when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent.takeIf { it.value === scalar }
            is YAMLSequenceItem -> PsiTreeUtil.getParentOfType(parent, YAMLKeyValue::class.java, true)
            else -> null
        }

    fun enclosingSpritePath(scalar: YAMLScalar): String? {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.value !== scalar || keyValue.keyText != "state") return null

        var mapping = PsiTreeUtil.getParentOfType(keyValue, YAMLMapping::class.java, true)
        while (mapping != null) {
            val sprite = mapping.getKeyValueByKey("sprite") ?: mapping.getKeyValueByKey("rsiPath")
            val path = (sprite?.value as? YAMLScalar)?.textValue?.trim()
            if (!path.isNullOrEmpty()) return path
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java, true)
        }
        return null
    }

    fun isPrototypeKindKey(keyValue: YAMLKeyValue): Boolean =
        keyValue.keyText == "type" &&
            PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true) == null

    fun isComponentTypeKey(keyValue: YAMLKeyValue): Boolean {
        if (keyValue.keyText != "type") return false
        val enclosing =
            PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true) ?: return false
        if (enclosing.keyText != "components") return false
        val entityMapping =
            PsiTreeUtil.getParentOfType(enclosing, YAMLMapping::class.java, true) ?: return false
        return entityMapping.getKeyValueByKey("type")?.valueText == "entity"
    }
}
