package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex

object RobustLocalization {
    data class MessageSite(val file: VirtualFile, val offset: Int)

    fun keys(project: Project): List<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val keys = sortedSetOf<String>()
            FileBasedIndex.getInstance().processAllKeys(
                RobustLocaleIndex.NAME,
                { key ->
                    keys += key
                    true
                },
                localeScope(project),
                null,
            )
            CachedValueProvider.Result.create(
                keys.toList(),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    fun hasMessage(project: Project, id: String): Boolean =
        id.isNotEmpty() &&
            FileBasedIndex.getInstance()
                .getContainingFiles(RobustLocaleIndex.NAME, id, localeScope(project))
                .isNotEmpty()

    fun hasAnyMessage(project: Project): Boolean = keys(project).isNotEmpty()

    /**
     * Whether the message is declared, answered from the cached key list rather than from the index.
     * [hasMessage] costs a query apiece, and the sibling check asks about every message-shaped value
     * of a mapping — `wordReplacements` puts hundreds of them under one key. The list comes out of a
     * sorted set, so a binary search is all it takes.
     */
    fun declaresMessage(project: Project, id: String): Boolean =
        id.isNotEmpty() && keys(project).binarySearch(id) >= 0

    fun sites(project: Project, id: String): List<MessageSite> {
        if (id.isEmpty()) return emptyList()

        val sites = mutableListOf<MessageSite>()
        FileBasedIndex.getInstance().processValues(
            RobustLocaleIndex.NAME,
            id,
            null,
            { file, offset ->
                sites += MessageSite(file, offset)
                true
            },
            localeScope(project),
        )
        return sites
    }

    /**
     * `HasMessage` cuts the id at the first dot: what follows is a Fluent attribute of the same
     * message, not a message of its own.
     */
    fun messageId(raw: String): String = raw.substringBefore('.')

    fun looksLikeMessageId(id: String): Boolean =
        id.isNotEmpty() && id.first().isLetter() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /**
     * The shape a key has to have when nothing but the text claims it is one — the same one the
     * usage indexes demand. [looksLikeMessageId] is deliberately looser, because a field typed
     * `LocId` may hold whatever the author wrote; here the text is the only evidence there is.
     *
     * A dash is not enough, and the segment after it has to be non-empty:
     * `metamorphicFillBaseName: fill-` is a prefix of a sprite state, and it sits among
     * `reagent-name-*` neighbours that do resolve — 150 findings in the reagent files came of it.
     */
    fun looksLikeStandaloneMessageId(id: String): Boolean = STANDALONE_ID.matches(id)

    private val STANDALONE_ID = Regex("""[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+""")

    /**
     * Body of the message declared at [offset]: the text after `=` plus the indented continuation
     * lines, which is how Fluent writes multiline values and attributes. A blank line or a line
     * starting at column zero ends the entry.
     */
    fun messageAt(text: CharSequence, offset: Int): String? =
        entryLines(text, offset)?.joinToString("\n") { it.trim() }?.trim()?.ifEmpty { null }

    /** A Fluent entry as it is written: the value of the message, then its attributes. */
    data class Entry(val value: String?, val attributes: Map<String, String>)

    /**
     * The same entry split the way the engine reads it. `LocalizationManager.Entity` takes the name
     * from the value and the description and the suffix from the `desc` and `suffix` attributes, and
     * in the content the attributes carry more than the value does: 13909 descriptions against 7489
     * written as `description:` in YAML. An entry with attributes only has no value, which the engine
     * checks for explicitly — that is how a translation overrides a description alone.
     */
    fun entryAt(text: CharSequence, offset: Int): Entry? {
        val lines = entryLines(text, offset) ?: return null

        val value = StringBuilder()
        val attributes = linkedMapOf<String, StringBuilder>()
        var current = value
        for (line in lines) {
            val attribute = ATTRIBUTE.matchEntire(line.trim())
            if (attribute != null) {
                current = StringBuilder(attribute.groupValues[2].trim())
                attributes[attribute.groupValues[1]] = current
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line.trim())
            }
        }
        return Entry(
            value.toString().trim().ifEmpty { null },
            attributes.mapValues { it.value.toString().trim() }.filterValues { it.isNotEmpty() },
        )
    }

    /** The lines an entry occupies: the text after `=`, then the indented continuation lines. */
    private fun entryLines(text: CharSequence, offset: Int): List<String>? {
        val source = text.toString()
        val equals = source.indexOf('=', offset)
        if (offset >= source.length || equals < 0) return null

        val lines = mutableListOf<String>()
        var lineEnd = source.indexOf('\n', equals).takeIf { it >= 0 } ?: source.length
        lines += source.substring(equals + 1, lineEnd)

        while (lineEnd < source.length) {
            val next = source.indexOf('\n', lineEnd + 1).takeIf { it >= 0 } ?: source.length
            val line = source.substring(lineEnd + 1, next)
            if (line.isBlank() || !line.first().isWhitespace()) break
            lines += line
            lineEnd = next
        }
        return lines
    }

    /**
     * The message named at [offset] of a `.ftl`, whether the text there declares it or refers to it.
     * The file has no PSI to ask, so the line is read instead: a declaration opens the line and ends
     * at the `=`, a reference stands inside a placeable.
     */
    fun idAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null

        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = offset
        while (end < text.length && text[end] != '\n') end++

        val line = text.subSequence(start, end)
        DECLARATION.matchAt(line, 0)?.groups?.get(1)?.let { id ->
            if (offset - start in id.range.first..id.range.last + 1) return id.value
        }

        return RobustLocaleUsageIndex.placeables(line)
            .firstOrNull { offset - start in it.start..it.end }
            ?.id
    }

    /**
     * The message as every culture writes it, culture first. Sorted rather than left in index order,
     * which is not guaranteed: an unsorted list would put the cultures in a different order after
     * each reindexing and make the popup look like it changed.
     */
    fun translations(project: Project, id: String): List<Pair<String, String>> =
        read(project, id) { text, offset -> messageAt(text, offset) }

    /** The same, split into value and attributes: what an entity needs and a bare key does not. */
    fun entries(project: Project, id: String): List<Pair<String, Entry>> =
        read(project, id) { text, offset -> entryAt(text, offset) }

    /**
     * A message with its placeables filled in. Of the 32000 entity texts in the content 11593 are
     * nothing but `{ ent-X }` — a translator writes one entry and points the rest at it — and 545 are
     * `{ "" }`, which is how Fluent spells a deliberately empty override. Left alone, a third of the
     * popups would read `{ ent-BaseCrowbar }` instead of a name.
     *
     * Resolution stays inside one culture, as a Fluent bundle does: `ru-RU` never reads `en-US`.
     * Anything else between the braces — `{ $user }`, `{ NUMBER($x) }` — is an argument the engine
     * supplies at runtime and nobody here can, so it is left standing as written.
     */
    fun resolved(project: Project, culture: String, text: String): String =
        resolved(text) { id -> entries(project, id).firstOrNull { it.first == culture }?.second }

    /** The same with the lookup handed in, which is all the rule itself needs to be checked. */
    fun resolved(text: String, depth: Int = 0, lookup: (String) -> Entry?): String =
        PLACEABLE.replace(text) { match ->
            val literal = match.groups[LITERAL_GROUP]
            if (literal != null) return@replace literal.value
            if (depth >= MAX_DEPTH) return@replace match.value

            val entry = lookup(match.groupValues[MESSAGE_GROUP]) ?: return@replace match.value
            val attribute = match.groups[ATTRIBUTE_GROUP]?.value
            val body = (if (attribute == null) entry.value else entry.attributes[attribute])
                ?: return@replace match.value
            resolved(body, depth + 1, lookup)
        }

    private fun <T> read(project: Project, id: String, parse: (CharSequence, Int) -> T?): List<Pair<String, T>> =
        sites(project, id)
            .mapNotNull { site ->
                val text = runCatching { VfsUtilCore.loadText(site.file) }.getOrNull()
                    ?: return@mapNotNull null
                val parsed = parse(text, site.offset) ?: return@mapNotNull null
                (cultureOf(site.file) ?: site.file.name) to parsed
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

    /**
     * Whether anything in the checkout asks for this message. Five sources are asked, and each of them
     * cost a measurement to find: values of prototypes, string literals of C#, references from other
     * messages, `{Loc key}` bindings of XAML and `Key="…"` attributes of guidebooks. Two more are not
     * lookups at all — a key the engine builds, and a key assembled from a prefix.
     *
     * `ent-*` is granted unconditionally: `LocalizationManager.Entity` builds one for every entity
     * prototype without any file naming it, 13951 of them. That leaves the 389 whose prototype no
     * longer exists unreported, which is the safe way round to be wrong.
     *
     * The affixes are the last resort and the loosest: a key assembled at runtime is nowhere written
     * whole, so only its head or its tail can be recognised.
     */
    fun isUsed(project: Project, id: String): Boolean {
        if (id.startsWith(ENTITY_PREFIX)) return true
        if (RobustLocaleUsageIndex.files(project, id).isNotEmpty()) return true
        if (RobustYamlValueIndex.files(project, id).isNotEmpty()) return true
        return RobustLocaleAffixIndex.covers(project, id)
    }

    /** Culture of a locale file, taken from the directory right under `Locale`. */
    fun cultureOf(file: VirtualFile): String? {
        var current = file.parent
        while (current != null) {
            val parent = current.parent ?: return null
            if (parent.name == LOCALE_DIR) return current.name
            current = parent
        }
        return null
    }

    /** The id a stand-in declaration carries, or null for anything that is not one. */
    fun declaredMessageId(element: PsiElement): String? = (element as? MessageDeclaration)?.name

    fun declaration(project: Project, id: String): PsiElement? = declarations(project, id).firstOrNull()

    /** One per culture: `shell-command-success` is declared in both `ru-RU` and `en-US`. */
    fun declarations(project: Project, id: String): List<PsiElement> {
        val manager = PsiManager.getInstance(project)
        return sites(project, id).mapNotNull { site ->
            manager.findFile(site.file)?.let { MessageDeclaration(it, id, site.offset) }
        }
    }

    private fun localeScope(project: Project): GlobalSearchScope = ProjectScope.getAllScope(project)

    private const val LOCALE_DIR = "Locale"

    private const val ENTITY_PREFIX = "ent-"

    private val DECLARATION = Regex("""﻿?([A-Za-z][\w-]*)[ \t]*=""")

    private val ATTRIBUTE = Regex("""\.([\w-]+)[ \t]*=(.*)""")

    private val PLACEABLE =
        Regex("""\{[ \t]*(?:"([^"]*)"|([A-Za-z][\w-]*)(?:\.([\w-]+))?)[ \t]*}""")

    private const val LITERAL_GROUP = 1
    private const val MESSAGE_GROUP = 2
    private const val ATTRIBUTE_GROUP = 3
    /**
     * Deep enough for the longest chain in the content and still a stop for a cycle, which Fluent
     * itself only catches at runtime. Translators point one entry at another for whole families of
     * items — `ent-ClothingHeadHeadHatBaseFlipped` reaches `ent-BaseItem` in five hops — and a limit
     * of four left braces standing in ten popups.
     */
    private const val MAX_DEPTH = 16
}

/**
 * `.ftl` has no parser on the frontend, so there is no PSI to resolve into: a plain text file is one
 * token and navigation would always land on its first line. This stands in for the declaration and
 * navigates by offset itself.
 */
internal class MessageDeclaration(
    private val file: PsiFile,
    private val id: String,
    private val offset: Int,
) : FakePsiElement() {
    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String = id

    override fun getPresentableText(): String = id

    override fun getTextOffset(): Int = offset

    override fun getTextRange(): TextRange = TextRange(offset, offset + id.length)

    // Left alone, a fake element reports no text and zero length, and the usage preview paints the
    // whole `.ftl` instead of the line the key is declared on: the preview highlights the range of
    // the element it restores from the usage. Physical it must not become — a smart pointer would
    // then be rebuilt from an offset in a plain text file, and restore the whole file as one token.
    override fun getText(): String = id

    override fun getTextLength(): Int = id.length

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
    }
}
