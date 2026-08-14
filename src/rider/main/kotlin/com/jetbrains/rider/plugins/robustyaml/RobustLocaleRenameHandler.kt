package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler

private val logger = logger<RobustLocaleRenameHandler>()

/**
 * Shift+F6 with the caret on a localization key — inside a `.ftl`, or on the literal that names it in
 * C#, XAML or a guidebook. In a `.ftl` the platform has nothing to walk up: the file is a single
 * plain token, so the caret would land on the whole file and the refactoring would refuse. The
 * message is read off the line instead, and the declaration it names is handed to the usual rename.
 *
 * In C# and in XAML this is never asked, and the reason is worth knowing. `CSharpActionSupportPolicyBase`
 * sends everything but `GotoDeclaration` and `FindUsages` to `BACKEND_ONLY`, and there the whole action
 * is a `BackendDelegatingAction`: it reads the strategy in `performBackendDelegatingAction` and goes
 * straight to `executeBackend`. The platform's `RenameElementAction` — and with it the loop in
 * `FrontendRenameHandlerRegistry` that would have walked this extension point first — never runs, so
 * no extension can take the refactoring back. Ctrl+click is the way across: from the `.ftl` the rename
 * works, and it rewrites the C# literals anyway.
 *
 * A guidebook `.xml` is a different matter — Rider registers no `backend.actions.support` for XML, so
 * the strategy is `FrontendOnly` and the key under the caret is renamed from there.
 */
class RobustLocaleRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        messageAt(dataContext) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val id = messageAt(dataContext) ?: return
        val declaration = RobustLocalization.declaration(project, id)
        logger.debug { "Rename from a locale file: '$id' -> ${declaration != null}" }
        if (declaration == null) return

        PsiElementRenameHandler.rename(declaration, project, declaration, editor)
    }

    /** Renaming several elements at once has no meaning for a message. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) = Unit

    private fun messageAt(dataContext: DataContext): String? {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val offset = editor.caretModel.offset

        if (file.name.endsWith(".${RobustLocaleIndex.EXTENSION}", ignoreCase = true)) {
            return RobustLocalization.idAt(file.viewProvider.contents, offset)
        }
        return localeUsageAt(file, offset)
    }
}
