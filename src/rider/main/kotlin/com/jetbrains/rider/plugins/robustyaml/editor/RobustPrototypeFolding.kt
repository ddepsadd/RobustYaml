package com.jetbrains.rider.plugins.robustyaml.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Folds a declaration into what it is — `entity MobHuman` — and a component into its name. The
 * stock builder folds a mapping into `...`, which in a file of two hundred entities says nothing;
 * the id is the one word that tells them apart.
 *
 * Adding a builder does not replace the one YAML already has: `LanguageFolding.forLanguage` wraps
 * everything registered for the language into a `CompositeFoldingBuilder`, so both sets of regions
 * are offered. Ours start at the dash of the sequence item, the stock ones at the mapping after it,
 * so the two never claim the same range.
 */
class RobustPrototypeFolding : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val regions = mutableListOf<FoldingDescriptor>()
        for (item in PsiTreeUtil.findChildrenOfType(root, YAMLSequenceItem::class.java)) {
            val title = titleOf(item) ?: continue
            val range = foldable(item, document) ?: continue
            regions += FoldingDescriptor(item.node, range, null, title)
        }
        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? = null

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /**
     * What the folded item is called. A declaration is named by its kind and its id, a component by
     * its type alone — under `components:` the type is the name, and there is nothing else to say
     * in one line.
     */
    private fun titleOf(item: YAMLSequenceItem): String? {
        val mapping = item.value as? YAMLMapping ?: return null
        val typeKey = mapping.getKeyValueByKey(TYPE_KEY) ?: return null
        val type = (typeKey.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() } ?: return null

        if (RobustYamlContext.isComponentTypeKey(typeKey)) return type
        if (!RobustYamlContext.isPrototypeKindKey(typeKey)) return null

        val id = (mapping.getKeyValueByKey(ID_KEY)?.value as? YAMLScalar)?.textValue
        return if (id.isNullOrEmpty()) type else "$type $id"
    }

    /**
     * The item without the blank line that usually follows it: a region that swallowed the trailing
     * newline would glue the next declaration onto the folded one.
     */
    private fun foldable(item: YAMLSequenceItem, document: Document): TextRange? {
        val range = item.textRange
        var end = range.endOffset
        while (end > range.startOffset && document.charsSequence[end - 1].isWhitespace()) end--
        // One line folds into itself; the platform draws a marker for it and the placeholder would
        // be longer than the text it hides.
        if (document.getLineNumber(end) == document.getLineNumber(range.startOffset)) return null
        return TextRange(range.startOffset, end)
    }

    private companion object {
        const val TYPE_KEY = "type"
        const val ID_KEY = "id"
    }
}
