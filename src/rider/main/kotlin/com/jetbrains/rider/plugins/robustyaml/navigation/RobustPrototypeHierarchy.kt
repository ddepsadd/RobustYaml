package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeHierarchy
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.Comparator
import java.util.function.Supplier
import javax.swing.JPanel
import javax.swing.JTree

/**
 * Ctrl+H over a prototype id: who it inherits from, and who inherits from it.
 *
 * Inheritance in Robust is a graph of ids rather than of types, and there is no PSI element that
 * stands for "the prototype X" — one id may be declared in several files and of several kinds. So
 * the node of the tree is the declaration of an id, found through the index, and the edges are read
 * from `parent:` in both directions.
 *
 * Chosen over a tree of files or of components because it is the question the content raises:
 * 15823 of 30493 declarations have a `parent:`, `required:` fields and sprites reach a prototype
 * through the chain, and the chain is the one thing a file never shows.
 */
class RobustPrototypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(context: DataContext): PsiElement? {
        val editor = context.getData(CommonDataKeys.EDITOR) ?: return null
        val file = context.getData(CommonDataKeys.PSI_FILE) ?: return null

        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
            ?: return null

        if (RobustYamlContext.isPrototypeIdDeclaration(keyValue)) return keyValue

        // Standing on a reference is the common case — `parent: BaseItem` is where the question is
        // asked from — so the declaration it points at becomes the base of the tree.
        val id = keyValue.valueText.trim().takeIf { it.isNotEmpty() } ?: return null
        val scalar = keyValue.value as? YAMLScalar ?: return null
        if (!RobustYamlContext.isPrototypeIdReference(scalar)) return null
        return RobustPrototypeIndex.findDeclarations(keyValue.project, id).firstOrNull()
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        PrototypeHierarchyBrowser(target.project, target)

    override fun browserActivated(browser: HierarchyBrowser) {
        (browser as PrototypeHierarchyBrowser)
            .changeView(TypeHierarchyBrowserBase.getTypeHierarchyType())
    }
}

private class PrototypeHierarchyBrowser(project: Project, element: PsiElement) :
    TypeHierarchyBrowserBase(project, element) {

    override fun isInterface(psiElement: PsiElement): Boolean = false

    override fun canBeDeleted(psiElement: PsiElement): Boolean = false

    override fun getQualifiedName(psiElement: PsiElement): String = idOf(psiElement).orEmpty()

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        descriptor.psiElement

    override fun getPrevOccurenceActionNameImpl(): String = "Previous prototype"

    override fun getNextOccurenceActionNameImpl(): String = "Next prototype"

    override fun getActionPlace(): String = ActionPlaces.TYPE_HIERARCHY_VIEW_TOOLBAR

    override fun createLegendPanel(): JPanel? = null

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)
    }

    override fun isApplicableElement(element: PsiElement): Boolean =
        element is YAMLKeyValue && RobustYamlContext.isPrototypeIdDeclaration(element)

    override fun getComparator(): Comparator<NodeDescriptor<*>> =
        Comparator { first, second -> first.toString().compareTo(second.toString()) }

    /**
     * The name of the tab, which the platform would otherwise build as "Class id": the word for a
     * type hierarchy plus `PsiNamedElement` of a `YAMLKeyValue`, and the name of that is the key —
     * literally `id`. Both halves are replaced: the tab says which prototype, and the three views
     * are named for what they hold rather than for classes, of which there are none here.
     */
    override fun getContentDisplayName(typeName: String, element: PsiElement): String? {
        val id = idOf(element) ?: return null
        val kind = kindOf(element)
        return if (kind == null) id else "$id ($kind)"
    }

    override fun getPresentableNameMap(): MutableMap<String, Supplier<String>> = mutableMapOf(
        getTypeHierarchyType() to Supplier { "Prototype" },
        getSubtypesHierarchyType() to Supplier { "Children" },
        getSupertypesHierarchyType() to Supplier { "Parents" },
    )

    override fun createHierarchyTreeStructure(type: String, element: PsiElement): HierarchyTreeStructure? {
        val id = idOf(element) ?: return null
        val kind = kindOf(element)
        return when (type) {
            getSupertypesHierarchyType() -> PrototypeTreeStructure(element.project, element, id, kind, false)
            getSubtypesHierarchyType(), getTypeHierarchyType() ->
                PrototypeTreeStructure(element.project, element, id, kind, true)
            else -> null
        }
    }
}

/**
 * One direction of the graph. Both are the same walk with the edges reversed, so they share a class
 * and differ by a flag — the alternative was two classes whose only difference was the name of the
 * function they call.
 */
private class PrototypeTreeStructure(
    project: Project,
    element: PsiElement,
    id: String,
    private val kind: String?,
    private val downwards: Boolean,
) : HierarchyTreeStructure(project, PrototypeNodeDescriptor(project, null, element, id, kind, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val node = descriptor as? PrototypeNodeDescriptor ?: return emptyArray()
        val project = node.project ?: return emptyArray()

        val ids =
            if (downwards) RobustPrototypeHierarchy.childrenOf(project, node.id, kind)
            else RobustPrototypeHierarchy.parentsOf(project, node.id, kind)

        return ids.mapNotNull { id ->
            val declaration = RobustPrototypeIndex.findDeclarations(project, id, kind).firstOrNull()
                ?: return@mapNotNull null
            PrototypeNodeDescriptor(project, descriptor, declaration, id, kind, false)
        }.toTypedArray()
    }
}

private class PrototypeNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    element: PsiElement,
    val id: String,
    private val kind: String?,
    isBase: Boolean,
) : HierarchyNodeDescriptor(project, parent, element, isBase) {

    override fun update(): Boolean {
        val changed = super.update()
        // The text of a node is the id, not the text of the `id:` key the element stands for:
        // `PsiNamedElement` on a `YAMLKeyValue` answers with the key, which is the word `id`.
        val appearance = CompositeAppearance()
        appearance.ending.addText(id)
        if (kind != null) appearance.ending.addText("  ($kind)", getPackageNameAttributes())
        if (appearance.toString() != myHighlightedText.toString()) {
            myHighlightedText = appearance
            return true
        }
        return changed
    }
}

private fun idOf(element: PsiElement): String? =
    (element as? YAMLKeyValue)?.valueText?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The kind of the declaration the element stands in, read from its own `- type:`. Taking it from the
 * index by id would answer with whichever kind came first, and one id often names several.
 */
private fun kindOf(element: PsiElement): String? =
    RobustYamlContext.declarationAround(element)?.takeIf { !it.isComponent }?.name
