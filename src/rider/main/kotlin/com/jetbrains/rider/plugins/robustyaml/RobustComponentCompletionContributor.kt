package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.debug
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

private class KeyInsertHandler(private val popup: Boolean) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val offset = context.tailOffset
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        if (document.charsSequence.subSequence(offset, lineEnd).contains(':')) return

        document.insertString(offset, ": ")
        context.setTailOffset(offset + 2)
        context.editor.caretModel.moveToOffset(offset + 2)
        context.commitDocument()
        if (popup) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }

    companion object {
        val PLAIN = KeyInsertHandler(false)
        val WITH_POPUP = KeyInsertHandler(true)
    }
}

private fun atKeyPosition(parameters: CompletionParameters): Boolean {
    val document = parameters.editor.document
    val offset = parameters.offset
    val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
    return !document.charsSequence.subSequence(lineStart, offset).contains(':')
}

class RobustComponentCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), ComponentNameProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeKindProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeIdProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), EnumValueProvider)
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
        logger.debug { "Fields at ${declaration.name}/${path.joinToString("/")}: ${fields.size}" }
        if (fields.isEmpty()) return

        val taken = takenAt(declaration, position, path)
        val typeText =
            if (isComponentEntry(declaration, path)) "component"
            else if (path.isNotEmpty()) path.last()
            else if (declaration.isComponent) declaration.name
            else "${declaration.name} prototype"
        var offered = 0
        for (field in fields) {
            if (field in taken) continue
            val handler =
                if (hasSuggestions(project, declaration, path, field)) KeyInsertHandler.WITH_POPUP
                else KeyInsertHandler.PLAIN
            result.addElement(
                LookupElementBuilder.create(field)
                    .withTypeText(typeText, true)
                    .withInsertHandler(handler),
            )
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
        if (isComponentEntry(declaration, path)) return listOf(TYPE_KEY)

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

    private fun hasSuggestions(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        path: List<String>,
        field: String,
    ): Boolean {
        if (isComponentEntry(declaration, path)) return true
        if (RobustYamlContext.isResourcePathKey(field)) return true

        val declared = RobustValidation.fieldAt(project, declaration, path, field) ?: return false
        return declared.values.isNotEmpty() ||
            declared.keyValues.isNotEmpty() ||
            declared.prototypeKind != null ||
            declared.keyPrototypeKind != null
    }

    private fun isComponentEntry(
        declaration: RobustYamlContext.DeclarationContext,
        path: List<String>,
    ): Boolean = !declaration.isComponent &&
        declaration.name == ENTITY_KIND &&
        path == listOf(COMPONENTS_KEY)

    private const val TYPE_KEY = "type"
    private const val COMPONENTS_KEY = "components"
    private const val ENTITY_KIND = "entity"
}

private object EnumValueProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        val field = RobustValidation.fieldAround(position) ?: return
        val values = if (atKeyPosition(parameters)) field.keyValues else field.values
        logger.debug { "Enum values for '${field.name}: ${field.type}': ${values.size}" }
        if (values.isEmpty()) return

        for (value in values) {
            result.addElement(LookupElementBuilder.create(value).withTypeText(field.type, true))
        }
        result.stopHere()
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
        logger.debug { "Ids for kind '${kind ?: "any"}': ${ids.size}" }

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
