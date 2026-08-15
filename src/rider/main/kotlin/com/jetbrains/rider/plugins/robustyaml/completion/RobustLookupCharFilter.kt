package com.jetbrains.rider.plugins.robustyaml.completion

import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import org.jetbrains.yaml.psi.YAMLFile

/**
 * A space must not pick the highlighted item. By default the platform answers
 * `SELECT_ITEM_AND_FINISH_LOOKUP` and types the character afterwards, which left `type: Sprite `
 * behind. C# in Rider does not do that: a space there closes the popup and is typed as itself, and
 * picking an item is left to Enter and Tab.
 *
 * Only prototype declarations are affected, so plain YAML elsewhere in the solution keeps the
 * stock behaviour.
 */
class RobustLookupCharFilter : CharFilter() {
    override fun acceptChar(c: Char, prefixLength: Int, lookup: Lookup): Result? {
        if (c != ' ' || !lookup.isCompletion) return null

        val file = lookup.psiFile as? YAMLFile ?: return null
        val element = file.findElementAt(lookup.editor.caretModel.offset) ?: return null
        if (RobustYamlContext.declarationAround(element) == null) return null

        return Result.HIDE_LOOKUP
    }
}
