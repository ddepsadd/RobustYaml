package com.jetbrains.rider.plugins.robustyaml

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

    override fun getVersion(): Int = 6

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
        private val FIELD =
            Regex("""(?:public|private|protected|internal)\s[^;{=]*?\s(\w+)\s*(?:=|;|\{)""")
        private val FIELD_TYPE =
            Regex(
                """(?:public|private|protected|internal)\s+(?:readonly\s+|required\s+|static\s+|new\s+)*""" +
                    """([\w\.]+(?:<[^;{=]*?>)?[\?\[\]]*)\s+\w+\s*(?:=|;|\{)""",
            )
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
            for (attribute in ATTRIBUTE.findAll(text)) {
                val owner = ownerAt(scopes, classes, attribute.range.first) ?: continue
                val name = SPECIAL_NAMES[attribute.groupValues[1]]
                    ?: attribute.groups[2]?.value
                    ?: fieldNameAfter(text, attribute.range.last)
                    ?: continue
                fields.getOrPut(owner) { mutableSetOf() } += name
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
                result[CLASS_KEY + name] = bases.joinToString(",") + "|" + names.joinToString(",")
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
            return result
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

        fun parseBases(value: String): List<String> =
            value.substringBefore('|').split(',').filter { it.isNotEmpty() }

        fun parseFields(value: String): List<String> =
            value.substringAfter('|').split(',').filter { it.isNotEmpty() }

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
                val next = if (i + 1 < text.length) text[i + 1] else ' '
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
