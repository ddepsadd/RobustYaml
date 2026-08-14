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
 * Ctrl+click inside a `.ftl`, and on a key spelled in C#, XAML or a guidebook. A reference contributor
 * is of no use here: references are only asked for from an element implementing
 * [com.intellij.psi.ContributedReferenceHost], and `PsiPlainTextImpl` — the single token a parser-less
 * file consists of — is not one. Both extension points used here are handed the caret offset instead,
 * which is all the file can be read by.
 *
 * In a `.cs` file the frontend gets a say because `CSharpActionSupportPolicyBase` maps
 * `GotoDeclaration` to `BACKEND_FIRST`, and that strategy has `isFrontendInvolved` set: ReSharper
 * answers first and we are the fallback, which is right — on a string literal it has no declaration
 * to offer.
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
 *
 * Deliberately not extended to C#. `CSharpActionSupportPolicyBase.getCallStrategy` answers
 * `FindUsages` with `BACKEND_ONLY` for anything that is not a `PsiFile`, and the caret in an open
 * file never is — the dummy parser builds a token tree, so `findElementAt` returns a token. Rider
 * registers no `usageTargetProvider` or `findUsagesHandlerFactory` for C# either: the action is
 * handed to ReSharper whole, and a provider here would never be asked. Search from the `.ftl` side
 * instead, where the C# literals are already listed.
 */
class RobustLocaleUsageTargetProvider : UsageTargetProvider {
    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        if (!file.name.endsWith(".${RobustLocaleIndex.EXTENSION}", ignoreCase = true)) return null
        val id = messageAt(file, editor.caretModel.offset) ?: return null
        val declaration = RobustLocalization.declaration(file.project, id) ?: return null

        // The flag is what the deprecated one-argument constructor passes anyway: the target keeps
        // the element it was built from rather than looking it up again.
        return arrayOf(PsiElement2UsageTargetAdapter(declaration, true))
    }
}

/**
 * A `.ftl` is read line by line, because there a key is either a declaration or a placeable and both
 * are written plainly. Everywhere else the key is quoted inside code, so the scanner behind
 * [localeUsageAt] decides what is a literal and what is prose.
 */
private fun messageAt(file: PsiFile, offset: Int): String? =
    if (file.name.endsWith(".${RobustLocaleIndex.EXTENSION}", ignoreCase = true)) {
        RobustLocalization.idAt(file.viewProvider.contents, offset)
    } else {
        localeUsageAt(file, offset)
    }
