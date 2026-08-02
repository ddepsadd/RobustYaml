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

class RobustComponentCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), ComponentNameProvider)
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
