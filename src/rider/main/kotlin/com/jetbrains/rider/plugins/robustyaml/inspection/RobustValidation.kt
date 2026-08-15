package com.jetbrains.rider.plugins.robustyaml.inspection

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.text.EditDistance
import com.jetbrains.rd.ide.model.RobustDataField
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.backend.RobustBackend
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustDataFields
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustGuidebook
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustMigrations
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangeEnumValueFix
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangeFieldNameFix
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangeLocalizationIdFix
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangePrototypeIdFix
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

private val logger = logger<RobustValidation>()

object RobustValidation {
    data class UnknownField(val message: String, val suggestions: List<String>)

    /**
     * The element is not always a scalar: a guidebook embed names its prototype from an XML
     * attribute, and the check behind the message is the same one either way.
     */
    data class IdProblem(
        val element: PsiElement,
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

    /** Like [ScalarProblem], but the offender may be a key of a mapping rather than a value. */
    data class MessageProblem(
        val element: PsiElement,
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

    /**
     * Dead message ids recognised by their neighbours instead of by their type. `wordReplacements` is
     * declared `Dictionary<string, string>`, and that both sides of it name messages is known only to
     * the system that reads the field — `ReplacementAccentSystem` hands each of them to
     * `Loc.GetString`. Tracing that back from a `[DataField]` is dataflow analysis; locality answers
     * the same question for nothing. Where a mapping holds a crowd of message ids and all but one are
     * declared, the odd one out is a typo rather than a value of some other kind.
     *
     * Two declared neighbours and half the mapping are enough, and the thresholds are not what does
     * the work: the whole prototype tree of ss14-wega yields 24 findings in 7 files, and the answer
     * stops moving well below the loosest setting tried. What does the work is the two exclusions —
     * a key that means an id, and a value that names a prototype. Without the second one
     * `flavor: raw-egg` reads as a dead message, because `Flavors/flavors.yml` spells reagent
     * flavours in the same kebab case.
     *
     * Seven of the 24 are reached by nothing else: `JobPrototype.Description` is a plain `string?`,
     * the same trap as `AntagPrototype.Name`, so the typed check never looks at it.
     */
    fun siblingLocalizationIds(mapping: YAMLMapping): List<MessageProblem> {
        val project = mapping.project
        if (!RobustLocalization.hasAnyMessage(project)) return emptyList()

        val elements = mutableListOf<PsiElement>()
        val entries = mutableListOf<String>()
        for (keyValue in mapping.keyValues) {
            if (RobustYamlContext.isPrototypeIdKey(keyValue.keyText)) continue

            val sides = mutableListOf<Pair<PsiElement, String>>()
            keyValue.key?.let { sides += it to keyValue.keyText }
            (keyValue.value as? YAMLScalar)?.let { sides += it to it.textValue }

            // A field the backend typed `LocId` is already checked by `localizationIds`; counting it
            // here as well would put two warnings on one value.
            if (sides.isEmpty() || typedField(keyValue)?.localized == true) continue

            elements += sides.map { it.first }
            entries += sides.map { it.second }
        }

        val dead = deadSiblings(
            entries,
            { RobustLocalization.declaresMessage(project, it) },
            { RobustPrototypeIndex.declaresId(project, it) },
        )
        return dead.map { at ->
            val id = RobustLocalization.messageId(entries[at].trim())
            MessageProblem(
                elements[at],
                "No localization message with id '$id'",
                ChangeLocalizationIdFix.suggest(project, id),
            )
        }
    }

    /**
     * Positions in [entries] that name a message nobody declares, judged against the rest of the
     * list. Kept free of PSI so a measurement can run the shipped rule over the whole content rather
     * than a retelling of it — the same reason [accepts] was pulled out of the scalar check.
     */
    fun deadSiblings(
        entries: List<String>,
        declaresMessage: (String) -> Boolean,
        declaresPrototype: (String) -> Boolean,
    ): List<Int> {
        val ids = HashMap<Int, String>()
        for ((at, entry) in entries.withIndex()) {
            val id = RobustLocalization.messageId(entry.trim())
            if (id.isEmpty() || id.first() in NON_VALUES) continue
            if (!RobustLocalization.looksLikeStandaloneMessageId(id)) continue
            // Whatever it is spelled like, a value naming a prototype is not a message:
            // `flavor: raw-egg` points at `Flavors/flavors.yml`.
            if (declaresPrototype(id)) continue
            ids[at] = id
        }
        if (ids.isEmpty()) return emptyList()

        val declared = ids.count { declaresMessage(it.value) }
        if (declared < MIN_DECLARED_SIBLINGS || declared * 2 < ids.size) return emptyList()

        return ids.filterNot { declaresMessage(it.value) }.keys.sorted()
    }

    private const val MIN_DECLARED_SIBLINGS = 2

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
        val kind = scalarKind(field.type) ?: return emptyList()

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
        !field.customSerializer && scalarKind(field.type) == ValueKind.COLOR

    fun colorNames(): List<String> = NAMED_COLORS

    fun accepts(type: String, raw: String): Boolean? {
        val kind = scalarKind(type) ?: return null
        return isValid(kind, raw.trim())
    }

    /**
     * A sequence carries the rules of its element type: `vertices` is `Vector2[]`, and every item of
     * it is read by the very serializer a bare `Vector2` would use. Anything with more than one type
     * argument is left alone — a dictionary is a mapping in YAML, not a list of scalars.
     */
    private fun scalarKind(type: String): ValueKind? {
        val declared = type.removeSuffix("?").trim()
        PRIMITIVES[declared]?.let { return it }
        if (declared.endsWith("[]")) return scalarKind(declared.dropLast(2))

        val argument = GENERIC.matchEntire(declared)?.groupValues?.get(1) ?: return null
        if (argument.contains(',')) return null
        return scalarKind(argument)
    }

    private val GENERIC = Regex("""[\w.]+<(.+)>""")

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
        element: PsiElement,
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
            (listOfNotNull(reachable(project, renamed, kind)) +
                ChangePrototypeIdFix.suggest(project, id, kind)).distinct()
        return IdProblem(element, message, suggestions, critical)
    }

    /**
     * The prototype a guidebook embed names. Nothing checks those ids while the game loads, and the
     * page is what breaks: `GuideEntityEmbed.TryParseTag` spawns the entity outright, and the reagent
     * and discipline embeds give up on the tag. One embed of ss14-wega is dead this way —
     * `MachineTraversalDistorter`, removed by `migration.yml` and left in `TraversalDistorter.xml`.
     */
    fun guidebookPrototypeId(value: XmlAttributeValue): IdProblem? {
        val kind = RobustGuidebook.kindOf(value) ?: return null
        val project = value.project
        // An id is only unknown once the index has something to say; during the first scan of a
        // checkout every embed would look dead.
        if (!RobustPrototypeIndex.hasAnyId(project)) return null
        return check(project, kind, value, value.value, critical = true)
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
        val origin = RobustYamlContext.originTo(declaration, keyValue) ?: return null
        return fieldAt(keyValue.project, declaration, origin, keyValue.keyText)
    }

    fun keyKindAt(element: PsiElement): String? = fieldAround(element)?.keyPrototypeKind

    fun fieldAround(element: PsiElement): RobustDataField? {
        val declaration = RobustYamlContext.declarationAround(element) ?: return null
        val origin = RobustYamlContext.originAt(declaration, element) ?: return null
        if (origin.path.isEmpty()) return null
        return fieldAt(element.project, declaration, origin.parent(), origin.path.last())
    }

    /**
     * Classes the `!type:` tag on [carrier] is allowed to name. The carrier is either a key — the
     * tag then belongs to the value of that key — or a sequence item, which contributes no segment
     * of its own, so the field is already the last one on the path.
     */
    fun tagTypes(carrier: PsiElement): List<String>? {
        val declaration = RobustYamlContext.declarationAround(carrier) ?: return null
        val origin = RobustYamlContext.originAt(declaration, carrier) ?: return null

        val project = carrier.project
        val root = origin.root ?: RobustDataFields.rootClass(project, declaration) ?: return null
        val path = if (carrier is YAMLKeyValue) origin.path + carrier.keyText else origin.path
        if (path.isEmpty()) return null

        return RobustBackend.getInstance(project).cachedImplementations(root, path)
    }

    /** The field a tag carrier stands for: the key itself, or the field the sequence belongs to. */
    fun fieldOf(carrier: PsiElement): RobustDataField? {
        val declaration = RobustYamlContext.declarationAround(carrier) ?: return null
        val origin = RobustYamlContext.originAt(declaration, carrier) ?: return null

        return when {
            carrier is YAMLKeyValue -> fieldAt(carrier.project, declaration, origin, carrier.keyText)
            origin.path.isEmpty() -> null
            else -> fieldAt(carrier.project, declaration, origin.parent(), origin.path.last())
        }
    }

    fun unknownTag(carrier: PsiElement): TagProblem? {
        val tag = RobustYamlContext.taggedType(carrier) ?: return null
        if (tag in LOOSE_TYPES) return null

        val allowed = tagTypes(carrier) ?: return null
        logger.debug { "Tag '$tag': ${allowed.size} allowed, known ${tag in allowed}" }
        if (allowed.isEmpty() || tag in allowed) return null

        return TagProblem(
            "Unknown type '$tag' here",
            allowed.filter { EditDistance.levenshtein(tag, it, false) <= TAG_EDITS }.sorted(),
        )
    }

    data class TagProblem(val message: String, val suggestions: List<String>)

    /**
     * `ReflectionManager.TryLooseGetType` answers these six before it ever looks through the
     * assemblies, so they name primitives that no class declares — `Bool` is `System.Boolean`.
     * Searching for an inheritor of them finds nothing, which would paint 157 valid values red.
     */
    private val LOOSE_TYPES = setOf("Byte", "Bool", "Double", "SByte", "Single", "String")

    private const val TAG_EDITS = 3

    fun fieldAt(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
        name: String,
    ): RobustDataField? {
        val root = origin.root ?: RobustDataFields.rootClass(project, declaration) ?: return null
        return RobustBackend.getInstance(project).cachedField(root, origin.path, name)
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
        return IdProblem(
            scalar,
            message,
            listOfNotNull(reachable(project, renamed, kind = null)),
            isParentReference(scalar),
        )
    }

    /**
     * The migration target, if it still names something. The dictionary is content and the plugin
     * never writes to it — renaming a prototype leaves every entry that pointed at it aiming at a
     * name nobody declares any more, and the entry has to stay that way: it is the record maps and
     * documentation are read against, and quietly rewriting somebody else's compatibility contract
     * is not the refactoring's business. What the refactoring must not do either is hand that dead
     * name to a quick fix, replacing one dangling id with another. `MeasureMigrations` keeps the
     * checkout itself clean of this — 0 unreachable targets — so the guard costs nothing there and
     * only speaks up after a local rename.
     */
    private fun reachable(project: Project, renamed: String?, kind: String?): String? {
        val target = renamed ?: return null
        val known = if (kind == null) {
            RobustPrototypeIndex.isKnownId(project, target)
        } else {
            RobustPrototypeIndex.sites(project, target).any { it.kind == kind }
        }
        return target.takeIf { known }
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
