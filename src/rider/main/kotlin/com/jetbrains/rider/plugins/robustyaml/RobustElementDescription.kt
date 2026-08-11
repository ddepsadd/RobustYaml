package com.jetbrains.rider.plugins.robustyaml

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * How a prototype id is named in refactoring and search UI. The platform asks the element for its
 * name, and the name of a key-value is its key — so Shift+F6 offered to rename `id` and put that in
 * the input field, while Find Usages titled its results "Usages of id". The declaration is named by
 * its value, and that is what both should show.
 */
class RobustElementDescription : ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        val keyValue = element as? YAMLKeyValue ?: return null
        if (!RobustYamlContext.isPrototypeIdDeclaration(keyValue)) return null

        return when (location) {
            is UsageViewTypeLocation -> "prototype id"
            is UsageViewShortNameLocation, is UsageViewLongNameLocation -> keyValue.valueText
            else -> null
        }
    }
}
