package com.jetbrains.rider.plugins.robustyaml.reference

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.languages.fileTypes.csharp.psi.CSharpStringLiteralExpression
import com.jetbrains.rider.plugins.robustyaml.index.CodeLink
import com.jetbrains.rider.plugins.robustyaml.index.CodeLinkKind
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.codeLinks
import com.jetbrains.rider.plugins.robustyaml.lookup.expectedKind

/**
 * References inside a string literal of C#. The frontend does have PSI for those — 2026.2 parses C#
 * with `CSharpKotoParserDefinition`, and `CSharpStringLiteralExpression` carries a registered
 * `ElementManipulator` — so Rider hangs its own references on the same class
 * (`CSharpWebReferenceContributor`), and a reference is a better carrier than a handler reading the
 * caret offset: it underlines under Ctrl, it says which part of the literal it covers, and its
 * `getVariants` is what the platform's `ReferenceBasedCompletionContributor` turns into completion.
 *
 * What a literal names is still decided by [com.jetbrains.rider.plugins.robustyaml.index.RobustCodeLinks],
 * read off the text of the file: the PSI says where the literals are, not what is written in them,
 * and the same rule has to answer for the index, which has no PSI at all.
 */
class RobustCodeReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(CSharpStringLiteralExpression::class.java),
            CodeLiteralReferenceProvider,
        )
    }
}

private object CodeLiteralReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val content = ElementManipulators.getValueTextRange(element)
        val start = element.textRange.startOffset + content.startOffset
        val link = codeLinks(file).firstOrNull { it.start == start } ?: return PsiReference.EMPTY_ARRAY

        return when (link.kind) {
            CodeLinkKind.SPRITE_STATE ->
                arrayOf(SpriteStateReference(element, link.spritePath.orEmpty()))

            CodeLinkKind.PROTOTYPE_ID -> arrayOf(PrototypeIdLiteralReference(element, link))

            // The path is absolute by the rule that recognised it, so the set starts one character
            // in and resolves against the roots rather than against `Textures`.
            CodeLinkKind.PATH ->
                ResourcePathReferenceSet(
                    link.value.substring(1),
                    element,
                    content.startOffset + 1,
                    this,
                    true,
                ).allReferences.let { @Suppress("UNCHECKED_CAST") (it as Array<PsiReference>) }
        }
    }
}

/**
 * An id written in C#. Apart from the kind it is [PrototypeIdReference]: there the key of the YAML
 * decides what may stand under it, here the type beside the literal does, and `ProtoId<X>` names it
 * outright — so the declaration is looked for under that kind alone, and the completion offers the
 * ids of that kind alone.
 */
class PrototypeIdLiteralReference(element: PsiElement, private val link: CodeLink) :
    PsiReferenceBase<PsiElement>(element, ElementManipulators.getValueTextRange(element), true) {

    override fun resolve(): PsiElement? =
        RobustPrototypeIndex.findDeclarations(element.project, value, kind()).firstOrNull()

    override fun getVariants(): Array<Any> {
        val kind = kind() ?: return emptyArray()
        return RobustPrototypeIndex.idsOfKind(element.project, kind)
            .map { LookupElementBuilder.create(it).withTypeText(kind, true) }
            .toTypedArray()
    }

    private fun kind(): String? = expectedKind(element.project, link)
}
