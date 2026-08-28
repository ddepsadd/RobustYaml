package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.index.RobustYamlReferenceIndex
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Who a prototype inherits from and who inherits from it.
 *
 * Downwards there is no index to ask directly, and none was added: `RobustYamlReferenceIndex`
 * already records every id a file names as a `parent:` under its own prefix, so the files that
 * could hold a child are one query away and only they are opened. On the widest id of the checkout
 * that is a few hundred files rather than three thousand.
 *
 * The scope is `getAllScope`, as everywhere else here: prototypes come in as a `SyntheticLibrary`
 * and are not in the content scope at all.
 *
 * Both directions are asked with a kind, not with an id alone. One id may name prototypes of several
 * kinds — `Binoculars` is an entity and the `latheRecipe` that makes it — and inheritance never
 * crosses between them, so an unfiltered walk showed the parents of the item above the recipe.
 */
object RobustPrototypeHierarchy {
    fun parentsOf(project: Project, id: String, kind: String?): List<String> {
        val parents = LinkedHashSet<String>()
        for (declaration in RobustPrototypeIndex.findDeclarations(project, id, kind)) {
            val prototype = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
                ?: continue
            parents += RobustYamlContext.parentIds(prototype)
        }
        return parents.toList()
    }

    fun childrenOf(project: Project, id: String, kind: String?): List<String> {
        val index = FileBasedIndex.getInstance()
        val scope = ProjectScope.getAllScope(project)
        val manager = PsiManager.getInstance(project)

        val children = sortedSetOf<String>()
        val files = index.getContainingFiles(
            RobustYamlReferenceIndex.NAME,
            RobustYamlReferenceIndex.PARENT_PREFIX + id,
            scope,
        )
        for (file in files) {
            val psi = manager.findFile(file) as? YAMLFile ?: continue
            for (item in PsiTreeUtil.findChildrenOfType(psi, YAMLSequenceItem::class.java)) {
                val mapping = item.value as? YAMLMapping ?: continue
                val typeKey = mapping.getKeyValueByKey(TYPE_KEY) ?: continue
                if (!RobustYamlContext.isPrototypeKindKey(typeKey)) continue
                // Inheritance stays inside a kind, and the id alone does not say which: `Binoculars`
                // is both an entity and the recipe that makes it.
                if (kind != null && typeKey.valueText.trim() != kind) continue
                if (id !in RobustYamlContext.parentIds(mapping)) continue
                idOf(mapping)?.let { children += it }
            }
        }
        return children.toList()
    }

    private fun idOf(prototype: YAMLMapping): String? =
        prototype.getKeyValueByKey(ID_KEY)?.valueText?.trim()?.takeIf { it.isNotEmpty() }

    private const val ID_KEY = "id"
    private const val TYPE_KEY = "type"
}
