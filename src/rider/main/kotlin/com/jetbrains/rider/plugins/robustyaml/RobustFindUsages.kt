package com.jetbrains.rider.plugins.robustyaml

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val logger = logger<RobustFindUsagesHandler>()

/**
 * What Alt+F7 can look for: an id declared by a prototype, and a message declared in a `.ftl`.
 * Both are named by their text rather than by a name of their own, which is why the stock searcher
 * is of no use — it would look for the word `id`, the name of the key-value it is handed.
 */
class RobustFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        val target = searchedTarget(element)
        logger.debug {
            // A stand-in declaration has no text of its own, and `getText` is nullable there.
            val text: String? = element.text
            "canFindUsages(${element.javaClass.simpleName} " +
                "'${text?.lineSequence()?.firstOrNull().orEmpty()}') -> $target"
        }
        return target != null
    }

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler? {
        val target = searchedTarget(element) ?: return null
        return RobustFindUsagesHandler(element, target)
    }
}

/** What is being searched for: the text of a reference, and the kind of reference it stands for. */
data class SearchedTarget(val name: String, val localization: Boolean)

/**
 * The caret may stand on the declaration or on a reference to it, and both must lead to the same
 * search: `TargetElementUtil` hands over the key-value it finds — `parent: BaseAction` as readily
 * as `id: BaseAction` — because a key-value is a [com.intellij.psi.PsiNamedElement] and the walk
 * stops there, never reaching the reference underneath.
 */
fun searchedTarget(element: PsiElement): SearchedTarget? {
    RobustLocalization.declaredMessageId(element)?.let { return SearchedTarget(it, localization = true) }

    val keyValue = element as? YAMLKeyValue ?: return null
    if (RobustYamlContext.isPrototypeIdDeclaration(keyValue)) {
        return keyValue.valueText.takeIf { it.isNotEmpty() }?.let { SearchedTarget(it, localization = false) }
    }

    // `type:` names a kind or a component and has references of its own; a nested `id:` names an
    // animation or a sprite state — 3% of them in the content — and refers to nothing at all.
    if (keyValue.keyText == TYPE_KEY || keyValue.keyText == ID_KEY) return null

    val scalar = keyValue.value as? YAMLScalar ?: return null
    val text = scalar.textValue.takeIf { it.isNotEmpty() } ?: return null
    if (RobustYamlContext.isPrototypeIdReference(scalar)) {
        return SearchedTarget(text, localization = false)
    }

    // A localization key is recognised the way the hover recognises it — by the index of `.ftl`,
    // not by its shape: 111 of the 83693 keys are PascalCase (`JobCaptain`) and look like an id.
    // Waiting for the backend to type the field would leave Alt+F7 dead until it warms up.
    val message = RobustLocalization.messageId(text)
    if (RobustLocalization.hasMessage(keyValue.project, message)) {
        return SearchedTarget(message, localization = true)
    }
    return null
}

private const val ID_KEY = "id"
private const val TYPE_KEY = "type"

/**
 * Every reference to [target] in the content. The index narrows the walk to the files that mention
 * the text; what the value actually refers to is decided by the reference sitting on it, so a value
 * that merely reads alike — a sprite state, a component name — never gets in.
 *
 * A read action is taken per file rather than once for all of them: holding one over thousands of
 * files would stall every write the IDE wants to make.
 */
internal fun processReferences(
    project: Project,
    target: SearchedTarget,
    consumer: (PsiReference) -> Boolean,
): Boolean {
    if (!processYamlReferences(project, target, consumer)) return false

    // A localization key is named from C# as often as from a prototype — 5657 of the 56269 messages
    // of ss14-wega against 10875 — and those files have no PSI on the frontend to search through.
    if (!target.localization) return true
    return processLocaleTextUsages(project, target.name, consumer)
}

private fun processYamlReferences(
    project: Project,
    target: SearchedTarget,
    consumer: (PsiReference) -> Boolean,
): Boolean {
    val files = ReadAction.compute<Collection<VirtualFile>, RuntimeException> {
        RobustYamlValueIndex.files(project, target.name)
    }
    logger.debug { "References to '${target.name}': ${files.size} candidate files" }

    val manager = PsiManager.getInstance(project)
    for (file in files) {
        ProgressManager.checkCanceled()

        val wanted = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = manager.findFile(file) ?: return@compute true
            for (scalar in PsiTreeUtil.findChildrenOfType(psiFile, YAMLScalar::class.java)) {
                val reference = referenceOn(scalar, target) ?: continue
                if (!consumer(reference)) return@compute false
            }
            true
        }
        if (!wanted) return false
    }
    return true
}

private fun referenceOn(scalar: YAMLScalar, target: SearchedTarget): PsiReference? {
    if (target.localization) {
        if (RobustLocalization.messageId(scalar.textValue) != target.name) return null
        return scalar.references.firstOrNull { it is LocalizationIdReference }
    }

    if (scalar.textValue != target.name) return null
    return scalar.references.firstOrNull { it is PrototypeIdReference }
}

class RobustFindUsagesHandler(element: PsiElement, private val target: SearchedTarget) :
    FindUsagesHandler(element) {

    /**
     * Taken here rather than inside the search: the handler is built under a read action, and
     * [processElementUsages] runs without one. Asking a `YAMLKeyValue` for its project walks up the
     * AST, which asserts read access — the search then died before its first file and Alt+F7
     * answered "Nothing found" with the failure buried in the log as a plugin error.
     */
    private val project: Project =
        ReadAction.compute<Project, RuntimeException> { element.project }

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        if (target.localization && !processDeclarations(processor)) return false
        return processReferences(project, target) { processor.process(UsageInfo(it)) }
    }

    /**
     * Where the message is written down. A declaration is not a usage anywhere else, and for a
     * prototype id it would be pointless — the caret usually starts there. A message is different:
     * it is declared once per culture, 27636 of the 56269 in ss14-wega more than once, and "where is
     * this text" is the question Alt+F7 is being asked. Without them the search answered with the
     * YAML value the caret already stood on and nothing else.
     */
    private fun processDeclarations(processor: Processor<in UsageInfo>): Boolean {
        val manager = PsiManager.getInstance(project)
        for (site in ReadAction.compute<List<RobustLocalization.MessageSite>, RuntimeException> {
            RobustLocalization.sites(project, target.name)
        }) {
            ProgressManager.checkCanceled()

            val wanted = ReadAction.compute<Boolean, RuntimeException> {
                val file = manager.findFile(site.file) ?: return@compute true
                processor.process(UsageInfo(file, site.offset, site.offset + target.name.length))
            }
            if (!wanted) return false
        }
        return true
    }
}
