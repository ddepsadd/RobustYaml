package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * How a prototype id is named in refactoring and search UI. The platform asks the element for its
 * name, and the name of a key-value is its key — so Shift+F6 offered to rename `id` and put that in
 * the input field, while Find Usages titled its results "Usages of id". The declaration is named by
 * its value, and that is what both should show.
 */
class RobustElementDescription : ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        RobustLocalization.declaredMessageId(element)?.let { id ->
            return when (location) {
                is UsageViewTypeLocation -> "localization message"
                is UsageViewShortNameLocation, is UsageViewLongNameLocation -> id
                else -> null
            }
        }

        val keyValue = element as? YAMLKeyValue ?: return null

        // Not just declarations: with the caret on `tooltip: door-remote-open-close-text` the search
        // is for the message, yet the window was titled "tooltip in All Places" — the key is what a
        // key-value answers when asked for its name, whichever side of the reference it stands on.
        val target = searchedTarget(keyValue) ?: return null

        return when (location) {
            is UsageViewTypeLocation -> if (target.localization) "localization message" else "prototype id"
            is UsageViewShortNameLocation, is UsageViewLongNameLocation -> target.name
            else -> null
        }
    }
}
