package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

object RobustValidation {
    data class UnknownField(val message: String, val suggestions: List<String>)

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

    fun unknownPrototypeId(scalar: YAMLScalar): String? {
        if (!RobustYamlContext.isValidatedPrototypeIdReference(scalar)) return null

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
