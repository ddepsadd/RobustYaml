package com.jetbrains.rider.plugins.robustyaml

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class RobustTypeDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val element = file.findElementAt(offset) ?: return emptyList()
        val owningKey = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
        if (owningKey?.key?.textRange?.containsOffset(offset) == true) return fieldTargets(owningKey)
        return typeTargets(file, offset)
    }

    private fun fieldTargets(keyValue: YAMLKeyValue): List<DocumentationTarget> {
        val field = keyValue.keyText
        if (field.isEmpty()) return emptyList()

        val declaration = RobustYamlContext.declarationAround(keyValue) ?: return emptyList()
        val path = pathTo(declaration, keyValue) ?: return emptyList()

        val project = keyValue.project
        val root =
            if (declaration.isComponent) RobustDataFields.componentClass(project, declaration.name)
            else RobustDataFields.prototypeClass(project, declaration.name)
        root ?: return emptyList()

        val owner = if (path.isEmpty()) RobustDataFields.ownerOfField(project, root, field) else null
        if (path.isEmpty() && owner == null) return emptyList()
        return listOf(FieldDocumentationTarget(project, owner, field, root, path))
    }

    private fun pathTo(
        declaration: RobustYamlContext.DeclarationContext,
        keyValue: YAMLKeyValue,
    ): List<String>? {
        val path = ArrayDeque<String>()
        var mapping = keyValue.parentMapping ?: return null
        while (mapping !== declaration.mapping) {
            val owner = PsiTreeUtil.getParentOfType(mapping, YAMLKeyValue::class.java, true) ?: return null
            path.addFirst(owner.keyText)
            mapping = owner.parentMapping ?: return null
        }
        return path.toList()
    }

    private fun typeTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()
        val keyValue = scalar.parent as? YAMLKeyValue ?: return emptyList()
        if (keyValue.value !== scalar) return emptyList()

        val name = scalar.textValue
        if (name.isEmpty()) return emptyList()
        val project = file.project

        val source = when {
            RobustYamlContext.isComponentTypeKey(keyValue) ->
                RobustComponentIndex.findComponentFile(project, name)
                    ?.let { it to listOf("${name}Component", "Shared${name}Component", name) }
            RobustYamlContext.isPrototypeKindKey(keyValue) ->
                RobustPrototypeIndex.findKindFile(project, name)
                    ?.let { it to listOf("${name.replaceFirstChar(Char::uppercase)}Prototype", name) }
            else -> null
        } ?: return emptyList()

        return listOf(TypeDocumentationTarget(project, source.first, name, source.second))
    }
}

private fun StringBuilder.section(header: String, key: TextAttributesKey, value: String) {
    append(DocumentationMarkup.SECTION_HEADER_START)
    append(header)
    append(DocumentationMarkup.SECTION_SEPARATOR)
    HtmlSyntaxInfoUtil.appendStyledSpan(this, key, StringUtil.escapeXmlEntities(value), 1.0f)
    append(DocumentationMarkup.SECTION_END)
}

private class FieldDocumentationTarget(
    private val project: Project,
    private val owner: String?,
    private val field: String,
    private val root: String,
    private val path: List<String>,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(field)
            .locationText(owner ?: (listOf(root) + path).joinToString(" / "))
            .presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            val declared = RobustBackend.getInstance(project).field(root, path, field)
            val summary = owner?.let { smartReadAction(project) { summary(it) } }
            if (declared == null && summary == null) return@asyncDocumentation null

            val html = buildString {
                append(DocumentationMarkup.DEFINITION_START)
                HtmlSyntaxInfoUtil.appendStyledSpan(
                    this,
                    DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                    StringUtil.escapeXmlEntities(field),
                    1.0f,
                )
                if (declared != null) {
                    append(": ")
                    HtmlSyntaxInfoUtil.appendStyledSpan(
                        this,
                        DefaultLanguageHighlighterColors.CLASS_NAME,
                        StringUtil.escapeXmlEntities(declared.type),
                        1.0f,
                    )
                }
                append(DocumentationMarkup.DEFINITION_END)

                if (summary != null) {
                    append(DocumentationMarkup.CONTENT_START)
                    append(summary)
                    append(DocumentationMarkup.CONTENT_END)
                }

                val kind = declared?.prototypeKind
                if (kind != null || owner != null) {
                    append(DocumentationMarkup.SECTIONS_START)
                    if (kind != null) {
                        section("Prototype", RobustYamlColors.PROTOTYPE_KIND, kind)
                    }
                    if (owner != null) {
                        section("Declared in", DefaultLanguageHighlighterColors.CLASS_NAME, owner)
                    }
                    append(DocumentationMarkup.SECTIONS_END)
                }
            }
            DocumentationResult.documentation(DocumentationContent.content(html))
        }

    private fun summary(owner: String): String? {
        for (file in RobustDataFields.declaringFiles(project, owner)) {
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
            val offset = RobustDataFieldIndex.findField(text, owner, field) ?: continue
            return RobustXmlDoc.summaryAt(text, offset) ?: continue
        }
        return null
    }
}

private class TypeDocumentationTarget(
    private val project: Project,
    private val source: VirtualFile,
    private val name: String,
    private val candidates: List<String>,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(name).locationText(source.name).presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            smartReadAction(project) {
                val resolved = resolve()
                val owner = resolved?.first
                    ?: RobustXmlDoc.extract(source, candidates)?.className
                    ?: return@smartReadAction null
                val summary = resolved?.second

                val html = buildString {
                    append(DocumentationMarkup.DEFINITION_START)
                    HtmlSyntaxInfoUtil.appendStyledSpan(
                        this,
                        RobustYamlColors.COMPONENT_NAME,
                        StringUtil.escapeXmlEntities(name),
                        1.0f,
                    )
                    val base = RobustDataFields.basesOf(project, owner).firstOrNull()
                    append(DocumentationMarkup.GRAYED_START)
                    append(" class ")
                    append(StringUtil.escapeXmlEntities(owner))
                    if (base != null) {
                        append(" : ")
                        append(StringUtil.escapeXmlEntities(base))
                    }
                    append(DocumentationMarkup.GRAYED_END)
                    append(DocumentationMarkup.DEFINITION_END)

                    if (summary != null) {
                        append(DocumentationMarkup.CONTENT_START)
                        append(summary)
                        append(DocumentationMarkup.CONTENT_END)
                    }
                    if (owner !in candidates) {
                        append(DocumentationMarkup.SECTIONS_START)
                        section("Inherited from", DefaultLanguageHighlighterColors.CLASS_NAME, owner)
                        append(DocumentationMarkup.SECTIONS_END)
                    }
                }
                DocumentationResult.documentation(DocumentationContent.content(html))
            }
        }

    private fun resolve(): Pair<String, String>? {
        var names = candidates
        var files: Collection<VirtualFile> = listOf(source)
        val visited = mutableSetOf<String>()

        repeat(MAX_DEPTH) {
            var owner: String? = null
            for (file in files) {
                val extracted = RobustXmlDoc.extract(file, names) ?: continue
                if (extracted.summary != null) return extracted.className to extracted.summary
                owner = extracted.className
            }

            val current = owner ?: return null
            if (!visited.add(current)) return null
            val base = RobustDataFields.basesOf(project, current)
                .firstOrNull { it !in IGNORED_BASES && it !in visited } ?: return null
            names = listOf(base)
            files = RobustDataFields.declaringFiles(project, base)
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 8
        val IGNORED_BASES = setOf("Component", "IComponent")
    }
}
