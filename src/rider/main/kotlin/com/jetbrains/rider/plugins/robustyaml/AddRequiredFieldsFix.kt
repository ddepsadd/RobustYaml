package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLKeyValue

class AddRequiredFieldsFix(typeKey: YAMLKeyValue, private val fields: List<String>) :
    LocalQuickFixAndIntentionActionOnPsiElement(typeKey) {

    override fun getText(): String =
        if (fields.size == 1) "Add required field '${fields.first()}'"
        else "Add required fields: ${fields.joinToString()}"

    override fun getFamilyName(): String = "Add required fields"

    /**
     * Written through the document rather than PSI: the keys are inserted at the indent of the
     * `type:` key itself, which is what the sequence handlers already keep for prototypes, and a
     * generated `YAMLKeyValue` would come back reindented by the code style.
     */
    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val typeKey = startElement as? YAMLKeyValue ?: return
        val mapping = typeKey.parentMapping ?: return
        val last = mapping.keyValues.lastOrNull() ?: return

        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return

        val lineStart = document.getLineStartOffset(document.getLineNumber(typeKey.textRange.startOffset))
        val indent = typeKey.textRange.startOffset - lineStart
        if (indent < 0) return

        val insertion = fields.joinToString("") { "\n" + " ".repeat(indent) + "$it: " }
        val at = lastLineEnd(document, last.textRange.endOffset)
        document.insertString(at, insertion)
        documentManager.commitDocument(document)

        editor?.caretModel?.moveToOffset(at + insertion.length)
    }

    /** End of the line the mapping ends on: a trailing comment must stay where it is. */
    private fun lastLineEnd(document: com.intellij.openapi.editor.Document, offset: Int): Int =
        document.getLineEndOffset(document.getLineNumber(offset))
}
