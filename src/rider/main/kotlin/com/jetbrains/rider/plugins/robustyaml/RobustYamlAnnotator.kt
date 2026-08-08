package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class RobustYamlAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is YAMLKeyValue -> {
                annotateTypeKey(element, holder)
                if (!inspectionsRun(element)) {
                    reportUnknownField(element, holder)
                    reportDuplicateId(element, holder)
                    reportPrototypeIdValues(element, holder)
                    reportEnumValues(element, holder)
                    reportScalarValues(element, holder)
                }
            }
            is YAMLScalar -> {
                annotateScalar(element, holder)
                if (!inspectionsRun(element)) reportUnknownPrototypeId(element, holder)
            }
        }
    }

    private fun inspectionsRun(element: PsiElement): Boolean =
        HighlightingLevelManager.getInstance(element.project).shouldInspect(element)

    private fun reportUnknownField(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        val problem = RobustValidation.unknownField(keyValue) ?: return
        var builder = holder.newAnnotation(HighlightSeverity.WEAK_WARNING, problem.message)
            .range(keyValue.key ?: keyValue)
        for (suggestion in problem.suggestions) {
            builder = builder.withFix(ChangeFieldNameFix(keyValue, suggestion))
        }
        builder.create()
    }

    private fun reportDuplicateId(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        val message = RobustValidation.duplicateId(keyValue) ?: return
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(keyValue.value ?: keyValue)
            .create()
    }

    private fun reportPrototypeIdValues(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        for (problem in RobustValidation.prototypeIdValues(keyValue)) {
            val severity = if (problem.critical) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            var builder = holder.newAnnotation(severity, problem.message)
                .range(problem.element)
            if (!problem.critical) builder = builder.enforcedTextAttributes(RobustYamlColors.waveAttributes(false))
            for (suggestion in problem.suggestions) {
                builder = builder.withFix(ChangePrototypeIdFix(problem.element, suggestion))
            }
            builder.create()
        }
    }

    private fun reportEnumValues(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        for (problem in RobustValidation.enumValues(keyValue)) {
            var builder = holder.newAnnotation(HighlightSeverity.ERROR, problem.message)
                .range(problem.element)
            for (suggestion in problem.suggestions) {
                builder = builder.withFix(ChangeEnumValueFix(problem.element, suggestion))
            }
            builder.create()
        }
    }

    private fun reportScalarValues(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        for (problem in RobustValidation.scalarValues(keyValue)) {
            holder.newAnnotation(HighlightSeverity.ERROR, problem.message)
                .range(problem.element)
                .create()
        }
    }

    private fun reportUnknownPrototypeId(scalar: YAMLScalar, holder: AnnotationHolder) {
        val problem = RobustValidation.unknownPrototypeId(scalar) ?: return
        val severity = if (problem.critical) HighlightSeverity.ERROR else HighlightSeverity.WARNING

        var builder = holder.newAnnotation(severity, problem.message).range(scalar)
        if (!problem.critical) {
            builder = builder.enforcedTextAttributes(RobustYamlColors.waveAttributes(false))
        }
        for (suggestion in problem.suggestions) {
            builder = builder.withFix(ChangePrototypeIdFix(scalar, suggestion))
        }
        builder.create()
    }

    private fun annotateScalar(scalar: YAMLScalar, holder: AnnotationHolder) {
        if (RobustYamlContext.isPrototypeId(scalar)) {
            paint(holder, scalar, RobustYamlColors.PROTOTYPE_ID)
            return
        }
        annotateResourcePath(scalar, holder)
    }

    private fun annotateTypeKey(keyValue: YAMLKeyValue, holder: AnnotationHolder) {
        val value = keyValue.value ?: return

        when {
            RobustYamlContext.isPrototypeKindKey(keyValue) -> {
                paint(holder, value, RobustYamlColors.PROTOTYPE_KIND)
                reportUnknownKind(value, holder)
            }
            RobustYamlContext.isComponentTypeKey(keyValue) -> {
                paint(holder, value, RobustYamlColors.COMPONENT_NAME)
                reportUnknownComponent(value, holder)
            }
        }
    }

    private fun reportUnknownComponent(value: PsiElement, holder: AnnotationHolder) {
        val scalar = value as? YAMLScalar ?: return
        val name = scalar.textValue
        if (name.isEmpty()) return

        val project = scalar.project
        if (!RobustComponentIndex.hasAnyComponent(project)) return
        if (RobustComponentIndex.isKnownComponent(project, name)) return

        var builder = holder.newAnnotation(HighlightSeverity.ERROR, "Unknown component '$name'")
            .range(scalar)
        for (suggestion in ChangeComponentNameFix.suggest(project, name)) {
            builder = builder.withFix(ChangeComponentNameFix(scalar, suggestion))
        }
        builder.create()
    }

    private fun reportUnknownKind(value: PsiElement, holder: AnnotationHolder) {
        val scalar = value as? YAMLScalar ?: return
        val kind = scalar.textValue
        if (kind.isEmpty()) return

        val project = scalar.project
        if (!RobustPrototypeIndex.hasAnyKind(project)) return
        if (RobustPrototypeIndex.isKnownKind(project, kind)) return

        holder.newAnnotation(HighlightSeverity.ERROR, "Unknown prototype kind '$kind'")
            .range(scalar)
            .create()
    }

    private fun annotateResourcePath(scalar: YAMLScalar, holder: AnnotationHolder) {
        if (RobustYamlContext.isResourcePathValue(scalar)) {
            paint(holder, scalar, RobustYamlColors.RESOURCE_PATH)
            reportMissingResource(scalar, holder)
            return
        }

        val text = scalar.textValue
        if (!text.contains('/') || text.any { it.isWhitespace() }) return
        paint(holder, scalar, RobustYamlColors.RESOURCE_PATH)
    }

    private fun reportMissingResource(scalar: YAMLScalar, holder: AnnotationHolder) {
        val path = scalar.textValue
        if (!RobustResources.looksLikePath(path)) return

        val project = scalar.project
        if (RobustResources.roots(project).isEmpty()) return
        if (RobustResources.resolve(project, path) != null) return

        holder.newAnnotation(HighlightSeverity.ERROR, "Resource not found: '$path'")
            .range(scalar)
            .create()
    }

    private fun paint(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
            .create()
    }
}
