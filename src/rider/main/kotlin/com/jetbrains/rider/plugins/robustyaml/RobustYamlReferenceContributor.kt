package com.jetbrains.rider.plugins.robustyaml

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class RobustYamlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            ComponentReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            ResourcePathReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            SpriteStateReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            PrototypeKindReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            PrototypeIdReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            LocalizationIdReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLKeyValue::class.java),
            TypeTagReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLSequenceItem::class.java),
            TypeTagReferenceProvider,
        )
    }
}

/**
 * The tag is a lexer token, and references are collected from elements that own them, so the
 * provider sits on the carrier and points a range inside it. The range covers the whole tag,
 * `!type:` included: leaving the prefix out only earned a "Cannot find declaration" popup on it.
 */
private object TypeTagReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val tag = RobustYamlContext.tagToken(element) ?: return PsiReference.EMPTY_ARRAY
        if (tag.textLength <= TYPE_TAG.length) return PsiReference.EMPTY_ARRAY

        val start = tag.textRange.startOffset - element.textRange.startOffset
        return arrayOf(TypeTagReference(element, TextRange(start, start + tag.textLength)))
    }
}

class TypeTagReference(carrier: PsiElement, range: TextRange) :
    PsiReferenceBase<PsiElement>(carrier, range, true) {

    private val className: String get() = value.removePrefix(TYPE_TAG)

    override fun resolve(): PsiElement? {
        val name = className.takeIf { it.isNotEmpty() } ?: return null
        val file = RobustDataFields.declaringFiles(element.project, name).firstOrNull() ?: return null
        val psi = PsiManager.getInstance(element.project).findFile(file) ?: return null
        return RobustDeclarationTarget.of(psi, name)
    }
}

private const val TYPE_TAG = "!type:"

private object LocalizationIdReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        if (!RobustYamlContext.isLocalizationValue(scalar)) return PsiReference.EMPTY_ARRAY
        return arrayOf(LocalizationIdReference(scalar))
    }
}

class LocalizationIdReference(scalar: YAMLScalar) :
    PsiReferenceBase<YAMLScalar>(scalar, ElementManipulators.getValueTextRange(scalar), true) {

    override fun resolve(): PsiElement? =
        RobustLocalization.declaration(element.project, RobustLocalization.messageId(value))

    /**
     * The declaration stands for a line of a `.ftl`, which has no PSI of its own, so a fresh
     * instance is built on every resolve and identity comparison never holds.
     */
    override fun isReferenceTo(element: PsiElement): Boolean =
        RobustLocalization.declaredMessageId(element) == RobustLocalization.messageId(value)

    /**
     * The reference spans the whole value, but what is being renamed is the message alone: what
     * follows a dot is a Fluent attribute of it, and rewriting the range wholesale would drop the
     * attribute — 29 values of ss14-wega are written that way.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = value.substringAfter('.', "")
        val replacement = if (attribute.isEmpty()) newElementName else "$newElementName.$attribute"
        return super.handleElementRename(replacement)
    }
}

private object PrototypeIdReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        if (!RobustYamlContext.isPrototypeIdReference(scalar)) return PsiReference.EMPTY_ARRAY
        return arrayOf(PrototypeIdReference(scalar))
    }
}

class PrototypeIdReference(scalar: YAMLScalar) :
    PsiReferenceBase<YAMLScalar>(scalar, ElementManipulators.getValueTextRange(scalar), true) {

    override fun resolve(): PsiElement? =
        RobustPrototypeIndex.findDeclarations(element.project, value).firstOrNull()

    /**
     * One id may be declared by several kinds — `Syndicate` is an antag, a department and more —
     * and [resolve] answers with the first of them. Comparing against that one would drop the
     * usages of every other declaration, so the target is recognised by what it declares.
     */
    override fun isReferenceTo(element: PsiElement): Boolean =
        element is YAMLKeyValue &&
            RobustYamlContext.isPrototypeIdDeclaration(element) &&
            element.valueText == value
}

private object PrototypeKindReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val keyValue = scalar.parent as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
        if (keyValue.value !== scalar) return PsiReference.EMPTY_ARRAY
        if (!RobustYamlContext.isPrototypeKindKey(keyValue)) return PsiReference.EMPTY_ARRAY
        return arrayOf(PrototypeKindReference(scalar))
    }
}

class PrototypeKindReference(scalar: YAMLScalar) :
    PsiReferenceBase<YAMLScalar>(scalar, ElementManipulators.getValueTextRange(scalar), true) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val file = RobustPrototypeIndex.findKindFile(project, value) ?: return null
        return PsiManager.getInstance(project).findFile(file)
    }
}

private object SpriteStateReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val sprite = RobustYamlContext.enclosingSpritePath(scalar) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(SpriteStateReference(scalar, sprite))
    }
}

class SpriteStateReference(scalar: YAMLScalar, private val spritePath: String) :
    PsiReferenceBase<YAMLScalar>(scalar, ElementManipulators.getValueTextRange(scalar), true) {

    override fun resolve(): PsiElement? {
        val rsi = rsiDirectory() ?: return null
        val png = rsi.findChild("$value.png") ?: return null
        return PsiManager.getInstance(element.project).findFile(png)
    }

    override fun getVariants(): Array<Any> {
        val rsi = rsiDirectory() ?: return emptyArray()
        return rsi.children
            .filter { !it.isDirectory && it.extension.equals("png", ignoreCase = true) }
            .map { LookupElementBuilder.create(it.nameWithoutExtension).withTypeText("state", true) }
            .toTypedArray()
    }

    private fun rsiDirectory(): VirtualFile? =
        RobustResources.resolve(element.project, spritePath)?.takeIf { it.isDirectory }
}

private object ResourcePathReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        if (!RobustYamlContext.isResourcePathValue(scalar)) return PsiReference.EMPTY_ARRAY

        val text = scalar.textValue
        if (text.contains('\\') || text.any { it.isWhitespace() }) return PsiReference.EMPTY_ARRAY

        val valueRange = ElementManipulators.getValueTextRange(scalar)
        val absolute = text.startsWith("/")
        val path = if (absolute) text.substring(1) else text
        val start = valueRange.startOffset + if (absolute) 1 else 0

        return ResourcePathReferenceSet(path, scalar, start, this, absolute).allReferences
            .let { @Suppress("UNCHECKED_CAST") (it as Array<PsiReference>) }
    }
}

class ResourcePathReferenceSet(
    path: String,
    scalar: YAMLScalar,
    startInElement: Int,
    provider: PsiReferenceProvider,
    private val absolute: Boolean,
) : FileReferenceSet(path, scalar, startInElement, provider, true) {

    override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
        val project = element.project
        val manager = PsiManager.getInstance(project)
        val roots = RobustResources.roots(project)
        val directories = if (absolute) roots else roots.mapNotNull { it.findChild(TEXTURES_DIR) }
        return directories.mapNotNull { manager.findDirectory(it) }
    }

    override fun isSoft(): Boolean = true

    private companion object {
        const val TEXTURES_DIR = "Textures"
    }
}

private object ComponentReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val keyValue = scalar.parent as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
        if (!RobustYamlContext.isComponentTypeKey(keyValue)) return PsiReference.EMPTY_ARRAY
        return arrayOf(ComponentReference(scalar))
    }
}

class ComponentReference(scalar: YAMLScalar) :
    PsiReferenceBase<YAMLScalar>(scalar, ElementManipulators.getValueTextRange(scalar), true) {

    override fun resolve(): PsiElement? {
        val name = value
        if (name.isEmpty()) return null
        return findComponentFile(element.project, name)
    }

    companion object {
        fun findComponentFile(project: Project, componentName: String): PsiElement? {
            val file = RobustComponentIndex.findComponentFile(project, componentName) ?: return null
            return PsiManager.getInstance(project).findFile(file)
        }
    }
}
