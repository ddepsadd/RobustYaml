package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

object RobustYamlContext {
    private val RESOURCE_PATH_KEYS = setOf("sprite", "rsiPath", "path", "sound", "texturePath")
    private val PROTOTYPE_ID_KEYS = setOf("parent", "proto", "prototype", "entity", "id")

    fun isResourcePathValue(scalar: YAMLScalar): Boolean {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        return keyValue.value === scalar && keyValue.keyText in RESOURCE_PATH_KEYS
    }

    fun isResourcePathKey(name: String): Boolean = name in RESOURCE_PATH_KEYS

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

    /**
     * Where a key lives: the path walked from the declaration, and the class that path starts at.
     * [root] is null for the usual case — the component or prototype itself — and holds a class name
     * when a `!type:` tag was crossed on the way up: everything below the tag belongs to that class,
     * not to the declared type of the field.
     */
    data class Origin(val root: String?, val path: List<String>) {
        fun parent(): Origin = Origin(root, path.dropLast(1))

        fun of(segments: List<String>): Origin = Origin(root, segments)
    }

    fun originTo(declaration: DeclarationContext, keyValue: YAMLKeyValue): Origin? =
        originAt(declaration, keyValue)

    fun originAt(declaration: DeclarationContext, element: PsiElement): Origin? {
        val path = ArrayDeque<String>()
        var child: PsiElement = element
        var current: PsiElement? = element.parent
        while (current != null) {
            if (current === declaration.mapping) return Origin(null, path.toList())

            // A tag describes what lies inside the value it is attached to, so it counts only when
            // the walk came up through that value: standing on the key itself, its own tag is none
            // of its business.
            val fromValue = when (current) {
                is YAMLKeyValue -> current.value === child
                is YAMLSequenceItem -> current.value === child
                else -> true
            }
            if (fromValue) taggedType(current)?.let { return Origin(it, path.toList()) }

            if (current is YAMLKeyValue && current.value === child) path.addFirst(current.keyText)
            child = current
            current = current.parent
        }
        return null
    }

    /**
     * `!type:` sits between the colon and a block mapping, so it is a child of the key-value rather
     * than of the mapping — [YAMLValue.getTag] only sees it when the value starts with the tag,
     * as in a flow mapping or a sequence item.
     */
    fun taggedType(element: PsiElement): String? {
        val tag = tagToken(element)?.text ?: return null
        if (!tag.startsWith(TYPE_TAG)) return null
        return tag.removePrefix(TYPE_TAG).takeIf { it.isNotEmpty() }
    }

    /**
     * The tag token lives in one of two places depending on the shape of the value: right inside
     * the key-value when a block mapping follows, or as the first child of the value itself — the
     * form [YAMLValue.getTag] was written for. Both are asked, so a caller holding the key finds
     * the tag no matter how the parser laid it out.
     */
    fun tagToken(element: PsiElement): PsiElement? {
        directTag(element)?.let { return it }

        val value = when (element) {
            is YAMLKeyValue -> element.value
            is YAMLSequenceItem -> element.value
            else -> null
        }
        return value?.let { directTag(it) }
    }

    private fun directTag(element: PsiElement): PsiElement? {
        var child = element.firstChild
        while (child != null) {
            if (child.node?.elementType == YAMLTokenTypes.TAG) return child
            child = child.nextSibling
        }
        return null
    }

    fun pathTo(declaration: DeclarationContext, keyValue: YAMLKeyValue): List<String>? =
        originTo(declaration, keyValue)?.path

    fun pathAt(declaration: DeclarationContext, element: PsiElement): List<String>? =
        originAt(declaration, element)?.path

    private const val TYPE_TAG = "!type:"

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

    fun isLocalizationValue(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        return RobustValidation.field(keyValue)?.localized == true
    }

    fun owningKey(scalar: YAMLScalar): YAMLKeyValue? =
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
