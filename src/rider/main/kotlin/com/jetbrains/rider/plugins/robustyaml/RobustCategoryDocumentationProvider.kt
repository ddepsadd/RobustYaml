package com.jetbrains.rider.plugins.robustyaml

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
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val logger = logger<RobustCategoryDocumentationProvider>()

private const val CATEGORY_KIND = "entityCategory"

/**
 * Hovering a value of `categories:` shows what that category is: its localized name and description,
 * and the two flags that explain what belonging to it does to a prototype. In the content one
 * category stands behind 998 of the 1140 values, and nothing about `HideSpawnMenu` says what it does.
 */
class RobustCategoryDocumentationProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: return emptyList()
        val id = scalar.textValue.trim()
        if (id.isEmpty()) return emptyList()

        // Necessary but not sufficient, and cheap: a target is worth building only for a value that
        // some file declares as a category. Which kind the value actually means is settled later.
        val project = file.project
        val kinds = RobustPrototypeIndex.sites(project, id).mapTo(mutableSetOf()) { it.kind }
        if (CATEGORY_KIND !in kinds) return emptyList()

        val keyValue = RobustYamlContext.owningKey(scalar)
        val declaration = keyValue?.let { RobustYamlContext.declarationAround(it) }
        val origin = declaration?.let { RobustYamlContext.originTo(it, keyValue) }
        val root = origin?.root ?: declaration?.let { RobustDataFields.rootClass(project, it) }
        logger.debug {
            "Category target for '$id': kinds=$kinds key=${keyValue?.keyText} " +
                "declaration=${declaration?.name} path=${origin?.path} root=$root"
        }
        if (keyValue == null || origin == null || root == null) return emptyList()

        return listOf(
            CategoryDocumentationTarget(project, id, root, origin.path, keyValue.keyText, kinds),
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

private class CategoryDocumentationTarget(
    private val project: Project,
    private val id: String,
    private val root: String,
    private val path: List<String>,
    private val field: String,
    private val kinds: Set<String>,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(id).locationText(CATEGORY_KIND).presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            // `Debug` is declared both as an `entityCategory` and as a `storeCategory`, so the value
            // by itself does not always say which is meant, and the kind then has to come from the
            // type of the field, as it does in validation. Waiting for the backend is allowed here —
            // the hover is the one place that may, being computed off the daemon thread.
            val declared = RobustBackend.getInstance(project).field(root, path, field)
            val kind = declared?.prototypeKind
            logger.debug { "Category '$id': backend kind=$kind of ${declared?.type}, declared as $kinds" }

            // With no answer the id still decides, as long as it can only mean one thing: the
            // backend was needed for the collision, so it is only required where one exists. That
            // keeps the popup working on a cold backend, and on a checkout opened without a solution.
            val confirmed = kind == CATEGORY_KIND || (kind == null && kinds == setOf(CATEGORY_KIND))
            if (!confirmed) return@asyncDocumentation null

            val category = smartReadAction(project) { declaration() }
            logger.debug { "Category '$id': declaration ${category ?: "not read"}" }
            if (category == null) return@asyncDocumentation null
            val name = category.name?.let { it to smartReadAction(project) { translations(it) } }
            val description = category.description?.let { it to smartReadAction(project) { translations(it) } }
            val suffix = category.suffix?.let { it to smartReadAction(project) { translations(it) } }

            val html = buildString {
                append(DocumentationMarkup.DEFINITION_START)
                HtmlSyntaxInfoUtil.appendStyledSpan(
                    this,
                    RobustYamlColors.PROTOTYPE_KIND,
                    StringUtil.escapeXmlEntities(CATEGORY_KIND),
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

                if (description != null && description.second.isNotEmpty()) {
                    append(DocumentationMarkup.CONTENT_START)
                    append(paragraphs(description.second))
                    append(DocumentationMarkup.CONTENT_END)
                }

                append(DocumentationMarkup.SECTIONS_START)
                if (name != null) section("Name", name, DefaultLanguageHighlighterColors.STRING)
                if (suffix != null) section("Suffix", suffix, DefaultLanguageHighlighterColors.STRING)

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
            DocumentationResult.documentation(DocumentationContent.content(html))
        }

    private fun declaration(): CategoryDeclaration? {
        val site = RobustPrototypeIndex.sites(project, id).firstOrNull { it.kind == CATEGORY_KIND }
            ?: return null
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

    private fun paragraphs(translations: List<Pair<String, String>>): String =
        translations.joinToString("<br>") { (culture, text) ->
            val body = StringUtil.escapeXmlEntities(text)
            if (translations.size == 1) {
                body
            } else {
                DocumentationMarkup.GRAYED_START + StringUtil.escapeXmlEntities(culture) +
                    DocumentationMarkup.GRAYED_END + " " + body
            }
        }

    /**
     * A key with its resolved text, the key itself kept in grey so it can be copied. Most categories
     * are named in one culture only, so the culture is spelled out only when there is more than one
     * — otherwise every line would carry a label that says nothing.
     */
    private fun StringBuilder.section(
        header: String,
        localized: Pair<String, List<Pair<String, String>>>,
        key: TextAttributesKey,
    ) {
        val (locId, translations) = localized
        if (translations.isEmpty()) {
            // Nothing to resolve is the normal state, not a mistake: `entity-category-name-hide` is
            // declared in `ru-RU` alone, and eight of the twelve categories have no description.
            plain(header, locId, DefaultLanguageHighlighterColors.IDENTIFIER)
            return
        }

        val value =
            if (translations.size == 1) translations.first().second
            else translations.joinToString(" · ") { (culture, text) -> "$text ($culture)" }
        plain(header, value, key, grayed = locId)
    }

    private fun StringBuilder.plain(
        header: String,
        value: String,
        key: TextAttributesKey,
        grayed: String? = null,
    ) {
        append(DocumentationMarkup.SECTION_HEADER_START)
        append(StringUtil.escapeXmlEntities(header))
        append(DocumentationMarkup.SECTION_SEPARATOR)
        HtmlSyntaxInfoUtil.appendStyledSpan(this, key, StringUtil.escapeXmlEntities(value), 1.0f)
        if (grayed != null) {
            append(DocumentationMarkup.GRAYED_START)
            append("&nbsp;")
            append(StringUtil.escapeXmlEntities(grayed))
            append(DocumentationMarkup.GRAYED_END)
        }
        append(DocumentationMarkup.SECTION_END)
    }
}
