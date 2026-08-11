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
 * Shift+F6 with the caret inside a `.ftl`. Everywhere else the platform finds what to rename by
 * walking up the PSI, and here there is none to walk: a plain text file is a single token, so the
 * caret would land on the whole file and the refactoring would refuse. The message is read off the
 * line instead, and the declaration it names is handed to the usual rename.
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
        if (!file.name.endsWith(".${RobustLocaleIndex.EXTENSION}", ignoreCase = true)) return null

        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        return RobustLocalization.idAt(file.viewProvider.contents, editor.caretModel.offset)
    }
}
