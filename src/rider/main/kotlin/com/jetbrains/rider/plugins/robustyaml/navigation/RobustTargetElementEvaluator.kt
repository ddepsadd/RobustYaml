package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * With the caret inside `id: BaseMobMutant` there is no reference to follow and nothing the
 * platform recognises as a named element, so Alt+F7 refused with "Cannot search for usages from
 * this location" before any factory was asked. The declaration of a prototype id is exactly such
 * an element — it is named by its value rather than by its key.
 */
class RobustTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? {
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
            ?: return null

        val value = keyValue.value ?: return null
        if (!PsiTreeUtil.isAncestor(value, element, false)) return null

        // An item of a sequence is handed over as itself: the key-value above it holds the whole
        // list, so the id under the caret would be lost — `prototypes:` of a spawner names as many
        // prototypes as it has lines.
        val item = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java, false)
        if (item != null && PsiTreeUtil.isAncestor(value, item, false)) {
            val scalar = item.value as? YAMLScalar ?: return null
            return scalar.takeIf { searchedTarget(it) != null }
        }

        // Anything the search can work with, not just a declaration: a reference to a localization
        // key is one of these, and waiting for the backend to type the field would make Alt+F7
        // depend on whether the daemon has already asked about that very key.
        return keyValue.takeIf { searchedTarget(it) != null }
    }
}
