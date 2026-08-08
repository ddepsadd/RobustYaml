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

    data class ScalarProblem(
        val element: YAMLScalar,
        val message: String,
        val suggestions: List<String> = emptyList(),
    )

    private enum class ValueKind(
        val label: String,
        val hint: String = "",
        val arity: Int = 0,
        val integral: Boolean = false,
        val thousands: Boolean = false,
    ) {
        BOOL("a boolean"),
        INTEGER("an integer"),
        DECIMAL("a number", thousands = true),
        EXACT_DECIMAL("a number"),
        TIMESPAN("a time span", ": use seconds ('3', '1.5') or a suffix ('30s', '2m', '1.5h')"),
        COLOR("a color", ": use a hex code ('#4a90d9') or a name ('red')"),
        ANGLE("an angle", ": use degrees ('90') or radians ('1.57rad')", thousands = true),
        VECTOR2("a vector", ": use '1.5,2' or '1.5x2'", arity = 2),
        VECTOR2I("a vector", ": use '1,2' or '1x2'", arity = 2, integral = true),
        VECTOR3("a vector", ": use '1,2,3'", arity = 3),
        VECTOR4("a vector", ": use '1,2,3,4'", arity = 4),
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

    fun localizationIds(keyValue: YAMLKeyValue): List<ScalarProblem> {
        val field = typedField(keyValue) ?: return emptyList()
        if (!field.localized) return emptyList()

        val project = keyValue.project
        if (!RobustLocalization.hasAnyMessage(project)) return emptyList()

        return when (val value = keyValue.value) {
            is YAMLSequence ->
                value.items.mapNotNull { item ->
                    (item.value as? YAMLScalar)?.let { checkMessage(project, it) }
                }
            is YAMLScalar -> listOfNotNull(checkMessage(project, value))
            else -> emptyList()
        }
    }

    private fun checkMessage(project: Project, element: YAMLScalar): ScalarProblem? {
        val raw = element.textValue.trim()
        if (raw.isEmpty() || raw.first() in NON_VALUES) return null

        val id = RobustLocalization.messageId(raw)
        if (!RobustLocalization.looksLikeMessageId(id)) return null
        if (RobustLocalization.hasMessage(project, id)) return null

        return ScalarProblem(
            element,
            "No localization message with id '$id'",
            ChangeLocalizationIdFix.suggest(project, id),
        )
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
        if (field.customSerializer) return emptyList()
        val kind = PRIMITIVES[field.type.removeSuffix("?")] ?: return emptyList()

        return when (val value = keyValue.value) {
            is YAMLSequence ->
                value.items.mapNotNull { item -> (item.value as? YAMLScalar)?.let { checkScalar(kind, it) } }
            is YAMLScalar -> listOfNotNull(checkScalar(kind, value))
            else -> emptyList()
        }
    }

    private fun checkScalar(kind: ValueKind, element: YAMLScalar): ScalarProblem? {
        val raw = element.textValue.trim()
        if (raw.isEmpty()) return ScalarProblem(element, "Empty value where ${kind.label} is expected")
        if (raw.first() in NON_VALUES) return null

        if (!isValid(kind, raw)) {
            val hint =
                if (kind == ValueKind.EXACT_DECIMAL && raw.contains(',')) COMMA_HINT else kind.hint
            return ScalarProblem(element, "'$raw' is not ${kind.label}$hint")
        }

        val number = if (kind == ValueKind.ANGLE) degrees(raw) else raw
        if (kind.thousands && number.contains(',')) {
            val parsed = number.replace(",", "").toDoubleOrNull()
            return ScalarProblem(
                element,
                "'$raw' is read as $parsed: a comma separates thousands here, not decimals",
            )
        }
        return null
    }

    fun isColorField(field: RobustDataField): Boolean =
        !field.customSerializer && PRIMITIVES[field.type.removeSuffix("?")] == ValueKind.COLOR

    fun colorNames(): List<String> = NAMED_COLORS

    fun accepts(type: String, raw: String): Boolean? {
        val kind = PRIMITIVES[type.removeSuffix("?")] ?: return null
        return isValid(kind, raw.trim())
    }

    private fun isValid(kind: ValueKind, raw: String): Boolean = when (kind) {
        ValueKind.BOOL -> raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true)
        ValueKind.INTEGER -> INTEGER_FORM.matches(raw)
        ValueKind.DECIMAL -> DECIMAL_FORM.matches(raw.replace(",", ""))
        ValueKind.EXACT_DECIMAL -> raw == MAX_VALUE || DECIMAL_FORM.matches(raw)
        ValueKind.TIMESPAN -> isTimeSpan(raw)
        ValueKind.COLOR -> isColor(raw)
        ValueKind.ANGLE -> DECIMAL_FORM.matches(degrees(raw).replace(",", ""))
        else -> isVector(kind, raw)
    }

    private fun isColor(raw: String): Boolean {
        if (raw.first() != '#') return raw.lowercase() in NAMED_COLOR_SET
        val digits = raw.substring(1)
        return digits.length in HEX_DIGITS && digits.all { Character.digit(it, 16) >= 0 }
    }

    private fun isVector(kind: ValueKind, raw: String): Boolean {
        val parts = VECTOR_SEPARATORS
            .map { raw.split(it) }
            .firstOrNull { it.size == kind.arity }
            ?: return false
        val form = if (kind.integral) INTEGER_FORM else DECIMAL_FORM
        return parts.all { form.matches(it.trim()) }
    }

    private fun degrees(raw: String): String =
        if (raw.endsWith(RADIANS)) raw.dropLast(RADIANS.length).trim() else raw

    private fun isTimeSpan(raw: String): Boolean {
        if (raw.any { it == ',' || it == ' ' || it == ':' }) return false
        if (DECIMAL_FORM.matches(raw)) return true
        if (raw.length <= 1 || raw.last().lowercaseChar() !in TIME_UNITS) return false
        return DECIMAL_FORM.matches(raw.dropLast(1))
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
        "bool" to ValueKind.BOOL,
        "byte" to ValueKind.INTEGER,
        "sbyte" to ValueKind.INTEGER,
        "short" to ValueKind.INTEGER,
        "ushort" to ValueKind.INTEGER,
        "int" to ValueKind.INTEGER,
        "uint" to ValueKind.INTEGER,
        "long" to ValueKind.INTEGER,
        "ulong" to ValueKind.INTEGER,
        "float" to ValueKind.DECIMAL,
        "double" to ValueKind.DECIMAL,
        "decimal" to ValueKind.DECIMAL,
        "TimeSpan" to ValueKind.TIMESPAN,
        "FixedPoint2" to ValueKind.EXACT_DECIMAL,
        "Color" to ValueKind.COLOR,
        "Angle" to ValueKind.ANGLE,
        "Vector2" to ValueKind.VECTOR2,
        "Vector2i" to ValueKind.VECTOR2I,
        "Vector3" to ValueKind.VECTOR3,
        "Vector4" to ValueKind.VECTOR4,
    )

    private const val MAX_VALUE = "MaxValue"
    private const val RADIANS = "rad"
    private const val COMMA_HINT = ": a comma is not a decimal separator here"
    private val HEX_DIGITS = setOf(3, 4, 6, 8)
    private val VECTOR_SEPARATORS = listOf(',', 'x')
    private val TIME_UNITS = setOf('s', 'm', 'h')
    private val INTEGER_FORM = Regex("""[+-]?\d+""")
    private val DECIMAL_FORM = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")
    private const val LISTED_VALUES = 8
    private val NON_IDS = setOf("null", "true", "false")

    private val NAMED_COLORS: List<String> = (
        "transparent aliceblue antiquewhite aqua aquamarine azure beige betterviolet bisque black " +
            "blanchedalmond blue blueviolet brown burlywood cadetblue chartreuse chocolate coral " +
            "cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen " +
            "darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen " +
            "darkslateblue darkslategray darkturquoise darkviolet deeppink deepskyblue dimgray dodgerblue " +
            "firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green " +
            "greenyellow honeydew hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen " +
            "lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow lightgreen lightgray " +
            "lightpink lightsalmon lightseagreen lightskyblue lightslategray lightsteelblue lightyellow " +
            "lime limegreen linen magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple " +
            "mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred " +
            "midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange " +
            "orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff " +
            "peru pink plum powderblue purple red rosybrown royalblue ruber saddlebrown salmon " +
            "sandybrown seablue seagreen seashell sienna silver skyblue slateblue slategray snow " +
            "springgreen steelblue tan teal thistle tomato turquoise violet vividgamboge wheat white " +
            "whitesmoke yellow yellowgreen"
        ).split(' ')

    private val NAMED_COLOR_SET = NAMED_COLORS.toSet()
}
