package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.text.EditDistance
import org.jetbrains.yaml.psi.YAMLKeyValue

class ChangeFieldNameFix(
    keyValue: YAMLKeyValue,
    private val newName: String,
    override val rank: Int = 0,
) : LocalQuickFixAndIntentionActionOnPsiElement(keyValue), RankedFix {

    override fun getText(): String = "Change to '$newName'"

    override fun getFamilyName(): String = "Change field name"

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement,
    ) {
        val keyValue = startElement as? YAMLKeyValue
            ?: PsiTreeUtil.getParentOfType(startElement, YAMLKeyValue::class.java, false)
            ?: return
        val start = contentStart(keyValue)
        keyValue.setName(newName)
        editor?.caretModel?.moveToOffset(start + newName.length)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        fun suggest(name: String, fields: List<String>): List<String> {
            val limit = maxOf(2, minOf(name.length / 2, 4))
            return fields
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
