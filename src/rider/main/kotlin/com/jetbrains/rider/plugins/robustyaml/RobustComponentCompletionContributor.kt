package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

private val logger = logger<RobustComponentCompletionContributor>()

class RobustComponentCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), ComponentNameProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeKindProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeIdProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), DataFieldProvider)
    }
}

private object DataFieldProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        if (!atKeyPosition(parameters)) return

        val position = parameters.position
        val declaration = RobustYamlContext.declarationAround(position) ?: return
        val project = position.project
        val path = RobustYamlContext.pathAt(declaration, position) ?: return

        val fields = fieldsAt(project, declaration, path)
        logger.info("Fields at ${declaration.name}/${path.joinToString("/")}: ${fields.size}")
        if (fields.isEmpty()) return

        val taken = takenAt(declaration, position, path)
        val typeText =
            if (path.isNotEmpty()) path.last()
            else if (declaration.isComponent) declaration.name
            else "${declaration.name} prototype"
        var offered = 0
        for (field in fields) {
            if (field in taken) continue
            result.addElement(LookupElementBuilder.create(field).withTypeText(typeText, true))
            offered++
        }
        if (offered > 0) result.stopHere()
    }

    private fun fieldsAt(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        path: List<String>,
    ): List<String> {
        if (path.isEmpty()) {
            return if (declaration.isComponent) RobustDataFields.forComponent(project, declaration.name)
            else RobustDataFields.forPrototype(project, declaration.name)
        }

        val owner = RobustValidation.fieldAt(project, declaration, path.dropLast(1), path.last())
        if (owner?.dictionary == true) return emptyList()

        val root = RobustDataFields.rootClass(project, declaration) ?: return emptyList()
        return RobustBackend.getInstance(project).cachedFields(root, path)?.map { it.name } ?: emptyList()
    }

    private fun takenAt(
        declaration: RobustYamlContext.DeclarationContext,
        position: PsiElement,
        path: List<String>,
    ): Set<String> {
        var mapping = PsiTreeUtil.getParentOfType(position, YAMLMapping::class.java, false)
        while (mapping != null && RobustYamlContext.pathAt(declaration, mapping) != path) {
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java, true)
        }
        val target = mapping ?: declaration.mapping.takeIf { path.isEmpty() } ?: return emptySet()
        return target.keyValues.mapNotNull { it.keyText }.toSet()
    }

    private fun atKeyPosition(parameters: CompletionParameters): Boolean {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        return !document.charsSequence.subSequence(lineStart, offset).contains(':')
    }
}

private object PrototypeIdProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        val project = position.project
        val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false)

        val kind = scalar?.let { RobustValidation.expectedKind(it) }
            ?: RobustValidation.keyKindAt(position)
        val ids = when {
            kind != null -> RobustPrototypeIndex.idsOfKind(project, kind)
            scalar != null && RobustYamlContext.isPrototypeIdReference(scalar) ->
                RobustPrototypeIndex.ids(project)
            else -> return
        }
        logger.info("Ids for kind '${kind ?: "any"}': ${ids.size}")

        val typeText = kind ?: "prototype id"
        for (id in ids) {
            result.addElement(LookupElementBuilder.create(id).withTypeText(typeText, true))
        }
        if (kind != null && ids.isNotEmpty()) result.stopHere()
    }
}

private object PrototypeKindProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val keyValue =
            PsiTreeUtil.getParentOfType(parameters.position, YAMLKeyValue::class.java, false) ?: return
        if (!RobustYamlContext.isPrototypeKindKey(keyValue)) return

        for (kind in RobustPrototypeIndex.kinds(parameters.position.project)) {
            result.addElement(LookupElementBuilder.create(kind).withTypeText("prototype", true))
        }
    }
}

private object ComponentNameProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val keyValue =
            PsiTreeUtil.getParentOfType(parameters.position, YAMLKeyValue::class.java, false) ?: return
        if (!RobustYamlContext.isComponentTypeKey(keyValue)) return

        for (name in RobustComponentIndex.componentNames(parameters.position.project)) {
            result.addElement(LookupElementBuilder.create(name).withTypeText("component", true))
        }
    }
}
