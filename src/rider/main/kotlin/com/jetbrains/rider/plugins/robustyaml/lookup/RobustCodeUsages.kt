package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.index.CodeLink
import com.jetbrains.rider.plugins.robustyaml.index.CodeLinkKind
import com.jetbrains.rider.plugins.robustyaml.index.RobustCodeLinks
import com.jetbrains.rider.plugins.robustyaml.index.RobustYamlValueIndex
import org.jetbrains.yaml.psi.YAMLKeyValue

private val logger = logger<PrototypeIdCodeReference>()

/** The kind `EntProtoId` stands for. Its generic form constrains a component, never the kind. */
private const val ENTITY_KIND = "entity"

/**
 * A prototype id written as a string literal in C#. There is no PSI to hang a reference on — the
 * frontend parses no C# — so the reference sits on the file and carries the range it stands for, and
 * the rewrite goes through the document for the same reason. The shape is the one
 * [LocaleTextReference] already uses for a localization key.
 */
class PrototypeIdCodeReference(
    file: PsiFile,
    range: TextRange,
    private val id: String,
    private val kind: String?,
) : PsiReferenceBase<PsiFile>(file, range, true) {

    override fun resolve(): PsiElement? =
        RobustPrototypeIndex.findDeclarations(element.project, id, kind).firstOrNull()

    /**
     * Recognised by what the target declares rather than by [resolve]: one id may belong to several
     * kinds — `Syndicate` is an antag, a department and more — and comparing against the first of
     * them would drop the usages of every other declaration.
     */
    override fun isReferenceTo(element: PsiElement): Boolean =
        element is YAMLKeyValue &&
            RobustYamlContext.isPrototypeIdDeclaration(element) &&
            element.valueText == id

    override fun handleElementRename(newElementName: String): PsiElement {
        val file = element
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return file
        val range = rangeInElement

        // The range was taken when the reference was built; anything else standing there now means
        // the file moved under us, and rewriting it would corrupt unrelated code.
        if (range.endOffset > document.textLength || document.getText(range) != id) {
            logger.debug { "Stale usage of '$id' in ${file.name} at ${range.startOffset}" }
            return file
        }

        document.replaceString(range.startOffset, range.endOffset, newElementName)
        documents.commitDocument(document)
        return file
    }
}

/**
 * The link written at [offset] of a `.cs` file. The whole file is scanned rather than the line under
 * the caret, because telling a literal from the text of a comment is exactly what the scanner is for.
 *
 * Cached against the file because the caller is asked on every keystroke: Ctrl+hover goes through
 * `gotoDeclarationHandler`, and the same handler decides whether the underline appears. The read
 * action is taken here rather than assumed for the same reason as in [localeUsageAt] — an action
 * update is not one, and the index would refuse to answer.
 */
internal fun codeLinkAt(file: PsiFile, offset: Int): CodeLink? {
    if (!file.name.endsWith(".${RobustCodeLinks.EXTENSION}", ignoreCase = true)) return null
    if (DumbService.isDumb(file.project)) return null

    return ReadAction.compute<CodeLink?, RuntimeException> {
        val links = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(RobustCodeLinks.links(file.viewProvider.contents), file)
        }
        RobustCodeLinks.at(links, offset)
    }
}

/**
 * Every mention of [id] in C#. Without them a rename in YAML leaves `EntProtoId Mob = "MobHuman"`
 * pointing at nothing — silently, because a string literal keeps compiling and only the game notices.
 *
 * The kind is checked before a reference is built, and this is where the rule for C# is stricter than
 * the one for a jump: `ProtoId<X>` names the class, so the literal is a usage only if the id really
 * is declared under the kind that class stands for. A rename writes, and a literal that names another
 * kind's id of the same name would be rewritten into a dangling one.
 *
 * A read action is taken per file, as in the YAML walk — holding one across the whole search would
 * block every write the IDE wants to make meanwhile.
 */
internal fun processCodeIdUsages(
    project: Project,
    id: String,
    consumer: (PsiReference) -> Boolean,
): Boolean {
    val candidates = ReadAction.compute<Collection<VirtualFile>, RuntimeException> {
        RobustYamlValueIndex.files(project, id)
            .filter { it.extension.equals(RobustCodeLinks.EXTENSION, ignoreCase = true) }
    }
    if (candidates.isEmpty()) return true

    val declared = ReadAction.compute<Set<String>, RuntimeException> {
        RobustPrototypeIndex.sites(project, id).mapTo(mutableSetOf()) { it.kind }
    }
    logger.debug { "Code usages of '$id': ${candidates.size} candidate files, declared as $declared" }

    val manager = PsiManager.getInstance(project)
    for (file in candidates) {
        ProgressManager.checkCanceled()

        val wanted = ReadAction.compute<Boolean, RuntimeException> {
            val psiFile = manager.findFile(file) ?: return@compute true
            for (link in RobustCodeLinks.ids(psiFile.viewProvider.contents)) {
                if (link.value != id) continue

                val kind = expectedKind(project, link)
                if (kind != null && kind !in declared) continue

                val range = TextRange(link.start, link.end)
                if (!consumer(PrototypeIdCodeReference(psiFile, range, id, kind))) return@compute false
            }
            true
        }
        if (!wanted) return false
    }
    return true
}

/**
 * The kind the literal claims. Null means the type says nothing usable — `ProtoId<T>` inside generic
 * code, or a prototype class the index has not seen — and then the id alone decides, the way it does
 * everywhere the kind is unknown.
 */
internal fun expectedKind(project: Project, link: CodeLink): String? {
    if (link.kind != CodeLinkKind.PROTOTYPE_ID) return null
    val className = link.prototypeClass ?: return ENTITY_KIND
    return RobustPrototypeIndex.kindOfClass(project, className)
}
