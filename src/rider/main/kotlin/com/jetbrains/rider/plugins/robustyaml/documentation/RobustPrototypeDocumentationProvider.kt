package com.jetbrains.rider.plugins.robustyaml.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationContent
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.backend.RobustBackend
import com.jetbrains.rider.plugins.robustyaml.highlighting.RobustYamlColors
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustDataFields
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustEntityLoc
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustLocalization
import com.jetbrains.rider.plugins.robustyaml.lookup.RobustPrototypeIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val logger = logger<RobustPrototypeDocumentationProvider>()

private const val CATEGORY_KIND = "entityCategory"

/** A message id with what every culture makes of it. */
private typealias Localized = Pair<String, List<Pair<String, String>>>

/**
 * Hovering a prototype id says what stands behind it. For an entity that is the name and description
 * the player reads, which the declaration alone does not give — see [RobustEntityLoc]; for a category
 * it is the two flags that explain what belonging to it does, one category standing behind 998 of the
 * 1140 values with nothing about `HideSpawnMenu` saying what it means.
 */
class RobustPrototypeDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()
        val id = scalar.textValue.trim()
        if (id.isEmpty()) return emptyList()

        // Necessary but not sufficient, and cheap: a target is worth building only for a value some
        // file declares as a prototype. Which kind the value actually means is settled later.
        val project = file.project
        val kinds = RobustPrototypeIndex.sites(project, id).mapTo(mutableSetOf()) { it.kind }
        if (kinds.isEmpty()) return emptyList()

        // The query the backend is asked for the kind of the field. It is allowed to be missing —
        // a value outside a declaration still has an id, and one kind may be enough to name it.
        val keyValue = RobustYamlContext.owningKey(scalar)
        val declaration = keyValue?.let { RobustYamlContext.declarationAround(it) }
        val origin = declaration?.let { RobustYamlContext.originTo(it, keyValue) }
        val root = origin?.root ?: declaration?.let { RobustDataFields.rootClass(project, it) }
        logger.debug {
            "Prototype target for '$id': kinds=$kinds key=${keyValue?.keyText} " +
                "declaration=${declaration?.name} path=${origin?.path} root=$root"
        }

        return listOf(
            PrototypeDocumentationTarget(
                project = project,
                id = id,
                root = root.takeIf { origin != null },
                path = origin?.path.orEmpty(),
                field = keyValue?.keyText.orEmpty(),
                kinds = kinds,
                untyped = RobustYamlContext.isPrototypeId(scalar),
            ),
        )
    }
}

/** The keys of a category declaration that are worth showing. */
internal data class CategoryDeclaration(
    val file: String,
    val name: String?,
    val description: String?,
    val suffix: String?,
    val hideSpawnMenu: Boolean?,
    val inheritable: Boolean?,
)

/**
 * Reads a category out of its declaration. Values of `bool` are parsed the way the engine parses
 * them — `BooleanSerializer` is `bool.Parse`, so the case does not matter and `True` is a boolean.
 */
internal fun categoryAt(declaration: YAMLKeyValue, file: String): CategoryDeclaration? {
    val mapping = declaration.parentMapping ?: return null

    fun text(key: String): String? =
        (mapping.getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.trim()?.takeIf { it.isNotEmpty() }

    fun flag(key: String): Boolean? = text(key)?.lowercase()?.toBooleanStrictOrNull()

    return CategoryDeclaration(
        file = file,
        name = text("name"),
        description = text("description"),
        suffix = text("suffix"),
        hideSpawnMenu = flag("hideSpawnMenu"),
        inheritable = flag("inheritable"),
    )
}

private class PrototypeDocumentationTarget(
    private val project: Project,
    private val id: String,
    private val root: String?,
    private val path: List<String>,
    private val field: String,
    private val kinds: Set<String>,
    private val untyped: Boolean,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(id).locationText(kinds.singleOrNull() ?: LOCATION).presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            val kind = kind() ?: return@asyncDocumentation null
            val html = when (kind) {
                RobustEntityLoc.KIND -> entity()
                CATEGORY_KIND -> category()
                else -> other(kind)
            } ?: return@asyncDocumentation null
            DocumentationResult.documentation(DocumentationContent.content(html))
        }

    /**
     * `Debug` is declared both as an `entityCategory` and as a `storeCategory`, so the value by itself
     * does not always say which is meant, and the kind then has to come from the type of the field, as
     * it does in validation. Waiting for the backend is allowed here — the hover is the one place that
     * may, being computed off the daemon thread.
     *
     * Without an answer the id alone decides, and only under a key that means an id even to a checkout
     * with no solution: `parent`, `proto`, `prototype`, `entity`, `id`. Elsewhere a coincidence is the
     * likelier reading — 47230 values in the content stand under some other key and happen to spell an
     * id, `state: Vending` and `suffix: Empty` among them, and every one of the keys that really do
     * hold a reference (`graph`, `result`, `material`, `ReagentId`) is typed and answered for above.
     */
    private suspend fun kind(): String? {
        val declared = root?.let { RobustBackend.getInstance(project).field(it, path, field) }
        val kind = declared?.prototypeKind
        logger.debug { "Prototype '$id': backend kind=$kind of ${declared?.type}, declared as $kinds" }

        // A field whose kind names something this id is not declared as is a dead reference, and the
        // validator says so already; answering with the declaration that does exist would argue back.
        if (kind != null) return kind.takeIf { it in kinds }
        return if (untyped) kinds.singleOrNull() else null
    }

    private suspend fun entity(): String? {
        val loc = smartReadAction(project) { RobustEntityLoc.of(project, id) } ?: return null
        logger.debug { "Entity '$id': $loc" }

        return buildString {
            definition(RobustEntityLoc.KIND)
            loc.description?.let { content(it) }

            append(DocumentationMarkup.SECTIONS_START)
            loc.name?.let { localized("Name", it, DefaultLanguageHighlighterColors.STRING) }
            loc.suffix?.let { localized("Suffix", it, DefaultLanguageHighlighterColors.STRING) }
            if (loc.parents.isNotEmpty()) {
                plain("Parent", loc.parents.joinToString(", "), RobustYamlColors.PROTOTYPE_ID)
            }
            if (loc.abstract) plain("Abstract", "yes", DefaultLanguageHighlighterColors.KEYWORD)
            if (loc.categories.isNotEmpty()) {
                plain("Categories", loc.categories.joinToString(", "), RobustYamlColors.PROTOTYPE_ID)
            }
            plain("Declared in", loc.file, DefaultLanguageHighlighterColors.CLASS_NAME)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private suspend fun category(): String? {
        val described = described(CATEGORY_KIND) ?: return null
        val category = described.declaration
        logger.debug { "Category '$id': $category" }

        return buildString {
            definition(CATEGORY_KIND)
            val description = described.description
            if (description != null && description.second.isNotEmpty()) {
                append(DocumentationMarkup.CONTENT_START)
                append(paragraphs(description.second))
                append(DocumentationMarkup.CONTENT_END)
            }

            append(DocumentationMarkup.SECTIONS_START)
            described.name?.let { message("Name", it, DefaultLanguageHighlighterColors.STRING) }
            described.suffix?.let { message("Suffix", it, DefaultLanguageHighlighterColors.STRING) }

            // Only what is not the default is worth a line. `inheritable` is true unless said
            // otherwise, and "Inheritable: yes" on every popup would be noise; `hideSpawnMenu`
            // is the opposite way round, and it is the whole point of the commonest category.
            if (category.hideSpawnMenu == true) {
                plain("Spawn menu", "hidden", DefaultLanguageHighlighterColors.KEYWORD)
            }
            if (category.inheritable == false) {
                plain("Inheritable", "no", DefaultLanguageHighlighterColors.KEYWORD)
            }
            plain("Declared in", category.file, DefaultLanguageHighlighterColors.CLASS_NAME)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /**
     * The other 202 kinds have no shared shape, so only what every prototype has is shown: where it
     * is declared, plus the keys that name it if they happen to be there. Their values are put through
     * localization when they turn out to be message ids — `reagent` and `job` write them that way —
     * and left as written when they do not.
     */
    private suspend fun other(kind: String): String? {
        val described = described(kind) ?: return null

        return buildString {
            definition(kind)
            append(DocumentationMarkup.SECTIONS_START)
            described.name?.let { message("Name", it, DefaultLanguageHighlighterColors.STRING) }
            described.description?.let { message("Description", it, DefaultLanguageHighlighterColors.STRING) }
            described.suffix?.let { message("Suffix", it, DefaultLanguageHighlighterColors.STRING) }
            plain("Declared in", described.declaration.file, DefaultLanguageHighlighterColors.CLASS_NAME)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /** A declaration with its three naming keys already resolved, read in one pass under one lock. */
    private class Described(
        val declaration: CategoryDeclaration,
        val name: Localized?,
        val description: Localized?,
        val suffix: Localized?,
    )

    private suspend fun described(kind: String): Described? =
        smartReadAction(project) {
            val declaration = declaration(kind) ?: return@smartReadAction null
            Described(
                declaration,
                declaration.name?.let { it to translations(it) },
                declaration.description?.let { it to translations(it) },
                declaration.suffix?.let { it to translations(it) },
            )
        }

    private fun StringBuilder.definition(kind: String) {
        append(DocumentationMarkup.DEFINITION_START)
        HtmlSyntaxInfoUtil.appendStyledSpan(
            this,
            RobustYamlColors.PROTOTYPE_KIND,
            StringUtil.escapeXmlEntities(kind),
            1.0f,
        )
        append(' ')
        HtmlSyntaxInfoUtil.appendStyledSpan(
            this,
            RobustYamlColors.PROTOTYPE_ID,
            StringUtil.escapeXmlEntities(id),
            1.0f,
        )
        append(DocumentationMarkup.DEFINITION_END)
    }

    /** The description, translated when it is translated and as written when it is not. */
    private fun StringBuilder.content(text: RobustEntityLoc.Text) {
        val body = when {
            text.translations.isNotEmpty() -> paragraphs(text.translations)
            text.written != null -> RobustMarkup.toHtml(text.written) + inherited(text)
            else -> return
        }
        append(DocumentationMarkup.CONTENT_START)
        append(body)
        append(DocumentationMarkup.CONTENT_END)
    }

    private fun StringBuilder.localized(header: String, text: RobustEntityLoc.Text, key: TextAttributesKey) {
        if (text.translations.isNotEmpty()) {
            plain(header, joined(text.translations), key)
        } else if (text.written != null) {
            plain(header, text.written, key, tail = inherited(text))
        }
    }

    /** Where written text came from, said only when it came from somewhere else. */
    private fun inherited(text: RobustEntityLoc.Text): String {
        val from = text.from?.takeIf { it != id } ?: return ""
        return DocumentationMarkup.GRAYED_START + "&nbsp;from " +
            StringUtil.escapeXmlEntities(from) + DocumentationMarkup.GRAYED_END
    }

    /**
     * A key with its resolved text, the key itself kept in grey so it can be copied. Most values are
     * named in one culture only, so the culture is spelled out only when there is more than one —
     * otherwise every line would carry a label that says nothing.
     */
    private fun StringBuilder.message(header: String, localized: Localized, key: TextAttributesKey) {
        val (locId, translations) = localized
        if (translations.isEmpty()) {
            // Nothing to resolve is the normal state, not a mistake: eight of the twelve categories
            // have no description, and a value that is plain text is not a message id at all.
            plain(header, locId, DefaultLanguageHighlighterColors.IDENTIFIER)
            return
        }
        plain(
            header,
            joined(translations),
            key,
            tail = DocumentationMarkup.GRAYED_START + "&nbsp;" +
                StringUtil.escapeXmlEntities(locId) + DocumentationMarkup.GRAYED_END,
        )
    }

    private fun StringBuilder.plain(header: String, value: String, key: TextAttributesKey, tail: String = "") {
        append(DocumentationMarkup.SECTION_HEADER_START)
        append(StringUtil.escapeXmlEntities(header))
        append(DocumentationMarkup.SECTION_SEPARATOR)
        HtmlSyntaxInfoUtil.appendStyledSpan(this, key, RobustMarkup.toHtml(value), 1.0f)
        append(tail)
        append(DocumentationMarkup.SECTION_END)
    }

    private fun joined(translations: List<Pair<String, String>>): String =
        if (translations.size == 1) translations.first().second
        else translations.joinToString(" · ") { (culture, text) -> "$text ($culture)" }

    private fun paragraphs(translations: List<Pair<String, String>>): String =
        translations.joinToString("<br>") { (culture, text) ->
            val body = RobustMarkup.toHtml(text)
            if (translations.size == 1) {
                body
            } else {
                DocumentationMarkup.GRAYED_START + StringUtil.escapeXmlEntities(culture) +
                    DocumentationMarkup.GRAYED_END + " " + body
            }
        }

    private fun declaration(kind: String): CategoryDeclaration? {
        val site = RobustPrototypeIndex.sites(project, id).firstOrNull { it.kind == kind } ?: return null
        val file = PsiManager.getInstance(project).findFile(site.file) ?: return null
        val element = PsiTreeUtil.getParentOfType(
            file.findElementAt(site.offset),
            YAMLKeyValue::class.java,
            false,
        ) ?: return null
        return categoryAt(element, site.file.name)
    }

    private fun translations(locId: String): List<Pair<String, String>> =
        RobustLocalization.translations(project, RobustLocalization.messageId(locId))

    private companion object {
        const val LOCATION = "prototype"
    }
}
