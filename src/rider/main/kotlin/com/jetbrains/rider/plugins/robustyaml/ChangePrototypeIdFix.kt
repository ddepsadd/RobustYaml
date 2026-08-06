package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import org.jetbrains.yaml.psi.YAMLScalar

class ChangePrototypeIdFix(scalar: YAMLScalar, private val newId: String) :
    LocalQuickFixAndIntentionActionOnPsiElement(scalar) {

    override fun getText(): String = "Change to '$newId'"

    override fun getFamilyName(): String = "Change prototype id"

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
        private const val POOL = 24

        fun suggest(project: Project, id: String, kind: String): List<String> {
            val limit = maxOf(2, minOf(id.length / 2, 4))
            return RobustPrototypeIndex.ids(project)
                .asSequence()
                .map { Candidate(it, EditDistance.levenshtein(id, it, false), commonPrefix(id, it)) }
                .filter { it.distance in 1..limit }
                .sortedWith(compareBy({ it.distance }, { -it.prefix }, { it.name }))
                .take(POOL)
                .filter { candidate ->
                    RobustPrototypeIndex.sites(project, candidate.name).any { it.kind == kind }
                }
                .take(MAX_SUGGESTIONS)
                .map { it.name }
                .toList()
        }

        private fun commonPrefix(a: String, b: String): Int =
            a.commonPrefixWith(b, ignoreCase = true).length

        private data class Candidate(val name: String, val distance: Int, val prefix: Int)
    }
}
