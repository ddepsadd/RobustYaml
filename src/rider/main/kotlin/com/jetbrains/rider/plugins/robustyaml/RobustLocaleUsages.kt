package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase

private val logger = logger<LocaleTextReference>()

/**
 * A mention of a localization key in a file the frontend has no parser for — a string literal in C#,
 * a placeable in another Fluent message. There is no PSI to hang a reference on, so the reference
 * sits on the file itself and carries the range it stands for; the rewrite goes through the document
 * for the same reason.
 */
class LocaleTextReference(
    file: PsiFile,
    range: TextRange,
    private val id: String,
) : PsiReferenceBase<PsiFile>(file, range, true) {

    override fun resolve(): PsiElement? = RobustLocalization.declaration(element.project, id)

    override fun isReferenceTo(element: PsiElement): Boolean =
        RobustLocalization.declaredMessageId(element) == id

    /**
     * Written through the document rather than through a manipulator: the containing file is one
     * plain token to the frontend, and `ElementManipulators` has nothing to offer for it.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val file = element
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return file
        val range = rangeInElement

        // The range was taken when the reference was built; anything else standing there now means
        // the file moved under us, and rewriting it would corrupt unrelated text.
        if (range.endOffset > document.textLength ||
            document.getText(range) != id
        ) {
            logger.debug { "Stale usage of '$id' in ${file.name} at ${range.startOffset}" }
            return file
        }

        document.replaceString(range.startOffset, range.endOffset, newElementName)
        documents.commitDocument(document)
        return file
    }
}

/**
 * Every mention of [id] in C# and in other Fluent messages. Prototypes are walked separately: there
 * the value carries a real reference, and what it points at is decided by resolving it rather than
 * by the text alone.
 *
 * A read action is taken per file, as in the YAML walk — holding one across the whole search would
 * block every write the IDE wants to make meanwhile.
 */
internal fun processLocaleTextUsages(
    project: Project,
    id: String,
    consumer: (PsiReference) -> Boolean,
): Boolean {
    val files = ReadAction.compute<Collection<com.intellij.openapi.vfs.VirtualFile>, RuntimeException> {
        RobustLocaleUsageIndex.files(project, id)
    }
    logger.debug { "Text usages of '$id': ${files.size} candidate files" }

    val manager = PsiManager.getInstance(project)
    for (file in files) {
        ProgressManager.checkCanceled()

        val wanted = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = manager.findFile(file) ?: return@compute true
            val text = psiFile.viewProvider.contents
            for (usage in RobustLocaleUsageIndex.usages(text, file.extension.orEmpty())) {
                if (usage.id != id) continue
                val reference = LocaleTextReference(psiFile, TextRange(usage.start, usage.end), id)
                if (!consumer(reference)) return@compute false
            }
            true
        }
        if (!wanted) return false
    }
    return true
}
