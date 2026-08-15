package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import org.jetbrains.yaml.psi.YAMLScalar

class ChangeEnumValueFix(scalar: YAMLScalar, private val newValue: String) :
    LocalQuickFixAndIntentionActionOnPsiElement(scalar) {

    override fun getText(): String = "Change to '$newValue'"

    override fun getFamilyName(): String = "Change enum value"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val manipulator = ElementManipulators.getManipulator(startElement) ?: return
        manipulator.handleContentChange(startElement, newValue)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        fun suggest(value: String, values: List<String>): List<String> {
            val limit = maxOf(2, minOf(value.length / 2, 4))
            return values
                .asSequence()
                .map { it to EditDistance.levenshtein(value, it, false) }
                .filter { it.second in 1..limit }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .take(MAX_SUGGESTIONS)
                .map { it.first }
                .toList()
        }
    }
}
