package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val logger = logger<RobustRenameProcessor>()

/**
 * Shift+F6 on a prototype id. The declaration is a key-value named by its value rather than by its
 * key, so neither half of the stock rename fits: `setName` would rewrite the key `id`, and
 * `ReferencesSearch` would look for usages of the word `id`. Both are replaced here.
 */
class RobustRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean {
        val target = searchedTarget(element)
        logger.debug {
            val text: String? = element.text
            "canProcessElement(${element.javaClass.simpleName} " +
                "'${text?.lineSequence()?.firstOrNull().orEmpty()}') -> $target"
        }
        return target != null && !target.localization
    }

    /**
     * The caret is more often on a reference than on the declaration, and renaming the reference
     * alone would leave the id it points at untouched. An id declared more than once belongs to
     * several kinds at once — `Syndicate` is an antag, a department and more — and there is no way
     * to tell which of them the caret meant, so the refactoring steps aside instead of guessing.
     */
    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val keyValue = element as? YAMLKeyValue ?: return element
        if (RobustYamlContext.isPrototypeIdDeclaration(keyValue)) return element

        val id = (keyValue.value as? YAMLScalar)?.textValue ?: return null
        val declarations = RobustPrototypeIndex.findDeclarations(element.project, id)
        logger.debug { "Renaming from a reference to '$id': ${declarations.size} declarations" }
        return declarations.singleOrNull()
    }

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> {
        val target = searchedTarget(element) ?: return emptyList()

        val references = mutableListOf<PsiReference>()
        processReferences(element.project, target) {
            references += it
            true
        }
        logger.debug { "Renaming '${target.name}': ${references.size} references" }
        return references
    }

    /**
     * The platform skips usages it finds in files outside the project — it says as much before the
     * refactoring starts — and prototypes are attached as a synthetic library, so every reference
     * would be left behind. They are rewritten here instead, through the same manipulators the
     * platform would have used; a reference already carrying the new text is written again to no
     * effect. The declaration comes last: its new text goes into the value of the key, because
     * `setName` on a key-value renames the key.
     */
    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        // Undo is per document unless the command says otherwise, and a rename spans as many files
        // as it has references: without this, Ctrl+Z in one file would leave the others rewritten.
        val project = element.project
        CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)

        var rewritten = 0
        val touched = mutableListOf<VirtualFile>()
        for (usage in usages) {
            val reference = usage.reference ?: continue
            if (reference !is PrototypeIdReference) continue

            reference.element.containingFile?.virtualFile?.let { touched += it }
            reference.handleElementRename(newName)
            rewritten++
        }
        if (touched.isNotEmpty()) {
            CommandProcessor.getInstance().addAffectedFiles(project, *touched.distinct().toTypedArray())
        }
        logger.debug { "Renamed to '$newName': $rewritten references rewritten" }

        val value = (element as? YAMLKeyValue)?.value as? YAMLScalar
        if (value != null) {
            ElementManipulators.getManipulator(value)?.handleContentChange(value, newName)
        }
        listener?.elementRenamed(element)
    }
}
