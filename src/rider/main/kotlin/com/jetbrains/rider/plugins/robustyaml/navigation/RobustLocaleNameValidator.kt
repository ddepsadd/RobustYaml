package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameInputValidatorEx
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.lookup.MessageDeclaration

/**
 * What counts as a name for a message. Without this the platform falls through to
 * `LanguageNamesValidation` for the language of the element, and a message declaration has none of
 * its own — a key would then be judged by whatever the default validator calls an identifier, and
 * every key of the content holds a dash. The shape asked for here is the one
 * [RobustLocaleIndex] reads back.
 */
class RobustLocaleNameValidator : RenameInputValidatorEx {
    override fun getPattern(): ElementPattern<out PsiElement> =
        PlatformPatterns.psiElement(MessageDeclaration::class.java)

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean =
        NAME.matches(newName)

    override fun getErrorMessage(newName: String, project: Project): String? =
        if (NAME.matches(newName)) null
        else "A message id starts with a letter and holds letters, digits, '-' and '_'."

    private companion object {
        val NAME = Regex("""[A-Za-z][\w-]*""")
    }
}
