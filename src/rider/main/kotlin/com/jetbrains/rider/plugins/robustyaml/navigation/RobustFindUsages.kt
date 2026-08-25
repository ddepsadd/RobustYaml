package com.jetbrains.rider.plugins.robustyaml.navigation

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.index.RobustCodeLinks
import com.jetbrains.rider.plugins.robustyaml.index.RobustYamlValueIndex
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustDataFields
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustGuidebook
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import com.jetbrains.rider.plugins.robustyaml.lookup.processCodeIdUsages
import com.jetbrains.rider.plugins.robustyaml.lookup.processLocaleTextUsages
import com.jetbrains.rider.plugins.robustyaml.reference.LocalizationIdReference
import com.jetbrains.rider.plugins.robustyaml.reference.PrototypeIdReference
import org.jetbrains.yaml.psi.YAMLAlias
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

    // An item of a sequence has no key-value of its own — `prototypes:` carries the whole list —
    // so the value itself is what the search starts from.
    if (element is YAMLScalar) return targetOfValue(element)

    val keyValue = element as? YAMLKeyValue ?: return null
    if (RobustYamlContext.isPrototypeIdDeclaration(keyValue)) {
        // Not `valueText`: it answers with an empty string for `id: *BackgammonBoard`, and thirteen
        // prototypes in the content are declared that way.
        val declared = RobustYamlContext.resolvedText(keyValue.value)
        return declared?.takeIf { it.isNotEmpty() }?.let { SearchedTarget(it, localization = false) }
    }

    return targetOfValue(keyValue.value as? YAMLScalar ?: return null)
}

/** What a value stands for, whether it is written after a key or as an item of its sequence. */
private fun targetOfValue(scalar: YAMLScalar): SearchedTarget? {
    val text = scalar.textValue.takeIf { it.isNotEmpty() } ?: return null
    val keyValue = RobustYamlContext.owningKey(scalar) ?: return null
    if (RobustYamlContext.isPrototypeIdDeclaration(keyValue)) return null

    // `type:` names a kind or a component and has references of its own; a nested `id:` names an
    // animation or a sprite state — 3% of them in the content — and refers to nothing at all.
    if (keyValue.keyText == TYPE_KEY || keyValue.keyText == ID_KEY) return null

    // The five names, and the ones a datafield of the checkout declares as a prototype id: the
    // caret stands on a reference more often than on a declaration, and `result: Crowbar` is one
    // as much as `parent: Crowbar` is.
    if (RobustYamlContext.isPrototypeIdReference(scalar) ||
        RobustDataFields.namesPrototype(keyValue)
    ) {
        return SearchedTarget(text, localization = false)
    }

    // A localization key is recognised the way the hover recognises it — by the index of `.ftl`,
    // not by its shape: 111 of the 83693 keys are PascalCase (`JobCaptain`) and look like an id.
    // Waiting for the backend to type the field would leave Alt+F7 dead until it warms up.
    val message = RobustLocalization.messageId(text)
    if (RobustLocalization.hasMessage(scalar.project, message)) {
        return SearchedTarget(message, localization = true)
    }
    return null
}

private const val ID_KEY = "id"
private const val TYPE_KEY = "type"

/**
 * A candidate the YAML walk has no business opening. The value index keys the ids of `.cs` files
 * too, and those are read by their own walk — asking the PSI of a C# file for `YAMLScalar` children
 * would answer nothing after building a tree for every candidate.
 */
private fun isCode(file: VirtualFile): Boolean =
    file.extension.equals(RobustCodeLinks.EXTENSION, ignoreCase = true)

/** How many candidate file names the debug log spells out before cutting the list short. */
private const val LOGGED_CANDIDATES = 20

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
    if (target.localization) return processLocaleTextUsages(project, target.name, consumer)

    // An id is named from C# too, by the 1123 literals declared `EntProtoId` or `ProtoId<X>`. The
    // same walk answers both callers, so a rename that misses them cannot happen while the search
    // finds them: what Alt+F7 lists is what Shift+F6 rewrites.
    return processCodeIdUsages(project, target.name, consumer)
}

private fun processYamlReferences(
    project: Project,
    target: SearchedTarget,
    consumer: (PsiReference) -> Boolean,
): Boolean {
    val files = ReadAction.compute<Collection<VirtualFile>, RuntimeException> {
        RobustYamlValueIndex.files(project, target.name).filterNot(::isCode)
    }
    // The names, not just the count: an absent candidate is invisible otherwise, and that is the
    // shape every divergence between the text rule of an index and the PSI rule beside it takes —
    // the reference still resolves in the editor while the file it lives in never reaches the walk.
    logger.debug {
        val listed = files.take(LOGGED_CANDIDATES).joinToString { it.name }
        val more = if (files.size > LOGGED_CANDIDATES) ", …" else ""
        "References to '${target.name}': ${files.size} candidate files [$listed$more]"
    }

    val manager = PsiManager.getInstance(project)
    for (file in files) {
        ProgressManager.checkCanceled()

        val wanted = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = manager.findFile(file) ?: return@compute true
            for (reference in referencesIn(psiFile, target)) {
                if (!consumer(reference)) return@compute false
            }
            true
        }
        if (!wanted) return false
    }
    return true
}

/**
 * The candidates a file offers. A guidebook document is walked by attribute values rather than by
 * scalars — it is XML, and the 1240 embeds of ss14-wega name prototypes from there; a message is
 * never named that way, so the localization search has nothing to look for.
 */
private fun referencesIn(file: PsiFile, target: SearchedTarget): List<PsiReference> {
    if (file.name.endsWith(".${RobustGuidebook.EXTENSION}", ignoreCase = true)) {
        if (target.localization) return emptyList()
        return PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .filter { it.value == target.name }
            .mapNotNull { value -> value.references.firstOrNull { it is PrototypeIdReference } }
    }

    return PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
        .mapNotNull { referenceOn(it, target) }
}

private fun referenceOn(scalar: YAMLScalar, target: SearchedTarget): PsiReference? {
    if (target.localization) {
        if (RobustLocalization.messageId(scalar.textValue) != target.name) return null
        return scalar.references.firstOrNull { it is LocalizationIdReference }
    }

    if (scalar.textValue != target.name) return null
    return scalar.references.firstOrNull { it is PrototypeIdReference }
}

/**
 * Values written as an alias. The engine resolves those while parsing the document, so
 * `tooltip: *TextOpenClose` names its message as plainly as the spelled-out text would; the walk
 * above cannot see them, because an alias is not a [YAMLScalar] and carries no text of its own.
 *
 * They are reported as usages and not as references on purpose. The text lives at the anchor, and
 * that is where a rename writes it — an alias needs no edit and must not get one, or a link the
 * author wrote deliberately would be replaced by a literal copy.
 */
private fun processAliasUsages(
    project: Project,
    target: SearchedTarget,
    consumer: (YAMLAlias) -> Boolean,
): Boolean {
    val files = ReadAction.compute<Collection<VirtualFile>, RuntimeException> {
        RobustYamlValueIndex.files(project, target.name).filterNot(::isCode)
    }

    val manager = PsiManager.getInstance(project)
    for (file in files) {
        ProgressManager.checkCanceled()

        val wanted = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = manager.findFile(file) ?: return@compute true
            val aliases = PsiTreeUtil.findChildrenOfType(psiFile, YAMLAlias::class.java)
            if (aliases.isEmpty()) return@compute true

            val anchored = RobustYamlContext.anchoredScalars(psiFile)
            for (alias in aliases) {
                val text = anchored[alias.aliasName]?.textValue ?: continue
                val name = if (target.localization) RobustLocalization.messageId(text) else text
                if (name != target.name) continue

                // `id: *BackgammonBoard` declares the prototype rather than using it, and a
                // declaration is left out of the results the way a spelled-out one is.
                val owner = alias.parent as? YAMLKeyValue
                if (owner?.value === alias && owner != null &&
                    RobustYamlContext.isPrototypeIdDeclaration(owner)
                ) {
                    continue
                }
                if (!consumer(alias)) return@compute false
            }
            true
        }
        if (!wanted) return false
    }
    return true
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
        if (!processReferences(project, target) { processor.process(UsageInfo(it)) }) return false
        return processAliasUsages(project, target) { processor.process(UsageInfo(it)) }
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
