package com.jetbrains.rider.plugins.robustyaml.completion

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
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.rd.ide.model.RobustDataField
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.backend.RobustBackend
import com.jetbrains.rider.plugins.robustyaml.inspection.RobustValidation
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustComponentIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustDataFields
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

private val logger = logger<RobustComponentCompletionContributor>()

private class KeyInsertHandler(private val popup: Boolean, private val inline: Boolean) :
    InsertHandler<LookupElement> {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val offset = context.tailOffset
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        if (document.charsSequence.subSequence(offset, lineEnd).contains(':')) return

        val suffix = if (inline) ": " else ":"
        document.insertString(offset, suffix)
        context.setTailOffset(offset + suffix.length)
        context.editor.caretModel.moveToOffset(offset + suffix.length)
        context.commitDocument()
        if (popup) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }

    companion object {
        private val HANDLERS = List(4) { KeyInsertHandler(it and 1 != 0, it and 2 != 0) }

        fun of(popup: Boolean, inline: Boolean): KeyInsertHandler =
            HANDLERS[(if (popup) 1 else 0) or (if (inline) 2 else 0)]
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
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), TypeTagProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), ComponentNameProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeKindProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PrototypeIdProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), EnumValueProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), LocalizationIdProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), ColorNameProvider)
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
        val origin = RobustYamlContext.originAt(declaration, position) ?: return
        val path = origin.path

        val fields = fieldsAt(project, declaration, origin)
        logger.debug { "Fields at ${origin.root ?: declaration.name}/${path.joinToString("/")}: ${fields.size}" }
        if (fields.isEmpty()) {
            if (isDictionaryLevel(project, declaration, origin)) {
                completeFreeKeys(parameters, result, inline = !hasNestedKeys(project, declaration, origin))
            }
            return
        }

        val taken = takenAt(declaration, position, path)
        val typeText =
            if (isComponentEntry(declaration, origin)) "component"
            else if (path.isNotEmpty()) path.last()
            else if (origin.root != null) origin.root
            else if (declaration.isComponent) declaration.name
            else "${declaration.name} prototype"
        var offered = 0
        for (field in fields) {
            if (field in taken) continue
            val declared = RobustValidation.fieldAt(project, declaration, origin, field)
            val handler = KeyInsertHandler.of(
                popup = hasSuggestions(declaration, origin, field, declared),
                inline = declared == null || !(declared.sequence || declared.dictionary),
            )
            result.addElement(
                LookupElementBuilder.create(field)
                    .withTypeText(typeText, true)
                    .withInsertHandler(handler),
            )
            offered++
        }
        if (offered > 0) result.stopHere()
    }

    /**
     * Keys of a dictionary are written by the author, so the stock YAML contributor — which offers
     * keys already seen in the document — is the only useful source here. It inserts the bare name,
     * hence the decoration: the colon is ours either way.
     */
    private fun completeFreeKeys(
        parameters: CompletionParameters,
        result: CompletionResultSet,
        inline: Boolean,
    ) {
        val handler = KeyInsertHandler.of(popup = false, inline = inline)
        result.runRemainingContributors(parameters) { completion ->
            result.addElement(
                LookupElementDecorator.withInsertHandler(completion.lookupElement) { context, item ->
                    item.delegate.handleInsert(context)
                    handler.handleInsert(context, item)
                },
            )
        }
        result.stopHere()
    }

    /**
     * Values of a dictionary carry keys of their own when the value type has datafields — the entry
     * then opens a block mapping, and a trailing space after the colon would be dead text.
     */
    private fun hasNestedKeys(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
    ): Boolean {
        val root = origin.root ?: RobustDataFields.rootClass(project, declaration) ?: return false
        return RobustBackend.getInstance(project).cachedFields(root, origin.path)?.isNotEmpty() == true
    }

    private fun isDictionaryLevel(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
    ): Boolean {
        if (origin.path.isEmpty()) return false
        val owner = RobustValidation.fieldAt(project, declaration, origin.parent(), origin.path.last())
        return owner?.dictionary == true
    }

    private fun fieldsAt(
        project: Project,
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
    ): List<String> {
        val path = origin.path
        if (origin.root == null && path.isEmpty()) {
            return if (declaration.isComponent) RobustDataFields.forComponent(project, declaration.name)
            else RobustDataFields.forPrototype(project, declaration.name)
        }
        if (isComponentEntry(declaration, origin)) return listOf(TYPE_KEY)

        if (path.isNotEmpty()) {
            val owner = RobustValidation.fieldAt(project, declaration, origin.parent(), path.last())
            if (owner?.dictionary == true) return emptyList()
        }

        val root = origin.root ?: RobustDataFields.rootClass(project, declaration) ?: return emptyList()
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
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
        field: String,
        declared: RobustDataField?,
    ): Boolean {
        if (isComponentEntry(declaration, origin)) return true
        if (RobustYamlContext.isResourcePathKey(field)) return true

        if (declared == null) return false
        return declared.polymorphic ||
            declared.values.isNotEmpty() ||
            declared.keyValues.isNotEmpty() ||
            declared.localized ||
            RobustValidation.isColorField(declared) ||
            declared.prototypeKind != null ||
            declared.keyPrototypeKind != null
    }

    private fun isComponentEntry(
        declaration: RobustYamlContext.DeclarationContext,
        origin: RobustYamlContext.Origin,
    ): Boolean = origin.root == null &&
        !declaration.isComponent &&
        declaration.name == ENTITY_KIND &&
        origin.path == listOf(COMPONENTS_KEY)

    private const val TYPE_KEY = "type"
    private const val COMPONENTS_KEY = "components"
    private const val ENTITY_KIND = "entity"
}

/**
 * Names a `!type:` tag may carry. The tag is a single token rather than a value with a reference,
 * so the position is recognised by the text left of the caret; the carrier of the tag — the key or
 * the sequence item — is what tells the backend which field to look at.
 */
private object TypeTagProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val typed = document.charsSequence.subSequence(lineStart, offset)
        val tagStart = typed.lastIndexOf(TYPE_TAG)
        val started = tagStart >= 0
        if (started && typed.subSequence(tagStart + TYPE_TAG.length, typed.length).any { it.isWhitespace() }) return

        val opening = if (started) "" else openingTag(typed) ?: return

        val position = parameters.position
        val carrier = generateSequence(position as PsiElement) { it.parent }
            .firstOrNull { it is YAMLKeyValue || it is YAMLSequenceItem }
            ?: return

        // Without a tag typed yet the field itself has to ask for it, and only an abstract field
        // does: inheritors are a solution-wide search, too expensive to run on every empty value.
        if (!started && RobustValidation.fieldOf(carrier)?.polymorphic != true) return

        val names = RobustValidation.tagTypes(carrier) ?: return
        logger.debug { "Tag types for '${carrier.text.lineSequence().first()}': ${names.size}" }
        if (names.isEmpty()) return

        val prefixed = result.withPrefixMatcher(
            if (started) typed.substring(tagStart + TYPE_TAG.length) else opening,
        )
        for (name in names) {
            val text = if (started) name else TYPE_TAG + name
            prefixed.addElement(LookupElementBuilder.create(text).withTypeText(TAG_TYPE, true))
        }
        prefixed.stopHere()
    }

    /**
     * What stands after the colon while the tag is still being opened: nothing at all, or a prefix
     * of `!type:` such as `!` or `!ty`. Anything else is a value being written, not a tag.
     */
    private fun openingTag(typed: CharSequence): String? {
        val colon = typed.lastIndexOf(':')
        if (colon < 0) return null

        val after = typed.subSequence(colon + 1, typed.length).trim().toString()
        return after.takeIf { TYPE_TAG.startsWith(it) }
    }

    private const val TYPE_TAG = "!type:"
    private const val TAG_TYPE = "type"
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

private object ColorNameProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        if (atKeyPosition(parameters)) return

        val field = RobustValidation.fieldAround(position) ?: return
        if (!RobustValidation.isColorField(field)) return

        for (name in RobustValidation.colorNames()) {
            result.addElement(LookupElementBuilder.create(name).withTypeText(field.type, true))
        }
        result.stopHere()
    }
}

private object LocalizationIdProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val position = parameters.position
        if (atKeyPosition(parameters)) return

        val field = RobustValidation.fieldAround(position) ?: return
        if (!field.localized) return

        val keys = RobustLocalization.keys(position.project)
        logger.debug { "Localization ids for '${field.name}': ${keys.size}" }
        if (keys.isEmpty()) return

        for (key in keys) {
            result.addElement(LookupElementBuilder.create(key).withTypeText(LOC_ID_TYPE, true))
        }
        result.stopHere()
    }

    private const val LOC_ID_TYPE = "LocId"
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
