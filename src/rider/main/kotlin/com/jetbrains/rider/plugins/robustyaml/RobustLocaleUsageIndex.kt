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
 * Where a localization key is used outside prototypes: string literals in C#, placeables in other
 * Fluent messages, `{Loc key}` bindings in XAML and `Key="…"` attributes of guidebook documents.
 * Prototypes are covered by [RobustYamlValueIndex], and between the five of them a rename knows every
 * place that has to follow.
 *
 * Measured on ss14-wega: of the 56269 declared messages 10875 are used from prototypes, 5657 from C#
 * literals, 1936 from other messages, 1560 from XAML and 495 from guidebooks — a rename that knew
 * only about YAML would quietly break more keys than it fixed.
 */
class RobustLocaleUsageIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getVersion(): Int = 3

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> mentions(file.extension.orEmpty()) }

    override fun getIndexer(): DataIndexer<String, Void?, FileContent> =
        DataIndexer { content ->
            usages(content.contentAsText, content.file.extension.orEmpty())
                .associate { it.id to null }
        }

    companion object {
        val NAME: ID<String, Void> = ID.create("robustyaml.locale.usages")

        const val CODE_EXTENSION = "cs"
        const val MARKUP_EXTENSION = "xaml"
        const val DOCUMENT_EXTENSION = "xml"

        private val EXTENSIONS = listOf(
            CODE_EXTENSION,
            RobustLocaleIndex.EXTENSION,
            MARKUP_EXTENSION,
            DOCUMENT_EXTENSION,
        )

        // The extension may be namespaced — `{controls:Loc 'shuttle-console-docks-label'}` — and
        // seven keys in the content are written only that way.
        private val BINDING =
            Regex("""\{[ \t]*(?:[\w.]+:)?Loc[ \t]+(['"]?)([A-Za-z][\w-]*)\1[ \t]*}""")

        private val ATTRIBUTE = Regex("""\bKey[ \t]*=[ \t]*"([A-Za-z][\w-]*)"""")

        private const val COMMENT_START = "<!--"
        private const val COMMENT_END = "-->"

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

        /** Whether a file of this kind can hold a mention at all — a `.ftl` declares as well. */
        fun mentions(extension: String): Boolean =
            EXTENSIONS.any { extension.equals(it, ignoreCase = true) }

        fun usages(text: CharSequence, extension: String): List<LocaleUsage> = when {
            extension.equals(CODE_EXTENSION, ignoreCase = true) -> literals(text)
            extension.equals(RobustLocaleIndex.EXTENSION, ignoreCase = true) -> placeables(text)
            extension.equals(MARKUP_EXTENSION, ignoreCase = true) -> bindings(text)
            extension.equals(DOCUMENT_EXTENSION, ignoreCase = true) -> attributes(text)
            else -> emptyList()
        }

        /**
         * The mention the caret stands in. Both ends count as inside: the caret sits between
         * characters, and clicking right after the last one of a key still points at it.
         */
        fun at(usages: List<LocaleUsage>, offset: Int): LocaleUsage? =
            usages.firstOrNull { offset >= it.start && offset <= it.end }

        /** [at] over a file read from scratch — the shape a test can check without a project. */
        fun usageAt(text: CharSequence, extension: String, offset: Int): LocaleUsage? =
            at(usages(text, extension), offset)

        /**
         * `Text="{Loc 'ui-options-title'}"` in a XAML window. Both forms are in use — 1326 values
         * quoted, 378 bare — and the id is the whole binding, so a rename has to reach it: without
         * this source 1560 keys look unused and none of them would be renamed.
         */
        fun bindings(text: CharSequence): List<LocaleUsage> = matches(text, BINDING, 2)

        /**
         * `<FTLTextpart Key="guidebook-antags-thief"/>` in a guidebook document. Only the attribute
         * the guidebook reads is taken: any string of an XML file would drag in `encoding="utf-8"`
         * and every other value that happens to be spelled with a dash.
         */
        fun attributes(text: CharSequence): List<LocaleUsage> = matches(text, ATTRIBUTE, 1)

        private fun matches(text: CharSequence, pattern: Regex, group: Int): List<LocaleUsage> {
            val comments = commentRanges(text)
            val found = mutableListOf<LocaleUsage>()
            for (match in pattern.findAll(text)) {
                val id = match.groups[group] ?: continue
                if (!MESSAGE_ID.matches(id.value)) continue
                if (comments.any { id.range.first in it }) continue
                found += LocaleUsage(id.value, id.range.first, id.range.last + 1)
            }
            return found
        }

        /** Markup commented out is not a usage, for the same reason a commented-out literal is not. */
        private fun commentRanges(text: CharSequence): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var at = 0
            while (true) {
                val start = text.indexOf(COMMENT_START, at).takeIf { it >= 0 } ?: break
                val end = text.indexOf(COMMENT_END, start).takeIf { it >= 0 } ?: text.length
                ranges += start..end
                at = end + COMMENT_END.length
            }
            return ranges
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
        private const val INTERPOLATED = 7
        private const val INTERPOLATED_VERBATIM = 8
        private const val RAW_INTERPOLATED = 9

        /**
         * Content ranges of every string literal, walked character by character because a regex
         * cannot tell a literal from the text of a comment. Raw strings get a state of their own:
         * without it the three quotes of `"""` read as an empty literal followed by an opening one,
         * and the rest of the file is scanned as if it were a string — 57 files of ss14-wega.
         */
        private fun stringRanges(text: CharSequence): List<Pair<Int, Int>> {
            val ranges = mutableListOf<Pair<Int, Int>>()
            // The interpolated strings whose holes the scan is currently inside, with the brace depth
            // reached in each: a hole is code, and the code in it may open another such string.
            val resume = ArrayDeque<Int>()
            val depth = ArrayDeque<Int>()
            var state = CODE
            var start = 0
            var i = 0
            while (i < text.length) {
                val current = text[i]
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                val third = text.getOrElse(i + 2) { ' ' }
                when (state) {
                    CODE -> when {
                        current == '/' && next == '/' -> { state = LINE_COMMENT; i++ }
                        current == '/' && next == '*' -> { state = BLOCK_COMMENT; i++ }
                        current == '$' && next == '"' && third == '"' -> {
                            state = RAW_INTERPOLATED
                            start = i + 4
                            i += 3
                        }
                        current == '$' && next == '@' && third == '"' ||
                            current == '@' && next == '$' && third == '"' -> {
                            state = INTERPOLATED_VERBATIM
                            start = i + 3
                            i += 2
                        }
                        current == '$' && next == '"' -> { state = INTERPOLATED; start = i + 2; i++ }
                        current == '"' && next == '"' && third == '"' -> { state = RAW; i += 2 }
                        current == '@' && next == '"' -> { state = VERBATIM; start = i + 2; i++ }
                        current == '"' -> { state = STRING; start = i + 1 }
                        current == '\'' -> state = CHAR
                        current == '{' && resume.isNotEmpty() -> depth[depth.lastIndex]++
                        current == '}' && resume.isNotEmpty() ->
                            if (depth.last() == 0) {
                                state = resume.removeLast()
                                depth.removeLast()
                                start = i + 1
                            } else {
                                depth[depth.lastIndex]--
                            }
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
                    // A hole is not part of the text, so the run before it is closed off and the run
                    // after it starts fresh; `{{` and `}}` are how a brace is written literally.
                    INTERPOLATED, INTERPOLATED_VERBATIM -> when (current) {
                        '\\' -> if (state == INTERPOLATED) i++
                        '{' ->
                            if (next == '{') {
                                i++
                            } else {
                                ranges += start to i
                                resume.addLast(state)
                                depth.addLast(0)
                                state = CODE
                            }
                        '}' -> if (next == '}') i++
                        '"' ->
                            if (state == INTERPOLATED_VERBATIM && next == '"') {
                                i++
                            } else {
                                ranges += start to i
                                state = CODE
                            }
                        '\n' -> if (state == INTERPOLATED) state = CODE
                    }
                    RAW -> if (current == '"' && next == '"' && third == '"') {
                        state = CODE
                        i += 2
                    }
                    // A raw string may be interpolated too, and then its holes are code like any
                    // other. Skipping the body wholesale is safe but blind: three keys of the content
                    // are written only inside one, and none of them would be renamed.
                    RAW_INTERPOLATED -> when {
                        current == '"' && next == '"' && third == '"' -> {
                            ranges += start to i
                            state = CODE
                            i += 2
                        }
                        current == '{' -> {
                            ranges += start to i
                            resume.addLast(RAW_INTERPOLATED)
                            depth.addLast(0)
                            state = CODE
                        }
                    }
                }
                i++
            }
            return ranges
        }
    }
}
