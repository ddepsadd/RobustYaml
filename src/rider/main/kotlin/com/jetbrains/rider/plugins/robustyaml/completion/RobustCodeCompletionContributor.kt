package com.jetbrains.rider.plugins.robustyaml.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.languages.fileTypes.csharp.psi.CSharpStringLiteralExpression

/**
 * Completion inside a string literal of C#. The items are not built here: they come from
 * `getVariants` of the references
 * [com.jetbrains.rider.plugins.robustyaml.reference.RobustCodeReferenceContributor] hangs on the
 * literal, which the platform's `LegacyCompletionContributor` — registered for every language, last
 * in the order — turns into a lookup.
 *
 * What has to be done here is the identifier the platform writes into the copy of the file before
 * asking. By default it is `IntellijIdeaRulezzz ` with a trailing space, and every rule that says
 * what a literal names rejects a space: an id, a state and a path are all written without one. With
 * the space in place the copy holds no reference at all, and the lookup would be empty on an empty
 * literal — the one place completion is asked for most.
 */
class RobustCodeCompletionContributor : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        val literal = PsiTreeUtil.getParentOfType(
            context.file.findElementAt(context.startOffset),
            CSharpStringLiteralExpression::class.java,
            false,
        )
        if (literal != null) context.dummyIdentifier = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED
    }
}
