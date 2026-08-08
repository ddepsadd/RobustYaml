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

    data class IdProblem(
        val element: YAMLScalar,
        val message: String,
        val suggestions: List<String>,
        val critical: Boolean,
    )

    data class EnumProblem(val element: YAMLScalar, val message: String, val suggestions: List<String>)

    data class ScalarProblem(val element: YAMLScalar, val message: String)

    private enum class Numeric(val label: String) {
        BOOL("a boolean"),
        INTEGER("an integer"),
        DECIMAL("a number"),
    }

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

        val critical = keyValue.keyText == PARENT_KEY
        val problems = mutableListOf<IdProblem>()
        when (val value = keyValue.value) {
            is YAMLMapping ->
                for (entry in value.keyValues) {
                    val key = entry.key as? YAMLScalar ?: continue
                    check(project, field.keyPrototypeKind, key, entry.keyText, critical)?.let { problems += it }
                }
            is YAMLSequence ->
                for (item in value.items) {
                    val scalar = item.value as? YAMLScalar ?: continue
                    check(project, field.prototypeKind, scalar, scalar.textValue, critical)?.let { problems += it }
                }
            is YAMLScalar ->
                check(project, field.prototypeKind, value, value.textValue, critical)?.let { problems += it }
            else -> {}
        }
        return problems
    }

    fun enumValues(keyValue: YAMLKeyValue): List<EnumProblem> {
        val field = typedField(keyValue) ?: return emptyList()
        if (field.values.isEmpty() && field.keyValues.isEmpty()) return emptyList()

        val problems = mutableListOf<EnumProblem>()
        when (val value = keyValue.value) {
            is YAMLMapping ->
                for (entry in value.keyValues) {
                    val key = entry.key as? YAMLScalar ?: continue
                    checkEnum(field.keyValues, key, entry.keyText)?.let { problems += it }
                }
            is YAMLSequence ->
                for (item in value.items) {
                    val scalar = item.value as? YAMLScalar ?: continue
                    checkEnum(field.values, scalar, scalar.textValue)?.let { problems += it }
                }
            is YAMLScalar ->
                checkEnum(field.values, value, value.textValue)?.let { problems += it }
            else -> {}
        }
        return problems
    }

    fun scalarValues(keyValue: YAMLKeyValue): List<ScalarProblem> {
        val field = typedField(keyValue) ?: return emptyList()
        val kind = PRIMITIVES[field.type.removeSuffix("?")] ?: return emptyList()

        return when (val value = keyValue.value) {
            is YAMLSequence ->
                value.items.mapNotNull { item -> (item.value as? YAMLScalar)?.let { checkScalar(kind, it) } }
            is YAMLScalar -> listOfNotNull(checkScalar(kind, value))
            else -> emptyList()
        }
    }

    private fun checkScalar(kind: Numeric, element: YAMLScalar): ScalarProblem? {
        val raw = element.textValue.trim()
        if (raw.isEmpty()) return ScalarProblem(element, "Empty value where ${kind.label} is expected")
        if (raw.first() in NON_VALUES) return null

        val valid = when (kind) {
            Numeric.BOOL -> raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true)
            Numeric.INTEGER -> raw.toLongOrNull() != null || raw.toULongOrNull() != null
            Numeric.DECIMAL -> raw.replace(",", "").toDoubleOrNull() != null
        }
        if (!valid) return ScalarProblem(element, "'$raw' is not ${kind.label}")

        if (kind == Numeric.DECIMAL && raw.contains(',')) {
            val parsed = raw.replace(",", "").toDoubleOrNull()
            return ScalarProblem(
                element,
                "'$raw' is read as $parsed: a comma separates thousands here, not decimals",
            )
        }
        return null
    }

    private fun checkEnum(values: List<String>, element: YAMLScalar, raw: String): EnumProblem? {
        if (values.isEmpty()) return null

        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { !looksLikeEnumValue(it) }) return null

        val unknown = parts.filter { part -> values.none { it.equals(part, ignoreCase = true) } }
        if (unknown.isEmpty()) return null

        val name = unknown.first()
        val expected =
            if (values.size <= LISTED_VALUES) ", expected one of: ${values.joinToString()}" else ""
        return EnumProblem(
            element,
            "Unknown enum value '$name'$expected",
            ChangeEnumValueFix.suggest(name, values),
        )
    }

    private fun looksLikeEnumValue(text: String): Boolean {
        if (text in NON_IDS) return false
        if (text.toLongOrNull() != null) return false
        return text.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun check(
        project: Project,
        kind: String?,
        element: YAMLScalar,
        raw: String,
        critical: Boolean,
    ): IdProblem? {
        if (kind == null) return null

        val id = raw.trim()
        if (!looksLikeId(id)) return null

        val kinds = RobustPrototypeIndex.sites(project, id).mapTo(mutableSetOf()) { it.kind }
        if (kind in kinds) return null

        val renamed = RobustMigrations.renamedTo(project, id)
        val message = when {
            renamed != null -> "'$id' was renamed to '$renamed' by migration.yml"
            RobustMigrations.isRemoved(project, id) -> "'$id' was removed by migration.yml"
            kinds.isEmpty() -> "Unknown $kind prototype '$id'"
            else -> "'$id' is ${kinds.sorted().joinToString("', '", "'", "'")}, expected '$kind'"
        }
        val suggestions =
            (listOfNotNull(renamed) + ChangePrototypeIdFix.suggest(project, id, kind)).distinct()
        return IdProblem(element, message, suggestions, critical)
    }

    fun isParentReference(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)?.keyText == PARENT_KEY

    fun expectedKind(scalar: YAMLScalar): String? {
        val parent = scalar.parent
        if (parent is YAMLKeyValue && parent.key === scalar) {
            val owner = PsiTreeUtil.getParentOfType(parent, YAMLKeyValue::class.java, true) ?: return null
            return typedField(owner)?.keyPrototypeKind
        }
        val owner = RobustYamlContext.owningKey(scalar) ?: return null
        return typedField(owner)?.prototypeKind
    }

    fun field(keyValue: YAMLKeyValue): RobustDataField? = typedField(keyValue)

    private fun typedField(keyValue: YAMLKeyValue): RobustDataField? {
        val declaration = RobustYamlContext.declarationAround(keyValue) ?: return null
        val path = RobustYamlContext.pathTo(declaration, keyValue) ?: return null
        return fieldAt(keyValue.project, declaration, path, keyValue.keyText)
    }

    fun keyKindAt(element: PsiElement): String? = fieldAround(element)?.keyPrototypeKind

    fun fieldAround(element: PsiElement): RobustDataField? {
        val declaration = RobustYamlContext.declarationAround(element) ?: return null
        val path = RobustYamlContext.pathAt(declaration, element) ?: return null
        if (path.isEmpty()) return null
        return fieldAt(element.project, declaration, path.dropLast(1), path.last())
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

    fun unknownPrototypeId(scalar: YAMLScalar): IdProblem? {
        if (!RobustYamlContext.isValidatedPrototypeIdReference(scalar)) return null

        val owner = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false)
        if (owner != null && typedField(owner)?.prototypeKind != null) return null

        val id = scalar.textValue.trim()
        if (!looksLikeId(id)) return null

        val project = scalar.project
        if (!RobustPrototypeIndex.hasAnyId(project)) return null
        if (RobustPrototypeIndex.isKnownId(project, id)) return null

        val renamed = RobustMigrations.renamedTo(project, id)
        val message = when {
            renamed != null -> "'$id' was renamed to '$renamed' by migration.yml"
            RobustMigrations.isRemoved(project, id) -> "'$id' was removed by migration.yml"
            else -> "Unknown prototype id '$id'"
        }
        return IdProblem(scalar, message, listOfNotNull(renamed), isParentReference(scalar))
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
    private const val PARENT_KEY = "parent"

    private val NON_VALUES = setOf('!', '*', '&')

    private val PRIMITIVES = mapOf(
        "bool" to Numeric.BOOL,
        "byte" to Numeric.INTEGER,
        "sbyte" to Numeric.INTEGER,
        "short" to Numeric.INTEGER,
        "ushort" to Numeric.INTEGER,
        "int" to Numeric.INTEGER,
        "uint" to Numeric.INTEGER,
        "long" to Numeric.INTEGER,
        "ulong" to Numeric.INTEGER,
        "float" to Numeric.DECIMAL,
        "double" to Numeric.DECIMAL,
        "decimal" to Numeric.DECIMAL,
    )
    private const val LISTED_VALUES = 8
    private val NON_IDS = setOf("null", "true", "false")
}
