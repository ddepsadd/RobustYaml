package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.ProjectScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/** A mention of a localization key, and where in the text it sits. */
data class LocaleUsage(val id: String, val start: Int, val end: Int)

/**
 * Where a localization key is used outside prototypes: string literals in C# and placeables in other
 * Fluent messages. Prototypes are covered by [RobustYamlValueIndex], and between the three of them a
 * rename knows every place that has to follow.
 *
 * Measured on ss14-wega: of the 56269 declared messages 10875 are used from prototypes, 5657 from C#
 * literals and 1936 from other messages — a rename that knew only about YAML would quietly break
 * more keys than it fixed.
 */
class RobustLocaleUsageIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file ->
            val extension = file.extension ?: return@InputFilter false
            extension.equals(CODE_EXTENSION, ignoreCase = true) ||
                extension.equals(RobustLocaleIndex.EXTENSION, ignoreCase = true)
        }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content ->
            usages(content.contentAsText, content.file.extension.orEmpty())
                .associate { it.id to null }
        }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.locale.usages")

        const val CODE_EXTENSION = "cs"

        /**
         * A key holds a dash, and demanding one is what keeps the index small: 6052 literals of the
         * 12748 C# files of ss14-wega match against every string literal in the checkout otherwise.
         * Upper case has to be allowed — the engine names an entity `ent-<prototype id>`, and those
         * are referenced by name from other messages 1936 times.
         */
        private val MESSAGE_ID = Regex("""[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+""")

        /**
         * `{ other-message }` and `{ -term }`, the two ways one Fluent message names another. The
         * closing brace or dot is part of the match on purpose: without it a selector (`{ $count ->`)
         * and a function call (`{ NUMBER($x) }`) read as references to messages that do not exist.
         */
        private val PLACEABLE = Regex("""\{[ \t]*-?([A-Za-z][\w-]*)[ \t]*[}.]""")

        fun usages(text: CharSequence, extension: String): List<LocaleUsage> = when {
            extension.equals(CODE_EXTENSION, ignoreCase = true) -> literals(text)
            extension.equals(RobustLocaleIndex.EXTENSION, ignoreCase = true) -> placeables(text)
            else -> emptyList()
        }

        /**
         * String literals of a C# file that are shaped like a key. Comments are stepped over by the
         * scanner rather than stripped: `// "comp-thief-target"` is not a usage, and a rename that
         * rewrote it would be editing prose.
         */
        fun literals(text: CharSequence): List<LocaleUsage> {
            val found = mutableListOf<LocaleUsage>()
            for ((start, end) in stringRanges(text)) {
                val value = text.subSequence(start, end)
                if (MESSAGE_ID.matches(value)) found += LocaleUsage(value.toString(), start, end)
            }
            return found
        }

        /**
         * References from one Fluent message to another. A comment line is skipped for the same
         * reason it is in C#, and the trailing `.` of `{ msg.attr }` is allowed because the message
         * being named is still `msg`.
         */
        fun placeables(text: CharSequence): List<LocaleUsage> {
            val found = mutableListOf<LocaleUsage>()
            for (line in lines(text)) {
                if (text.subSequence(line.first, line.last).trimStart().startsWith('#')) continue
                for (match in PLACEABLE.findAll(text.subSequence(line.first, line.last))) {
                    val id = match.groups[1] ?: continue
                    found += LocaleUsage(
                        id.value,
                        line.first + id.range.first,
                        line.first + id.range.last + 1,
                    )
                }
            }
            return found
        }

        fun files(project: Project, id: String): Collection<VirtualFile> =
            FileBasedIndex.getInstance()
                .getContainingFiles(NAME, id, ProjectScope.getAllScope(project))

        private fun lines(text: CharSequence): List<IntRange> {
            val lines = mutableListOf<IntRange>()
            var start = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    lines += start..i
                    start = i + 1
                }
            }
            if (start <= text.length) lines += start..text.length
            return lines
        }

        private const val CODE = 0
        private const val LINE_COMMENT = 1
        private const val BLOCK_COMMENT = 2
        private const val STRING = 3
        private const val CHAR = 4
        private const val VERBATIM = 5
        private const val RAW = 6

        /**
         * Content ranges of every string literal, walked character by character because a regex
         * cannot tell a literal from the text of a comment. Raw strings get a state of their own:
         * without it the three quotes of `"""` read as an empty literal followed by an opening one,
         * and the rest of the file is scanned as if it were a string — 57 files of ss14-wega.
         */
        private fun stringRanges(text: CharSequence): List<Pair<Int, Int>> {
            val ranges = mutableListOf<Pair<Int, Int>>()
            var state = CODE
            var start = 0
            var i = 0
            while (i < text.length) {
                val current = text[i]
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                when (state) {
                    CODE -> when {
                        current == '/' && next == '/' -> { state = LINE_COMMENT; i++ }
                        current == '/' && next == '*' -> { state = BLOCK_COMMENT; i++ }
                        current == '"' && next == '"' && text.getOrElse(i + 2) { ' ' } == '"' -> {
                            state = RAW
                            i += 2
                        }
                        current == '@' && next == '"' -> { state = VERBATIM; start = i + 2; i++ }
                        current == '"' -> { state = STRING; start = i + 1 }
                        current == '\'' -> state = CHAR
                    }
                    LINE_COMMENT -> if (current == '\n') state = CODE
                    BLOCK_COMMENT -> if (current == '*' && next == '/') { state = CODE; i++ }
                    STRING -> when (current) {
                        '\\' -> i++
                        '"' -> { ranges += start to i; state = CODE }
                        // An unterminated literal means the scan lost its place; a newline inside
                        // one is not legal C#, so the scanner goes back to code rather than
                        // swallowing the rest of the file.
                        '\n' -> state = CODE
                    }
                    CHAR -> when (current) {
                        '\\' -> i++
                        '\'', '\n' -> state = CODE
                    }
                    VERBATIM -> if (current == '"') {
                        if (next == '"') i++ else { ranges += start to i; state = CODE }
                    }
                    RAW -> if (current == '"' && next == '"' && text.getOrElse(i + 2) { ' ' } == '"') {
                        state = CODE
                        i += 2
                    }
                }
                i++
            }
            return ranges
        }
    }
}
