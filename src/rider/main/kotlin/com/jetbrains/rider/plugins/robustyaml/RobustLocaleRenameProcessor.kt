package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.jetbrains.yaml.psi.YAMLKeyValue

private val logger = logger<RobustLocaleRenameProcessor>()

/**
 * Shift+F6 on a localization message. Nothing about it fits the stock rename: the declaration is a
 * line of a `.ftl`, a file the frontend has no parser for, and the key is spelled out again in C#
 * literals and in other messages — places with no PSI to rename through either. Every one of them is
 * rewritten here, through the document.
 */
class RobustLocaleRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean {
        if (RobustLocalization.declaredMessageId(element) != null) return true
        return searchedTarget(element)?.localization == true
    }

    /**
     * The caret usually stands on a usage — a value in a prototype — and renaming that alone would
     * leave the message it names untouched. The declaration is the same one the reference resolves
     * to; there is no ambiguity to guard against, because a message belongs to a single id no matter
     * how many cultures declare it.
     */
    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        if (RobustLocalization.declaredMessageId(element) != null) return element

        val target = searchedTarget(element) ?: return null
        return RobustLocalization.declaration(element.project, target.name)
    }

    /**
     * `ent-<prototype id>` is built by `LocalizationManager.Entity` for every entity, and no source
     * file spells it out — renaming the message would only detach it from the prototype it belongs
     * to. Reported as a conflict rather than a refusal: the dialog says what will happen and leaves
     * the decision where it belongs.
     */
    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        val id = RobustLocalization.declaredMessageId(element) ?: return
        if (!id.startsWith(ENTITY_PREFIX)) return

        conflicts.putValue(
            element,
            "Message '$id' is named after a prototype id — the engine builds it as " +
                "'$ENTITY_PREFIX&lt;id&gt;'. Renaming the message will not rename the prototype, and " +
                "the entity will lose its name.",
        )
    }

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> {
        val target = searchedTarget(element) ?: return emptyList()

        val references = mutableListOf<PsiReference>()
        processReferences(element.project, target) {
            references += it
            true
        }
        logger.debug { "Renaming '${target.name}': ${references.size} references" }
        return references
    }

    /**
     * What the platform can rewrite by itself here is only the prototype values: everything else —
     * the declarations, the C# literals, the placeables of other messages — lives in text, and the
     * edits are applied to the documents in one pass. They are ordered back to front within a file
     * so that an earlier edit never shifts the one after it.
     */
    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val project = element.project
        val id = RobustLocalization.declaredMessageId(element) ?: return
        CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)

        var values = 0
        val touched = mutableSetOf<VirtualFile>()
        for (usage in usages) {
            val reference = usage.reference
            if (reference !is LocalizationIdReference) continue

            reference.element.containingFile?.virtualFile?.let { touched += it }
            reference.handleElementRename(newName)
            values++
        }

        val edits = mutableListOf<TextEdit>()
        edits += declarationEdits(project, id)
        processLocaleTextUsages(project, id) { reference ->
            if (reference is LocaleTextReference) {
                val file = reference.element.containingFile
                edits += TextEdit(file, reference.rangeInElement.startOffset, id)
            }
            true
        }

        val rewritten = applyEdits(project, newName, edits, touched)
        logger.debug {
            "Renamed '$id' to '$newName': $values prototype values, ${edits.size} text edits, " +
                "$rewritten applied in ${touched.size} files"
        }

        if (touched.isNotEmpty()) {
            CommandProcessor.getInstance().addAffectedFiles(project, *touched.toTypedArray())
        }
        listener?.elementRenamed(element)
    }

    /**
     * The same message is declared once per culture — `shell-command-success` lives in both `ru-RU`
     * and `en-US` — and a rename that touched only the one the caret came from would split the
     * message in two.
     */
    private fun declarationEdits(project: com.intellij.openapi.project.Project, id: String): List<TextEdit> {
        val manager = PsiManager.getInstance(project)
        return RobustLocalization.sites(project, id).mapNotNull { site ->
            manager.findFile(site.file)?.let { TextEdit(it, site.offset, id) }
        }
    }

    private fun applyEdits(
        project: com.intellij.openapi.project.Project,
        newName: String,
        edits: List<TextEdit>,
        touched: MutableSet<VirtualFile>,
    ): Int {
        val documents = PsiDocumentManager.getInstance(project)
        var applied = 0
        for ((file, fileEdits) in edits.groupBy { it.file }) {
            val document = documents.getDocument(file) ?: continue

            for (edit in fileEdits.sortedByDescending { it.offset }) {
                if (!edit.stillThere(document)) {
                    logger.debug { "Stale edit of '${edit.old}' in ${file.name} at ${edit.offset}" }
                    continue
                }
                document.replaceString(edit.offset, edit.offset + edit.old.length, newName)
                applied++
            }
            documents.commitDocument(document)
            file.virtualFile?.let { touched += it }
        }
        return applied
    }

    private companion object {
        const val ENTITY_PREFIX = "ent-"
    }
}

/** One place in a text file where the old id stands and the new one has to go. */
private class TextEdit(val file: PsiFile, val offset: Int, val old: String) {
    fun stillThere(document: Document): Boolean =
        offset >= 0 &&
            offset + old.length <= document.textLength &&
            document.charsSequence.subSequence(offset, offset + old.length).toString() == old
}
