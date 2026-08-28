package com.jetbrains.rider.plugins.robustyaml.editor

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.structureView.YAMLCustomStructureViewFactory

/**
 * The structure of a prototype file: the declarations it holds, and under each the components it
 * registers. The stock view is a tree of keys, where every declaration reads `- ` and the id is a
 * child two levels down — true to the YAML and useless for finding anything.
 *
 * Plugged in through the extension point YAML keeps for exactly this
 * (`com.intellij.yaml.customStructureViewFactory`), not by registering a second
 * `lang.psiStructureViewFactory`: that one is resolved to a single instance per language, so taking
 * it would mean owning the structure view of every YAML file in the IDE. Answering null here leaves
 * the stock view in place, and that is what happens in any file that declares no prototypes.
 */
class RobustPrototypeStructureView : YAMLCustomStructureViewFactory {
    override fun getStructureViewBuilder(file: YAMLFile): StructureViewBuilder? {
        if (declarationsOf(file).isEmpty()) return null

        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                PrototypeStructureModel(file, editor)
        }
    }
}

private class PrototypeStructureModel(file: YAMLFile, editor: Editor?) :
    StructureViewModelBase(file, editor, FileElement(file)),
    StructureViewModel.ElementInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        element is DeclarationElement && element.componentItems().isEmpty()
}

private class FileElement(private val file: YAMLFile) : PsiTreeElementBase<YAMLFile>(file) {
    override fun getPresentableText(): String = file.name

    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        declarationsOf(file).map { DeclarationElement(it) }
}

private class DeclarationElement(item: YAMLSequenceItem) : PsiTreeElementBase<YAMLSequenceItem>(item) {
    override fun getPresentableText(): String? = titleOf(element ?: return null)

    override fun getChildrenBase(): Collection<StructureViewTreeElement> =
        componentItems().map { ComponentElement(it) }

    fun componentItems(): List<YAMLSequenceItem> {
        val mapping = element?.value as? YAMLMapping ?: return emptyList()
        val components = mapping.getKeyValueByKey(COMPONENTS_KEY)?.value ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(components, YAMLSequenceItem::class.java)
            .filter { it.parent === components }
    }
}

private class ComponentElement(item: YAMLSequenceItem) : PsiTreeElementBase<YAMLSequenceItem>(item) {
    override fun getPresentableText(): String? =
        typeOf(element?.value as? YAMLMapping ?: return null)

    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}

/** Every declaration of the file: an item of the document's own sequence with a kind for a type. */
private fun declarationsOf(file: YAMLFile): List<YAMLSequenceItem> =
    PsiTreeUtil.findChildrenOfType(file, YAMLSequenceItem::class.java)
        .filter { item ->
            val typeKey = (item.value as? YAMLMapping)?.getKeyValueByKey(TYPE_KEY)
            typeKey != null && RobustYamlContext.isPrototypeKindKey(typeKey)
        }

private fun titleOf(item: YAMLSequenceItem): String? {
    val mapping = item.value as? YAMLMapping ?: return null
    val type = typeOf(mapping) ?: return null
    val id = (mapping.getKeyValueByKey(ID_KEY)?.value as? YAMLScalar)?.textValue
    return if (id.isNullOrEmpty()) type else "$id  ($type)"
}

private fun typeOf(mapping: YAMLMapping): String? =
    (mapping.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }

private const val TYPE_KEY = "type"
private const val ID_KEY = "id"
private const val COMPONENTS_KEY = "components"
