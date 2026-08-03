package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

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
        val fields =
            if (declaration.isComponent) RobustDataFields.forComponent(project, declaration.name)
            else RobustDataFields.forPrototype(project, declaration.name)
        if (fields.isEmpty()) return

        val taken = declaration.mapping.keyValues.mapNotNull { it.keyText }.toSet()
        val typeText = if (declaration.isComponent) declaration.name else "${declaration.name} prototype"
        for (field in fields) {
            if (field in taken) continue
            result.addElement(LookupElementBuilder.create(field).withTypeText(typeText, true))
        }
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
        val scalar =
            PsiTreeUtil.getParentOfType(parameters.position, YAMLScalar::class.java, false) ?: return
        if (!RobustYamlContext.isPrototypeIdReference(scalar)) return

        for (id in RobustPrototypeIndex.ids(parameters.position.project)) {
            result.addElement(LookupElementBuilder.create(id).withTypeText("prototype id", true))
        }
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
