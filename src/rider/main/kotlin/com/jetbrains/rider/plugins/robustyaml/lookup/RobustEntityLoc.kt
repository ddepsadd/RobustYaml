package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import com.jetbrains.rider.plugins.robustyaml.documentation.CategoryDeclaration
import com.jetbrains.rider.plugins.robustyaml.documentation.categoryAt
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * The name, description and suffix an entity shows in the game, which is not what its declaration
 * says: of the 9712 prototypes carrying a `name:`, 9577 have an `ent-<id>` message that overrides it,
 * and 4371 more have no `name:` at all and read one off an ancestor. Both halves are needed to answer
 * "what is this thing", and neither is visible in the editor.
 *
 * The rule is `LocalizationManager.CalcEntityLoc`. Two details of it are easy to get wrong. The
 * message id is built once, from the id asked about — `$"ent-{prototypeId}"` inside the loop over
 * parents refers to the argument, not to the prototype of that iteration — so a translation is never
 * inherited; only `localizationId` reaches across, and it is a plain datafield, pushed down by
 * inheritance like any other. Written text, on the other hand, is inherited, and through the push
 * rather than through the walk: `EnumerateParents` drops abstract prototypes (`TryIndex` fails on
 * them), so the walk below deliberately keeps them, which is where names such as `BaseItem`'s live.
 */
object RobustEntityLoc {
    const val KIND = "entity"

    /** A piece of text with what produced it: translations win, written YAML is the fallback. */
    data class Text(
        val translations: List<Pair<String, String>>,
        val written: String?,
        val from: String?,
    )

    data class Loc(
        val name: Text?,
        val description: Text?,
        val suffix: Text?,
        val parents: List<String>,
        val abstract: Boolean,
        val categories: List<String>,
        val file: String,
    )

    fun of(project: Project, id: String): Loc? {
        val chain = chain(project, id)
        val self = chain.firstOrNull() ?: return null

        val locId = chain.firstNotNullOfOrNull { text(it.mapping, LOCALIZATION_KEY) } ?: (MESSAGE_PREFIX + id)
        val entries = RobustLocalization.entries(project, locId)
        val categories = categories(project, chain)

        return Loc(
            name = resolve(project, chain, entries, NAME_KEY) { it.value },
            description = resolve(project, chain, entries, DESCRIPTION_KEY) { it.attributes[DESC_ATTRIBUTE] },
            suffix = resolve(project, chain, entries, SUFFIX_KEY) { it.attributes[SUFFIX_ATTRIBUTE] }
                ?: inferredSuffix(project, categories),
            parents = RobustYamlContext.parentIds(self.mapping),
            abstract = text(self.mapping, ABSTRACT_KEY)?.lowercase()?.toBooleanStrictOrNull() == true,
            categories = categories,
            file = self.file,
        )
    }

    private data class Node(val id: String, val mapping: YAMLMapping, val file: String)

    /**
     * The prototype and its ancestors, nearest first. Breadth-first over `parent`, as the engine
     * walks it, so that with two parents the first one wins the same way it does at load time.
     */
    private fun chain(project: Project, id: String): List<Node> {
        val nodes = mutableListOf<Node>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue += id

        while (queue.isNotEmpty() && nodes.size < MAX_CHAIN) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val node = nodeOf(project, current) ?: continue
            nodes += node
            queue += RobustYamlContext.parentIds(node.mapping)
        }
        return nodes
    }

    private fun nodeOf(project: Project, id: String): Node? {
        val site = RobustPrototypeIndex.sites(project, id).firstOrNull { it.kind == KIND } ?: return null
        val file = PsiManager.getInstance(project).findFile(site.file) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(
            file.findElementAt(site.offset),
            YAMLKeyValue::class.java,
            false,
        ) ?: return null
        return Node(id, declaration.parentMapping ?: return null, site.file.name)
    }

    /**
     * A translation wins over written text, and it wins even when it resolves to nothing: `{ "" }`
     * is how a translator blanks a suffix inherited from a base, and falling back to YAML there would
     * put back the very text that was suppressed. So the fallback turns on whether the message
     * carries the field at all, and blank results are only kept out of the popup.
     */
    private fun resolve(
        project: Project,
        chain: List<Node>,
        entries: List<Pair<String, RobustLocalization.Entry>>,
        key: String,
        pick: (RobustLocalization.Entry) -> String?,
    ): Text? {
        val picked = entries.mapNotNull { (culture, entry) ->
            pick(entry)?.let { culture to RobustLocalization.resolved(project, culture, it) }
        }
        val translations = picked.filter { it.second.isNotBlank() }
        val written =
            if (picked.isNotEmpty()) null
            else chain.firstNotNullOfOrNull { node -> text(node.mapping, key)?.let { node.id to it } }

        if (translations.isEmpty() && written == null) return null
        return Text(translations, written?.second, written?.first)
    }

    /**
     * Categories of the prototype and the inheritable ones of its ancestors. The filter is not
     * pedantry: `HideSpawnMenu` is the one category in the content declared `inheritable: false`, and
     * it stands on 998 of the 1140 values — inherited blindly, it would claim that thousands of
     * entities are missing from the spawn panel. Categories a component brings in through
     * `[EntityCategory]` are not counted, there being no way to ask for them from here.
     */
    private fun categories(project: Project, chain: List<Node>): List<String> {
        val own = declaredCategories(chain.firstOrNull()?.mapping)
        val inherited = chain.drop(1)
            .flatMap { declaredCategories(it.mapping) }
            .filter { it !in own && isInheritable(project, it) }
        return own + inherited.distinct()
    }

    private fun declaredCategories(mapping: YAMLMapping?): List<String> {
        val value = mapping?.getKeyValueByKey(CATEGORIES_KEY)?.value ?: return emptyList()
        val values =
            if (value is YAMLSequence) value.items.mapNotNull { RobustYamlContext.resolvedText(it.value) }
            else listOfNotNull(RobustYamlContext.resolvedText(value))
        return values.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isInheritable(project: Project, category: String): Boolean =
        declarationOf(project, category, CATEGORY_KIND)?.inheritable != false

    /** With no suffix of its own an entity takes one from its categories, joined as the engine joins it. */
    private fun inferredSuffix(project: Project, categories: List<String>): Text? {
        val ids = categories.mapNotNull { declarationOf(project, it, CATEGORY_KIND)?.suffix }
        if (ids.isEmpty()) return null

        val cultures = ids.map { RobustLocalization.translations(project, RobustLocalization.messageId(it)) }
        val translations = cultures.flatMap { it.map { (culture, _) -> culture } }.distinct().sorted()
            .map { culture ->
                culture to cultures.joinToString(SUFFIX_SEPARATOR) { translation ->
                    val body = translation.firstOrNull { it.first == culture }?.second.orEmpty()
                    RobustLocalization.resolved(project, culture, body)
                }
            }
        return Text(translations.filter { it.second.isNotBlank() }, ids.joinToString(SUFFIX_SEPARATOR), null)
    }

    private fun declarationOf(project: Project, id: String, kind: String): CategoryDeclaration? {
        val site = RobustPrototypeIndex.sites(project, id).firstOrNull { it.kind == kind } ?: return null
        val file = PsiManager.getInstance(project).findFile(site.file) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(
            file.findElementAt(site.offset),
            YAMLKeyValue::class.java,
            false,
        ) ?: return null
        return categoryAt(declaration, site.file.name)
    }

    private fun text(mapping: YAMLMapping, key: String): String? =
        RobustYamlContext.resolvedText(mapping.getKeyValueByKey(key)?.value)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private const val CATEGORY_KIND = "entityCategory"
    private const val MESSAGE_PREFIX = "ent-"
    private const val DESC_ATTRIBUTE = "desc"
    private const val SUFFIX_ATTRIBUTE = "suffix"
    private const val NAME_KEY = "name"
    private const val DESCRIPTION_KEY = "description"
    private const val SUFFIX_KEY = "suffix"
    private const val ABSTRACT_KEY = "abstract"
    private const val CATEGORIES_KEY = "categories"
    private const val LOCALIZATION_KEY = "localizationId"
    private const val SUFFIX_SEPARATOR = ", "
    private const val MAX_CHAIN = 64
}
