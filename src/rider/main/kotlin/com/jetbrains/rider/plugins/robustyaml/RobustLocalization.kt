package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
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
     * Body of the message declared at [offset]: the text after `=` plus the indented continuation
     * lines, which is how Fluent writes multiline values and attributes. A blank line or a line
     * starting at column zero ends the entry.
     */
    fun messageAt(text: CharSequence, offset: Int): String? {
        val source = text.toString()
        val equals = source.indexOf('=', offset)
        if (offset >= source.length || equals < 0) return null

        val body = StringBuilder()
        var lineEnd = source.indexOf('\n', equals).takeIf { it >= 0 } ?: source.length
        body.append(source.substring(equals + 1, lineEnd).trim())

        while (lineEnd < source.length) {
            val next = source.indexOf('\n', lineEnd + 1).takeIf { it >= 0 } ?: source.length
            val line = source.substring(lineEnd + 1, next)
            if (line.isBlank() || !line.first().isWhitespace()) break
            body.append('\n').append(line.trim())
            lineEnd = next
        }
        return body.toString().trim().ifEmpty { null }
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

    private val DECLARATION = Regex("""﻿?([A-Za-z][\w-]*)[ \t]*=""")
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
