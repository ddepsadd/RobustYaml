package com.jetbrains.rider.plugins.robustyaml.lookup

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.rider.plugins.robustyaml.RobustYamlContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.util.concurrent.ConcurrentHashMap

/**
 * Which `.rsi` a `state:` is written against, and what states that `.rsi` declares.
 *
 * The question exists because the sprite is usually not on the same line: of 18479 values of
 * `state:` in the checkout 6818 stand in a prototype whose `sprite:` came from an ancestor, and
 * until this walk there was nothing to check them against at all.
 *
 * The order of search is the order of the engine. A layer with its own `sprite:` answers for
 * itself; otherwise the layer belongs to a component and the component's own `sprite:` answers;
 * otherwise the same component is looked up in the ancestors, because `ComponentRegistrySerializer`
 * merges components along the chain before anything is read. The walk is accumulating and goes to
 * the end of the chain rather than stopping at the first ancestor that declares the component —
 * the same reason [RobustRequiredFields] does: bases like `BaseItem` are abstract, and `sprite:`
 * may sit several links above the `state:` that uses it.
 */
object RobustSprites {
    /**
     * The states a directory declares, read from `meta.json` by the same regex the hover uses:
     * `"name"` outside `"states"` does not occur in any of the 400 files checked when that rule
     * was written.
     */
    fun states(rsi: VirtualFile): List<String> {
        val meta = rsi.findChild(META) ?: return emptyList()
        val text = runCatching { VfsUtilCore.loadText(meta) }.getOrNull() ?: return emptyList()
        return states(text)
    }

    /** The same reading over the text alone, so that the measurement runs the shipped rule. */
    @JvmStatic
    fun states(meta: CharSequence): List<String> =
        STATE_NAME.findAll(meta.toString().substringAfter(STATES, "")).map { it.groupValues[1] }.toList()

    /**
     * The path a `state:` value is governed by, or null when it cannot be told — and null is the
     * common answer, not a failure: a `state:` under a visualizer or inside a tagged value names
     * something else entirely, and a rule that guessed there would paint working content red.
     */
    fun pathFor(stateKey: YAMLKeyValue): String? {
        val mapping = stateKey.parentMapping ?: return null
        val component = componentMappingOf(mapping)
        val name = component
            ?.let { (it.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue }
        val prototype = component
            ?.let { PsiTreeUtil.getParentOfType(it, YAMLMapping::class.java, true) }

        // An abstract prototype is never instantiated, and its sprite may come from a descendant
        // just as well as from an ancestor: `BenchBaseMiddle` declares `state: middle` and leaves
        // `sprite:` to the benches below it. `RobustRequiredFields` steps around abstracts too.
        if (prototype != null && isAbstract(prototype)) return null

        // Asked before the sprite of the mapping, not after: for a component's own state the two
        // are the same mapping, so answering with its `sprite:` first made the rule unreachable.
        // A state written beside `layers:` is never read — `SpriteComponent` turns the component's
        // own state into a layer only `if (layerDatums.Count == 0)`, and layers merge along the
        // chain, so a list on an ancestor silences it as well. 149 values in the checkout.
        if (mapping === component && name != null && prototype != null &&
            hasLayers(stateKey.project, component, prototype, name)
        ) {
            return null
        }

        spriteIn(mapping)?.let { return it }
        if (component == null || name == null || prototype == null) return null

        spriteIn(component)?.let { return it }
        return inheritedSprite(stateKey.project, prototype, name)
    }

    /**
     * The mapping of the component a value belongs to: the one whose parent sequence is the
     * `components:` of a declaration. A layer is one step below it, which is why this climbs rather
     * than looks at the parent alone.
     */
    private fun componentMappingOf(mapping: YAMLMapping): YAMLMapping? {
        var current: YAMLMapping? = mapping
        while (current != null) {
            val typeKey = current.getKeyValueByKey(TYPE_KEY)
            if (typeKey != null && RobustYamlContext.isComponentTypeKey(typeKey)) return current
            current = PsiTreeUtil.getParentOfType(current, YAMLMapping::class.java, true)
        }
        return null
    }

    private fun spriteIn(mapping: YAMLMapping): String? =
        (mapping.getKeyValueByKey(SPRITE_KEY)?.value as? YAMLScalar)
            ?.textValue
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The states the value of [stateKey] may be one of, or null when nothing can be said — no
     * sprite in scope, or an `.rsi` that is not in the checkout. Null is the common answer and
     * never an error: of 13315 values in the checkout 1449 have no sprite to be judged against.
     */
    fun declaredStates(stateKey: YAMLKeyValue): Set<String>? {
        val path = pathFor(stateKey) ?: return null
        val rsi = RobustResources.resolve(stateKey.project, path)?.takeIf { it.isDirectory }
            ?: return null
        return states(rsi).takeIf { it.isNotEmpty() }?.toSet()
    }

    /**
     * The frame that stands for an entity: the state its `Sprite` names, resolved through the same
     * chain of parents as everything else here, and `icon.png` when the sprite is named but the
     * state is not. Answers null for anything that is not an entity or draws nothing.
     *
     * Walked on demand and never in bulk: the caller is the renderer of a lookup element, and the
     * platform builds those only for the rows a user can see — of the 14274 ids of kind `entity`
     * a list shows a dozen at a time.
     */
    fun iconOf(project: Project, id: String): VirtualFile? {
        val path = spriteOf(project, id, SPRITE_COMPONENT) ?: return null
        val rsi = RobustResources.resolve(project, path)?.takeIf { it.isDirectory } ?: return null

        val state = stateOf(project, id, mutableSetOf())
        if (state != null) rsi.findChild("$state.png")?.let { return it }
        return rsi.findChild(ICON)
    }

    /** The state of the first layer, or the component's own — whichever the chain names first. */
    private fun stateOf(project: Project, id: String, visited: MutableSet<String>): String? {
        if (!visited.add(id) || visited.size > MAX_CHAIN) return null

        for (declaration in RobustPrototypeIndex.findDeclarations(project, id)) {
            val prototype = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
                ?: continue
            componentMapping(prototype, SPRITE_COMPONENT)?.let { sprite ->
                (sprite.getKeyValueByKey(LAYERS_KEY)?.value as? YAMLSequence)
                    ?.items
                    ?.firstNotNullOfOrNull { item ->
                        ((item.value as? YAMLMapping)?.getKeyValueByKey(STATE_KEY)?.value as? YAMLScalar)
                            ?.textValue
                    }
                    ?.let { return it }
                (sprite.getKeyValueByKey(STATE_KEY)?.value as? YAMLScalar)?.textValue?.let { return it }
            }
            for (parent in RobustYamlContext.parentIds(prototype)) {
                stateOf(project, parent, visited)?.let { return it }
            }
        }
        return null
    }

    private fun isAbstract(prototype: YAMLMapping): Boolean =
        (prototype.getKeyValueByKey(ABSTRACT_KEY)?.value as? YAMLScalar)?.textValue
            .equals("true", ignoreCase = true)

    private fun hasLayers(
        project: Project,
        component: YAMLMapping,
        prototype: YAMLMapping,
        name: String,
    ): Boolean {
        if (component.getKeyValueByKey(LAYERS_KEY) != null) return true
        return RobustYamlContext.parentIds(prototype).any { layersOf(project, it, name, mutableSetOf()) }
    }

    private fun layersOf(
        project: Project,
        id: String,
        component: String,
        visited: MutableSet<String>,
    ): Boolean {
        if (!visited.add(id) || visited.size > MAX_CHAIN) return false

        for (declaration in RobustPrototypeIndex.findDeclarations(project, id)) {
            val prototype = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
                ?: continue
            if (componentMapping(prototype, component)?.getKeyValueByKey(LAYERS_KEY) != null) return true
            if (RobustYamlContext.parentIds(prototype).any { layersOf(project, it, component, visited) }) {
                return true
            }
        }
        return false
    }

    private fun inheritedSprite(project: Project, prototype: YAMLMapping, component: String): String? {
        for (parent in RobustYamlContext.parentIds(prototype)) {
            spriteOf(project, parent, component)?.let { return it }
        }
        return null
    }

    private fun spriteOf(project: Project, id: String, component: String): String? =
        cache(project).computeIfAbsent("$id@$component") {
            collect(project, id, component, mutableSetOf()) ?: ABSENT
        }.takeIf { it != ABSENT }

    private fun collect(
        project: Project,
        id: String,
        component: String,
        visited: MutableSet<String>,
    ): String? {
        if (!visited.add(id) || visited.size > MAX_CHAIN) return null

        for (declaration in RobustPrototypeIndex.findDeclarations(project, id)) {
            val prototype = PsiTreeUtil.getParentOfType(declaration, YAMLMapping::class.java, true)
                ?: continue

            componentMapping(prototype, component)?.let { mapping ->
                spriteIn(mapping)?.let { return it }
            }
            for (parent in RobustYamlContext.parentIds(prototype)) {
                collect(project, parent, component, visited)?.let { return it }
            }
        }
        return null
    }

    private fun componentMapping(prototype: YAMLMapping, component: String): YAMLMapping? {
        val components = prototype.getKeyValueByKey(COMPONENTS_KEY)?.value as? YAMLSequence
            ?: return null
        for (item in components.items) {
            val mapping = item.value as? YAMLMapping ?: continue
            if ((mapping.getKeyValueByKey(TYPE_KEY)?.value as? YAMLScalar)?.textValue == component) {
                return mapping
            }
        }
        return null
    }

    /**
     * Memoised for one pass of the daemon, as everywhere else in the plugin: a file of entities
     * names the same ancestor line after line, and every miss is a walk of the whole chain.
     */
    private fun cache(project: Project): ConcurrentHashMap<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, String>(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    /** Stands for "walked and found nothing", so that a miss is memoised as well as a hit. */
    private const val ABSENT = " "

    private const val META = "meta.json"
    private const val STATES = "\"states\""
    private const val SPRITE_KEY = "sprite"
    private const val TYPE_KEY = "type"
    private const val COMPONENTS_KEY = "components"
    private const val LAYERS_KEY = "layers"
    private const val STATE_KEY = "state"
    private const val SPRITE_COMPONENT = "Sprite"
    private const val ICON = "icon.png"
    private const val ABSTRACT_KEY = "abstract"
    private const val MAX_CHAIN = 64

    private val STATE_NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
}
