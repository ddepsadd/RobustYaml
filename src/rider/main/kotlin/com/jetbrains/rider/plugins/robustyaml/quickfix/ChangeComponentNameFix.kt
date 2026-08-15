package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.text.EditDistance
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustComponentIndex
import org.jetbrains.yaml.psi.YAMLScalar

class ChangeComponentNameFix(scalar: YAMLScalar, private val newName: String) :
    LocalQuickFixAndIntentionActionOnPsiElement(scalar) {

    override fun getText(): String = "Change to '$newName'"

    override fun getFamilyName(): String = "Change component name"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val manipulator = ElementManipulators.getManipulator(startElement) ?: return
        manipulator.handleContentChange(startElement, newName)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        fun suggest(project: Project, name: String): List<String> {
            val limit = maxOf(2, minOf(name.length / 2, 4))
            return RobustComponentIndex.componentNames(project)
                .asSequence()
                .map { Candidate(it, EditDistance.levenshtein(name, it, false), commonPrefix(name, it)) }
                .filter { it.distance <= limit }
                .sortedWith(compareBy({ it.distance }, { -it.prefix }, { it.name }))
                .take(MAX_SUGGESTIONS)
                .map { it.name }
                .toList()
        }

        private fun commonPrefix(a: String, b: String): Int =
            a.commonPrefixWith(b, ignoreCase = true).length

        private data class Candidate(val name: String, val distance: Int, val prefix: Int)
    }
}
