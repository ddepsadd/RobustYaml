package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import org.jetbrains.yaml.psi.YAMLScalar

class ChangeEnumValueFix(
    scalar: YAMLScalar,
    private val newValue: String,
    override val rank: Int = 0,
) : LocalQuickFixAndIntentionActionOnPsiElement(scalar), RankedFix {

    override fun getText(): String = "Change to '$newValue'"

    override fun getFamilyName(): String = "Change enum value"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        rewriteContent(startElement, newValue, editor)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        /**
         * The distance is case sensitive, unlike everywhere else, because a value that differs from
         * a member only in case is a real error here: `ConstantSerializer` and `FlagSerializer` read
         * with `Enum.Parse` without ignoreCase. Measured case-insensitively that value is zero edits
         * away, the `1..limit` filter drops it, and the one suggestion the user needs — the same
         * name spelled properly — is the only one never offered. Nothing is lost on the enums that
         * do ignore case: there a difference in case is not reported at all.
         */
        fun suggest(value: String, values: List<String>): List<String> {
            val limit = maxOf(2, minOf(value.length / 2, 4))
            return values
                .asSequence()
                .map { it to EditDistance.levenshtein(value, it, true) }
                .filter { it.second in 1..limit }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .take(MAX_SUGGESTIONS)
                .map { it.first }
                .toList()
        }
    }
}
