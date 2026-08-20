package com.jetbrains.rider.plugins.robustyaml.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttributeValue
import com.jetbrains.rider.plugins.robustyaml.inspection.RobustValidation
import com.jetbrains.rider.plugins.robustyaml.quickfix.ChangePrototypeIdFix

/**
 * Ids named by guidebook embeds. An annotator rather than an inspection for the same reason the
 * prototypes have one: the guidebook is attached as a synthetic library, and
 * `HighlightingSettingsPerFile.shouldInspect` refuses to inspect a file that is in library scope and
 * out of content — a `LocalInspectionTool` would never run on it.
 */
class RobustGuidebookAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val value = element as? XmlAttributeValue ?: return
        val problem = RobustValidation.guidebookPrototypeId(value) ?: return

        var builder = holder.newAnnotation(HighlightSeverity.ERROR, problem.message)
            .range(value)
        for ((rank, suggestion) in problem.suggestions.withIndex()) {
            builder = builder.withFix(ChangePrototypeIdFix(value, suggestion, rank))
        }
        builder.create()
    }
}
