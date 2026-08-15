package com.jetbrains.rider.plugins.robustyaml.reference

import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustGuidebook

/**
 * Prototype ids named from a guidebook document. Registered for XML rather than for plain text
 * because the frontend parses XML: the value of an attribute is an element of its own, with a
 * manipulator that writes inside the quotes, so nothing here has to carry ranges by hand the way
 * [LocaleTextReference] does for `.ftl` and `.cs`.
 *
 * Which of Ctrl+click, Alt+F7 and Shift+F6 survive is decided by `backend.actions.support`, and XML
 * is not among the languages Rider hands to ReSharper — the registrations for it are commented out
 * in `RiderExtensionPoints.xml`, so all three stay with the frontend.
 */
class RobustGuidebookReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(), GuidebookPrototypeProvider)
    }
}

private object GuidebookPrototypeProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        if (!RobustGuidebook.namesPrototype(value)) return PsiReference.EMPTY_ARRAY
        if (value.value.isEmpty()) return PsiReference.EMPTY_ARRAY
        return arrayOf(PrototypeIdReference(value))
    }
}
