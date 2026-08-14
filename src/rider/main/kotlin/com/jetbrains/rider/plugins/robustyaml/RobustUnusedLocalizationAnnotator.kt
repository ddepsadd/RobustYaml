package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

private val logger = logger<RobustUnusedLocalizationAnnotator>()

/**
 * Greys out a message of a `.ftl` that nothing asks for. On ss14-wega 1776 of the 56280 declared
 * messages are like that — 3%, and a sweep of the whole checkout finds not one of them written
 * anywhere outside a comment.
 *
 * An annotator rather than an inspection, and a plain text one at that. `Locale` is attached as a
 * synthetic library root, and `HighlightingSettingsPerFile.shouldInspect` refuses to run inspections
 * on a file that is in a library scope and not in content — the same reason prototype validation
 * lives in [RobustYamlAnnotator] instead of an inspection. Plain text is not a problem in itself: the
 * platform registers annotators for it too (`LargeFilesAnnotator`), and they are handed the
 * [PsiFile] itself, there being no smaller element to speak of.
 */
class RobustUnusedLocalizationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return

        val file = element.virtualFile ?: return
        if (!file.extension.equals(RobustLocaleIndex.EXTENSION, ignoreCase = true)) return
        if (!RobustYamlSettings.getInstance(element.project).state.warnUnusedLocalization) return

        for ((id, offset) in unused(element)) {
            val range = TextRange(offset, minOf(offset + id.length, element.textLength))
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, message(id))
                .range(range)
                .create()
        }
    }

    private fun message(id: String): String =
        "Message '$id' is not used by any prototype, source file, layout or other message"

    /**
     * Held onto per file: the daemon runs on every keystroke, and a `.ftl` of the content carries up
     * to a few hundred messages, each of them two index lookups and a walk of 364 affixes.
     */
    private fun unused(file: PsiFile): Map<String, Int> =
        CachedValuesManager.getCachedValue(file) {
            val declared = RobustLocaleIndex.messages(file.viewProvider.contents)
            val unused = declared.filterKeys { !RobustLocalization.isUsed(file.project, it) }
            logger.debug { "${file.name}: ${unused.size} unused of ${declared.size} messages" }
            CachedValueProvider.Result.create(unused, PsiModificationTracker.MODIFICATION_COUNT)
        }
}
