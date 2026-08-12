package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider

private val logger = logger<RobustLocaleDeclarationHandler>()

/**
 * Ctrl+click inside a `.ftl`. A reference contributor is of no use here: references are only asked
 * for from an element implementing [com.intellij.psi.ContributedReferenceHost], and `PsiPlainTextImpl`
 * — the single token a parser-less file consists of — is not one. Both extension points used here
 * are handed the caret offset instead, which is all the file can be read by.
 */
class RobustLocaleDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        element: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = element?.containingFile ?: return null
        val id = messageAt(file, offset) ?: return null

        // Standing on a declaration, the useful jump is to the other cultures — the same text in
        // another language. Standing on a reference, every declaration is a target.
        val here = file.virtualFile
        val targets = RobustLocalization.declarations(file.project, id).filterNot { declaration ->
            declaration.containingFile?.virtualFile == here &&
                offset in declaration.textRange.startOffset..declaration.textRange.endOffset
        }
        logger.debug { "Goto declaration of '$id': ${targets.size} targets" }

        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}

/**
 * Alt+F7 inside a `.ftl`. The platform looks for something to search by walking up the PSI, and a
 * plain text file offers only itself; this hands it the message under the caret instead, after which
 * the usual factory takes over.
 */
class RobustLocaleUsageTargetProvider : UsageTargetProvider {
    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        val id = messageAt(file, editor.caretModel.offset) ?: return null
        val declaration = RobustLocalization.declaration(file.project, id) ?: return null

        // The flag is what the deprecated one-argument constructor passes anyway: the target keeps
        // the element it was built from rather than looking it up again.
        return arrayOf(PsiElement2UsageTargetAdapter(declaration, true))
    }
}

private fun messageAt(file: PsiFile, offset: Int): String? {
    if (!file.name.endsWith(".${RobustLocaleIndex.EXTENSION}", ignoreCase = true)) return null
    return RobustLocalization.idAt(file.viewProvider.contents, offset)
}
