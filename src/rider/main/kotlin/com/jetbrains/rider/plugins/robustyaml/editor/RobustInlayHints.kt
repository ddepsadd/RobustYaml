package com.jetbrains.rider.plugins.robustyaml.editor

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFontSize
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.HintMarginPadding
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.documentation.RobustMarkup
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustEntityLoc
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.ConcurrentHashMap

/**
 * What the player would read, written beside what the author wrote: the name of an entity after its
 * id, the text of a message after its key. Both are already known — the hover shows them — and the
 * point of the hint is not having to ask: `parent: MobHuman` says nothing until you know that
 * MobHuman is Урист МакЧеловек.
 *
 * Only the two questions a scalar answers by itself are asked. An id declaration and the four keys
 * that mean an id need no type from the backend, and a message is recognised by the index of `.ftl`
 * the way it is everywhere else in the plugin.
 */
class RobustInlayHints : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (DumbService.isDumb(file.project)) null else Collector()
}

private class Collector : SharedBypassCollector {
    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        val scalar = element as? YAMLScalar ?: return
        val text = scalar.textValue.trim()
        if (text.isEmpty() || text.length > MAX_VALUE) return

        // The end of the range is the end of the value: a plain scalar does not own the blanks
        // written behind it, which `RobustInlayAnchorTest` pins. Walking past them puts the hint a
        // column away from what it annotates on every line whose author left a trailing space.
        val hint = entityName(scalar, text) ?: messageText(scalar.project, text) ?: return
        sink.addPresentation(
            InlineInlayPosition(scalar.textRange.endOffset, true),
            null,
            null,
            HINT_FORMAT,
        ) {
            text(hint)
        }
    }


    /**
     * The name of the entity a value names. A declaration is worth annotating as much as a
     * reference — scrolling a file of entities, the id is the developer's name for the thing and
     * this is the player's — but only where the declaration is of an entity: for a reagent or a
     * recipe [RobustEntityLoc] has nothing to say and answers null anyway.
     */
    private fun entityName(scalar: YAMLScalar, text: String): String? {
        val keyValue = RobustYamlContext.owningKey(scalar) ?: return null
        val declaration = RobustYamlContext.isPrototypeIdDeclaration(keyValue)
        if (!declaration && !RobustYamlContext.isPrototypeIdReference(scalar)) return null

        val project = scalar.project
        if (declaration) {
            // The kind of *this* declaration, not of the id anywhere. `latheRecipe Binoculars` and
            // `entity Binoculars` are different prototypes that happen to share a name, and asking
            // the index by id alone put the name of the item beside the recipe that makes it.
            val around = RobustYamlContext.declarationAround(keyValue) ?: return null
            if (around.isComponent || around.name != RobustEntityLoc.KIND) return null
        }
        return names(project).getOrPut(text) { nameOf(project, text) ?: ABSENT }.takeIf { it != ABSENT }
    }

    private fun nameOf(project: Project, id: String): String? {
        val name = RobustEntityLoc.of(project, id)?.name ?: return null
        val written = name.translations.firstOrNull()?.second ?: name.written
        return written?.let(RobustMarkup::plain)?.takeIf { it.isNotBlank() }?.let(::shorten)
    }

    /**
     * The text of a message, taken from the first culture that declares it. Which one that is does
     * not matter much: the hint answers "what stands here", and where a key is translated at all
     * every culture says the same thing in its own words.
     */
    private fun messageText(project: Project, text: String): String? {
        val id = RobustLocalization.messageId(text)
        if ('-' !in id || !RobustLocalization.declaresMessage(project, id)) return null
        return messages(project).getOrPut(id) {
            RobustLocalization.translations(project, id).firstOrNull()?.second
                ?.let(RobustMarkup::plain)?.let(::shorten) ?: ABSENT
        }.takeIf { it != ABSENT }
    }

    /**
     * Memoised for the length of one pass of the daemon. Both answers walk the chain of parents and
     * read `.ftl` files, and a file names the same parent on line after line — without this the
     * work is repeated once per line. The tracker ticks on any edit, so the map never outlives the
     * text it was computed from.
     */
    private fun names(project: Project): ConcurrentHashMap<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, String>(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun messages(project: Project): ConcurrentHashMap<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, String>(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun shorten(text: String): String {
        val single = text.replace('\n', ' ').trim()
        return if (single.length <= MAX_HINT) single else single.take(MAX_HINT - 1) + "…"
    }

    private companion object {
        /**
         * The look of a hint, spelled out rather than left to the `hasBackground` shorthand: that
         * one is a wrapper over this very call, and `false` there means
         * `HintFormat.default.withColorKind(TextWithoutBackground)` — the reason the name of an
         * entity read as a continuation of the value instead of as an annotation over it.
         * `HintColorKind.Default` is the only kind that is not the exception (`hasBackground()`
         * is "anything but TextWithoutBackground"), so the scheme paints the hint chip itself and
         * the hint stays told apart from code in every theme, high contrast included. The spacing
         * is left at what `HintFormat.default` carries — an outer margin on top of it read as a
         * gap between the value and its hint, which is the very thing a hint must not have.
         */
        val HINT_FORMAT = HintFormat(
            HintColorKind.Default,
            HintFontSize.ABitSmallerThanInEditor,
            HintMarginPadding.OnlyPadding,
        )

        /** A value longer than this is prose, not an id or a key, and asking about it is wasted. */
        const val MAX_VALUE = 120
        const val MAX_HINT = 48

        /** Stands for "asked and there is nothing", so that a miss is memoised as well as a hit. */
        const val ABSENT = " "
    }
}
