package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.PsiNavigateUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.jetbrains.rider.plugins.robustyaml.index.RobustPrototypeIdIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import javax.swing.Icon

class RobustPrototypeSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?,
    ) {
        FileBasedIndex.getInstance()
            .processAllKeys(RobustPrototypeIdIndex.NAME, processor, scope, filter)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        for (declaration in RobustPrototypeIndex.findDeclarations(parameters.project, name)) {
            if (!processor.process(PrototypeNavigationItem(name, declaration))) return
        }
    }
}

private class PrototypeNavigationItem(
    private val id: String,
    private val declaration: YAMLKeyValue,
) : NavigationItem {

    override fun getName(): String = id

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = id

        override fun getLocationString(): String {
            val kind = prototypeKind()
            val file = declaration.containingFile?.name.orEmpty()
            return if (kind == null) file else "$kind in $file"
        }

        override fun getIcon(unused: Boolean): Icon? = declaration.getIcon(0)
    }

    override fun navigate(requestFocus: Boolean) = PsiNavigateUtil.navigate(declaration, requestFocus)

    override fun canNavigate(): Boolean = PsiNavigateUtil.getNavigatable(declaration) != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    private fun prototypeKind(): String? {
        val mapping = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
        return mapping?.getKeyValueByKey("type")?.valueText?.takeIf { it.isNotEmpty() }
    }
}
