package com.jetbrains.rider.plugins.robustyaml.documentation

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.jetbrains.rider.plugins.robustyaml.index.CodeLink
import com.jetbrains.rider.plugins.robustyaml.index.CodeLinkKind
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustResources
import com.jetbrains.rider.plugins.robustyaml.lookup.codeLinkAt
import com.jetbrains.rider.plugins.robustyaml.lookup.expectedKind
import com.jetbrains.rider.plugins.robustyaml.lookup.localeUsageAt

private val logger = logger<RobustCodeDocumentationProvider>()

/**
 * The hover of a string literal in C#: the frame of a sprite, the contents of an `.rsi`, the card of
 * an entity, the text of a message — everything the same value already shows in YAML.
 *
 * It is asked at all because Rider's own provider steps aside. `RiderDocumentationTargetProvider`
 * overrides `IdeDocumentationTargetProvider`, but its `documentationTargets(editor, file, offset)`
 * falls back to `IdeDocumentationTargetProviderImpl` — the list of these extensions — whenever the
 * backend returns no session for the offset, and on a string literal ReSharper has nothing to
 * document.
 */
class RobustCodeDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val project = file.project
        val link = codeLinkAt(file, offset)
        logger.debug { "Code documentation at $offset of ${file.name}: $link" }

        if (link == null) {
            // A key of the localization is not a link of ours — it has an index of its own, and the
            // same rule that gives it Ctrl+click gives it the hover.
            val message = localeUsageAt(file, offset) ?: return emptyList()
            return listOf(MessageDocumentationTarget(project, message))
        }

        return when (link.kind) {
            CodeLinkKind.PATH -> resourceTargets(RobustResources.resolve(project, link.value))

            CodeLinkKind.SPRITE_STATE -> {
                val rsi = RobustResources.resolve(project, link.spritePath.orEmpty())
                val png = rsi?.takeIf { it.isDirectory }?.findChild("${link.value}.png")
                png?.let { listOf(SpritePreviewTarget(it)) }.orEmpty()
            }

            CodeLinkKind.PROTOTYPE_ID -> prototypeTargets(file, link)
        }
    }

    private fun resourceTargets(target: VirtualFile?): List<DocumentationTarget> = when {
        target == null -> emptyList()
        target.isDirectory && target.name.endsWith(RSI_SUFFIX, ignoreCase = true) ->
            listOf(RsiContentsTarget(target))

        !target.isDirectory && target.extension.equals(PNG, ignoreCase = true) ->
            listOf(SpritePreviewTarget(target))

        else -> emptyList()
    }

    /**
     * The kind is not asked of the backend here, as it is in YAML: `ProtoId<X>` names it outright,
     * and the field the value stands in has no path to walk. Narrowing the declared kinds down to
     * that one is what lets the target read the id as a single declaration.
     */
    private fun prototypeTargets(file: PsiFile, link: CodeLink): List<DocumentationTarget> {
        val project = file.project
        val declared = RobustPrototypeIndex.sites(project, link.value).mapTo(mutableSetOf()) { it.kind }
        if (declared.isEmpty()) return emptyList()

        val kind = expectedKind(project, link)
        val kinds = if (kind == null) declared else declared.intersect(setOf(kind))
        if (kinds.isEmpty()) return emptyList()

        return listOf(
            PrototypeDocumentationTarget(
                project = project,
                id = link.value,
                root = null,
                path = emptyList(),
                field = "",
                kinds = kinds,
                untyped = true,
            ),
        )
    }

    private companion object {
        const val RSI_SUFFIX = ".rsi"
        const val PNG = "png"
    }
}
