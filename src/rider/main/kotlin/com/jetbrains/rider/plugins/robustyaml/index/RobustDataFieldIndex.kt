package com.jetbrains.rider.plugins.robustyaml.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

private class ClassScope(val name: String, val start: Int, val end: Int, val depth: Int)

class RobustDataFieldIndex : FileBasedIndexExtension<String, String>() {
    override fun getName(): ID<String, String> = NAME

    /**
     * Raise this whenever the shape of what is written changes, a new key included. The data live on
     * disk and are rebuilt on nothing else: the dictionary key was added while this stayed at 8, the
     * sandbox read back the index built before it, and the rule found no names at all — silently,
     * because an empty set of names is what a checkout without dictionaries would look like too.
     */
    override fun getVersion(): Int = 9

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension.equals("cs", ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { content -> index(content.contentAsText) }

    companion object {
        val NAME: ID<String, String> = ID.create("robustyaml.datafields")

        const val CLASS_KEY = "class:"
        const val COMPONENT_KEY = "component:"
        const val PROTOTYPE_KEY = "prototype:"

        /**
         * A YAML key that names a prototype by id, whatever class declares it. The kind of the
         * prototype is the backend's answer and it needs the owning type to give it; this one is
         * weaker on purpose — it says only that a datafield with this name is declared `ProtoId<T>`,
         * `EntProtoId` or through a prototype id serializer *somewhere* in the checkout. Weak is
         * what makes it usable: references have to be found in files nobody has opened, and there
         * the backend knows no types at all.
         *
         * Weak is also what makes it dangerous, and the companion key below is why both exist. A
         * name is global while the truth is per owning type: `[DataField("name")]` is a `ProtoId`
         * in one class and a plain string in a hundred others, and taking every `name:` of the
         * content for a reference would let a rename rewrite 249 values that only look alike.
         * A name is trusted only when nothing declares it otherwise, which is the difference of
         * the two key sets — asked of the index as two enumerations of keys, without reading a
         * single value.
         */
        const val PROTOTYPE_FIELD_KEY = "protoField:"

        /** A YAML key some datafield declares as something other than a prototype id. */
        const val PLAIN_FIELD_KEY = "plainField:"

        /**
         * A YAML key under which the ids are the **values of the mapping**, not the value of the key
         * itself: `[DataField] public Dictionary<string, EntProtoId> Equipment` writes
         * `equipment:` and then a slot name per line — `mask: ClothingMaskBreath` — where the key is
         * content of the author's choosing and the id stands on the right. A rule by the name of the
         * key cannot see that value at all: the name over it is `mask`, which is a datafield of
         * nothing, and one of the 110 the ambiguity test rejects anyway. Ctrl+click answered «Cannot
         * find declaration to go to» there, and rename walked past.
         *
         * Only the value side of a dictionary is recorded. `Dictionary<ProtoId<X>, int> Materials`
         * puts its ids in the keys of the mapping, and a key is not where a reference of ours can
         * live yet. Neither is this key a replacement for the one above: a name lands in both when
         * its type says both, and the two answer about different places in the YAML.
         */
        const val PROTOTYPE_VALUE_FIELD_KEY = "protoValueField:"

        private val MARKERS = listOf("class", "record", "struct")
        private const val REGISTER_MARKER = "RegisterComponent"
        private const val COMPONENT_SUFFIX = "Component"
        private const val PROTOTYPE_SUFFIX = "Prototype"

        private val CLASS =
            Regex(
                """(?:(?:public|internal|private|protected|sealed|partial|abstract|static|readonly|record|unsafe)\s+)+""" +
                    """(?:class|record|struct)\s+(\w+)\s*(?::\s*([^\{;\r\n]+))?""",
            )
        private val ATTRIBUTE =
            Regex("""[\[,]\s*(DataField|IdDataField|ParentDataField|AbstractDataField)(?:Attribute)?\s*(?:\(\s*"([^"]+)")?""")
        private val INCLUDE = Regex("""[\[,]\s*IncludeDataField(?:Attribute)?""")
        private val REQUIRED = Regex("""required\s*:\s*true""")
        private val FIELD =
            Regex("""(?:public|private|protected|internal)\s[^;{=]*?\s(\w+)\s*(?:=|;|\{)""")
        private val FIELD_TYPE =
            Regex(
                """(?:public|private|protected|internal)\s+(?:readonly\s+|required\s+|static\s+|new\s+)*""" +
                    """([\w\.]+(?:<[^;{=]*?>)?[\?\[\]]*)\s+\w+\s*(?:=|;|\{)""",
            )
        private val PROTO_ID = Regex("""\bProtoId\s*<\s*([\w.]+)""")
        private val ENT_PROTO_ID = Regex("""\bEntProtoId\b""")

        /**
         * The prototype a serializer checks is its **last** type argument — true for all seven of
         * them, `PrototypeIdDictionarySerializer<TValue, TPrototype>` included, and taking the first
         * one silently misses 26 fields.
         *
         * Where the backend matches the plain suffix `Serializer` this asks for `PrototypeId` in the
         * name, and the difference is not caution but capability: the backend goes on to demand a
         * `[Prototype]` attribute on the type it found, which is a question about another file and
         * an index cannot ask it. Without the stricter name `ConstantSerializer<DrawDepthTag>` and
         * `FlagSerializer<CollisionLayer>` come through as prototypes, and `drawdepth:` alone puts
         * 398 values of ss14-wega under a rename that must never touch them. All seven prototype id
         * serializers carry the words, the 250 uses of `TimeOffsetSerializer`, `ResPathSerializer`
         * and their like carry none.
         */
        private val SERIALIZER = Regex("""\b(\w*PrototypeId\w*Serializer)\s*<([^>]*)>""")

        /**
         * The one serializer of the seven that checks the values of a dictionary; its twin
         * `PrototypeIdDictionarySerializer` checks the keys, and the names differ by this word alone.
         */
        private const val VALUE_DICTIONARY_SERIALIZER = "PrototypeIdValueDictionarySerializer"

        private val DICTIONARY = Regex("""\b\w*Dictionary\s*<""")

        private const val ENTITY_PROTOTYPE = "EntityPrototype"

        private val PROTO_NAME = Regex("""ComponentProtoName\(\s*"([^"]+)"\s*\)""")
        private val PROTOTYPE_ATTRIBUTE =
            Regex("""[\[,]\s*Prototype(?:Attribute)?(?![\w])\s*(?:\(\s*"([^"]+)")?""")

        private const val CODE = 0
        private const val LINE_COMMENT = 1
        private const val BLOCK_COMMENT = 2
        private const val STRING = 3
        private const val CHAR = 4
        private const val VERBATIM = 5

        private val SPECIAL_NAMES = mapOf(
            "IdDataField" to "id",
            "ParentDataField" to "parent",
            "AbstractDataField" to "abstract",
        )

        fun index(text: CharSequence): Map<String, String> {
            if (MARKERS.none { text.contains(it) }) return emptyMap()

            val classes = CLASS.findAll(text).map { it.range.first to it }.toList()
            if (classes.isEmpty()) return emptyMap()

            val scopes = classScopes(text, classes)

            val fields = mutableMapOf<String, MutableSet<String>>()
            val required = mutableMapOf<String, MutableSet<String>>()
            val prototypeFields = mutableMapOf<String, String>()
            val prototypeValueFields = mutableMapOf<String, String>()
            val plainFields = mutableSetOf<String>()
            for (attribute in ATTRIBUTE.findAll(text)) {
                val owner = ownerAt(scopes, classes, attribute.range.first) ?: continue
                val name = SPECIAL_NAMES[attribute.groupValues[1]]
                    ?: attribute.groups[2]?.value
                    ?: fieldNameAfter(text, attribute.range.last)
                    ?: continue
                fields.getOrPut(owner) { mutableSetOf() } += name
                if (REQUIRED.containsMatchIn(argumentsOf(text, attribute))) {
                    required.getOrPut(owner) { mutableSetOf() } += name
                }
                val prototype = prototypeAt(text, attribute)
                if (prototype != null) prototypeFields[name] = prototype else plainFields += name
                dictionaryValueAt(text, attribute)?.let { prototypeValueFields[name] = it }
            }

            val included = mutableMapOf<String, MutableSet<String>>()
            for (include in INCLUDE.findAll(text)) {
                val owner = ownerAt(scopes, classes, include.range.first) ?: continue
                val type = fieldTypeAfter(text, include.range.last) ?: continue
                included.getOrPut(owner) { mutableSetOf() } += type
            }

            val result = mutableMapOf<String, String>()
            for ((_, match) in classes) {
                val name = className(match)
                val bases = basesOf(match) + included[name].orEmpty()
                val names = fields[name].orEmpty()
                if (bases.isEmpty() && names.isEmpty()) continue
                result[CLASS_KEY + name] = bases.joinToString(",") +
                    "|" + names.joinToString(",") +
                    "|" + required[name].orEmpty().joinToString(",")
            }

            val registered = text.contains(REGISTER_MARKER)
            for ((_, match) in classes) {
                val name = className(match)
                if (registered && name.endsWith(COMPONENT_SUFFIX)) {
                    result[COMPONENT_KEY + RobustComponentNameIndex.protoName(name)] = name
                }
            }
            for (proto in PROTO_NAME.findAll(text)) {
                val owner = classAfter(classes, proto.range.last) ?: continue
                result[COMPONENT_KEY + proto.groupValues[1]] = className(owner)
            }
            for (attribute in PROTOTYPE_ATTRIBUTE.findAll(text)) {
                val owner = classAfter(classes, attribute.range.last) ?: continue
                val name = className(owner)
                val kind = attribute.groups[1]?.value
                    ?: name.removeSuffix(PROTOTYPE_SUFFIX).replaceFirstChar { it.lowercase() }
                result[PROTOTYPE_KEY + kind] = name
            }
            for ((name, prototype) in prototypeFields) {
                result[PROTOTYPE_FIELD_KEY + name] = prototype
            }
            for ((name, prototype) in prototypeValueFields) {
                result[PROTOTYPE_VALUE_FIELD_KEY + name] = prototype
            }
            for (name in plainFields) {
                result[PLAIN_FIELD_KEY + name] = ""
            }
            return result
        }

        /**
         * The prototype class a datafield names, or null when it names none. The order is the one
         * the backend follows: a serializer overrides the type of the field — `[DataField("graph",
         * customTypeSerializer: typeof(PrototypeIdSerializer<ConstructionGraphPrototype>))] public
         * string Graph` is a plain string and the kind lives in the serializer alone — and only then
         * comes the type, where collections are transparent: `List<ProtoId<X>>` and a bare
         * `ProtoId<X>` are the same value in YAML.
         */
        private fun prototypeAt(text: CharSequence, attribute: MatchResult): String? {
            SERIALIZER.find(argumentsOf(text, attribute))?.let { serializer ->
                val last = lastArgument(serializer)
                if (last.isNotEmpty()) return last
            }
            return prototypeIn(FIELD_TYPE.find(text, attribute.range.last)?.groupValues?.get(1) ?: return null)
        }

        /**
         * The prototype class whose ids stand in the values of the mapping this datafield writes, or
         * null when it writes no such mapping. Two forms say it: the serializer that checks values
         * (`PrototypeIdValueDictionarySerializer<TValue, TPrototype>`, and its twin over keys must
         * not be mistaken for it), and a dictionary type whose **last** argument carries the id —
         * `Dictionary<string, EntProtoId>`, `Dictionary<string, List<ProtoId<X>>>` alike, because a
         * collection is transparent in YAML the same way it is for the plain rule.
         */
        private fun dictionaryValueAt(text: CharSequence, attribute: MatchResult): String? {
            SERIALIZER.find(argumentsOf(text, attribute))?.let { serializer ->
                val last = lastArgument(serializer)
                return last.takeIf { it.isNotEmpty() && serializer.groupValues[1] == VALUE_DICTIONARY_SERIALIZER }
            }
            val type = FIELD_TYPE.find(text, attribute.range.last)?.groupValues?.get(1) ?: return null
            return prototypeIn(dictionaryValue(type) ?: return null)
        }

        private fun prototypeIn(type: String): String? {
            PROTO_ID.find(type)?.let { return it.groupValues[1].substringAfterLast('.') }
            return ENTITY_PROTOTYPE.takeIf { ENT_PROTO_ID.containsMatchIn(type) }
        }

        private fun lastArgument(serializer: MatchResult): String =
            serializer.groupValues[2].substringAfterLast(',').trim().substringAfterLast('.')

        /**
         * The value type of a dictionary, read by balancing the angle brackets rather than by a
         * regex: the arguments nest (`Dictionary<string, List<ProtoId<X>>>`) and a comma inside a
         * nested one is not the comma that separates key from value.
         */
        private fun dictionaryValue(type: String): String? {
            val open = DICTIONARY.find(type)?.range?.last ?: return null
            var depth = 0
            var comma = -1
            for (index in open until type.length) {
                when (type[index]) {
                    '<' -> depth++
                    '>' -> {
                        depth--
                        if (depth == 0) {
                            return if (comma < 0) null else type.substring(comma + 1, index).trim()
                        }
                    }
                    ',' -> if (depth == 1 && comma < 0) comma = index
                }
            }
            return null
        }

        fun findField(text: CharSequence, className: String, field: String): Int? {
            val classes = CLASS.findAll(text).map { it.range.first to it }.toList()
            if (classes.isEmpty()) return null
            val scopes = classScopes(text, classes)

            for (attribute in ATTRIBUTE.findAll(text)) {
                if (ownerAt(scopes, classes, attribute.range.first) != className) continue
                val name = SPECIAL_NAMES[attribute.groupValues[1]]
                    ?: attribute.groups[2]?.value
                    ?: fieldNameAfter(text, attribute.range.last)
                    ?: continue
                if (name == field) return attribute.range.first
            }
            return null
        }

        fun parseBases(value: String): List<String> = part(value, 0)

        fun parseFields(value: String): List<String> = part(value, 1)

        fun parseRequired(value: String): List<String> = part(value, 2)

        private fun part(value: String, index: Int): List<String> =
            value.split('|').getOrNull(index)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

        /**
         * Everything inside the parentheses of the attribute, brackets balanced —
         * `[DataField("graph", required: true, customTypeSerializer: typeof(PrototypeIdSerializer<X>))]`
         * tears any `\(([^)]*)\)` apart on the nested `typeof`.
         *
         * The walk starts at the end of the attribute **name**, not at the end of the match: the
         * pattern above swallows `("graph"` whenever the tag is spelled out, and starting after that
         * lands on a comma and reads no arguments at all. It cost the `required` flag of every field
         * that names its key — silently, because the flag is only ever read as a yes.
         */
        private fun argumentsOf(text: CharSequence, attribute: MatchResult): CharSequence {
            var i = attribute.groups[1]!!.range.last + 1
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '(') return ""

            val start = i
            var depth = 0
            var quoted = false
            while (i < text.length) {
                val current = text[i]
                when {
                    quoted && current == '\\' -> i++
                    current == '"' -> quoted = !quoted
                    quoted -> Unit
                    current == '(' -> depth++
                    current == ')' -> {
                        depth--
                        if (depth == 0) return text.subSequence(start, i)
                    }
                }
                i++
            }
            return ""
        }

        private fun className(match: MatchResult): String = match.groupValues[1]

        private fun basesOf(match: MatchResult): List<String> =
            match.groupValues[2]
                .substringBefore(" where ")
                .split(',')
                .map { simpleTypeName(it) }
                .filter { it.isNotEmpty() && it.first().isLetter() }

        private fun simpleTypeName(type: String): String =
            type.trim()
                .substringBefore('<')
                .trimEnd('?', '[', ']')
                .substringAfterLast('.')

        private fun fieldTypeAfter(text: CharSequence, offset: Int): String? {
            val match = FIELD_TYPE.find(text, offset) ?: return null
            return simpleTypeName(match.groupValues[1]).takeIf { it.isNotEmpty() }
        }

        private fun classAt(
            classes: List<Pair<Int, MatchResult>>,
            offset: Int,
        ): MatchResult? = classes.lastOrNull { it.first < offset }?.second

        private fun ownerAt(
            scopes: List<ClassScope>,
            classes: List<Pair<Int, MatchResult>>,
            offset: Int,
        ): String? {
            val enclosing = scopes
                .filter { offset > it.start && offset < it.end }
                .minByOrNull { it.end - it.start }
            return enclosing?.name ?: classAt(classes, offset)?.let { className(it) }
        }

        private fun classScopes(
            text: CharSequence,
            classes: List<Pair<Int, MatchResult>>,
        ): List<ClassScope> {
            val starts = classes.associate { it.first to className(it.second) }
            val scopes = mutableListOf<ClassScope>()
            val open = ArrayDeque<ClassScope>()
            var pending: String? = null
            var depth = 0
            var state = CODE
            var i = 0
            while (i < text.length) {
                val current = text[i]
                val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
                when (state) {
                    CODE -> {
                        starts[i]?.let { pending = it }
                        when {
                            current == '/' && next == '/' -> { state = LINE_COMMENT; i++ }
                            current == '/' && next == '*' -> { state = BLOCK_COMMENT; i++ }
                            current == '@' && next == '"' -> { state = VERBATIM; i++ }
                            current == '"' -> state = STRING
                            current == '\'' -> state = CHAR
                            current == '{' -> {
                                depth++
                                val name = pending
                                if (name != null) {
                                    open.addLast(ClassScope(name, i, text.length, depth))
                                    pending = null
                                }
                            }
                            current == '}' -> {
                                if (open.isNotEmpty() && open.last().depth == depth) {
                                    val scope = open.removeLast()
                                    scopes += ClassScope(scope.name, scope.start, i, scope.depth)
                                }
                                depth--
                            }
                        }
                    }
                    LINE_COMMENT -> if (current == '\n') state = CODE
                    BLOCK_COMMENT -> if (current == '*' && next == '/') { state = CODE; i++ }
                    STRING -> when (current) {
                        '\\' -> i++
                        '"' -> state = CODE
                    }
                    CHAR -> when (current) {
                        '\\' -> i++
                        '\'' -> state = CODE
                    }
                    VERBATIM -> if (current == '"') {
                        if (next == '"') i++ else state = CODE
                    }
                }
                i++
            }
            scopes += open
            return scopes
        }

        private fun classAfter(
            classes: List<Pair<Int, MatchResult>>,
            offset: Int,
        ): MatchResult? = classes.firstOrNull { it.first > offset }?.second

        private fun fieldNameAfter(text: CharSequence, offset: Int): String? {
            val match = FIELD.find(text, offset) ?: return null
            val name = match.groupValues[1].trimStart('_')
            if (name.isEmpty()) return null
            return name.replaceFirstChar { it.lowercase() }
        }
    }
}
