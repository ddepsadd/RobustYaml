package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.jetbrains.rider.plugins.robustyaml.index.CodeLinkKind
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustResources
import com.jetbrains.rider.plugins.robustyaml.lookup.codeLinkAt
import com.jetbrains.rider.plugins.robustyaml.lookup.expectedKind

private val logger = logger<RobustCodeDeclarationHandler>()

/**
 * Ctrl+click from a string literal of C# into the content: a file under `Resources` for a path, the
 * declaration of a prototype for an id. Both are written as plain strings, and the backend has
 * nothing to offer on them — `CSharpActionSupportPolicyBase` maps `GotoDeclaration` to
 * `BACKEND_FIRST`, and that strategy leaves the frontend as the fallback, which is exactly what this
 * is: ReSharper answers first everywhere it can.
 *
 * The other two actions are not available here and cannot be made to be. `FindUsages` and
 * `RenameElement` are answered `BACKEND_ONLY`, and their actions are replaced by
 * `BackendDelegatingAction` above any extension point of the platform — so the way back from the
 * content is the search that starts in YAML, where these literals are listed among the usages.
 */
class RobustCodeDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        element: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val file = element?.containingFile ?: return null
        val link = codeLinkAt(file, offset) ?: return null
        val project = file.project

        val targets: Array<PsiElement>? = when (link.kind) {
            // A path may name a directory as readily as a file: `/Textures/Interface/Misc/job_icons.rsi`
            // is a folder of frames, and the jump into it is the useful one.
            CodeLinkKind.PATH -> {
                val target = RobustResources.resolve(project, link.value)
                val manager = PsiManager.getInstance(project)
                val psi =
                    if (target == null) null
                    else if (target.isDirectory) manager.findDirectory(target)
                    else manager.findFile(target)
                psi?.let { arrayOf(it) }
            }

            // A state is a frame inside the `.rsi` the call names beside it, and that is the same
            // `<state>.png` the reference in YAML resolves to.
            CodeLinkKind.SPRITE_STATE -> {
                val rsi = RobustResources.resolve(project, link.spritePath.orEmpty())
                val png = rsi?.takeIf { it.isDirectory }?.findChild("${link.value}.png")
                png?.let { PsiManager.getInstance(project).findFile(it) }?.let { arrayOf(it) }
            }

            CodeLinkKind.PROTOTYPE_ID ->
                RobustPrototypeIndex
                    .findDeclarations(project, link.value, expectedKind(project, link))
                    .takeIf { it.isNotEmpty() }
                    ?.toTypedArray()
        }

        logger.debug { "Goto declaration of ${link.kind} '${link.value}': ${targets?.size ?: 0} targets" }
        return targets
    }
}
