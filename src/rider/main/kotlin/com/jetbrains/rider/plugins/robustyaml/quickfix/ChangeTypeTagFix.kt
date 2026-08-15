package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext

/**
 * A tag is a single lexer token, so there is no [com.intellij.psi.ElementManipulator] to write
 * through and no content range to write into: the text of the token holds the `!type:` prefix as
 * well. The replacement therefore goes through the document, and the fix holds the carrier of the
 * tag rather than the token — PSI is rebuilt between building the fix and applying it.
 */
class ChangeTypeTagFix(carrier: PsiElement, private val newType: String) :
    LocalQuickFixAndIntentionActionOnPsiElement(carrier) {

    override fun getText(): String = "Change to '$newType'"

    override fun getFamilyName(): String = "Change tagged type"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val tag = tagOf(startElement) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        val range = tag.textRange
        val replacement = TYPE_TAG + newType
        document.replaceString(range.startOffset, range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // The caret sits wherever the typo left it — mid-word after the name grew.
        editor?.caretModel?.moveToOffset(range.startOffset + replacement.length)
    }

    companion object {
        private const val TYPE_TAG = "!type:"

        fun tagOf(carrier: PsiElement): PsiElement? = RobustYamlContext.tagToken(carrier)
    }
}
