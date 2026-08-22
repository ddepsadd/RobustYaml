package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.inspection.RobustValidation
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
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

    /**
     * The scalar a value stands for: itself, or, for an alias, the one its anchor marks. The engine
     * resolves aliases while parsing the document (`DataNodeParser.ResolveAliases`), so by the time a
     * prototype is read there is no alias left — `id: *BackgammonBoard` is the text written next to
     * `&BackgammonBoard` and nothing downstream can tell the difference.
     *
     * `YAMLKeyValue.getValueText` is no help here: it answers with an empty string for anything that
     * is neither a scalar nor a compound value, and an alias is neither.
     *
     * The anchor is looked up rather than taken from the stock [org.jetbrains.yaml.psi.YAMLAlias]
     * reference, which resolves through a cached value and needs an `InjectedLanguageManager` a
     * parsing test has not got. Doing it here also states the engine's rule instead of the spec's:
     * an anchor is searched for across the whole file, so an alias standing above it still resolves
     * — `DataNodeParser` leaves a placeholder for exactly that case and fills it in afterwards.
     */
    fun resolvedScalar(value: YAMLValue?): YAMLScalar? =
        when (value) {
            is YAMLScalar -> value
            is YAMLAlias -> value.containingFile?.let { anchoredScalars(it)[value.aliasName] }
            else -> null
        }

    fun resolvedText(value: YAMLValue?): String? = resolvedScalar(value)?.textValue

    /**
     * Single values marked by an anchor, by name. Handed out whole rather than searched per alias:
     * one file of the content carries 218 of them, and a walk of the tree for each would be paid for
     * over and over. A duplicate anchor is a load-time exception for the engine, so which of the two
     * wins is of no consequence; the first is kept, as a search would have found it.
     */
    fun anchoredScalars(file: PsiFile): Map<String, YAMLScalar> {
        val anchored = mutableMapOf<String, YAMLScalar>()
        for (anchor in PsiTreeUtil.findChildrenOfType(file, YAMLAnchor::class.java)) {
            val name = anchor.name ?: continue
            val value = anchor.markedValue as? YAMLScalar ?: continue
            anchored.putIfAbsent(name, value)
        }
        return anchored
    }

    /**
     * Ids a declaration inherits from. `parent` is written three ways — a scalar, an inline list and
     * a block list under the key — and all three mean the same to the engine, which reads the field
     * as `string[]`.
     */
    fun parentIds(mapping: YAMLMapping): List<String> {
        val parents = mutableListOf<String>()
        when (val value = mapping.getKeyValueByKey(PARENT_KEY)?.value) {
            is YAMLSequence -> value.items.mapNotNullTo(parents) { resolvedText(it.value) }
            else -> resolvedText(value)?.let { parents += it }
        }
        return parents.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private const val PARENT_KEY = "parent"

    private val VALIDATED_ID_KEYS = setOf("parent", "proto", "prototype", "entity")

    fun isValidatedPrototypeIdReference(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        return keyValue.keyText in VALIDATED_ID_KEYS
    }

    /** A key that names a prototype, whatever the value looks like. */
    fun isPrototypeIdKey(name: String): Boolean = name in PROTOTYPE_ID_KEYS

    fun isPrototypeId(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        return keyValue.keyText in PROTOTYPE_ID_KEYS
    }

    fun isPrototypeIdReference(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        if (keyValue.keyText !in PROTOTYPE_ID_KEYS) return false
        return !isPrototypeIdDeclaration(keyValue)
    }

    /**
     * A value that names a message. The type of the field says so first, and when it does not the
     * index does: `AntagPrototype.Name` is declared `string`, the content calls `Loc.GetString` on it
     * at the call site, and by the type alone `name: roles-antag-heretic-name` is not a reference at
     * all — Ctrl+click did nothing and Alt+F7 answered with the two declarations and no usage. Of the
     * messages named from prototypes 11224 are reached this way against 2668 through a typed `LocId`.
     *
     * The same rule the hover already uses, and safe for the same reason: a dash is required and the
     * text has to be a message somebody declares, which no prototype id and no component name is.
     * Guessing wrong costs a soft reference nobody follows; the validator is not widened with it —
     * warning about an unknown id where the field is a plain `string` would light up half the content.
     */
    fun isLocalizationValue(scalar: YAMLScalar): Boolean {
        val keyValue = owningKey(scalar) ?: return false
        if (RobustValidation.field(keyValue)?.localized == true) return true

        val id = RobustLocalization.messageId(scalar.textValue)
        if ('-' !in id || !RobustLocalization.looksLikeMessageId(id)) return false
        return RobustLocalization.hasMessage(scalar.project, id)
    }

    fun owningKey(scalar: YAMLScalar): YAMLKeyValue? =
        when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent.takeIf { it.value === scalar }
            is YAMLSequenceItem -> PsiTreeUtil.getParentOfType(parent, YAMLKeyValue::class.java, true)
            else -> null
        }

    /**
     * The key whose value is the mapping this key lies in, and null when the mapping is not written
     * under a key — the declaration itself is an item of the document's sequence, and its own keys
     * belong to nobody. What it is for: under a datafield declared a dictionary the names of the
     * keys are the author's, so the only thing that knows what stands there is the key above them.
     */
    fun mappingOwner(keyValue: YAMLKeyValue): YAMLKeyValue? =
        keyValue.parentMapping?.parent as? YAMLKeyValue

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
