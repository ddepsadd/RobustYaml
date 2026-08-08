package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import org.jetbrains.yaml.psi.YAMLScalar
import kotlin.math.abs

class ChangeLocalizationIdFix(scalar: YAMLScalar, private val newId: String) :
    LocalQuickFixAndIntentionActionOnPsiElement(scalar) {

    override fun getText(): String = "Change to '$newId'"

    override fun getFamilyName(): String = "Change localization id"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val manipulator = ElementManipulators.getManipulator(startElement) ?: return
        manipulator.handleContentChange(startElement, newId)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3
        private const val COMMON_PREFIX = 4

        /**
         * Levenshtein over all 56280 keys would be paid on every pass of the daemon, so the cheap
         * filters run first: a key of a wildly different length or without a shared prefix cannot be
         * a typo of this one.
         */
        fun suggest(project: Project, id: String): List<String> =
            suggest(RobustLocalization.keys(project), id)

        fun suggest(keys: List<String>, id: String): List<String> {
            if (id.length < COMMON_PREFIX) return emptyList()

            val limit = maxOf(2, minOf(id.length / 4, 5))
            return keys
                .asSequence()
                .filter { abs(it.length - id.length) <= limit }
                .filter { sharesPrefix(it, id) }
                .map { it to EditDistance.levenshtein(id, it, false) }
                .filter { it.second in 1..limit }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .take(MAX_SUGGESTIONS)
                .map { it.first }
                .toList()
        }

        /** Same test as `commonPrefixWith(...).length >= COMMON_PREFIX`, without allocating 56280 strings. */
        private fun sharesPrefix(candidate: String, id: String): Boolean {
            if (candidate.length < COMMON_PREFIX) return false
            for (index in 0 until COMMON_PREFIX) {
                if (!candidate[index].equals(id[index], ignoreCase = true)) return false
            }
            return true
        }
    }
}
