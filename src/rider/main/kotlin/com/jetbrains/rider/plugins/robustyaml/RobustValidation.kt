package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rd.ide.model.RobustDataField
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

object RobustValidation {
    data class UnknownField(val message: String, val suggestions: List<String>)

    data class IdProblem(val element: YAMLScalar, val message: String, val suggestions: List<String>)

    fun unknownField(keyValue: YAMLKeyValue): UnknownField? {
        val name = keyValue.keyText
        if (name.isEmpty() || name == TYPE_KEY || !name.all { it.isLetterOrDigit() || it == '_' }) return null

        val declaration = RobustYamlContext.declarationAround(keyValue) ?: return null
        if (declaration.mapping !== keyValue.parentMapping) return null

        val project = keyValue.project
        val fields =
            if (declaration.isComponent) RobustDataFields.forComponent(project, declaration.name)
            else RobustDataFields.forPrototype(project, declaration.name)
        if (fields.isEmpty() || name in fields) return null

        val owner =
            if (declaration.isComponent) "component '${declaration.name}'"
            else "prototype '${declaration.name}'"
        return UnknownField("Unknown field '$name' in $owner", ChangeFieldNameFix.suggest(name, fields))
    }

    fun prototypeIdValues(keyValue: YAMLKeyValue): List<IdProblem> {
        val field = typedField(keyValue) ?: return emptyList()
        if (field.prototypeKind == null && field.keyPrototypeKind == null) return emptyList()

        val project = keyValue.project
        if (!RobustPrototypeIndex.hasAnyId(project)) return emptyList()

        val problems = mutableListOf<IdProblem>()
        when (val value = keyValue.value) {
            is YAMLMapping ->
                for (entry in value.keyValues) {
                    val key = entry.key as? YAMLScalar ?: continue
                    check(project, field.keyPrototypeKind, key, entry.keyText)?.let { problems += it }
                }
            is YAMLSequence ->
                for (item in value.items) {
                    val scalar = item.value as? YAMLScalar ?: continue
                    check(project, field.prototypeKind, scalar, scalar.textValue)?.let { problems += it }
                }
            is YAMLScalar ->
                check(project, field.prototypeKind, value, value.textValue)?.let { problems += it }
            else -> {}
        }
        return problems
    }

    private fun check(project: Project, kind: String?, element: YAMLScalar, raw: String): IdProblem? {
        if (kind == null) return null

        val id = raw.trim()
        if (!looksLikeId(id)) return null

        val kinds = RobustPrototypeIndex.sites(project, id).mapTo(mutableSetOf()) { it.kind }
        if (kind in kinds) return null

        val message =
            if (kinds.isEmpty()) "Unknown $kind prototype '$id'"
            else "'$id' is ${kinds.sorted().joinToString("', '", "'", "'")}, expected '$kind'"
        return IdProblem(element, message, ChangePrototypeIdFix.suggest(project, id, kind))
    }

    fun expectedKind(scalar: YAMLScalar): String? {
        val parent = scalar.parent
        if (parent is YAMLKeyValue && parent.key === scalar) {
            val owner = PsiTreeUtil.getParentOfType(parent, YAMLKeyValue::class.java, true) ?: return null
            return typedField(owner)?.keyPrototypeKind
        }
        val owner = RobustYamlContext.owningKey(scalar) ?: return null
        return typedField(owner)?.prototypeKind
    }

    private fun typedField(keyValue: YAMLKeyValue): RobustDataField? {
        val declaration = RobustYamlContext.declarationAround(keyValue) ?: return null
        val path = RobustYamlContext.pathTo(declaration, keyValue) ?: return null
        return fieldAt(keyValue.project, declaration, path, keyValue.keyText)
    }

    fun keyKindAt(element: PsiElement): String? {
        val declaration = RobustYamlContext.declarationAround(element) ?: return null
        val path = RobustYamlContext.pathAt(declaration, element) ?: return null
        if (path.isEmpty()) return null
        return fieldAt(element.project, declaration, path.dropLast(1), path.last())?.keyPrototypeKind
    }

    fun fieldAt(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        path: List<String>,
        name: String,
    ): RobustDataField? {
        val root = RobustDataFields.rootClass(project, declaration) ?: return null
        return RobustBackend.getInstance(project).cachedField(root, path, name)
    }

    fun unknownPrototypeId(scalar: YAMLScalar): String? {
        if (!RobustYamlContext.isValidatedPrototypeIdReference(scalar)) return null

        val owner = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false)
        if (owner != null && typedField(owner)?.prototypeKind != null) return null

        val id = scalar.textValue.trim()
        if (!looksLikeId(id)) return null

        val project = scalar.project
        if (!RobustPrototypeIndex.hasAnyId(project)) return null
        if (RobustPrototypeIndex.isKnownId(project, id)) return null
        return "Unknown prototype id '$id'"
    }

    fun duplicateId(keyValue: YAMLKeyValue): String? {
        if (!RobustYamlContext.isPrototypeIdDeclaration(keyValue)) return null

        val id = (keyValue.value as? YAMLScalar)?.textValue?.trim() ?: return null
        if (id.isEmpty()) return null

        val kind = kindOf(keyValue) ?: return null
        val same = RobustPrototypeIndex.sites(keyValue.project, id).filter { it.kind == kind }
        if (same.size < 2) return null

        val file = keyValue.containingFile?.virtualFile
        val elsewhere = same.count { it.file != file }
        val where = if (elsewhere > 0) "${same.size} declarations" else "${same.size} declarations in this file"
        return "Duplicate id '$id' for prototype kind '$kind': $where"
    }

    private fun kindOf(keyValue: YAMLKeyValue): String? {
        val mapping = PsiTreeUtil.getParentOfType(keyValue, YAMLMapping::class.java, true) ?: return null
        return (mapping.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
    }

    private fun looksLikeId(id: String): Boolean {
        if (id.isEmpty() || id in NON_IDS) return false
        if (id.first() == '!' || id.first() == '*' || id.first() == '&') return false
        if (id.first().isDigit() || id.toDoubleOrNull() != null) return false
        return id.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    private const val TYPE_KEY = "type"
    private val NON_IDS = setOf("null", "true", "false")
}
